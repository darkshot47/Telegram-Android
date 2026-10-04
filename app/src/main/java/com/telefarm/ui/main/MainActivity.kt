package com.telefarm.ui.main

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
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
import com.telefarm.data.model.ChatUi
import com.telefarm.data.model.ConnectionStatus
import com.telefarm.databinding.ActivityMainBinding
import com.telefarm.notifications.NotificationCenter
import com.telefarm.ui.auth.AuthActivity
import com.telefarm.ui.chat.ChatActivity
import com.telefarm.ui.common.resolve
import com.telefarm.ui.common.showSnackbar
import com.telefarm.ui.contacts.ContactsActivity
import com.telefarm.ui.search.SearchActivity
import com.telefarm.ui.settings.SettingsActivity
import kotlinx.coroutines.launch

/**
 * Main screen: the Telegram chat list.
 *
 * The list is fed by TDLib and updates live. The search action and the overflow menu open only
 * screens that are implemented, and every chat action offered by a long press has real
 * behaviour behind it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChatListAdapter

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel(appGraph) } }
    }

    private var signInScreenShown = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                appGraph.settings.setNotificationsEnabled(true)
            } else {
                binding.root.showSnackbar(getString(R.string.settings_notifications_unsupported))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ChatListAdapter(
            loader = appGraph.thumbnails,
            scope = lifecycleScope,
            onChatClick = { chat -> openChat(chat.id) },
            onChatLongClick = ::showChatActions
        )

        binding.chatList.layoutManager = LinearLayoutManager(this)
        binding.chatList.adapter = adapter
        binding.chatList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - PREFETCH_DISTANCE) {
                    viewModel.loadMore()
                }
            }
        })

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.toolbar.setOnMenuItemClickListener(::onMenuItemSelected)
        binding.stateAction.setOnClickListener { viewModel.retry() }

        observeState()
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Returning to the list means no conversation is on screen any more.
        appGraph.notifications.dismissAll()
        requestNotificationPermissionIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopPlayback()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.transientError.collect { error ->
                    if (error != null) {
                        binding.root.showSnackbar(error.resolve(this@MainActivity))
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    private fun render(state: MainState) {
        adapter.submitList(state.chats)
        binding.swipeRefresh.isRefreshing = state.isRefreshing

        binding.offlineBanner.isVisible = state.connectionStatus != ConnectionStatus.READY
        binding.offlineBannerText.text = when (state.connectionStatus) {
            ConnectionStatus.WAITING_FOR_NETWORK -> getString(R.string.state_offline)
            ConnectionStatus.UPDATING -> getString(R.string.state_updating)
            else -> getString(R.string.state_connecting)
        }

        val showLoading = state.isLoading && state.chats.isEmpty() && !state.requiresSignIn
        val showEmpty = state.isEmpty && state.chats.isEmpty() && !state.requiresSignIn
        val showError = state.error != null && state.chats.isEmpty() && !state.requiresSignIn

        binding.stateContainer.isVisible = showLoading || showEmpty || showError
        binding.stateProgress.isVisible = showLoading
        binding.stateTitle.isVisible = showEmpty || showError
        binding.stateMessage.isVisible = showEmpty || showError
        binding.stateAction.isVisible = showError

        when {
            showError -> {
                binding.stateTitle.setText(R.string.chat_list_error_title)
                binding.stateMessage.text = state.error?.resolve(this)
                binding.stateIcon.isVisible = true
                binding.stateIcon.setImageResource(R.drawable.ic_error)
            }

            showEmpty -> {
                binding.stateTitle.setText(R.string.chat_list_empty_title)
                binding.stateMessage.setText(R.string.chat_list_empty_message)
                binding.stateIcon.isVisible = true
                binding.stateIcon.setImageResource(R.drawable.ic_message)
            }

            else -> {
                binding.stateIcon.isVisible = false
                binding.stateMessage.text = null
            }
        }

        if (state.requiresSignIn && !signInScreenShown) {
            signInScreenShown = true
            startActivity(
                Intent(this, AuthActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_search -> {
            startActivity(SearchActivity.intent(this))
            true
        }

        R.id.action_settings -> {
            startActivity(SettingsActivity.intent(this))
            true
        }

        R.id.action_contacts -> {
            startActivity(ContactsActivity.intent(this))
            true
        }

        R.id.action_saved_messages -> {
            viewModel.openSavedMessages { chatId ->
                if (chatId != null) {
                    openChat(chatId)
                } else {
                    binding.root.showSnackbar(getString(R.string.error_generic))
                }
            }
            true
        }

        R.id.action_about -> {
            showAboutDialog()
            true
        }

        R.id.action_logout -> {
            confirmLogout()
            true
        }

        else -> false
    }

    private fun openChat(chatId: Long) {
        startActivity(ChatActivity.intent(this, chatId))
    }

    /** Context actions of a chat; only features that are implemented are offered. */
    private fun showChatActions(chat: ChatUi) {
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        actions += if (chat.isPinned) {
            R.string.chat_action_unpin to { viewModel.setPinned(chat.id, false) }
        } else {
            R.string.chat_action_pin to { viewModel.setPinned(chat.id, true) }
        }
        actions += if (chat.isMuted) {
            R.string.chat_action_unmute to { viewModel.setMuted(chat.id, false) }
        } else {
            R.string.chat_action_mute to { viewModel.setMuted(chat.id, true) }
        }
        actions += if (chat.hasUnread) {
            R.string.chat_action_mark_read to { viewModel.markAsRead(chat.id) }
        } else {
            R.string.chat_action_mark_unread to { viewModel.setMarkedAsUnread(chat.id, true) }
        }
        actions += R.string.chat_action_delete_chat to { confirmDeleteChat(chat) }

        val labels = actions.map { getString(it.first) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(chat.title)
            .setItems(labels) { _, index -> actions[index].second() }
            .show()
    }

    private fun confirmDeleteChat(chat: ChatUi) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_chat_title)
            .setMessage(getString(R.string.dialog_delete_chat_message, chat.title))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_delete_confirm) { _, _ -> viewModel.deleteChat(chat.id) }
            .show()
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_logout_title)
            .setMessage(R.string.dialog_logout_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_logout_confirm) { _, _ ->
                viewModel.logOut()
                startActivity(
                    Intent(this, AuthActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finishAffinity()
            }
            .show()
    }

    private fun showAboutDialog() {
        val message = buildString {
            append(getString(R.string.about_description))
            append("\n\n")
            append(getString(R.string.about_privacy_title))
            append(": ")
            append(getString(R.string.about_privacy_text))
            append("\n\n")
            append(getString(R.string.about_disclaimer_title))
            append(": ")
            append(getString(R.string.about_disclaimer_text))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(message)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!viewModel.settings.value.notificationsEnabled) return
        if (appGraph.notifications.hasPermission()) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Opens the conversation a notification was tapped for. */
    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val chatId = intent.getLongExtra(NotificationCenter.EXTRA_CHAT_ID, 0L)
        if (chatId == 0L) return
        intent.removeExtra(NotificationCenter.EXTRA_CHAT_ID)
        openChat(chatId)
    }

    private companion object {
        const val PREFETCH_DISTANCE = 8
    }
}
