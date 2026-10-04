package com.telefarm.ui.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.button.MaterialButton
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.data.model.ChatKind
import com.telefarm.databinding.ActivityProfileBinding
import com.telefarm.ui.chat.ChatActivity
import com.telefarm.ui.chat.ProfileTarget
import com.telefarm.ui.common.Avatars
import com.telefarm.ui.common.TimeFormat
import com.telefarm.ui.common.resolve
import com.telefarm.ui.common.showSnackbar
import com.telefarm.ui.media.PhotoViewerActivity
import kotlinx.coroutines.launch

/**
 * Profile of a user or of a group/channel.
 *
 * The screen shows only information Telegram returned and offers only actions that work:
 * opening the conversation, listing members when they are visible and leaving a group or
 * channel the account is a member of.
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding

    private val userId: Long by lazy { intent.getLongExtra(EXTRA_USER_ID, 0L) }
    private val chatId: Long by lazy { intent.getLongExtra(EXTRA_CHAT_ID, 0L) }

    private val viewModel: ProfileViewModel by viewModels {
        viewModelFactory { initializer { ProfileViewModel(appGraph, userId, chatId) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.photoContainer.setOnClickListener {
            val photo = viewModel.state.value.profile?.photo ?: return@setOnClickListener
            startActivity(PhotoViewerActivity.intent(this, photo))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.event.collect { event ->
                    when (event) {
                        is ProfileEvent.OpenChat -> {
                            startActivity(ChatActivity.intent(this@ProfileActivity, event.chatId))
                        }
                        is ProfileEvent.Left -> {
                            binding.root.showSnackbar(getString(event.resId))
                            finish()
                        }
                        is ProfileEvent.Error -> binding.root.showSnackbar(
                            event.message.resolve(this@ProfileActivity)
                        )
                        null -> Unit
                    }
                    if (event != null) viewModel.clearEvent()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Avatars.cancel(binding.profilePhoto)
    }

    private fun render(state: ProfileScreenState) {
        val profile = state.profile
        binding.stateContainer.isVisible = state.isLoading || profile == null
        binding.stateProgress.isVisible = state.isLoading
        binding.stateTitle.isVisible = !state.isLoading && profile == null
        binding.stateMessage.isVisible = !state.isLoading && profile == null
        if (!state.isLoading && profile == null) {
            binding.stateTitle.setText(R.string.profile_error)
            binding.stateMessage.text = state.error?.resolve(this)
        }
        if (profile == null) return

        binding.toolbar.title = getString(
            when {
                profile.isUser && profile.kind == ChatKind.SAVED_MESSAGES -> R.string.profile_title_self
                profile.isUser -> R.string.profile_title_user
                profile.kind == ChatKind.CHANNEL -> R.string.profile_title_channel
                else -> R.string.profile_title_group
            }
        )
        binding.profileName.text = profile.title

        val subtitle = profile.status?.let { TimeFormat.status(this, it) }
        binding.profileStatus.isVisible = !subtitle.isNullOrBlank()
        binding.profileStatus.text = subtitle.orEmpty()

        binding.phoneRow.isVisible = !profile.phoneNumber.isNullOrBlank()
        binding.phoneRow.text = profile.phoneNumber.orEmpty()

        binding.usernameRow.isVisible = !profile.username.isNullOrBlank()
        binding.usernameRow.text = profile.username?.let { "@$it" }.orEmpty()

        binding.bioRow.isVisible = !profile.bio.isNullOrBlank()
        binding.bioRow.text = profile.bio.orEmpty()

        val membersLabel = if (profile.kind == ChatKind.CHANNEL) {
            resources.getQuantityString(R.plurals.subscribers_count, profile.memberCount, profile.memberCount)
        } else {
            resources.getQuantityString(R.plurals.members_count, profile.memberCount, profile.memberCount)
        }
        binding.membersRow.isVisible = !profile.isUser && profile.memberCount > 0
        binding.membersRow.text = membersLabel
        binding.membersRow.setOnClickListener {
            if (profile.canViewMembers && chatId != 0L) {
                startActivity(MemberListActivity.intent(this, chatId, profile.title))
            }
        }
        binding.membersRow.isClickable = profile.canViewMembers && chatId != 0L

        binding.actionContainer.removeAllViews()
        if (profile.canSendMessage) {
            binding.actionContainer.addView(actionRow(R.string.profile_send_message) { viewModel.openConversation() })
        }
        if (profile.canViewMembers && chatId != 0L) {
            binding.actionContainer.addView(
                actionRow(R.string.profile_view_members) {
                    startActivity(MemberListActivity.intent(this, chatId, profile.title))
                }
            )
        }
        if (profile.canLeave && !profile.isUser) {
            binding.actionContainer.addView(
                actionRow(R.string.profile_leave_chat) { viewModel.leave() }
            )
        }

        binding.profileInitials.isVisible = true
        Avatars.bind(
            image = binding.profilePhoto,
            initialsView = binding.profileInitials,
            name = profile.title,
            peerId = if (profile.isUser) profile.id else -profile.id,
            photo = profile.photo,
            loader = appGraph.thumbnails,
            scope = lifecycleScope,
            sizePx = resources.getDimensionPixelSize(R.dimen.avatar_xlarge)
        )
    }

    /** Builds one action row; the container is small enough to build it in code. */
    private fun actionRow(textRes: Int, action: () -> Unit): View {
        val padding = resources.getDimensionPixelSize(R.dimen.spacing_medium)
        val button = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            setText(textRes)
            minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
            gravity = Gravity.CENTER
            setOnClickListener { action() }
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(button)
        }
        return wrapper
    }

    companion object {

        private const val EXTRA_USER_ID = "com.telefarm.extra.USER_ID"
        private const val EXTRA_CHAT_ID = "com.telefarm.extra.CHAT_ID"

        /** Opens the profile of a user. */
        fun intent(context: Context, userId: Long, title: String? = null): Intent =
            Intent(context, ProfileActivity::class.java)
                .putExtra(EXTRA_USER_ID, userId)
                .putExtra(EXTRA_TITLE, title)

        /** Opens the profile of a chat; the chat id is the id of the conversation. */
        fun intent(context: Context, chatId: Long, title: String): Intent =
            Intent(context, ProfileActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_TITLE, title)

        /** Opens the profile of a resolved chat or user. */
        fun intent(context: Context, target: ProfileTarget, title: String? = null): Intent = when (target) {
            is ProfileTarget.User -> intent(context, target.userId, title)
            is ProfileTarget.ChatInfo -> intent(context, target.chatId, title.orEmpty())
        }

        private const val EXTRA_TITLE = "com.telefarm.extra.TITLE"
    }
}
