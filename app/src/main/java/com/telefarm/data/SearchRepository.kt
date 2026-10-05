package com.telefarm.data

import com.telefarm.core.td.TdLibClient
import com.telefarm.core.td.TdLibException
import com.telefarm.core.td.TelefarmLog
import com.telefarm.data.map.TdMappers
import com.telefarm.data.map.toUiMessage
import com.telefarm.data.model.ChatUi
import com.telefarm.data.model.SearchResultKind
import com.telefarm.data.model.SearchResultUi
import com.telefarm.data.model.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Chat and message search backed by TDLib.
 *
 * Chats are searched twice: locally first (instant) and on Telegram for chats this device has
 * never seen; both result sets are merged by chat id.
 */
class SearchRepository(
    private val td: TdLibClient,
    private val chats: ChatListRepository,
    private val users: UserRepository,
    private val session: TdSessionController,
    private val scope: CoroutineScope
) {

    /** Searches chats locally and on Telegram. */
    suspend fun searchChats(query: String, limit: Int = DEFAULT_LIMIT): List<ChatUi> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return emptyList()

        val found = LinkedHashMap<Long, TdApi.Chat>()
        val local = runCatching { td.send(localChatsRequest(trimmed, limit)) }.getOrNull()
        local?.chatIds?.forEach { chatId ->
            chats.chatOrNull(chatId)?.let { chat -> found[chat.id] = chat }
        }

        val serverIds = runCatching { td.send(serverChatsRequest(trimmed, limit)) }
            .getOrNull()?.chatIds?.toList().orEmpty()
        if (serverIds.isNotEmpty()) {
            coroutineScope {
                serverIds.map { chatId -> async { chats.chatOrFetch(chatId) } }
                    .awaitAll()
                    .filterNotNull()
                    .forEach { chat -> found[chat.id] = chat }
            }
        }

        val selfUserId = session.selfUserId.value
        return found.values.map { chat ->
            val senderId = chat.lastMessage?.let { message -> TdMappers.lastMessageSenderId(message, selfUserId) }
            val senderName = if (chat.lastMessage?.isOutgoing == true) null else senderId?.let(users::cachedDisplayName)
            TdMappers.chat(chat, selfUserId, senderName)
        }
    }

    /** Searches messages across all chats. */
    suspend fun searchMessages(query: String, limit: Int = DEFAULT_LIMIT): List<SearchResultUi> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return emptyList()
        val found = td.send(
            TdApi.SearchMessages().apply {
                chatList = TdApi.ChatListMain()
                this.query = trimmed
                offset = ""
                this.limit = limit
                filter = TdApi.SearchMessagesFilterEmpty()
                chatTypeFilter = null
                minDate = 0
                maxDate = 0
            }
        )
        return mapMessages(found.messages)
    }

    /** Searches messages inside one conversation. */
    suspend fun searchInChat(chatId: Long, query: String, limit: Int = DEFAULT_LIMIT): List<SearchResultUi> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return emptyList()
        val found = td.send(
            TdApi.SearchChatMessages().apply {
                this.chatId = chatId
                topicId = null
                this.query = trimmed
                senderId = null
                fromMessageId = 0L
                offset = 0
                this.limit = limit
                filter = null
            }
        )
        return mapMessages(found.messages)
    }

    private suspend fun mapMessages(messages: Array<TdApi.Message>?): List<SearchResultUi> {
        if (messages.isNullOrEmpty()) return emptyList()
        val chatIds = messages.map { it.chatId }.distinct()
        val chatTitles = coroutineScope {
            chatIds.map { chatId ->
                async {
                    val chat = chats.chatOrFetch(chatId) ?: return@async chatId to null
                    chatId to TdMappers.chat(chat, session.selfUserId.value)
                }
            }.awaitAll().toMap()
        }

        val senderIds = messages.mapNotNull { it.senderId }.filterIsInstance<TdApi.MessageSenderUser>()
            .map { it.userId }.distinct()
        if (senderIds.isNotEmpty()) users.users(senderIds)

        return messages.map { message ->
            val chat = chatTitles[message.chatId]
            SearchResultUi(
                kind = SearchResultKind.MESSAGE,
                id = message.id,
                chatId = message.chatId,
                messageId = message.id,
                title = chat?.title ?: message.chatId.toString(),
                subtitle = messagePreviewText(message),
                photo = chat?.photo,
                date = message.date
            )
        }
    }

    /** Plain text of a message, used as the subtitle of a search result. */
    private fun messagePreviewText(message: TdApi.Message): String? = when (val content = message.content) {
        is TdApi.MessageText -> content.text?.text
        is TdApi.MessagePhoto -> content.caption?.text
        is TdApi.MessageVideo -> content.caption?.text
        is TdApi.MessageDocument -> content.caption?.text
        is TdApi.MessageAudio -> content.caption?.text
        is TdApi.MessageVoiceNote -> content.caption?.text
        is TdApi.MessageAnimation -> content.caption?.text
        else -> null
    }

    private fun localChatsRequest(query: String, limit: Int) = TdApi.SearchChats().apply {
        this.query = query
        typeFilter = null
        this.limit = limit
    }

    private fun serverChatsRequest(query: String, limit: Int) = TdApi.SearchChatsOnServer().apply {
        this.query = query
        typeFilter = null
        this.limit = limit
    }

    /** Warms the local chat cache up so a later query answers instantly. */
    fun prefetch(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return
        scope.launch {
            runCatching { td.send(localChatsRequest(trimmed, DEFAULT_LIMIT)) }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    TelefarmLog.w(TAG, "Chat prefetch failed")
                }
        }
    }

    companion object {
        private const val TAG = "SearchRepository"

        /** Shortest query TDLib searches for. */
        const val MIN_QUERY_LENGTH = 2
        const val DEFAULT_LIMIT = 30

        private val FALLBACK_ERROR = UiMessage.Res(com.telefarm.R.string.search_error)

        /** Maps an exception to the message the search screen shows. */
        fun errorFor(error: Throwable): UiMessage =
            (error as? TdLibException)?.toUiMessage() ?: FALLBACK_ERROR
    }
}
