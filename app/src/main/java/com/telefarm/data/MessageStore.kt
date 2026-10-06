package com.telefarm.data

import com.telefarm.R
import com.telefarm.core.td.TdLibClient
import com.telefarm.core.td.TdLibException
import com.telefarm.core.td.TelefarmLog
import com.telefarm.data.map.MAX_MESSAGE_LENGTH
import com.telefarm.data.map.TdMappers
import com.telefarm.data.map.toUiMessage
import com.telefarm.data.map.userIdOrNull
import com.telefarm.data.model.ChatActionUi
import com.telefarm.data.model.ChatHeaderUi
import com.telefarm.data.model.ChatSubtitle
import com.telefarm.data.model.MessageRow
import com.telefarm.data.model.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi

/**
 * One open conversation: history, live updates, sending and read state.
 *
 * A store exists exactly as long as its conversation screen; [close] releases the update
 * collector, the drafts and the TDLib chat.
 */
class MessageStore(
    private val chatId: Long,
    private val td: TdLibClient,
    private val chats: ChatListRepository,
    private val users: UserRepository,
    private val session: TdSessionController,
    private val scope: CoroutineScope
) {

    private val messages = LinkedHashMap<Long, TdApi.Message>()
    private val messagesLock = Any()
    private val loadMutex = Mutex()
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    private var updateJob: Job? = null
    private var draftJob: Job? = null
    private var typingResetJob: Job? = null
    private var closed = false
    private var lastReadSentId = 0L
    private var loadedOnce = false
    private var started = false

    private val _rows = MutableStateFlow<List<MessageRow>>(emptyList())
    val rows: StateFlow<List<MessageRow>> = _rows.asStateFlow()

    private val _header = MutableStateFlow(ChatHeaderUi.EMPTY)
    val header: StateFlow<ChatHeaderUi> = _header.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()

    private val _reachedStart = MutableStateFlow(false)
    val reachedStart: StateFlow<Boolean> = _reachedStart.asStateFlow()

    private val _error = MutableStateFlow<UiMessage?>(null)
    val error: StateFlow<UiMessage?> = _error.asStateFlow()

    private val _typing = MutableStateFlow<ChatActionUi?>(null)
    val typing: StateFlow<ChatActionUi?> = _typing.asStateFlow()

    fun start() {
        if (started) return
        started = true
        updateJob = scope.launch { td.updates.collect(::handleUpdate) }
        scope.launch {
            for (ignored in dirty) {
                recomputeRows()
                delay(RECOMPUTE_INTERVAL_MS)
            }
        }
        scope.launch { users.changes.collect { markDirty() } }
        scope.launch { refreshHeader() }
        scope.launch { openChat() }
    }

    // region History

    /** Loads the newest page of the conversation. */
    suspend fun loadInitial() {
        if (closed) return
        if (loadedOnce) {
            loadOlder()
            return
        }
        loadMutex.withLock {
            if (loadedOnce) return
            _isLoading.value = true
            try {
                val page = fetchHistory(fromMessageId = 0L)
                synchronized(messagesLock) { page.forEach { messages[it.id] = it } }
                _reachedStart.value = page.isEmpty()
                loadedOnce = true
                _error.value = null
                loadSenders(page)
                markDirty()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                TelefarmLog.w(TAG, "Conversation could not be loaded")
                _error.value = (error as? TdLibException)?.toUiMessage()
                    ?: UiMessage.Res(R.string.chat_load_error)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Loads older messages; called while the user scrolls up. */
    suspend fun loadOlder() {
        if (closed || _isLoadingOlder.value || _reachedStart.value) return
        loadMutex.withLock {
            if (closed || _isLoadingOlder.value || _reachedStart.value) return
            _isLoadingOlder.value = true
            try {
                val oldest = synchronized(messagesLock) { messages.keys.minOrNull() } ?: 0L
                if (oldest == 0L) {
                    _reachedStart.value = true
                    return
                }
                val page = fetchHistory(fromMessageId = oldest)
                val added = synchronized(messagesLock) {
                    var count = 0
                    page.forEach { message ->
                        if (messages.put(message.id, message) == null) count++
                    }
                    count
                }
                if (added == 0) {
                    // Telegram has nothing older for this conversation.
                    _reachedStart.value = true
                } else {
                    loadSenders(page)
                }
                _error.value = null
                markDirty()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _error.value = (error as? TdLibException)?.toUiMessage()
                    ?: UiMessage.Res(R.string.chat_load_error)
            } finally {
                _isLoadingOlder.value = false
                markDirty()
            }
        }
    }

    private suspend fun fetchHistory(fromMessageId: Long): List<TdApi.Message> {
        val result = td.send(
            TdApi.GetChatHistory().apply {
                chatId = this@MessageStore.chatId
                this.fromMessageId = fromMessageId
                offset = 0
                limit = PAGE_SIZE
                onlyLocal = false
            }
        )
        return result.messages?.toList() ?: emptyList()
    }

    /** Makes sure the authors of the loaded messages are in the user cache. */
    private suspend fun loadSenders(page: List<TdApi.Message>) {
        val ids = page.filterNot { it.isOutgoing }.mapNotNull { it.senderId.userIdOrNull() }.distinct()
        if (ids.isNotEmpty()) users.users(ids)
    }

    private suspend fun openChat() {
        runCatching { td.send(TdApi.OpenChat().apply { chatId = this@MessageStore.chatId }) }
    }

    // endregion

    // region Sending

    /**
     * Sends a text message.
     *
     * Telegram allows at most [MAX_MESSAGE_LENGTH] UTF-16 characters per message; longer input
     * is rejected before it reaches the network.
     *
     * @return true when TDLib accepted the message.
     */
    suspend fun sendText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length > MAX_MESSAGE_LENGTH) {
            _error.value = UiMessage.Res(R.string.chat_too_long, listOf(MAX_MESSAGE_LENGTH))
            return false
        }
        return try {
            td.send(
                TdApi.SendMessage().apply {
                    chatId = this@MessageStore.chatId
                    topicId = null
                    replyTo = null
                    options = null
                    replyMarkup = null
                    inputMessageContent = TdApi.InputMessageText().apply {
                        // `this` is required: the enclosing function also has a `text` parameter.
                        this.text = TdApi.FormattedText().apply {
                            this.text = trimmed
                            entities = emptyArray()
                        }
                        linkPreviewOptions = null
                        clearDraft = true
                    }
                }
            )
            clearDraft()
            _error.value = null
            true
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _error.value = (error as? TdLibException)?.toUiMessage()
                ?: UiMessage.Res(R.string.chat_send_failed)
            false
        }
    }

    /** Retries a message Telegram reported as failed. */
    suspend fun retry(messageId: Long) {
        try {
            td.send(
                TdApi.ResendMessages().apply {
                    chatId = this@MessageStore.chatId
                    messageIds = longArrayOf(messageId)
                    quote = null
                    paidMessageStarCount = 0
                }
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _error.value = (error as? TdLibException)?.toUiMessage()
                ?: UiMessage.Res(R.string.chat_send_failed)
        }
    }

    /** Deletes a message for everyone. */
    suspend fun deleteMessage(messageId: Long) {
        try {
            td.send(
                TdApi.DeleteMessages().apply {
                    chatId = this@MessageStore.chatId
                    messageIds = longArrayOf(messageId)
                    revoke = true
                }
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _error.value = (error as? TdLibException)?.toUiMessage() ?: UiMessage.Res(R.string.error_generic)
        }
    }

    /** Stores the input as a server side draft, debounced so typing does not spam TDLib. */
    fun saveDraft(text: String) {
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            if (closed) return@launch
            val content = if (text.isBlank()) {
                null
            } else {
                TdApi.DraftMessageContentText().apply {
                    this.text = TdApi.FormattedText().apply {
                        this.text = text
                        entities = emptyArray()
                    }
                    linkPreviewOptions = null
                }
            }
            sendDraft(content)
        }
    }

    fun clearDraft() {
        draftJob?.cancel()
        draftJob = scope.launch { sendDraft(null) }
    }

    private suspend fun sendDraft(content: TdApi.DraftMessageContent?) {
        runCatching {
            td.send(
                TdApi.SetChatDraftMessage().apply {
                    chatId = this@MessageStore.chatId
                    topicId = null
                    draftMessage = if (content == null) {
                        null
                    } else {
                        TdApi.DraftMessage().apply {
                            replyTo = null
                            date = (System.currentTimeMillis() / 1000L).toInt()
                            this.content = content
                        }
                    }
                }
            )
        }
    }

    /** The draft Telegram currently stores for this chat, if any. */
    fun currentDraft(): String? =
        (chats.chatOrNull(chatId)?.draftMessage?.content as? TdApi.DraftMessageContentText)?.text?.text

    // endregion

    // region Read state

    /** Marks the newest loaded message as read. */
    suspend fun markRead() {
        val newest = synchronized(messagesLock) { messages.keys.maxOrNull() } ?: return
        if (newest <= lastReadSentId) return
        lastReadSentId = newest
        runCatching {
            td.send(
                TdApi.ViewMessages().apply {
                    chatId = this@MessageStore.chatId
                    messageIds = longArrayOf(newest)
                    source = TdApi.MessageSourceChatHistory()
                    forceRead = false
                }
            )
        }.onFailure {
            // Read state is cosmetic; a failure must not disturb the conversation.
            lastReadSentId = 0L
        }
    }

    // endregion

    // region Updates

    private fun handleUpdate(update: TdApi.Update) {
        if (closed) return
        when (update) {
            is TdApi.UpdateNewMessage -> if (update.message.chatId == chatId) {
                synchronized(messagesLock) { messages[update.message.id] = update.message }
                scope.launch { loadSenders(listOf(update.message)) }
                markDirty()
            }

            is TdApi.UpdateMessageSendSucceeded -> if (update.message.chatId == chatId) {
                synchronized(messagesLock) {
                    messages.remove(update.oldMessageId)
                    messages[update.message.id] = update.message
                }
                markDirty()
            }

            is TdApi.UpdateMessageSendFailed -> if (update.message.chatId == chatId) {
                synchronized(messagesLock) {
                    messages.remove(update.oldMessageId)
                    messages[update.message.id] = update.message
                }
                markDirty()
            }

            is TdApi.UpdateMessageContent -> if (update.chatId == chatId) {
                updateMessage(update.messageId) { message -> message.content = update.newContent }
            }

            is TdApi.UpdateMessageEdited -> if (update.chatId == chatId) {
                updateMessage(update.messageId) { message -> message.editDate = update.editDate }
            }

            is TdApi.UpdateDeleteMessages -> if (update.chatId == chatId && update.isPermanent) {
                synchronized(messagesLock) { update.messageIds?.forEach { messages.remove(it) } }
                markDirty()
            }

            is TdApi.UpdateChatReadOutbox -> if (update.chatId == chatId) {
                markDirty()
            }

            is TdApi.UpdateChatReadInbox -> if (update.chatId == chatId) {
                markDirty()
            }

            is TdApi.UpdateChatTitle -> if (update.chatId == chatId) scope.launch { refreshHeader() }

            is TdApi.UpdateChatPhoto -> if (update.chatId == chatId) scope.launch { refreshHeader() }

            is TdApi.UpdateChatLastMessage -> if (update.chatId == chatId) scope.launch { refreshHeader() }

            is TdApi.UpdateChatAction -> if (update.chatId == chatId) onTyping(chatAction(update))

            is TdApi.UpdateUser -> markDirty()

            else -> Unit
        }
    }

    private fun updateMessage(messageId: Long, block: (TdApi.Message) -> Unit) {
        val message = synchronized(messagesLock) { messages[messageId] } ?: return
        block(message)
        synchronized(messagesLock) { messages[messageId] = message }
        markDirty()
    }

    private fun onTyping(action: ChatActionUi?) {
        _typing.value = action
        typingResetJob?.cancel()
        if (action == null) return
        typingResetJob = scope.launch {
            delay(TYPING_TIMEOUT_MS)
            _typing.value = null
        }
    }

    private fun chatAction(update: TdApi.UpdateChatAction): ChatActionUi? {
        val selfId = session.selfUserId.value
        val senderId = update.senderId?.userIdOrNull()
        if (senderId != null && senderId == selfId) return null
        return when (update.action) {
            is TdApi.ChatActionTyping -> ChatActionUi.TYPING
            is TdApi.ChatActionUploadingPhoto -> ChatActionUi.UPLOADING_PHOTO
            is TdApi.ChatActionRecordingVoiceNote, is TdApi.ChatActionUploadingVoiceNote ->
                ChatActionUi.RECORDING_VOICE
            is TdApi.ChatActionUploadingDocument -> ChatActionUi.UPLOADING_DOCUMENT
            is TdApi.ChatActionChoosingSticker -> ChatActionUi.CHOOSING_STICKER
            is TdApi.ChatActionRecordingVideo, is TdApi.ChatActionUploadingVideo,
            is TdApi.ChatActionRecordingVideoNote, is TdApi.ChatActionUploadingVideoNote ->
                ChatActionUi.RECORDING_VIDEO
            is TdApi.ChatActionStartPlayingGame -> ChatActionUi.PLAYING_GAME
            is TdApi.ChatActionCancel -> null
            else -> null
        }
    }

    // endregion

    // region Header

    private suspend fun refreshHeader() {
        val chat = chats.chatOrFetch(chatId) ?: return
        val selfUserId = session.selfUserId.value
        val kind = TdMappers.chatKind(chat, selfUserId)

        val subtitle = when (val type = chat.type) {
            is TdApi.ChatTypePrivate -> {
                val userId = type.userId
                if (userId == selfUserId) {
                    ChatSubtitle.Self
                } else {
                    ChatSubtitle.Status(TdMappers.status(users.user(userId)?.status))
                }
            }

            is TdApi.ChatTypeBasicGroup -> ChatSubtitle.Members(
                count = basicGroupMemberCount(type.basicGroupId),
                isChannel = false
            )

            is TdApi.ChatTypeSupergroup -> ChatSubtitle.Members(
                count = supergroupMemberCount(type.supergroupId),
                isChannel = type.isChannel
            )

            else -> null
        }

        _header.value = ChatHeaderUi(
            title = chat.title.orEmpty(),
            subtitle = subtitle,
            canSendMessages = TdMappers.canSendMessages(chat, kind) &&
                chat.actionBar !is TdApi.ChatActionBarReportSpam,
            photo = TdMappers.fileRef(chat.photo?.small),
            kind = kind
        )
    }

    private suspend fun basicGroupMemberCount(basicGroupId: Long): Int = runCatching {
        td.send(TdApi.GetBasicGroup().apply { this.basicGroupId = basicGroupId }).memberCount
    }.getOrDefault(0)

    private suspend fun supergroupMemberCount(supergroupId: Long): Int = runCatching {
        td.send(TdApi.GetSupergroup().apply { this.supergroupId = supergroupId }).memberCount
    }.getOrDefault(0)

    // endregion

    private fun markDirty() {
        dirty.trySend(Unit)
    }

    /**
     * Rebuilds the visible rows: messages in ascending order, day separators between days and
     * a loading indicator while older history is on its way.
     */
    private fun recomputeRows() {
        if (closed) return
        val selfUserId = session.selfUserId.value
        val kind = _header.value.kind
        val lastReadOutbox = chats.chatOrNull(chatId)?.lastReadOutboxMessageId ?: 0L

        val sorted = synchronized(messagesLock) { messages.values.sortedBy { it.id } }
        val rows = ArrayList<MessageRow>(sorted.size + 8)
        var lastEpochDay = Long.MIN_VALUE

        sorted.forEach { message ->
            val epochDay = epochDayOf(message.date)
            if (epochDay != lastEpochDay) {
                rows.add(MessageRow.DateSeparator(message.date))
                lastEpochDay = epochDay
            }
            rows.add(
                MessageRow.Message(
                    TdMappers.message(
                        message = message,
                        selfUserId = selfUserId,
                        chatKind = kind,
                        lastReadOutboxMessageId = lastReadOutbox,
                        senderDisplayName = message.senderId.userIdOrNull()?.let { users.cachedDisplayName(it) }
                    )
                )
            )
        }

        if (!_reachedStart.value || _isLoadingOlder.value) {
            rows.add(0, MessageRow.LoadMore(_isLoadingOlder.value))
        }

        _rows.value = rows
    }

    /** Releases the chat when the conversation screen is destroyed. */
    fun close() {
        if (closed) return
        closed = true
        scope.launch {
            runCatching { td.send(TdApi.CloseChat().apply { chatId = this@MessageStore.chatId }) }
        }
        updateJob?.cancel()
        draftJob?.cancel()
        typingResetJob?.cancel()
        dirty.close()
    }

    private fun epochDayOf(timestamp: Int): Long =
        if (timestamp <= 0) Long.MIN_VALUE else timestamp.toLong() / SECONDS_PER_DAY

    private companion object {
        const val TAG = "MessageStore"
        const val PAGE_SIZE = 50
        const val RECOMPUTE_INTERVAL_MS = 50L
        const val TYPING_TIMEOUT_MS = 6_000L
        const val DRAFT_DEBOUNCE_MS = 1_200L
        const val SECONDS_PER_DAY = 86_400L
    }
}
