package com.telefarm.data

import com.telefarm.R
import com.telefarm.core.td.TdLibClient
import com.telefarm.core.td.TdLibException
import com.telefarm.core.td.TelefarmLog
import com.telefarm.data.map.TdMappers
import com.telefarm.data.map.toUiMessage
import com.telefarm.data.model.ChatDetails
import com.telefarm.data.model.ChatListState
import com.telefarm.data.model.ChatUi
import com.telefarm.data.model.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * The main chat list.
 *
 * TDLib is the single source of truth: this class keeps the chat objects TDLib sends through
 * updates, asks for the ordered list after loading and recomputes the presentation models in a
 * coalesced background pass, so a burst of updates never redraws the list more than a few
 * times per second.
 */
class ChatListRepository(
    private val td: TdLibClient,
    private val users: UserRepository,
    private val session: TdSessionController,
    private val scope: CoroutineScope
) {

    private val chatCache = ConcurrentHashMap<Long, TdApi.Chat>()
    private val orderedIds = mutableListOf<Long>()
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow<ChatListState>(ChatListState.Loading)
    val state: StateFlow<ChatListState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastError = MutableStateFlow<UiMessage?>(null)

    /** Last error surfaced by a chat list action, shown as a transient message. */
    val lastError: StateFlow<UiMessage?> = _lastError.asStateFlow()

    private val loadMutex = Mutex()
    private var serverChatsExhausted = false
    private var orderLimit = PAGE_SIZE
    private var closed = false

    fun start() {
        scope.launch {
            td.updates.collect(::handleUpdate)
        }
        scope.launch {
            for (ignored in dirty) {
                recomputeState()
                delay(RECOMPUTE_INTERVAL_MS)
            }
        }
        scope.launch {
            users.changes.collect {
                // Sender names improve the preview; a refresh is cheap and coalesced.
                markDirty()
            }
        }
        scope.launch {
            session.selfUserId.collect { markDirty() }
        }
    }

    // region Loading

    /** First load: pulls the chat list from Telegram and publishes it. */
    suspend fun loadInitial() {
        if (closed) return
        loadMutex.withLock {
            if (_state.value is ChatListState.Content) return
            try {
                loadFromServer(LOAD_INITIAL_CHATS)
                refreshOrder()
                _lastError.value = null
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                TelefarmLog.w(TAG, "Chat list could not be loaded")
                _lastError.value = (error as? TdLibException)?.toUiMessage()
                    ?: UiMessage.Res(R.string.chat_list_error_title)
                if (orderedIds.isEmpty()) {
                    _state.value = ChatListState.Failed(
                        _lastError.value ?: UiMessage.Res(R.string.chat_list_error_title)
                    )
                }
            }
        }
    }

    /** User initiated refresh: looks for new chats and re-reads the order. */
    suspend fun refresh() {
        if (closed) return
        _isRefreshing.value = true
        try {
            loadFromServer(LOAD_MORE_CHATS)
            loadMutex.withLock { refreshOrder() }
            _lastError.value = null
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            TelefarmLog.w(TAG, "Chat list refresh failed")
            _lastError.value = (error as? TdLibException)?.toUiMessage()
                ?: UiMessage.Res(R.string.error_network)
        } finally {
            _isRefreshing.value = false
        }
    }

    /** Loads another page of chats; called while the user scrolls. */
    suspend fun loadMore() {
        if (closed || serverChatsExhausted) return
        try {
            loadFromServer(LOAD_MORE_CHATS)
            if (orderedIds.size >= orderLimit && !serverChatsExhausted) {
                orderLimit += PAGE_SIZE
                loadMutex.withLock { refreshOrder() }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            TelefarmLog.w(TAG, "Next page of chats could not be loaded")
        }
    }

    /**
     * Asks TDLib for chats until the requested number is reached.
     *
     * TDLib answers `404` when every chat is already known, which is the normal end of the
     * loop and not an error.
     */
    private suspend fun loadFromServer(target: Int) {
        var loaded = chatCache.size
        while (!serverChatsExhausted && loaded < target) {
            val result = runCatching {
                td.send(
                    TdApi.LoadChats().apply {
                        chatList = TdApi.ChatListMain()
                        limit = PAGE_SIZE
                    }
                )
            }
            val error = result.exceptionOrNull()
            if (error != null) {
                if (error is CancellationException) throw error
                val apiError = (error as? TdLibException)?.apiError
                if (apiError?.code == NOT_FOUND_CODE) {
                    serverChatsExhausted = true
                    break
                }
                throw error
            }
            val previous = loaded
            loaded = chatCache.size
            if (loaded == previous) break
        }
    }

    /** Reads the ordered list of chat ids from TDLib and fetches any chat it has not sent yet. */
    private suspend fun refreshOrder() {
        val chats = td.send(
            TdApi.GetChats().apply {
                chatList = TdApi.ChatListMain()
                limit = orderLimit
            }
        )
        val ids = chats.chatIds?.toList() ?: emptyList()
        if (ids.isNotEmpty() && ids.size < orderedIds.size) {
            // TDLib returned fewer chats than we already show; keep the richer list.
            mergeOrder(ids)
        } else {
            mergeOrder(ids)
        }
        fetchMissingChats()
        markDirty()
    }

    private fun mergeOrder(ids: List<Long>) {
        synchronized(orderedIds) {
            orderedIds.clear()
            orderedIds.addAll(ids)
        }
    }

    private suspend fun fetchMissingChats() {
        val missing = synchronized(orderedIds) {
            orderedIds.filter { !chatCache.containsKey(it) }
        }
        missing.forEach { id ->
            runCatching {
                chatCache[id] = td.send(TdApi.GetChat().apply { chatId = id })
            }.onFailure { error ->
                if (error is CancellationException) throw error
                TelefarmLog.w(TAG, "A chat could not be loaded")
            }
        }
    }

    // endregion

    // region Updates

    private fun handleUpdate(update: TdApi.Update) {
        if (closed) return
        when (update) {
            is TdApi.UpdateNewChat -> {
                chatCache[update.chat.id] = update.chat
                markDirty()
            }

            is TdApi.UpdateChatTitle -> updateCached(update.chatId) { it.title = update.title }

            is TdApi.UpdateChatPhoto -> updateCached(update.chatId) { it.photo = update.photo }

            is TdApi.UpdateChatLastMessage -> updateCached(update.chatId) { chat ->
                chat.lastMessage = update.lastMessage
                if (!update.positions.isNullOrEmpty()) chat.positions = update.positions
            }

            is TdApi.UpdateChatPosition -> updateCached(update.chatId) { chat ->
                chat.positions = updateChatPositions(chat.positions, update.position)
            }

            is TdApi.UpdateChatReadInbox -> updateCached(update.chatId) { chat ->
                chat.lastReadInboxMessageId = update.lastReadInboxMessageId
                chat.unreadCount = update.unreadCount
            }

            is TdApi.UpdateChatReadOutbox -> updateCached(update.chatId) { chat ->
                chat.lastReadOutboxMessageId = update.lastReadOutboxMessageId
            }

            is TdApi.UpdateChatUnreadMentionCount -> updateCached(update.chatId) { chat ->
                chat.unreadMentionCount = update.unreadMentionCount
            }

            is TdApi.UpdateChatIsMarkedAsUnread -> updateCached(update.chatId) { chat ->
                chat.isMarkedAsUnread = update.isMarkedAsUnread
            }

            is TdApi.UpdateChatNotificationSettings -> updateCached(update.chatId) { chat ->
                chat.notificationSettings = update.notificationSettings
            }

            is TdApi.UpdateChatDraftMessage -> updateCached(update.chatId) { chat ->
                chat.draftMessage = update.draftMessage
            }

            is TdApi.UpdateChatPermissions -> updateCached(update.chatId) { chat ->
                chat.permissions = update.permissions
            }

            is TdApi.UpdateChatActionBar -> updateCached(update.chatId) { chat ->
                chat.actionBar = update.actionBar
            }

            is TdApi.UpdateChatAvailableReactions -> Unit

            else -> Unit
        }
    }

    private fun updateCached(chatId: Long, block: (TdApi.Chat) -> Unit) {
        val chat = chatCache[chatId] ?: return
        block(chat)
        chatCache[chatId] = chat
        markDirty()
    }

    private fun updateChatPositions(
        positions: Array<TdApi.ChatPosition>?,
        position: TdApi.ChatPosition?
    ): Array<TdApi.ChatPosition>? {
        if (position == null) return positions
        val listType = position.list
        val updated = (positions ?: emptyArray()).filterNot { it.list::class == listType::class }.toMutableList()
        updated.add(position)
        return updated.toTypedArray()
    }

    // endregion

    // region Content

    /** Cached chat object without a network request. */
    fun chatOrNull(chatId: Long): TdApi.Chat? = chatCache[chatId]

    /** Cached chat, loaded from TDLib when it is not known yet. */
    suspend fun chatOrFetch(chatId: Long): TdApi.Chat? {
        chatCache[chatId]?.let { return it }
        return try {
            td.send(TdApi.GetChat().apply { this.chatId = chatId }).also { chatCache[chatId] = it }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            null
        }
    }

    /**
     * Description and member count of a group or channel.
     *
     * The data comes from the full info of the underlying group; a failure returns null so the
     * profile screen can show what it already knows.
     */
    suspend fun chatDetails(chatId: Long): ChatDetails? {
        val chat = chatOrFetch(chatId) ?: return null
        return when (val type = chat.type) {
            is TdApi.ChatTypeSupergroup -> runCatching {
                val info = td.send(TdApi.GetSupergroupFullInfo().apply { supergroupId = type.supergroupId })
                ChatDetails(
                    description = info.description?.takeIf { it.isNotBlank() },
                    memberCount = info.memberCount,
                    canViewMembers = info.canGetMembers
                )
            }.getOrNull()

            is TdApi.ChatTypeBasicGroup -> runCatching {
                val info = td.send(TdApi.GetBasicGroupFullInfo().apply { basicGroupId = type.basicGroupId })
                ChatDetails(
                    description = info.description?.takeIf { it.isNotBlank() },
                    memberCount = info.members?.size ?: 0,
                    canViewMembers = true
                )
            }.getOrNull()

            else -> ChatDetails(description = null, memberCount = 0, canViewMembers = false)
        }
    }

    /** Presentation model of a single chat. */
    fun chatUi(chatId: Long): ChatUi? = chatCache[chatId]?.let { toUi(it) }

    /** Presentation models of every chat currently in the list. */
    fun visibleChats(): List<ChatUi> = synchronized(orderedIds) {
        orderedIds.mapNotNull { id -> chatCache[id]?.let(::toUi) }
    }

    /** Number of chats known to the client, used by pagination. */
    fun chatCount(): Int = synchronized(orderedIds) { orderedIds.size }

    /**
     * Opens (or creates) the private conversation with a user.
     *
     * Used by the contact list, by message actions and for Saved Messages, whose chat id is
     * the id of the signed in user.
     */
    suspend fun openPrivateChat(userId: Long): Long? = try {
        val chat = td.send(
            TdApi.CreatePrivateChat().apply {
                this.userId = userId
                force = false
            }
        )
        chatCache[chat.id] = chat
        synchronized(orderedIds) {
            if (!orderedIds.contains(chat.id)) orderedIds.add(0, chat.id)
        }
        markDirty()
        chat.id
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        TelefarmLog.w(TAG, "Private chat could not be opened")
        null
    }

    private fun toUi(chat: TdApi.Chat): ChatUi {
        val selfUserId = session.selfUserId.value
        val lastMessage = chat.lastMessage
        val senderId = lastMessage?.let { message -> TdMappers.lastMessageSenderId(message, selfUserId) }
        val senderName = when {
            lastMessage == null -> null
            lastMessage.isOutgoing -> null
            senderId != null && senderId == selfUserId -> null
            else -> senderId?.let { id -> users.cachedDisplayName(id) }
        }
        return TdMappers.chat(chat, selfUserId, senderName)
    }

    private fun recomputeState() {
        if (closed) return
        val chats = visibleChats().sortedWith(COMPARATOR)
        _state.value = when {
            chats.isNotEmpty() -> ChatListState.Content(chats)
            _state.value is ChatListState.Failed -> _state.value
            else -> ChatListState.Empty(null)
        }
    }

    private fun markDirty() {
        dirty.trySend(Unit)
    }

    // endregion

    // region Actions

    suspend fun setPinned(chatId: Long, pinned: Boolean) = runAction(R.string.error_generic) {
        td.send(
            TdApi.ToggleChatIsPinned().apply {
                chatList = TdApi.ChatListMain()
                this.chatId = chatId
                isPinned = pinned
            }
        )
    }

    suspend fun setMuted(chatId: Long, muted: Boolean) = runAction(R.string.error_generic) {
        val chat = chatOrFetch(chatId)
        val settings = chat?.notificationSettings ?: TdApi.ChatNotificationSettings()
        settings.muteFor = if (muted) MUTE_FOREVER else 0
        settings.useDefaultMuteFor = false
        td.send(
            TdApi.SetChatNotificationSettings().apply {
                this.chatId = chatId
                notificationSettings = settings
            }
        )
    }

    suspend fun setMarkedAsUnread(chatId: Long, unread: Boolean) = runAction(R.string.error_generic) {
        td.send(
            TdApi.ToggleChatIsMarkedAsUnread().apply {
                this.chatId = chatId
                isMarkedAsUnread = unread
            }
        )
    }

    /** Marks the chat as read up to its newest message. */
    suspend fun markAsRead(chatId: Long) = runAction(R.string.error_generic) {
        val lastMessageId = chatOrFetch(chatId)?.lastMessage?.id ?: 0L
        if (lastMessageId == 0L) return@runAction
        td.send(
            TdApi.ViewMessages().apply {
                this.chatId = chatId
                messageIds = longArrayOf(lastMessageId)
                source = TdApi.MessageSourceChatList()
                forceRead = true
            }
        )
    }

    suspend fun deleteChat(chatId: Long) = runAction(R.string.error_generic) {
        td.send(
            TdApi.DeleteChatHistory().apply {
                this.chatId = chatId
                removeFromChatList = true
                revoke = true
            }
        )
        chatCache.remove(chatId)
        synchronized(orderedIds) { orderedIds.remove(chatId) }
        markDirty()
    }

    suspend fun leaveChat(chatId: Long) = runAction(R.string.error_generic) {
        td.send(TdApi.LeaveChat().apply { this.chatId = chatId })
        chatCache.remove(chatId)
        synchronized(orderedIds) { orderedIds.remove(chatId) }
        markDirty()
    }

    /** Removes the history but keeps the chat in the list. */
    suspend fun clearHistory(chatId: Long) = runAction(R.string.error_generic) {
        td.send(
            TdApi.DeleteChatHistory().apply {
                this.chatId = chatId
                removeFromChatList = false
                revoke = true
            }
        )
        updateCached(chatId) { chat -> chat.lastMessage = null }
    }

    private suspend fun runAction(@androidx.annotation.StringRes errorRes: Int, block: suspend () -> Unit) {
        try {
            block()
            _lastError.value = null
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            TelefarmLog.w(TAG, "Chat action failed")
            _lastError.value = (error as? TdLibException)?.toUiMessage() ?: UiMessage.Res(errorRes)
        }
    }

    fun close() {
        closed = true
        dirty.close()
    }

    // endregion

    private companion object {
        const val TAG = "ChatListRepository"
        const val PAGE_SIZE = 100
        const val LOAD_INITIAL_CHATS = 100
        const val LOAD_MORE_CHATS = 200
        const val RECOMPUTE_INTERVAL_MS = 100L
        const val NOT_FOUND_CODE = 404
        const val MUTE_FOREVER = 2_147_483_647

        /** Pinned chats first, then by the order Telegram reports. */
        val COMPARATOR: Comparator<ChatUi> = compareByDescending<ChatUi> { it.isPinned }
            .thenByDescending { it.order }
    }
}
