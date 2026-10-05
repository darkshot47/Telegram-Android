package com.telefarm.ui.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.core.AppGraph
import com.telefarm.core.td.TelefarmLog
import com.telefarm.data.model.ChatMemberUi
import com.telefarm.databinding.ActivityMemberListBinding
import com.telefarm.databinding.ItemMemberBinding
import com.telefarm.media.ThumbnailLoader
import com.telefarm.ui.common.Avatars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Members of a group or channel.
 *
 * Supergroups are paged through `getSupergroupMembers`, basic groups return their complete
 * member list at once. When Telegram hides the member list the screen says so instead of
 * showing an empty list without explanation.
 */
class MemberListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMemberListBinding
    private lateinit var adapter: MemberAdapter

    private val chatId: Long by lazy { intent.getLongExtra(EXTRA_CHAT_ID, 0L) }

    private val viewModel: MemberListViewModel by viewModels {
        viewModelFactory { initializer { MemberListViewModel(appGraph, chatId) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemberListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = MemberAdapter(
            loader = appGraph.thumbnails,
            scope = lifecycleScope,
            onMemberClick = { member -> startActivity(ProfileActivity.intent(this, member.userId)) }
        )
        binding.memberList.layoutManager = LinearLayoutManager(this)
        binding.memberList.adapter = adapter
        binding.memberList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - PREFETCH) {
                    viewModel.loadMore()
                }
            }
        })

        binding.toolbar.setNavigationOnClickListener { finish() }
        intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let { title ->
            binding.toolbar.title = title
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: MemberListState) {
        adapter.submitList(state.members)
        binding.stateContainer.isVisible = state.isLoading || state.error != null || state.isEmpty
        binding.stateProgress.isVisible = state.isLoading
        binding.stateTitle.isVisible = state.error != null
        binding.stateMessage.isVisible = state.error != null
        if (state.error != null) {
            binding.stateTitle.setText(state.error)
        } else if (state.isEmpty) {
            binding.stateTitle.isVisible = true
            binding.stateMessage.isVisible = false
            binding.stateTitle.setText(R.string.members_empty)
        }
    }

    companion object {

        private const val EXTRA_CHAT_ID = "com.telefarm.extra.CHAT_ID"
        private const val EXTRA_TITLE = "com.telefarm.extra.TITLE"
        private const val PREFETCH = 6

        fun intent(context: Context, chatId: Long, title: String): Intent =
            Intent(context, MemberListActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_TITLE, title)
    }
}

/** State of the member list. */
data class MemberListState(
    val isLoading: Boolean = true,
    val members: List<ChatMemberUi> = emptyList(),
    val error: Int? = null,
    val reachedEnd: Boolean = false
) {
    val isEmpty: Boolean get() = !isLoading && error == null && members.isEmpty()
}

/** Loads members of the chat, page by page when Telegram allows it. */
class MemberListViewModel(
    private val graph: AppGraph,
    private val chatId: Long
) : ViewModel() {

    private val _state = MutableStateFlow(MemberListState())
    val state: StateFlow<MemberListState> = _state.asStateFlow()

    private var offset = 0
    private var loading = false

    init {
        loadMore()
    }

    /** Loads the next page of members. */
    fun loadMore() {
        if (loading || _state.value.reachedEnd) return
        loading = true
        _state.value = _state.value.copy(isLoading = _state.value.members.isEmpty())
        viewModelScope.launch {
            try {
                val chat = graph.chats.chatOrFetch(chatId)
                val page = when (val type = chat?.type) {
                    is TdApi.ChatTypeSupergroup -> graph.users.supergroupMembers(type.supergroupId, offset, PAGE_SIZE)
                    is TdApi.ChatTypeBasicGroup -> graph.users.basicGroupMembers(type.basicGroupId)
                    else -> emptyList()
                }
                offset += page.size
                _state.value = _state.value.copy(
                    isLoading = false,
                    members = _state.value.members + page.filterNot { member ->
                        _state.value.members.any { it.userId == member.userId }
                    },
                    error = null,
                    reachedEnd = page.size < PAGE_SIZE || chat?.type is TdApi.ChatTypeBasicGroup
                )
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                TelefarmLog.w(TAG, "Members could not be loaded")
                _state.value = _state.value.copy(isLoading = false, error = R.string.members_error, reachedEnd = true)
            } finally {
                loading = false
            }
        }
    }

    private companion object {
        const val TAG = "MemberList"
        const val PAGE_SIZE = 50
    }
}

/** Member rows. */
private class MemberAdapter(
    private val loader: ThumbnailLoader,
    private val scope: CoroutineScope,
    private val onMemberClick: (ChatMemberUi) -> Unit
) : ListAdapter<ChatMemberUi, MemberAdapter.MemberViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder =
        MemberViewHolder(
            ItemMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            loader,
            scope,
            onMemberClick
        )

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: MemberViewHolder) {
        holder.release()
        super.onViewRecycled(holder)
    }

    class MemberViewHolder(
        private val binding: ItemMemberBinding,
        private val loader: ThumbnailLoader,
        private val scope: CoroutineScope,
        private val onClick: (ChatMemberUi) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var current: ChatMemberUi? = null

        init {
            binding.memberRow.setOnClickListener { current?.let(onClick) }
        }

        fun bind(member: ChatMemberUi) {
            current = member
            binding.memberName.text = member.displayName
            val role = member.roleLabelResId?.let { itemView.context.getString(it) }
            binding.memberRole.isVisible = !role.isNullOrBlank()
            binding.memberRole.text = role.orEmpty()

            Avatars.bind(
                image = binding.memberPhoto,
                initialsView = binding.memberInitials,
                name = member.displayName,
                peerId = member.userId,
                photo = member.photo,
                loader = loader,
                scope = scope,
                sizePx = itemView.resources.getDimensionPixelSize(R.dimen.avatar_small)
            )
        }

        fun release() {
            current = null
            Avatars.cancel(binding.memberPhoto)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatMemberUi>() {
            override fun areItemsTheSame(oldItem: ChatMemberUi, newItem: ChatMemberUi): Boolean =
                oldItem.userId == newItem.userId

            override fun areContentsTheSame(oldItem: ChatMemberUi, newItem: ChatMemberUi): Boolean =
                oldItem == newItem
        }
    }
}
