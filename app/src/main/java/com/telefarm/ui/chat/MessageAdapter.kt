package com.telefarm.ui.chat

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
    private val onLongClick: (MessageUi) -> Unit
) : ListAdapter<MessageRow, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is MessageRow.Message -> TYPE_MESSAGE
        is MessageRow.DateSeparator -> TYPE_DATE
        is MessageRow.LoadMore -> TYPE_LOADING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_MESSAGE -> MessageViewHolder(ItemMessageBinding.inflate(inflater, parent, false), binder, onLongClick)
            TYPE_DATE -> DateViewHolder(inflater.inflate(R.layout.item_message_date, parent, false))
            else -> LoadingViewHolder(inflater.inflate(R.layout.item_messages_loading, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is MessageRow.Message -> (holder as MessageViewHolder).bind(row.message)
            is MessageRow.DateSeparator -> (holder as DateViewHolder).bind(row)
            is MessageRow.LoadMore -> (holder as LoadingViewHolder).bind(row)
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
        private val onLongClick: (MessageUi) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val jobs = mutableListOf<Job>()
        private var current: MessageUi? = null

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

        val DIFF = object : DiffUtil.ItemCallback<MessageRow>() {
            override fun areItemsTheSame(oldItem: MessageRow, newItem: MessageRow): Boolean =
                oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(oldItem: MessageRow, newItem: MessageRow): Boolean =
                oldItem == newItem
        }
    }
}
