package com.telefarm.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telefarm.R
import com.telefarm.core.AppGraph
import com.telefarm.data.model.UiMessage
import com.telefarm.data.model.UserUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the contact list screen. */
data class ContactsScreenState(
    val isLoading: Boolean = true,
    val contacts: List<UserUi> = emptyList(),
    val query: String = "",
    val error: UiMessage? = null
) {
    /** Contacts matching the local filter. */
    val visibleContacts: List<UserUi>
        get() = if (query.isBlank()) {
            contacts
        } else {
            contacts.filter { contact ->
                contact.displayName.contains(query, ignoreCase = true) ||
                    contact.username?.contains(query, ignoreCase = true) == true
            }
        }

    val isEmpty: Boolean get() = !isLoading && error == null && visibleContacts.isEmpty()
}

/**
 * Contacts of the signed in account.
 *
 * The list comes from Telegram through TDLib and filtering happens locally, so typing stays
 * instant. An account without contacts sees an explicit empty state, never a demo list.
 */
class ContactsViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(ContactsScreenState())
    val state: StateFlow<ContactsScreenState> = _state.asStateFlow()

    init {
        load()
    }

    /** Reloads the contact list from Telegram. */
    fun load() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val contacts = graph.users.contacts()
                _state.value = _state.value.copy(isLoading = false, contacts = contacts, error = null)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = UiMessage.Res(R.string.contacts_error)
                )
            }
        }
    }

    fun onQueryChanged(query: String) {
        _state.value = _state.value.copy(query = query)
    }
}
