package com.telefarm.ui.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.data.model.SearchResultKind
import com.telefarm.data.model.SearchResultUi
import com.telefarm.databinding.ActivitySearchBinding
import com.telefarm.databinding.ItemSearchResultBinding
import com.telefarm.media.ThumbnailLoader
import com.telefarm.ui.chat.ChatActivity
import com.telefarm.ui.common.Avatars
import com.telefarm.ui.common.PreviewText
import com.telefarm.ui.common.TimeFormat
import com.telefarm.ui.common.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Search screen.
 *
 * Results are grouped into chats and messages; both open the conversation the result belongs
 * to. Every state of the query is explicit: empty prompt, loading, no results and errors.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: SearchResultAdapter

    private val chatId: Long by lazy { intent.getLongExtra(EXTRA_CHAT_ID, 0L) }

    private val viewModel: SearchViewModel by viewModels {
        viewModelFactory { initializer { SearchViewModel(appGraph, chatId) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SearchResultAdapter(
            loader = appGraph.thumbnails,
            scope = lifecycleScope,
            onResultClick = ::openChat
        )
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.searchInput.hint = getString(if (chatId != 0L) R.string.search_in_chat_hint else R.string.search_hint)
        binding.searchInput.doAfterTextChanged { editable ->
            viewModel.onQueryChanged(editable?.toString().orEmpty())
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                binding.searchInput.clearFocus()
                true
            } else {
                false
            }
        }
        binding.searchInput.requestFocus()

        observeState()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: SearchScreenState) {
        val items = buildItems(state)
        adapter.submitList(items)

        val showPrompt = state.query.trim().length < 2
        val showLoading = state.isLoading
        val showEmpty = state.isEmptyResult
        val showError = state.error != null && !state.hasResults

        binding.stateContainer.isVisible = showPrompt || showLoading || showEmpty || showError
        binding.stateProgress.isVisible = showLoading
        binding.stateTitle.isVisible = showPrompt || showEmpty || showError
        binding.stateMessage.isVisible = showEmpty || showError

        when {
            showError -> {
                binding.stateTitle.setText(R.string.search_error)
                binding.stateMessage.text = state.error?.resolve(this)
            }
            showEmpty -> {
                binding.stateTitle.setText(R.string.search_no_results)
                binding.stateMessage.text = null
            }
            showPrompt -> {
                binding.stateTitle.setText(R.string.search_empty_query)
                binding.stateMessage.text = null
            }
        }
    }

    private fun buildItems(state: SearchScreenState): List<SearchListItem> {
        val items = mutableListOf<SearchListItem>()
        if (state.chats.isNotEmpty()) {
            items += SearchListItem.Header(getString(R.string.search_section_chats))
            items += state.chats.map { chat ->
                SearchListItem.Result(
                    SearchResultUi(
                        kind = SearchResultKind.CHAT,
                        id = chat.id,
                        chatId = chat.id,
                        messageId = 0L,
                        title = chat.title,
                        subtitle = PreviewText.of(this, chat),
                        photo = chat.photo,
                        date = chat.lastMessageDate
                    )
                )
            }
        }
        if (state.messages.isNotEmpty()) {
            items += SearchListItem.Header(getString(R.string.search_section_messages))
            items += state.messages.map { SearchListItem.Result(it) }
        }
        return items
    }

    private fun openChat(result: SearchResultUi) {
        startActivity(ChatActivity.intent(this, result.chatId))
    }

    companion object {

        private const val EXTRA_CHAT_ID = "com.telefarm.extra.CHAT_ID"

        fun intent(context: Context): Intent = Intent(context, SearchActivity::class.java)

        /** Opens the search screen limited to one conversation. */
        fun intent(context: Context, chatId: Long): Intent =
            Intent(context, SearchActivity::class.java).putExtra(EXTRA_CHAT_ID, chatId)
    }
}

/** Items rendered by the search list. */
sealed interface SearchListItem {
    val stableId: Long

    data class Header(val title: String) : SearchListItem {
        override val stableId: Long get() = title.hashCode().toLong()
    }

    data class Result(val result: SearchResultUi) : SearchListItem {
        override val stableId: Long get() = result.kind.ordinal.toLong() * 1_000_000L + result.id
    }
}

/** Renders search results and their section headers. */
private class SearchResultAdapter(
    private val loader: ThumbnailLoader,
    private val scope: CoroutineScope,
    private val onResultClick: (SearchResultUi) -> Unit
) : ListAdapter<SearchListItem, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchListItem.Header -> TYPE_HEADER
        is SearchListItem.Result -> TYPE_RESULT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_search_header, parent, false))
        } else {
            ResultViewHolder(
                ItemSearchResultBinding.inflate(inflater, parent, false),
                loader,
                scope,
                onResultClick
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is SearchListItem.Result -> (holder as ResultViewHolder).bind(item.result)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ResultViewHolder) holder.release()
        super.onViewRecycled(holder)
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.headerLabel)

        fun bind(item: SearchListItem.Header) {
            label.text = item.title
        }
    }

    class ResultViewHolder(
        private val binding: ItemSearchResultBinding,
        private val loader: ThumbnailLoader,
        private val scope: CoroutineScope,
        private val onResultClick: (SearchResultUi) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var current: SearchResultUi? = null

        init {
            binding.resultRow.setOnClickListener {
                current?.let(onResultClick)
            }
        }

        fun bind(result: SearchResultUi) {
            current = result
            binding.resultTitle.text = result.title
            binding.resultSubtitle.isVisible = !result.subtitle.isNullOrBlank()
            binding.resultSubtitle.text = result.subtitle.orEmpty()
            binding.resultTime.text = TimeFormat.chatTimestamp(binding.root.context, result.date)

            Avatars.bind(
                image = binding.resultPhoto,
                initialsView = binding.resultInitials,
                name = result.title,
                peerId = result.chatId,
                photo = result.photo,
                loader = loader,
                scope = scope,
                sizePx = binding.root.resources.getDimensionPixelSize(R.dimen.avatar_small)
            )
        }

        fun release() {
            current = null
            Avatars.cancel(binding.resultPhoto)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_RESULT = 1

        private val DIFF = object : DiffUtil.ItemCallback<SearchListItem>() {
            override fun areItemsTheSame(oldItem: SearchListItem, newItem: SearchListItem): Boolean =
                oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(oldItem: SearchListItem, newItem: SearchListItem): Boolean =
                oldItem == newItem
        }
    }
}
