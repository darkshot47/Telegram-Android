package com.telefarm.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import com.telefarm.core.reactions.Reactions
import com.telefarm.core.schedule.MessageSchedule
import com.telefarm.core.schedule.ScheduleDecision
import java.util.Calendar
import android.os.Bundle
import android.view.KeyEvent
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.telefarm.core.export.ExportFormat
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.data.model.ChatHeaderUi
import com.telefarm.data.model.MessageContentUi
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
import java.io.File
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
                viewModel.pinned.collect(::renderPinned)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.replyTarget.collect(::renderReplyTarget)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scheduleTarget.collect { target ->
                    if (target == null) {
                        binding.scheduleBar.isVisible = false
                    } else {
                        val offsetMinutes = timeZoneOffsetMinutes()
                        binding.scheduleText.text = getString(
                            R.string.chat_scheduled_for,
                            MessageSchedule.label(target, System.currentTimeMillis() / 1_000L, offsetMinutes)
                        )
                        binding.scheduleBar.isVisible = true
                        binding.scheduleClose.setOnClickListener { viewModel.clearSchedule() }
                    }
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

        R.id.action_chat_schedule -> {
            pickScheduleDay()
            true
        }

        R.id.action_chat_export -> {
            showExportDialog()
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
        options += R.string.chat_action_react to { showReactionPicker(message) }
        options += R.string.chat_action_reply to { viewModel.replyTo(message) }
        options += R.string.chat_action_pin_message to { pinMessage(message) }
        if (message.isOutgoing && !text.isNullOrEmpty()) {
            options += R.string.chat_edit_message to { showEditDialog(message, text) }
        }
        options += R.string.chat_delete_message to { confirmDelete(message) }

        val labels = options.map { getString(it.first) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setItems(labels) { _, index -> options[index].second() }
            .show()
    }

    /** Picks the format, then writes the transcript and hands it to another application. */
    private fun showExportDialog() {
        val formats = arrayOf(ExportFormat.HTML, ExportFormat.TEXT)
        val labels = formats.map { format ->
            getString(
                when (format) {
                    ExportFormat.HTML -> R.string.chat_export_html
                    ExportFormat.TEXT -> R.string.chat_export_text
                }
            )
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_export_title)
            .setItems(labels) { _, which -> shareExport(formats[which]) }
            .show()
    }

    private fun shareExport(format: ExportFormat) {
        val document = viewModel.export(format)
        if (document == null) {
            binding.root.showSnackbar(getString(R.string.chat_export_empty))
            return
        }
        val file = writeExport(format, document)
        if (file == null) {
            binding.root.showSnackbar(getString(R.string.chat_export_failed))
            return
        }
        // The transcript is handed over as a content URI, so the file itself stays in private
        // storage and the receiving application gets read access to this one file only.
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.chat_export_share_title)))
    }

    /** Writes the transcript into the cache, which the system may clear and nothing else reads. */
    private fun writeExport(format: ExportFormat, document: String): File? = try {
        val directory = File(cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        File(directory, viewModel.exportFileName(format)).apply { writeText(document, Charsets.UTF_8) }
    } catch (error: Throwable) {
        null
    }

    /**
     * The reactions on offer for a message.
     *
     * Eight, in one row, because a reaction is worth having only when it takes one tap; a picker
     * that has to be searched is slower than typing the reply.
     */
    private fun showReactionPicker(message: MessageUi) {
        val chosen = Reactions.chosen(message.reactions).firstOrNull()
        val labels = Reactions.QUICK.map { emoji ->
            if (emoji == chosen) getString(R.string.chat_reaction_remove, emoji) else emoji
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_react_title)
            .setItems(labels) { _, index -> applyReaction(message, Reactions.QUICK[index]) }
            .show()
    }

    private fun applyReaction(message: MessageUi, emoji: String) {
        lifecycleScope.launch {
            val done = viewModel.react(message.id, emoji)
            if (!done) binding.root.showSnackbar(getString(R.string.chat_reaction_failed))
        }
    }

    /** This device's offset from UTC, in minutes, the way [MessageSchedule] takes it. */
    private fun timeZoneOffsetMinutes(): Int {
        val calendar = Calendar.getInstance()
        return (calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)) / 60_000
    }

    /**
     * Picks the day a message is to go out, then the hour on it.
     *
     * Two pickers rather than one, because the platform's date picker does not do times and a
     * schedule is worth nothing without both. The time picker is not shown until a day has been
     * chosen, so a half-finished choice cannot be scheduled.
     */
    private fun pickScheduleDay() {
        val now = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day -> pickScheduleTime(year, month, day) },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pickScheduleTime(year: Int, month: Int, day: Int) {
        val now = Calendar.getInstance()
        TimePickerDialog(
            this,
            { _, hour, minute -> applySchedule(year, month, day, hour, minute) },
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            true
        ).show()
    }

    /** Turns the picked wall clock into a moment, and checks it can be used. */
    private fun applySchedule(year: Int, month: Int, day: Int, hour: Int, minute: Int) {
        val picked = Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }
        val targetSeconds = picked.timeInMillis / 1_000L
        val offsetMinutes = (picked.get(Calendar.ZONE_OFFSET) + picked.get(Calendar.DST_OFFSET)) / 60_000
        val nowSeconds = System.currentTimeMillis() / 1_000L
        when (val decision = MessageSchedule.validate(targetSeconds, nowSeconds)) {
            is ScheduleDecision.Ok -> {
                viewModel.scheduleAt(targetSeconds)
                binding.scheduleText.text = getString(
                    R.string.chat_scheduled_for,
                    MessageSchedule.label(targetSeconds, nowSeconds, offsetMinutes)
                )
                binding.scheduleBar.isVisible = true
                binding.messageInput.requestFocus()
            }

            is ScheduleDecision.TooSoon -> binding.root.showSnackbar(getString(R.string.chat_schedule_too_soon))
            is ScheduleDecision.TooFar -> binding.root.showSnackbar(getString(R.string.chat_schedule_too_far))
        }
    }

    /**
     * Shows what the next message will answer.
     *
     * The bar is above the text box rather than inside it, so the words being answered stay
     * visible while a long reply is being typed.
     */
    private fun renderReplyTarget(target: MessageUi?) {
        binding.replyBar.isVisible = target != null
        if (target == null) return
        binding.replySender.text = target.senderName
            ?.takeIf { it.isNotBlank() }
            ?: getString(if (target.isOutgoing) R.string.chat_reply_you else R.string.chat_reply_unknown_sender)
        binding.replyPreview.text = previewOf(target)
        binding.replyClose.setOnClickListener { viewModel.clearReplyTarget() }
        binding.messageInput.requestFocus()
    }

    /** Shows or hides the bar for the pinned message, and fills it in. */
    private fun renderPinned(pinned: MessageUi?) {
        binding.pinnedBar.isVisible = pinned != null
        if (pinned == null) return
        binding.pinnedText.text = previewOf(pinned)
        binding.pinnedContent.setOnClickListener { scrollTo(pinned.id) }
        binding.pinnedClose.setOnClickListener { unpinPinnedMessage() }
    }

    /** What the bar says, which is the words of the message and nothing else. */
    private fun previewOf(message: MessageUi): String = when (val content = message.content) {
        is MessageContentUi.Text -> content.text
        is MessageContentUi.Photo -> content.caption ?: getString(R.string.message_photo)
        is MessageContentUi.Video -> content.caption ?: getString(R.string.message_video)
        is MessageContentUi.Document -> content.fileName
        is MessageContentUi.Voice -> getString(R.string.message_voice)
        is MessageContentUi.Audio -> content.title ?: getString(R.string.message_audio)
        is MessageContentUi.Sticker -> getString(R.string.message_sticker)
        is MessageContentUi.Other -> getString(R.string.message_service)
    }

    /** Moves the list to a message, whether or not it is loaded. */
    private fun scrollTo(messageId: Long) {
        val position = adapter.currentList.indexOfFirst {
            it is MessageRow.Message && it.message.id == messageId
        }
        if (position >= 0) {
            binding.messageList.scrollToPosition(position)
        } else {
            binding.root.showSnackbar(getString(R.string.chat_pinned_not_loaded))
        }
    }

    private fun pinMessage(message: MessageUi) {
        lifecycleScope.launch {
            val done = viewModel.pinMessage(message.id)
            binding.root.showSnackbar(
                getString(if (done) R.string.chat_pinned_message else R.string.chat_pin_failed)
            )
        }
    }

    private fun unpinPinnedMessage() {
        lifecycleScope.launch {
            val done = viewModel.unpinPinnedMessage()
            if (!done) binding.root.showSnackbar(getString(R.string.chat_unpin_failed))
        }
    }

    /** A message this account wrote, with its text back in a box to be changed. */
    private fun showEditDialog(message: MessageUi, current: String) {
        val field = EditText(this).apply {
            setText(current)
            setSelection(current.length)
            minLines = MIN_EDIT_LINES
            maxLines = MAX_EDIT_LINES
            setPadding(EDIT_PADDING_PX, EDIT_PADDING_PX, EDIT_PADDING_PX, EDIT_PADDING_PX)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_edit_message)
            .setView(field)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.chat_edit_save) { _, _ ->
                val next = field.text?.toString().orEmpty().trim()
                if (next.isNotEmpty() && next != current) {
                    viewModel.editMessage(message.id, next)
                }
            }
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
