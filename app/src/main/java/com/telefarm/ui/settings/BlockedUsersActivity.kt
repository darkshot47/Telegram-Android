package com.telefarm.ui.settings

import kotlinx.coroutines.CoroutineScope
import com.telefarm.media.ThumbnailLoader
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.data.UserRepository
import com.telefarm.data.model.UserUi
import com.telefarm.databinding.ActivityBlockedUsersBinding
import com.telefarm.databinding.ItemBlockedUserBinding
import com.telefarm.ui.common.Avatars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Everybody the account has blocked.
 *
 * Blocking from a profile is only half of it: a block nobody can find is a block nobody can take
 * back. This is the other half, and the reason the list shows a sender whose name could not be
 * fetched rather than dropping them - a person is not unblocked because their name is unavailable.
 */
class BlockedUsersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedUsersBinding
    private lateinit var adapter: BlockedAdapter

    private val users: UserRepository get() = appGraph.users

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedUsersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = BlockedAdapter(appGraph.thumbnails, lifecycleScope, ::unblock)
        binding.blockedList.layoutManager = LinearLayoutManager(this)
        binding.blockedList.adapter = adapter
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.stateAction.setOnClickListener { load() }

        load()
    }

    private fun load() {
        binding.stateContainer.isVisible = true
        binding.stateProgress.isVisible = true
        binding.stateAction.isVisible = false
        binding.stateText.setText(R.string.blocked_loading)
        lifecycleScope.launch {
            val blocked = try {
                users.blockedUsers()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                binding.stateText.setText(R.string.blocked_failed)
                binding.stateProgress.isVisible = false
                binding.stateAction.isVisible = true
                return@launch
            }
            adapter.submitList(blocked.sortedBy { it.displayName.lowercase() })
            binding.stateProgress.isVisible = false
            binding.stateContainer.isVisible = blocked.isEmpty()
            if (blocked.isEmpty()) binding.stateText.setText(R.string.blocked_empty)
        }
    }

    private fun unblock(user: UserUi) {
        lifecycleScope.launch {
            users.setBlocked(user.id, blocked = false)
            // Read the list back rather than removing the row here: the list is what Telegram
            // holds, and it is the thing being shown.
            load()
        }
    }

    private class BlockedAdapter(
        private val loader: ThumbnailLoader,
        private val scope: CoroutineScope,
        private val onUnblock: (UserUi) -> Unit
    ) : ListAdapter<UserUi, BlockedAdapter.Holder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            ItemBlockedUserBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            loader,
            scope,
            onUnblock
        )

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        class Holder(
            private val binding: ItemBlockedUserBinding,
            private val loader: ThumbnailLoader,
            private val scope: CoroutineScope,
            private val onUnblock: (UserUi) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(user: UserUi) {
                val name = user.displayName.ifBlank {
                    binding.root.context.getString(R.string.blocked_unknown_name)
                }
                binding.blockedName.text = name
                Avatars.bind(
                    image = binding.blockedPhoto,
                    initialsView = binding.blockedInitials,
                    name = name,
                    peerId = user.id,
                    photo = user.photo,
                    loader = loader,
                    scope = scope,
                    sizePx = binding.root.resources.getDimensionPixelSize(R.dimen.avatar_medium)
                )
                binding.blockedUnblock.setOnClickListener { onUnblock(user) }
            }
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<UserUi>() {
                override fun areItemsTheSame(oldItem: UserUi, newItem: UserUi): Boolean =
                    oldItem.id == newItem.id

                override fun areContentsTheSame(oldItem: UserUi, newItem: UserUi): Boolean =
                    oldItem == newItem
            }
        }
    }
}
