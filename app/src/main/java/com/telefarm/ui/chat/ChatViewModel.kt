package com.telefarm.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telefarm.R
import com.telefarm.core.AppGraph
import com.telefarm.data.model.ChatActionUi
import com.telefarm.data.model.ChatHeaderUi
import com.telefarm.data.model.ChatSubtitle
import com.telefarm.data.model.ChatKind
import com.telefarm.data.model.MessageRow
import com.telefarm.data.model.UiMessage
import com.telefarm.ui.common.TimeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Peer described by an opened profile screen.
 *
 * A private chat has a user profile, every other chat has a chat profile. The type carries the
 * identifier that the profile screen needs, so the activity does not have to guess it.
 */
sealed interface ProfileTarget {
    data class User(val userId: Long) : ProfileTarget
    data class ChatInfo(val chatId: Long) : ProfileTarget
}

/** Screen state of one conversation. */
sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Empty(val header: ChatHeaderUi) : ChatUiState
    data class Failed(val message: UiMessage) : ChatUiState
    data class Content(val header: ChatHeaderUi, val rows: List<MessageRow>) : ChatUiState
}

/** One shot notification shown by the conversation screen. */
sealed interface ChatEvent {
    /** Text is either fixed ([textRes]) or an error that has to be resolved ([message]). */
    val textRes: Int
    val message: UiMessage?

    data class Message(override val textRes: Int, override val message: UiMessage? = null) : ChatEvent
    data class Error(override val textRes: Int = 0, override val message: UiMessage? = null) : ChatEvent
}

/**
 * One open conversation.
 *
 * History, live updates and sending belong to [com.telefarm.data.MessageStore]; this view model
 * publishes them to the screen and forwards user actions.
 */
class ChatViewModel(
    private val graph: AppGraph,
    val chatId: Long
) : ViewModel() {

    private val store = graph.messages.store(chatId)

    val header: StateFlow<ChatHeaderUi> = store.header

    /** Typing state of other members, null when nobody is doing anything. */
    val typing: StateFlow<ChatActionUi?> = store.typing

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _event = MutableStateFlow<ChatEvent?>(null)
    val event: StateFlow<ChatEvent?> = _event.asStateFlow()

    private val _profileTarget = MutableStateFlow<ProfileTarget?>(null)
    val profileTarget: StateFlow<ProfileTarget?> = _profileTarget.asStateFlow()

    /**
     * Everything the screen renders. The conversation counts as empty only when the store has
     * finished loading and really contains no message.
     */
    val state: StateFlow<ChatUiState> = combine(
        store.rows,
        store.header,
        store.isLoading,
        store.error
    ) { rows, header, loading, error ->
        val hasMessage = rows.any { it is MessageRow.Message }
        when {
            hasMessage -> ChatUiState.Content(header, rows)
            loading -> ChatUiState.Loading
            error != null -> ChatUiState.Failed(error)
            else -> ChatUiState.Empty(header)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatUiState.Loading)

    init {
        viewModelScope.launch { store.loadInitial() }
        viewModelScope.launch { resolveProfileTarget() }
        graph.notifications.onChatOpened(chatId)
    }

    // region History

    fun loadOlder() {
        viewModelScope.launch { store.loadOlder() }
    }

    fun retryLoad() {
        viewModelScope.launch { store.loadInitial() }
    }

    fun retryMessage(messageId: Long) {
        viewModelScope.launch { store.retry(messageId) }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            store.deleteMessage(messageId)
            _event.value = ChatEvent.Message(R.string.chat_message_deleted)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            try {
                graph.chats.clearHistory(chatId)
                _event.value = ChatEvent.Message(R.string.chat_history_cleared)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _event.value = ChatEvent.Error(message = UiMessage.Res(R.string.chat_clear_failed))
            }
        }
    }

    // endregion

    // region Sending

    /** Sends the composer content; returns true when TDLib accepted it. */
    suspend fun sendText(text: String): Boolean {
        if (text.isBlank()) return false
        _isSending.value = true
        return try {
            val sent = store.sendText(text)
            if (!sent) _event.value = ChatEvent.Error(message = UiMessage.Res(R.string.chat_send_failed))
            sent
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _event.value = ChatEvent.Error(message = UiMessage.Res(R.string.chat_send_failed))
            false
        } finally {
            _isSending.value = false
        }
    }

    fun saveDraft(text: String) {
        store.saveDraft(text)
    }

    /** Draft restored when the screen was reopened; null when there is none. */
    fun draft(): String? = store.currentDraft()

    fun markRead() {
        viewModelScope.launch { store.markRead() }
    }

    fun clearEvent() {
        _event.value = null
    }

    // endregion

    private suspend fun resolveProfileTarget() {
        val chat = runCatching { graph.chats.chatOrFetch(chatId) }.getOrNull() ?: return
        _profileTarget.value = when (val type = chat.type) {
            is TdApi.ChatTypePrivate -> ProfileTarget.User(type.userId)
            else -> ProfileTarget.ChatInfo(chatId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        graph.notifications.onChatClosed(chatId)
        graph.messages.release(chatId)
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Text of the indicator shown while another member is doing something in the chat. */
fun chatActionLabel(action: ChatActionUi): Int = when (action) {
    ChatActionUi.TYPING -> R.string.chat_action_typing
    ChatActionUi.UPLOADING_PHOTO -> R.string.chat_action_uploading_photo
    ChatActionUi.RECORDING_VOICE -> R.string.chat_action_recording_voice
    ChatActionUi.UPLOADING_DOCUMENT -> R.string.chat_action_uploading_document
    ChatActionUi.CHOOSING_STICKER -> R.string.chat_action_choosing_sticker
    ChatActionUi.RECORDING_VIDEO -> R.string.chat_action_recording_video
    ChatActionUi.PLAYING_GAME -> R.string.chat_action_playing_game
}

/**
 * Subtitle of the conversation header.
 *
 * Only values Telegram reported are used; when a group size is not known the chat type label
 * is shown instead of a made up number.
 */
fun subtitleOf(context: android.content.Context, header: ChatHeaderUi): CharSequence? =
    when (val subtitle = header.subtitle) {
        is ChatSubtitle.Status -> TimeFormat.status(context, subtitle.status)
        is ChatSubtitle.Members -> if (subtitle.isChannel) {
            context.resources.getQuantityString(
                R.plurals.subscribers_count,
                subtitle.count,
                subtitle.count
            )
        } else {
            context.resources.getQuantityString(R.plurals.members_count, subtitle.count, subtitle.count)
        }

        ChatSubtitle.Self -> context.getString(R.string.profile_status_self)
        null -> when (header.kind) {
            ChatKind.CHANNEL -> context.getString(R.string.profile_title_channel)
            ChatKind.BASIC_GROUP, ChatKind.SUPERGROUP -> context.getString(R.string.profile_title_group)
            else -> null
        }
    }
