package com.telefarm.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telefarm.R
import com.telefarm.core.AppGraph
import com.telefarm.data.SearchRepository
import com.telefarm.data.model.ChatUi
import com.telefarm.data.model.SearchResultUi
import com.telefarm.data.model.UiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the search screen. */
data class SearchScreenState(
    val query: String = "",
    val isLoading: Boolean = false,
    val error: UiMessage? = null,
    val chats: List<ChatUi> = emptyList(),
    val messages: List<SearchResultUi> = emptyList(),
    val isChatOnly: Boolean = false
) {
    val hasResults: Boolean get() = chats.isNotEmpty() || messages.isNotEmpty()
    val isEmptyResult: Boolean
        get() = !isLoading && error == null && query.trim().length >= SearchRepository.MIN_QUERY_LENGTH && !hasResults
}

/**
 * Search across chats and messages.
 *
 * Queries are debounced and the previous request is cancelled, so typing fast never queues
 * work and results always belong to the text currently in the field. When a chat id is given
 * the search is limited to that conversation.
 */
class SearchViewModel(
    private val graph: AppGraph,
    private val chatId: Long = 0L
) : ViewModel() {

    private val _state = MutableStateFlow(SearchScreenState(isChatOnly = chatId != 0L))
    val state: StateFlow<SearchScreenState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        _state.value = _state.value.copy(query = query, error = null)
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < SearchRepository.MIN_QUERY_LENGTH) {
            _state.value = _state.value.copy(isLoading = false, chats = emptyList(), messages = emptyList())
            return
        }
        graph.search.prefetch(trimmed)
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _state.value = _state.value.copy(isLoading = true)
            runSearch(trimmed)
        }
    }

    private suspend fun runSearch(query: String) {
        try {
            if (chatId != 0L) {
                val messages = graph.search.searchInChat(chatId, query)
                commit(query, chats = emptyList(), messages = messages)
            } else {
                val messages = graph.search.searchMessages(query)
                val chats = graph.search.searchChats(query)
                commit(query, chats = chats, messages = messages)
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (_state.value.query.trim() != query) return
            _state.value = _state.value.copy(isLoading = false, error = SearchRepository.errorFor(error))
        }
    }

    private fun commit(query: String, chats: List<ChatUi>, messages: List<SearchResultUi>) {
        if (_state.value.query.trim() != query) return
        _state.value = _state.value.copy(isLoading = false, error = null, chats = chats, messages = messages)
    }

    /** Keeps the search field in sync when the screen returns to the foreground. */
    fun clear() {
        searchJob?.cancel()
        _state.value = SearchScreenState(isChatOnly = chatId != 0L)
    }

    fun reportChatUnavailable() {
        _state.value = _state.value.copy(error = UiMessage.Res(R.string.search_error))
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
    }
}
