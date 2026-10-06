package com.telefarm.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.data.model.ChatHeaderUi
import com.telefarm.data.model.MessageContentUi
import com.telefarm.data.model.MessageRow
import com.telefarm.data.model.MessageUi
import com.telefarm.data.model.SendState
import com.telefarm.databinding.ActivityChatBinding
import com.telefarm.media.MediaOpener
import com.telefarm.ui.common.Avatars
import com.telefarm.ui.common.hideKeyboard
import com.telefarm.ui.common.resolve
import com.telefarm.ui.common.showSnackbar
import com.telefarm.ui.profile.ProfileActivity
import com.telefarm.ui.search.SearchActivity
import kotlinx.coroutines.launch

/**
 * One conversation: history, live messages and the composer.
 *
 * Nothing is shown before TDLib reports it. History loads in pages while the list is scrolled,
 * new messages arrive live and a message that could not be sent can be retried in place.
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter
    private lateinit var layoutManager: LinearLayoutManager

    private val chatId: Long by lazy { intent.getLongExtra(EXTRA_CHAT_ID, 0L) }

    private val viewModel: ChatViewModel by viewModels {
        viewModelFactory { initializer { ChatViewModel(appGraph, chatId) } }
    }

    private var sending = false
    private var lastHeader: ChatHeaderUi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val binder = MessageContentBinder(
            fileManager = appGraph.fileManager,
            thumbnails = appGraph.thumbnails,
            voicePlayer = appGraph.voicePlayer,
            scope = lifecycleScope,
            onOpenFile = ::openFile,
            onError = ::showError
        )
        adapter = MessageAdapter(binder, onLongClick = ::showMessageActions)
        layoutManager = LinearLayoutManager(this).apply { reverseLayout = true }

        binding.messageList.layoutManager = layoutManager
        binding.messageList.adapter = adapter
        binding.messageList.itemAnimator = null
        binding.messageList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (adapter.itemCount == 0) return
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - PREFETCH_DISTANCE) {
                    viewModel.loadOlder()
                }
            }
        })

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener(::onMenuItemSelected)
        binding.headerContent.setOnClickListener { openProfile() }
        binding.sendButton.setOnClickListener { sendCurrentInput() }
        binding.chatStateAction.setOnClickListener { viewModel.retryLoad() }
        binding.messageInput.setOnEditorActionListener { _, actionId, event ->
            val shouldSend = actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.isCtrlPressed)
            if (shouldSend) {
                sendCurrentInput()
                true
            } else {
                false
            }
        }

        observeState()
        restoreDraft()
    }

    /** Brings back an unsent draft of this chat. */
    private fun restoreDraft() {
        val draft = viewModel.draft()
        if (!draft.isNullOrBlank()) binding.messageInput.setText(draft)
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveDraft(binding.messageInput.text?.toString().orEmpty())
    }

    override fun onStop() {
        super.onStop()
        appGraph.voicePlayer.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        Avatars.cancel(binding.headerPhoto)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isSending.collect { isSending ->
                    binding.sendProgress.isVisible = isSending
                    binding.sendButton.isEnabled = !isSending
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.typing.collect { action ->
                    binding.typingIndicator.isVisible = action != null
                    binding.typingIndicator.text = action?.let { getString(chatActionLabel(it)) }.orEmpty()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.event.collect { event ->
                    if (event != null) {
                        val text = event.message?.resolve(this@ChatActivity) ?: getString(event.textRes)
                        binding.root.showSnackbar(text)
                        viewModel.clearEvent()
                    }
                }
            }
        }
    }

    private fun render(state: ChatUiState) {
        when (state) {
            ChatUiState.Loading -> {
                binding.chatStateContainer.isVisible = true
                binding.chatStateProgress.isVisible = true
                binding.chatStateTitle.isVisible = false
                binding.chatStateMessage.isVisible = false
                binding.chatStateAction.isVisible = false
            }

            is ChatUiState.Empty -> {
                binding.chatStateContainer.isVisible = true
                binding.chatStateProgress.isVisible = false
                binding.chatStateTitle.isVisible = true
                binding.chatStateTitle.setText(R.string.chat_no_messages)
                binding.chatStateMessage.isVisible = false
                binding.chatStateAction.isVisible = false
                renderHeader(state.header)
            }

            is ChatUiState.Failed -> {
                binding.chatStateContainer.isVisible = true
                binding.chatStateProgress.isVisible = false
                binding.chatStateTitle.isVisible = true
                binding.chatStateTitle.setText(R.string.chat_load_error)
                binding.chatStateMessage.isVisible = true
                binding.chatStateMessage.text = state.message.resolve(this)
                binding.chatStateAction.isVisible = true
            }

            is ChatUiState.Content -> {
                binding.chatStateContainer.isVisible = false
                renderHeader(state.header)
                val shouldStickToBottom = isAtBottom()
                adapter.submitList(state.rows.reversed()) {
                    if (adapter.itemCount > 0 && shouldStickToBottom) {
                        binding.messageList.scrollToPosition(0)
                    }
                }
                viewModel.markRead()
            }
        }
    }

    private fun renderHeader(header: ChatHeaderUi) {
        lastHeader = header
        binding.headerTitle.text = header.title
        val subtitle = subtitleOf(this, header)
        binding.headerSubtitle.isVisible = !subtitle.isNullOrBlank()
        binding.headerSubtitle.text = subtitle ?: ""

        val canSend = header.canSendMessages
        binding.composer.isVisible = canSend
        binding.cannotSendBanner.isVisible = !canSend

        Avatars.bind(
            image = binding.headerPhoto,
            initialsView = binding.headerInitials,
            name = header.title,
            peerId = chatId,
            photo = header.photo,
            loader = appGraph.thumbnails,
            scope = lifecycleScope,
            sizePx = resources.getDimensionPixelSize(R.dimen.avatar_small)
        )
    }

    /** True when the newest message is on screen. */
    private fun isAtBottom(): Boolean =
        adapter.itemCount == 0 || layoutManager.findFirstVisibleItemPosition() <= 0

    private fun sendCurrentInput() {
        val text = binding.messageInput.text?.toString().orEmpty()
        if (text.isBlank() || sending) return
        sending = true
        binding.messageInput.hideKeyboard()
        lifecycleScope.launch {
            try {
                if (viewModel.sendText(text)) {
                    binding.messageInput.setText("")
                    binding.messageList.scrollToPosition(0)
                }
            } finally {
                sending = false
            }
        }
    }

    private fun openFile(path: String, mimeType: String?) {
        if (!MediaOpener.open(this, path, mimeType)) {
            binding.root.showSnackbar(getString(R.string.error_no_app_for_file))
        }
    }

    private fun showError(message: CharSequence) {
        binding.root.showSnackbar(message.toString())
    }

    private fun openProfile() {
        val target = viewModel.profileTarget.value
        if (target == null) {
            binding.root.showSnackbar(getString(R.string.profile_loading))
            return
        }
        startActivity(ProfileActivity.intent(this, target, lastHeader?.title))
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_chat_info -> {
            openProfile()
            true
        }

        R.id.action_chat_search -> {
            startActivity(SearchActivity.intent(this, chatId))
            true
        }

        R.id.action_chat_clear_history -> {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_clear_history_title)
                .setMessage(R.string.dialog_clear_history_message)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_clear_history_confirm) { _, _ -> viewModel.clearHistory() }
                .show()
            true
        }

        else -> false
    }

    /** Actions offered on a long press; only implemented actions are listed. */
    private fun showMessageActions(message: MessageUi) {
        val options = mutableListOf<Pair<Int, () -> Unit>>()
        val text = (message.content as? MessageContentUi.Text)?.text
        if (!text.isNullOrEmpty()) {
            options += R.string.chat_copy_message to { copyToClipboard(text) }
        }
        if (message.sendState == SendState.FAILED && message.canRetry) {
            options += R.string.chat_retry_send to { viewModel.retryMessage(message.id) }
        }
        options += R.string.chat_delete_message to { confirmDelete(message) }

        val labels = options.map { getString(it.first) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setItems(labels) { _, index -> options[index].second() }
            .show()
    }

    private fun confirmDelete(message: MessageUi) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_delete_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_delete_confirm) { _, _ -> viewModel.deleteMessage(message.id) }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.chat_copy_message), text))
        binding.root.showSnackbar(getString(R.string.chat_copied))
    }

    companion object {

        private const val EXTRA_CHAT_ID = "com.telefarm.extra.CHAT_ID"
        private const val PREFETCH_DISTANCE = 4

        /** Opens the conversation with the given chat id. */
        fun intent(context: Context, chatId: Long): Intent =
            Intent(context, ChatActivity::class.java).putExtra(EXTRA_CHAT_ID, chatId)
    }
}
