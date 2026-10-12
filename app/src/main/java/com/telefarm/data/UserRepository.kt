package com.telefarm.data

import com.telefarm.core.td.TdLibClient
import com.telefarm.core.td.TelefarmLog
import com.telefarm.data.map.TdMappers
import com.telefarm.data.map.userIdOrNull
import com.telefarm.data.model.ChatKind
import com.telefarm.data.model.ChatMemberUi
import com.telefarm.data.model.ProfileUi
import com.telefarm.data.model.UserStatusUi
import com.telefarm.data.model.UserUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches users and loads profiles, contacts and group members.
 *
 * TDLib has no batch user request, so lookups are performed with a small number of concurrent
 * `getUser` calls and stored in a cache that is kept in sync with `updateUser`.
 */
class UserRepository(
    private val td: TdLibClient,
    private val scope: CoroutineScope,
    private val selfUserId: () -> Long
) {

    private val cache = ConcurrentHashMap<Long, TdApi.User>()
    private val loadMutex = Mutex()

    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 256)

    /** Emits the id of every user whose data changed. */
    val changes: SharedFlow<Long> = _changes.asSharedFlow()

    fun start() {
        scope.launch {
            td.updates.collect { update ->
                if (update is TdApi.UpdateUser) {
                    cache[update.user.id] = update.user
                    _changes.tryEmit(update.user.id)
                }
            }
        }
    }

    /** Cached user without a network request. */
    fun cached(id: Long): TdApi.User? = cache[id]

    /** Cached display name, used for chat list prefixes. */
    fun cachedDisplayName(id: Long): String? = cache[id]?.let(TdMappers::displayName)

    /** Cached presentation model. */
    fun cachedUi(id: Long): UserUi? = cache[id]?.let(TdMappers::user)

    /** Loads a single user, using the cache when possible. */
    suspend fun user(id: Long): TdApi.User? {
        if (id <= 0) return null
        cache[id]?.let { return it }
        return try {
            val user = td.send(TdApi.GetUser().apply { userId = id })
            cache[id] = user
            user
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            TelefarmLog.w(TAG, "User could not be loaded")
            null
        }
    }

    suspend fun userUi(id: Long): UserUi? = user(id)?.let(TdMappers::user)

    suspend fun displayName(id: Long): String? = user(id)?.let(TdMappers::displayName)

    /**
     * Loads several users at once with a bounded number of parallel requests.
     *
     * @return every user that could be loaded, keyed by id.
     */
    suspend fun users(ids: Collection<Long>): Map<Long, TdApi.User> = coroutineScope {
        val missing = ids.filter { it > 0 && !cache.containsKey(it) }.distinct()
        if (missing.isEmpty()) return@coroutineScope ids.associateWithNotNull { cache[it] }

        loadMutex.withLock {
            missing.chunked(MAX_CONCURRENT_FETCHES).forEach { chunk ->
                chunk.map { id ->
                    async {
                        if (cache.containsKey(id)) return@async
                        runCatching { td.send(TdApi.GetUser().apply { userId = id }) }
                            .onSuccess { user -> cache[id] = user }
                            .onFailure { error ->
                                if (error is CancellationException) throw error
                                TelefarmLog.w(TAG, "A user could not be loaded")
                            }
                    }
                }.awaitAll()
            }
        }
        ids.associateWithNotNull { cache[it] }
    }

    /** Full profile of a user, including the biography. */
    suspend fun fullInfo(userId: Long): TdApi.UserFullInfo? = try {
        td.send(TdApi.GetUserFullInfo().apply { this.userId = userId })
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        null
    }

    /** Contacts of the signed in account, ordered by first name. */
    suspend fun contacts(): List<UserUi> {
        val contacts = td.send(TdApi.GetContacts())
        val ids = contacts.userIds?.toList() ?: emptyList()
        users(ids)
        return ids.mapNotNull { cache[it]?.let(TdMappers::user) }
            .sortedBy { it.displayName.lowercase() }
    }

    /**
     * Members of a supergroup or channel.
     *
     * @param fromPage offset in the member list, used for pagination.
     */
    suspend fun supergroupMembers(
        supergroupId: Long,
        offset: Int,
        limit: Int
    ): List<ChatMemberUi> = try {
        val members = td.send(
            TdApi.GetSupergroupMembers().apply {
                this.supergroupId = supergroupId
                filter = TdApi.SupergroupMembersFilterRecent()
                this.offset = offset
                this.limit = limit
            }
        )
        val userIds = members.members?.mapNotNull { member -> member.memberId?.userIdOrNull() } ?: emptyList()
        users(userIds)
        members.members?.map { member ->
            TdMappers.chatMember(member, cache[member.memberId?.userIdOrNull() ?: 0L])
        } ?: emptyList()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        TelefarmLog.w(TAG, "Members could not be loaded")
        emptyList()
    }

    /** Members of a basic group; TDLib exposes them through the full group info. */
    suspend fun basicGroupMembers(basicGroupId: Long): List<ChatMemberUi> = try {
        val info = td.send(TdApi.GetBasicGroupFullInfo().apply { this.basicGroupId = basicGroupId })
        val members = info.members ?: emptyArray()
        val userIds = members.mapNotNull { member -> member.memberId?.userIdOrNull() }
        users(userIds)
        members.map { member -> TdMappers.chatMember(member, cache[member.memberId?.userIdOrNull() ?: 0L]) }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        emptyList()
    }

    /** Profile of a user, ready for the profile screen. */
    suspend fun profile(userId: Long): ProfileUi? {
        val user = user(userId) ?: return null
        val fullInfo = fullInfo(userId)
        return TdMappers.user(user).let { mapped ->
            ProfileUi(
                id = mapped.id,
                isUser = true,
                title = mapped.displayName,
                username = mapped.username,
                phoneNumber = mapped.phoneNumber,
                bio = fullInfo?.bio?.text?.takeIf { it.isNotBlank() },
                photo = mapped.photo,
                status = mapped.status,
                kind = if (userId == selfUserId()) ChatKind.SAVED_MESSAGES else ChatKind.PRIVATE,
                memberCount = 0,
                canViewMembers = false,
                canSendMessage = true,
                canLeave = false,
                isBlocked = userId in blockedIds()
            )
        }
    }

    /**
     * Who the account has blocked.
     *
     * Read from the block list because a user carries no mark of being blocked: the list is the
     * only place that says so.
     */
    suspend fun blockedIds(): Set<Long> = try {
        val senders = td.send(
            TdApi.GetBlockedMessageSenders().apply {
                blockList = TdApi.BlockListMain()
                offset = 0
                limit = BLOCKED_PAGE_SIZE
            }
        )
        senders.senders.orEmpty().mapNotNull { sender ->
            (sender as? TdApi.MessageSenderUser)?.userId
        }.toSet()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        emptySet()
    }

    /**
     * Who is blocked, with names.
     *
     * The block list holds senders and a sender is an identifier, so each one is fetched to give
     * the list something to read. One that cannot be fetched is still listed, under whatever is
     * known of it: a person blocked is not removed from the list because their name is not
     * available, and a block nobody can see is a block nobody can take back.
     */
    suspend fun blockedUsers(): List<UserUi> = blockedIds().map { id ->
        userUi(id) ?: UserUi(
            id = id,
            displayName = "",
            firstName = "",
            lastName = "",
            username = null,
            phoneNumber = null,
            photo = null,
            status = UserStatusUi.Empty,
            isBot = false,
            isVerified = false,
            isContact = false,
            isPremium = false
        )
    }

    /**
     * Blocks a user, or unblocks them.
     *
     * Blocking is a change to what the other person can do, so it is the account's setting and not
     * this device's: it holds in every client until it is taken back here or there.
     */
    suspend fun setBlocked(userId: Long, blocked: Boolean): Boolean = try {
        td.send(
            TdApi.SetMessageSenderBlockList().apply {
                senderId = TdApi.MessageSenderUser().apply { this.userId = userId }
                // Null is how a sender is taken off the list; there is no unblock call of its own.
                blockList = if (blocked) TdApi.BlockListMain() else null
            }
        )
        true
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        false
    }

    /** True when the user is the signed in account. */
    fun isSelf(userId: Long): Boolean = userId == selfUserId()

    private companion object {
        const val TAG = "UserRepository"
        const val MAX_CONCURRENT_FETCHES = 8

        /** How many blocked senders are read. A block list past this is unlikely and can be paged. */
        const val BLOCKED_PAGE_SIZE = 100
    }
}

/** Builds a map without null values. */
private inline fun <K, V : Any> Iterable<K>.associateWithNotNull(value: (K) -> V?): Map<K, V> {
    val result = LinkedHashMap<K, V>()
    for (key in this) {
        val mapped = value(key)
        if (mapped != null) result[key] = mapped
    }
    return result
}
