package com.telefarm.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telefarm.R
import com.telefarm.core.AppGraph
import com.telefarm.data.map.TdMappers
import com.telefarm.data.model.ChatKind
import com.telefarm.data.model.ProfileUi
import com.telefarm.data.model.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the profile screen. */
data class ProfileScreenState(
    val isLoading: Boolean = true,
    val profile: ProfileUi? = null,
    val error: UiMessage? = null
)

/**
 * Profiles of users, groups and channels.
 *
 * Exactly one of [userId] and [chatId] is set: a user profile is opened from the contact list,
 * a chat profile from a conversation. Every value shown here comes from TDLib.
 */
class ProfileViewModel(
    private val graph: AppGraph,
    private val userId: Long = 0L,
    private val chatId: Long = 0L
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileScreenState())
    val state: StateFlow<ProfileScreenState> = _state.asStateFlow()

    /** Event shown once, for example after leaving a group. */
    private val _event = MutableStateFlow<ProfileEvent?>(null)
    val event: StateFlow<ProfileEvent?> = _event.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val profile = if (userId != 0L) loadUser() else loadChat()
            _state.value = if (profile == null) {
                ProfileScreenState(isLoading = false, error = UiMessage.Res(R.string.profile_error))
            } else {
                ProfileScreenState(isLoading = false, profile = profile)
            }
        }
    }

    private suspend fun loadUser(): ProfileUi? = graph.users.profile(userId)

    private suspend fun loadChat(): ProfileUi? {
        val chat = graph.chats.chatOrFetch(chatId) ?: return null
        val kind = TdMappers.chatKind(chat, graph.session.selfUserId.value)
        val details = graph.chats.chatDetails(chatId)
        return TdMappers.profile(
            chat = chat,
            selfUserId = graph.session.selfUserId.value,
            bio = details?.description,
            memberCount = details?.memberCount ?: 0,
            canViewMembers = details?.canViewMembers == true,
            canLeave = kind != ChatKind.SAVED_MESSAGES
        )
    }

    /** Opens the conversation of this profile, creating it for a user when needed. */
    fun openConversation() {
        viewModelScope.launch {
            val targetChatId = if (chatId != 0L) chatId else graph.chats.openPrivateChat(userId)
            if (targetChatId == null) {
                _event.value = ProfileEvent.Error(UiMessage.Res(R.string.error_generic))
                return@launch
            }
            _event.value = ProfileEvent.OpenChat(targetChatId)
        }
    }

    /** Leaves a group or channel. */
    fun leave() {
        val profile = _state.value.profile ?: return
        if (profile.isUser) return
        viewModelScope.launch {
            graph.chats.leaveChat(profile.id)
            val error = graph.chats.lastError.value
            _event.value = if (error == null) {
                ProfileEvent.Left(R.string.profile_leave_success)
            } else {
                ProfileEvent.Error(error)
            }
        }
    }

    fun clearEvent() {
        _event.value = null
    }

    /** Chat whose members are listed, when the profile can open a member list. */
    fun memberListTarget(): Long = if (chatId != 0L) chatId else 0L
}

/** One shot event of the profile screen. */
sealed interface ProfileEvent {
    data class OpenChat(val chatId: Long) : ProfileEvent
    data class Left(val resId: Int) : ProfileEvent
    data class Error(val message: UiMessage) : ProfileEvent
}
