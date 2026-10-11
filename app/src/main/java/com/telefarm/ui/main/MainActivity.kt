package com.telefarm.ui.main

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.MenuItem
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
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
import com.google.android.material.tabs.TabLayout
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.data.model.ChatFolder
import com.telefarm.data.model.ChatUi
import com.telefarm.data.model.ConnectionStatus
import com.telefarm.data.model.FolderRule
import com.telefarm.data.model.FolderRuleMode
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

    /** True while the folder tabs are being rebuilt, when selection changes mean nothing. */
    private var isRebuildingTabs = false

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

        setupFolderTabs()
        observeState()
        handleNotificationIntent(intent)
    }

    /**
     * Selection of the folder strip.
     *
     * The tabs are rebuilt whenever the folders change, and rebuilding selects a tab, so the
     * listener ignores everything that happens while that is going on.
     */
    private fun setupFolderTabs() {
        binding.folderTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (isRebuildingTabs) return
                viewModel.selectFolder(tab.tag as? Long)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
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

        renderFolderTabs(state)

        binding.offlineBanner.isVisible = state.connectionStatus != ConnectionStatus.READY
        binding.offlineBannerText.text = when (state.connectionStatus) {
            ConnectionStatus.WAITING_FOR_NETWORK -> getString(R.string.state_offline)
            ConnectionStatus.UPDATING -> getString(R.string.state_updating)
            else -> getString(R.string.state_connecting)
        }

        // While TDLib has not reported a usable state there is nothing to show yet: a session
        // that turns out to be signed out must not flash the chat list before the sign in screen.
        val determining = state.signIn == SignInRequirement.UNKNOWN
        val signedOut = state.signIn == SignInRequirement.REQUIRED
        if (!signedOut) {
            // Anything but a pending sign in means the routing flag can be armed again, so a
            // later logout is routed to the sign in screen as well.
            signInScreenShown = false
        }
        binding.chatList.isVisible = !determining
        binding.swipeRefresh.isEnabled = !determining

        val showLoading = determining || (state.isLoading && state.chats.isEmpty() && !signedOut)
        val showEmpty = !determining && !signedOut && state.chats.isEmpty() &&
            (state.isEmpty || state.isFilteredEmpty)
        val showError = !determining && state.error != null && state.chats.isEmpty() && !signedOut

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
                if (state.isFilteredEmpty) {
                    binding.stateTitle.setText(R.string.chat_list_folder_empty_title)
                    binding.stateMessage.setText(R.string.chat_list_folder_empty_message)
                } else {
                    binding.stateTitle.setText(R.string.chat_list_empty_title)
                    binding.stateMessage.setText(R.string.chat_list_empty_message)
                }
                binding.stateIcon.isVisible = true
                binding.stateIcon.setImageResource(R.drawable.ic_message)
            }

            else -> {
                binding.stateIcon.isVisible = false
                binding.stateMessage.text = null
            }
        }

        if (signedOut && !signInScreenShown) {
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

        R.id.action_new_folder -> {
            promptNewFolder()
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
        val addable = viewModel.foldersWithout(chat.id)
        if (addable.isNotEmpty()) {
            actions += R.string.folder_add_chat to { pickFolder(addable) { id -> viewModel.addChatToFolder(id, chat.id) } }
        }
        val removable = viewModel.foldersWith(chat.id)
        if (removable.isNotEmpty()) {
            actions += R.string.folder_remove_chat to { pickFolder(removable) { id -> viewModel.removeChatFromFolder(id, chat.id) } }
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

    /** Rebuilds the folder strip; the first tab is always the whole chat list. */
    private fun renderFolderTabs(state: MainState) {
        val hasFolders = state.folders.isNotEmpty()
        binding.folderTabs.isVisible = hasFolders
        if (!hasFolders) return

        isRebuildingTabs = true
        binding.folderTabs.removeAllTabs()
        binding.folderTabs.addTab(binding.folderTabs.newTab().setText(R.string.folder_all_chats))
        var selectedIndex = 0
        state.folders.forEachIndexed { index, folder ->
            val label = if (folder.unreadCount > 0) {
                getString(R.string.folder_tab_with_count, folder.name, folder.unreadCount)
            } else {
                folder.name
            }
            val tab = binding.folderTabs.newTab().setText(label).setTag(folder.id)
            tab.view.setOnLongClickListener {
                showFolderActions(folder.id)
                true
            }
            binding.folderTabs.addTab(tab)
            if (folder.id == state.activeFolderId) {
                selectedIndex = index + 1
            }
        }
        binding.folderTabs.getTabAt(selectedIndex)?.select()
        isRebuildingTabs = false
    }

    /** Asks for the name of a new folder and then for the chats it holds. */
    private fun promptNewFolder() {
        promptFolderName(R.string.folder_create_title, "") { name ->
            promptFolderRules(emptySet(), FolderRuleMode.ANY) { rules, mode ->
                viewModel.createFolder(name, rules, mode)
            }
        }
    }

    /** Actions of one folder; opened by a long press on its tab. */
    private fun showFolderActions(folderId: Long) {
        val folder = viewModel.allFolders().firstOrNull { it.id == folderId } ?: return
        val actions: List<Pair<Int, () -> Unit>> = listOf(
            R.string.folder_action_rename to {
                promptFolderName(R.string.folder_rename_title, folder.name) { name ->
                    viewModel.renameFolder(folder.id, name)
                }
            },
            R.string.folder_action_rules to {
                promptFolderRules(folder.rules, folder.ruleMode) { rules, mode ->
                    viewModel.setFolderRules(folder.id, rules, mode)
                }
            },
            R.string.folder_action_move_earlier to { viewModel.moveFolderEarlier(folder.id) },
            R.string.folder_action_move_later to { viewModel.moveFolderLater(folder.id) },
            R.string.folder_action_delete to { confirmDeleteFolder(folder) }
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(folder.name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, index ->
                actions[index].second()
            }
            .show()
    }

    private fun confirmDeleteFolder(folder: ChatFolder) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_folder_title)
            .setMessage(getString(R.string.dialog_delete_folder_message, folder.name))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_delete_confirm) { _, _ -> viewModel.deleteFolder(folder.id) }
            .show()
    }

    /** Lets the user pick one of [folders]. */
    private fun pickFolder(folders: List<ChatFolder>, onPicked: (Long) -> Unit) {
        if (folders.size == 1) {
            onPicked(folders.single().id)
            return
        }
        val labels = folders.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.folder_actions_title)
            .setItems(labels) { _, index -> onPicked(folders[index].id) }
            .show()
    }

    /** Name of a folder, cut to what a tab label can show. */
    private fun promptFolderName(@StringRes titleRes: Int, initial: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text?.length ?: 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(ChatFolder.MAX_NAME_LENGTH))
            hint = getString(R.string.folder_name_label)
        }
        val container = FrameLayout(this).apply {
            val padding = resources.getDimensionPixelSize(R.dimen.spacing_medium)
            setPadding(padding, padding, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(container)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.folder_save) { _, _ -> onSave(input.text?.toString().orEmpty()) }
            .show()
    }

    /**
     * The rules of a folder.
     *
     * The neutral button switches between "one rule is enough" and "every rule has to hold" and
     * reopens the same dialog, so the choice is visible while the rules are being ticked.
     */
    private fun promptFolderRules(
        initialRules: Set<FolderRule>,
        initialMode: FolderRuleMode,
        onSave: (Set<FolderRule>, FolderRuleMode) -> Unit
    ) {
        val rules = FolderRule.entries
        val labels = rules.map { getString(folderRuleLabel(it)) }.toTypedArray()
        val checked = BooleanArray(rules.size) { rules[it] in initialRules }
        val selected = { rules.filterIndexed { index, _ -> checked[index] }.toSet() }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.folder_rules_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNeutralButton(
                if (initialMode == FolderRuleMode.ALL) R.string.folder_rules_require_all else R.string.folder_rules_any
            ) { _, _ ->
                val nextMode = if (initialMode == FolderRuleMode.ALL) FolderRuleMode.ANY else FolderRuleMode.ALL
                promptFolderRules(selected(), nextMode, onSave)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.folder_save) { _, _ -> onSave(selected(), initialMode) }
            .show()
    }

    /** Label of one rule. */
    @StringRes
    private fun folderRuleLabel(rule: FolderRule): Int = when (rule) {
        FolderRule.UNREAD -> R.string.folder_rule_unread
        FolderRule.UNMUTED -> R.string.folder_rule_unmuted
        FolderRule.MUTED -> R.string.folder_rule_muted
        FolderRule.PINNED -> R.string.folder_rule_pinned
        FolderRule.PRIVATE -> R.string.folder_rule_private
        FolderRule.GROUPS -> R.string.folder_rule_groups
        FolderRule.CHANNELS -> R.string.folder_rule_channels
        FolderRule.SAVED_MESSAGES -> R.string.folder_rule_saved
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
