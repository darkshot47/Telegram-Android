package com.telefarm.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telefarm.core.AppGraph
import com.telefarm.data.AppSettings
import com.telefarm.data.model.AuthState
import com.telefarm.data.model.ChatListState
import com.telefarm.data.model.ChatUi
import com.telefarm.data.model.ConnectionStatus
import com.telefarm.data.model.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the chat list screen renders. */
data class MainState(
    val isLoading: Boolean = true,
    val chats: List<ChatUi> = emptyList(),
    val isEmpty: Boolean = false,
    val error: UiMessage? = null,
    val isRefreshing: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTING,
    val requiresSignIn: Boolean = false
)

/**
 * Chat list logic.
 *
 * Chats come from TDLib through [com.telefarm.data.ChatListRepository]; the view model only
 * combines them with the connection and authorization state.
 */
class MainViewModel(private val graph: AppGraph) : ViewModel() {

    val settings: StateFlow<AppSettings> = graph.settings.settings

    private val _transientError = MutableStateFlow<UiMessage?>(null)

    /** Message shown once, for example after a failed chat action. */
    val transientError: StateFlow<UiMessage?> = _transientError.asStateFlow()

    val state: StateFlow<MainState> = combine(
        graph.chats.state,
        graph.chats.isRefreshing,
        graph.session.connectionStatus,
        graph.session.authState
    ) { chatListState, refreshing, connection, authState ->
        MainState(
            isLoading = chatListState is ChatListState.Loading,
            chats = (chatListState as? ChatListState.Content)?.chats.orEmpty(),
            isEmpty = chatListState is ChatListState.Empty,
            error = (chatListState as? ChatListState.Failed)?.message,
            isRefreshing = refreshing,
            connectionStatus = connection,
            requiresSignIn = authState.requiresSignIn()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MainState())

    init {
        viewModelScope.launch { graph.chats.loadInitial() }
        viewModelScope.launch {
            graph.chats.lastError.collect { error -> _transientError.value = error }
        }
    }

    fun refresh() {
        viewModelScope.launch { graph.chats.refresh() }
    }

    fun loadMore() {
        viewModelScope.launch { graph.chats.loadMore() }
    }

    fun retry() {
        viewModelScope.launch { graph.chats.loadInitial() }
    }

    fun clearError() {
        _transientError.value = null
    }

    // region Chat actions

    fun setPinned(chatId: Long, pinned: Boolean) = viewModelScope.launch {
        graph.chats.setPinned(chatId, pinned)
    }

    fun setMuted(chatId: Long, muted: Boolean) = viewModelScope.launch {
        graph.chats.setMuted(chatId, muted)
    }

    fun setMarkedAsUnread(chatId: Long, unread: Boolean) = viewModelScope.launch {
        graph.chats.setMarkedAsUnread(chatId, unread)
    }

    fun markAsRead(chatId: Long) = viewModelScope.launch {
        graph.chats.markAsRead(chatId)
    }

    fun deleteChat(chatId: Long) = viewModelScope.launch {
        graph.chats.deleteChat(chatId)
    }

    /** Resolves Saved Messages, whose chat id is the id of the signed in user. */
    fun openSavedMessages(onResolved: (Long?) -> Unit) {
        viewModelScope.launch {
            val selfUserId = graph.session.selfUserId.value
            onResolved(if (selfUserId > 0) graph.chats.openPrivateChat(selfUserId) else null)
        }
    }

    /** Signs out and clears everything held in memory. */
    fun logOut() {
        viewModelScope.launch {
            graph.notifications.dismissAll()
            graph.messages.releaseAll()
            graph.settings.logOut()
        }
    }

    /** Stops playback when the screen goes away. */
    fun stopPlayback() {
        graph.voicePlayer.stop()
    }

    fun chatById(chatId: Long): ChatUi? = graph.chats.chatUi(chatId)

    // endregion

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** True when the current authorization state has to be handled by the sign in screen. */
private fun AuthState.requiresSignIn(): Boolean = when (this) {
    AuthState.WaitPhoneNumber, AuthState.Closed -> true
    is AuthState.Failed -> true
    is AuthState.WaitCode, is AuthState.WaitPassword, is AuthState.WaitEmailAddress,
    is AuthState.WaitEmailCode, is AuthState.WaitRegistration,
    is AuthState.WaitOtherDeviceConfirmation -> true
    AuthState.Initializing, AuthState.LoggingOut, AuthState.Ready -> false
}
