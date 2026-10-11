package com.telefarm.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telefarm.R
import com.telefarm.core.AppGraph
import com.telefarm.core.folders.FolderMatcher
import com.telefarm.data.AppSettings
import com.telefarm.data.FolderCreateResult
import com.telefarm.data.model.AuthState
import com.telefarm.data.model.ChatFolder
import com.telefarm.data.model.ChatFolderUi
import com.telefarm.data.model.ChatListState
import com.telefarm.data.model.ChatUi
import com.telefarm.data.model.ConnectionStatus
import com.telefarm.data.model.FolderRule
import com.telefarm.data.model.FolderRuleMode
import com.telefarm.data.model.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the chat list screen has to do about the authorization state. */
enum class SignInRequirement {
    /** TDLib has not reported a usable state yet: the screen shows a loading state only. */
    UNKNOWN,

    /** The session is not authorized: the sign in screen takes over. */
    REQUIRED,

    /** A session is authorized and the chat list is the right screen. */
    NOT_REQUIRED
}

/** Everything the chat list screen renders. */
data class MainState(
    val isLoading: Boolean = true,
    val chats: List<ChatUi> = emptyList(),
    val isEmpty: Boolean = false,
    val error: UiMessage? = null,
    val isRefreshing: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTING,
    val signIn: SignInRequirement = SignInRequirement.UNKNOWN,
    /** Tabs of the folder strip, with the badge each of them carries right now. */
    val folders: List<ChatFolderUi> = emptyList(),
    /** Folder that is shown, or null while the whole list is shown. */
    val activeFolderId: Long? = null,
    /** True when the list is short because a folder filters it, not because there is nothing. */
    val isFilteredEmpty: Boolean = false
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

    /** Folders and the one that is open, as a single flow so the combination below stays typed. */
    private val folderTabs = combine(
        graph.folders.folders,
        graph.folders.activeFolder
    ) { folders, active -> folders to active }

    val state: StateFlow<MainState> = combine(
        combine(graph.chats.state, graph.chats.isRefreshing) { list, refreshing -> list to refreshing },
        combine(graph.session.connectionStatus, graph.session.authState) { connection, auth -> connection to auth },
        folderTabs
    ) { (chatListState, refreshing), (connection, authState), (folders, activeFolder) ->
        val allChats = (chatListState as? ChatListState.Content)?.chats.orEmpty()
        val visible = FolderMatcher.select(activeFolder, allChats)
        MainState(
            isLoading = chatListState is ChatListState.Loading,
            chats = visible,
            isEmpty = chatListState is ChatListState.Empty,
            error = (chatListState as? ChatListState.Failed)?.message,
            isRefreshing = refreshing,
            connectionStatus = connection,
            signIn = authState.signInRequirement(),
            folders = FolderMatcher.summarizeAll(folders, allChats),
            activeFolderId = activeFolder?.id,
            isFilteredEmpty = visible.isEmpty() && allChats.isNotEmpty() && activeFolder != null
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

    // region Folders

    /** Shows a folder, or the whole list when [folderId] is null. */
    fun selectFolder(folderId: Long?) {
        graph.folders.select(folderId)
    }

    /** Adds a tab; a failure is reported the same way as a failed chat action. */
    fun createFolder(name: String, rules: Set<FolderRule>, mode: FolderRuleMode) {
        when (val result = graph.folders.create(name, rules, mode)) {
            is FolderCreateResult.Created -> graph.folders.select(result.folder.id)
            FolderCreateResult.EmptyName -> _transientError.value = UiMessage.Res(R.string.folder_error_empty_name)
            FolderCreateResult.TooManyFolders ->
                _transientError.value = UiMessage.Res(R.string.folder_error_too_many)
        }
    }

    fun renameFolder(folderId: Long, name: String) {
        graph.folders.rename(folderId, name)
    }

    fun setFolderRules(folderId: Long, rules: Set<FolderRule>, mode: FolderRuleMode) {
        graph.folders.setRules(folderId, rules, mode)
    }

    fun deleteFolder(folderId: Long) {
        graph.folders.delete(folderId)
    }

    /** Folders in tab order, for the dialogs that name one. */
    fun allFolders(): List<ChatFolder> = graph.folders.folders.value

    fun moveFolderEarlier(folderId: Long) {
        graph.folders.moveEarlier(folderId)
    }

    fun moveFolderLater(folderId: Long) {
        graph.folders.moveLater(folderId)
    }

    /** Folders a chat could still be added to. */
    fun foldersWithout(chatId: Long): List<ChatFolder> =
        graph.folders.folders.value.filter { chatId !in it.includeChatIds }

    /** Folders that name a chat by hand and could have it taken out again. */
    fun foldersWith(chatId: Long): List<ChatFolder> =
        graph.folders.folders.value.filter { chatId in it.includeChatIds }

    fun addChatToFolder(folderId: Long, chatId: Long) {
        graph.folders.addChat(folderId, chatId)
    }

    fun removeChatFromFolder(folderId: Long, chatId: Long) {
        graph.folders.removeChat(folderId, chatId)
    }

    // endregion

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

/**
 * Sorts the authorization state into the three cases the chat list has to tell apart.
 *
 * `Initializing` and `LoggingOut` mean "not decided yet" and must not be read as "signed in":
 * that was the bug that opened the chat list on a fresh install before the sign in screen.
 */
private fun AuthState.signInRequirement(): SignInRequirement = when (this) {
    AuthState.Ready -> SignInRequirement.NOT_REQUIRED
    AuthState.Initializing, AuthState.LoggingOut -> SignInRequirement.UNKNOWN
    AuthState.WaitPhoneNumber, AuthState.Closed -> SignInRequirement.REQUIRED
    is AuthState.Failed -> SignInRequirement.REQUIRED
    is AuthState.WaitCode, is AuthState.WaitPassword, is AuthState.WaitEmailAddress,
    is AuthState.WaitEmailCode, is AuthState.WaitRegistration,
    is AuthState.WaitOtherDeviceConfirmation -> SignInRequirement.REQUIRED
}
