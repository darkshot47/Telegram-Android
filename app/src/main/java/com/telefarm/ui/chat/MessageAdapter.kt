package com.telefarm.ui.chat

import com.telefarm.core.reactions.Reactions
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.telefarm.R
import com.telefarm.data.model.MessageRow
import com.telefarm.data.model.MessageUi
import com.telefarm.data.model.SendState
import com.telefarm.databinding.ItemMessageBinding
import com.telefarm.ui.common.TimeFormat
import kotlinx.coroutines.Job

/**
 * Conversation rows.
 *
 * The list is submitted in ascending time order and rendered with a reversed layout manager,
 * so position 0 is always the newest message at the bottom of the screen.
 */
class MessageAdapter(
    private val binder: MessageContentBinder,
    private val onLongClick: (MessageUi) -> Unit,
    private val onSelectionTap: (MessageUi) -> Unit = {}
) : ListAdapter<MessageRow, RecyclerView.ViewHolder>(DIFF) {

    /**
     * The messages currently chosen, and whether choosing is what a tap does.
     *
     * Kept here rather than in the rows because the conversation and the selection are two
     * different things: the conversation comes from Telegram and the selection belongs to the
     * screen. Putting the selection into the rows would mean rebuilding the list to redraw a
     * highlight.
     */
    var selectionMode: Boolean = false
        set(value) {
            field = value
            if (!value) selectedIds = emptySet()
            refreshSelection()
        }

    var selectedIds: Set<Long> = emptySet()
        set(value) {
            field = value
            refreshSelection()
        }

    /** Redraws the rows so the highlight follows the selection. */
    private fun refreshSelection() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is MessageRow.Message -> TYPE_MESSAGE
        is MessageRow.DateSeparator -> TYPE_DATE
        is MessageRow.LoadMore -> TYPE_LOADING
        is MessageRow.UnreadDivider -> TYPE_UNREAD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_MESSAGE -> MessageViewHolder(
                ItemMessageBinding.inflate(inflater, parent, false), binder, onLongClick, onSelectionTap
            )
            TYPE_DATE -> DateViewHolder(inflater.inflate(R.layout.item_message_date, parent, false))
            TYPE_UNREAD -> UnreadViewHolder(inflater.inflate(R.layout.item_message_unread, parent, false))
            else -> LoadingViewHolder(inflater.inflate(R.layout.item_messages_loading, parent, false))
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: List<Any>
    ) {
        // A highlight change does not need the message rebuilt, only the selection redrawn.
        if (payloads.contains(SELECTION_PAYLOAD) && holder is MessageViewHolder) {
            holder.bindSelection(getItem(position), selectionMode, selectedIds)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is MessageRow.Message -> (holder as MessageViewHolder).apply {
                bind(row.message)
                bindSelection(row, selectionMode, selectedIds)
            }
            is MessageRow.DateSeparator -> (holder as DateViewHolder).bind(row)
            is MessageRow.LoadMore -> (holder as LoadingViewHolder).bind(row)
            is MessageRow.UnreadDivider -> (holder as UnreadViewHolder).bind(row)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is MessageViewHolder) holder.release()
        super.onViewRecycled(holder)
    }

    /** One conversation message. */
    class MessageViewHolder(
        private val binding: ItemMessageBinding,
        private val binder: MessageContentBinder,
        private val onLongClick: (MessageUi) -> Unit,
        private val onSelectionTap: (MessageUi) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val jobs = mutableListOf<Job>()
        private var current: MessageUi? = null

        /**
         * Draws whether this message is one of the chosen ones.
         *
         * A tap in selection mode chooses and unchooses rather than opening the message, because
         * opening one would leave the selection behind and the person would not know whether they
         * were still choosing.
         */
        fun bindSelection(row: MessageRow, selectionMode: Boolean, selectedIds: Set<Long>) {
            val message = (row as? MessageRow.Message)?.message ?: return
            val chosen = selectionMode && message.id in selectedIds
            binding.root.setBackgroundColor(
                if (chosen) binding.root.context.getColor(R.color.surface_pressed) else 0
            )
            if (selectionMode) {
                binding.root.setOnClickListener { onSelectionTap(message) }
            } else {
                binding.root.setOnClickListener(null)
                binding.root.isClickable = false
            }
        }

        fun bind(message: MessageUi) {
            release()
            current = message

            val context = binding.root.context
            val layout = binding.messageBubble.layoutParams as FrameLayout.LayoutParams
            layout.gravity = if (message.isOutgoing) Gravity.END else Gravity.START
            binding.messageBubble.layoutParams = layout
            binding.messageBubble.setBackgroundResource(
                if (message.isOutgoing) R.drawable.bg_bubble_outgoing else R.drawable.bg_bubble_incoming
            )

            binding.messageSender.isVisible = message.showSender && !message.senderName.isNullOrBlank()
            binding.messageSender.text = message.senderName.orEmpty()

            binding.messageTime.text = TimeFormat.messageTime(message.date)
            // The strip is drawn only when there is something on it, so a message nobody has
            // reacted to does not carry a blank line under it.
            val reactions = Reactions.label(message.reactions)
            binding.messageReactions.isVisible = reactions.isNotEmpty()
            binding.messageReactions.text = reactions
            binding.messageEdited.isVisible = message.isEdited

            bindState(message)
            binder.bind(binding.messageContent, message, jobs)

            binding.messageBubble.setOnLongClickListener {
                current?.let(onLongClick)
                true
            }
        }

        private fun bindState(message: MessageUi) {
            if (!message.isOutgoing) {
                binding.messageState.isVisible = false
                return
            }
            when (message.sendState) {
                SendState.SENDING -> {
                    binding.messageState.isVisible = true
                    binding.messageState.setImageResource(R.drawable.ic_clock)
                }
                SendState.FAILED -> {
                    binding.messageState.isVisible = true
                    binding.messageState.setImageResource(R.drawable.ic_error)
                }
                SendState.SENT -> {
                    binding.messageState.isVisible = true
                    binding.messageState.setImageResource(
                        if (message.isRead) R.drawable.ic_check_double else R.drawable.ic_check
                    )
                }
            }
        }

        fun release() {
            jobs.forEach { job -> job.cancel() }
            jobs.clear()
            current = null
        }
    }

    /** Day separator. */
    /** The line marking where the conversation was left. */
    class UnreadViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val label: TextView = view.findViewById(R.id.unreadLabel)

        fun bind(row: MessageRow.UnreadDivider) {
            label.text = label.context.resources.getQuantityString(
                R.plurals.chat_unread_messages, row.unreadCount, row.unreadCount
            )
        }
    }

    class DateViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val label: TextView = view.findViewById(R.id.dateLabel)

        fun bind(row: MessageRow.DateSeparator) {
            label.text = TimeFormat.daySeparator(label.context, row.date)
            label.contentDescription = TimeFormat.daySeparator(label.context, row.date)
        }
    }

    /** Progress row shown while older history is being fetched. */
    class LoadingViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val indicator: View = view.findViewById(R.id.loadingIndicator)

        fun bind(row: MessageRow.LoadMore) {
            indicator.isVisible = row.isLoading
        }
    }

    private companion object {
        const val TYPE_MESSAGE = 0
        const val TYPE_DATE = 1
        const val TYPE_LOADING = 2
        const val TYPE_UNREAD = 3

        /** Marks a redraw that only changes the highlight, not the message. */
        const val SELECTION_PAYLOAD = "selection"

        val DIFF = object : DiffUtil.ItemCallback<MessageRow>() {
            override fun areItemsTheSame(oldItem: MessageRow, newItem: MessageRow): Boolean =
                oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(oldItem: MessageRow, newItem: MessageRow): Boolean =
                oldItem == newItem
        }
    }
}
