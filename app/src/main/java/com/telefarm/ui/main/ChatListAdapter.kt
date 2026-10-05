package com.telefarm.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.telefarm.R
import com.telefarm.data.model.ChatKind
import com.telefarm.data.model.ChatUi
import com.telefarm.databinding.ItemChatBinding
import com.telefarm.media.ThumbnailLoader
import com.telefarm.ui.common.Avatars
import com.telefarm.ui.common.PreviewText
import com.telefarm.ui.common.TimeFormat
import kotlinx.coroutines.CoroutineScope

/**
 * Chat list rows.
 *
 * Rows are diffed by id and content, so live updates only redraw the chats that changed.
 */
class ChatListAdapter(
    private val loader: ThumbnailLoader,
    private val scope: CoroutineScope,
    private val onChatClick: (ChatUi) -> Unit,
    private val onChatLongClick: (ChatUi) -> Unit
) : ListAdapter<ChatUi, ChatListAdapter.ChatViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChatViewHolder(binding, loader, scope, onChatClick, onChatLongClick)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ChatViewHolder) {
        holder.release()
        super.onViewRecycled(holder)
    }

    class ChatViewHolder(
        private val binding: ItemChatBinding,
        private val loader: ThumbnailLoader,
        private val scope: CoroutineScope,
        private val onClick: (ChatUi) -> Unit,
        private val onLongClick: (ChatUi) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var current: ChatUi? = null

        init {
            binding.chatRow.setOnClickListener {
                current?.let(onClick)
            }
            binding.chatRow.setOnLongClickListener {
                current?.let(onLongClick)
                true
            }
        }

        fun bind(chat: ChatUi) {
            current = chat
            val context = binding.root.context

            binding.chatTitle.text = chat.title
            binding.chatPreview.text = PreviewText.of(context, chat)
            binding.chatPreview.isVisible = binding.chatPreview.text.isNotEmpty()
            binding.chatTime.text = TimeFormat.chatTimestamp(context, chat.lastMessageDate)

            binding.chatPin.isVisible = chat.isPinned
            binding.chatMute.isVisible = chat.isMuted

            val unread = when {
                chat.unreadMentionCount > 0 -> "@"
                chat.unreadCount > 0 -> chat.unreadCount.toString()
                chat.isMarkedAsUnread -> " "
                else -> null
            }
            binding.chatUnread.isVisible = unread != null
            binding.chatUnread.text = unread.orEmpty()
            binding.chatUnread.setBackgroundResource(
                if (chat.isMuted) R.drawable.bg_badge_muted else R.drawable.bg_badge
            )

            val kindIcon = when (chat.kind) {
                ChatKind.CHANNEL -> R.drawable.ic_channel
                ChatKind.BASIC_GROUP, ChatKind.SUPERGROUP -> R.drawable.ic_group
                ChatKind.SAVED_MESSAGES -> R.drawable.ic_bookmark
                else -> null
            }
            binding.chatKindIcon.isVisible = kindIcon != null
            if (kindIcon != null) {
                binding.chatKindIcon.setImageResource(kindIcon)
            }

            val sizePx = binding.root.resources.getDimensionPixelSize(R.dimen.chat_avatar_size)
            Avatars.bind(
                image = binding.chatPhoto,
                initialsView = binding.chatInitials,
                name = chat.title,
                peerId = chat.id,
                photo = chat.photo,
                loader = loader,
                scope = scope,
                sizePx = sizePx
            )

            binding.root.contentDescription = accessibilityLabel(chat)
        }

        /** Announces the row title and unread state to screen readers. */
        private fun accessibilityLabel(chat: ChatUi): String {
            val resources = binding.root.resources
            val unreadText = when {
                chat.unreadCount > 0 -> resources.getQuantityString(
                    R.plurals.unread_messages,
                    chat.unreadCount,
                    chat.unreadCount
                )
                chat.unreadMentionCount > 0 -> resources.getQuantityString(
                    R.plurals.unread_mentions,
                    chat.unreadMentionCount,
                    chat.unreadMentionCount
                )
                else -> null
            }
            val preview = PreviewText.of(binding.root.context, chat)
            return listOfNotNull(chat.title, preview.takeIf { it.isNotEmpty() }, unreadText).joinToString(", ")
        }

        fun release() {
            current = null
            Avatars.cancel(binding.chatPhoto)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatUi>() {
            override fun areItemsTheSame(oldItem: ChatUi, newItem: ChatUi): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChatUi, newItem: ChatUi): Boolean = oldItem == newItem
        }
    }
}
