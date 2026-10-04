package com.telefarm.data

import com.telefarm.core.td.TdLibClient
import com.telefarm.core.td.TelefarmLog
import com.telefarm.data.map.TdMappers
import com.telefarm.data.map.userIdOrNull
import com.telefarm.data.model.ChatKind
import com.telefarm.data.model.ChatMemberUi
import com.telefarm.data.model.ProfileUi
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
                canLeave = false
            )
        }
    }

    /** True when the user is the signed in account. */
    fun isSelf(userId: Long): Boolean = userId == selfUserId()

    private companion object {
        const val TAG = "UserRepository"
        const val MAX_CONCURRENT_FETCHES = 8
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
