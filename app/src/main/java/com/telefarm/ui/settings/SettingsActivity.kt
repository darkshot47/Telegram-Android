package com.telefarm.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.telefarm.BuildConfig
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.core.notifications.QuietHours
import com.telefarm.core.security.AutoLockDelay
import com.telefarm.core.security.PasscodeCheck
import com.telefarm.core.security.PasscodePolicy
import com.telefarm.data.PasscodeSetResult
import com.telefarm.data.UnlockResult
import com.telefarm.data.model.ThemeMode
import com.telefarm.databinding.ActivitySettingsBinding
import com.telefarm.databinding.DialogPasscodeBinding
import com.telefarm.ui.auth.AuthActivity
import com.telefarm.ui.common.Avatars
import com.telefarm.ui.common.Sizes
import com.telefarm.ui.common.resolve
import com.telefarm.ui.common.showSnackbar
import kotlinx.coroutines.launch

/**
 * Settings.
 *
 * The screen exposes theme, device local privacy switches, notifications, storage usage,
 * account editing, about information and logout. Nothing is listed that has no behaviour.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val viewModel: SettingsViewModel by viewModels {
        viewModelFactory { initializer { SettingsViewModel(appGraph) } }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.setNotificationsEnabled(true)
            } else {
                binding.notificationsSwitch.isChecked = false
                binding.root.showSnackbar(getString(R.string.settings_notifications_unsupported))
            }
            render(viewModel.state.value)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.accountRow.setOnClickListener { openEditProfile() }
        binding.themeRow.setOnClickListener { showThemeDialog() }
        binding.passcodeRow.setOnClickListener { showPasscodeDialog() }
        binding.privacyRow.setOnClickListener { startActivity(Intent(this, PrivacyActivity::class.java)) }
        binding.blockedRow.setOnClickListener { startActivity(Intent(this, BlockedUsersActivity::class.java)) }
        binding.sessionsRow.setOnClickListener { startActivity(Intent(this, SessionsActivity::class.java)) }
        binding.quietHoursSwitch.setOnCheckedChangeListener { _, checked ->
            appGraph.preferences.quietHours = appGraph.preferences.quietHours.copy(enabled = checked)
            updateQuietHoursRows()
        }
        binding.quietMentionsSwitch.setOnCheckedChangeListener { _, checked ->
            appGraph.preferences.quietHours = appGraph.preferences.quietHours.copy(allowsMentions = checked)
        }
        binding.quietTimesRow.setOnClickListener { showQuietTimesDialog() }
        binding.hidePhoneSwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.setHidePhoneNumbers(checked)
        }
        binding.hideOnlineSwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.setHideOnlineStatus(checked)
        }
        binding.notificationsSwitch.setOnCheckedChangeListener { _, checked ->
            if (!viewModel.setNotificationsEnabled(checked) && checked) {
                requestNotificationPermission()
            }
        }
        binding.clearCacheButton.setOnClickListener { confirmClearCache() }
        binding.aboutRow.setOnClickListener { showAboutDialog() }
        binding.logoutButton.setOnClickListener { confirmLogout() }
        binding.versionRow.text = getString(R.string.settings_about_version, BuildConfig.VERSION_NAME)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { message ->
                    if (message != null) {
                        binding.root.showSnackbar(message.resolve(this@SettingsActivity))
                        viewModel.clearEvent()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadAccount()
        viewModel.loadStorage()
        updatePasscodeRow()
        updateQuietHoursRows()
    }

    /** What the quiet hours rows say. */
    private fun updateQuietHoursRows() {
        val hours = appGraph.preferences.quietHours
        binding.quietHoursSwitch.isChecked = hours.enabled
        binding.quietMentionsSwitch.isChecked = hours.allowsMentions
        binding.quietTimesRow.isEnabled = hours.enabled
        binding.quietTimesValue.text = getString(
            R.string.settings_quiet_hours_format,
            QuietHours.format(hours.startMinuteOfDay),
            QuietHours.format(hours.endMinuteOfDay)
        )
    }

    /** Picks one end of the quiet hours. */
    private fun showQuietTimesDialog() {
        val actions = arrayOf(getString(R.string.quiet_from), getString(R.string.quiet_until))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_quiet_hours_times)
            .setItems(actions) { _, which -> showTimePicker(pickStart = which == 0) }
            .show()
    }

    private fun showTimePicker(pickStart: Boolean) {
        val hours = appGraph.preferences.quietHours
        val current = if (pickStart) hours.startMinuteOfDay else hours.endMinuteOfDay
        TimePickerDialog(
            this,
            { _, hour, minute ->
                val minuteOfDay = QuietHours.minuteOfDay(hour, minute)
                appGraph.preferences.quietHours = if (pickStart) {
                    hours.copy(startMinuteOfDay = minuteOfDay)
                } else {
                    hours.copy(endMinuteOfDay = minuteOfDay)
                }
                updateQuietHoursRows()
            },
            current / MINUTES_PER_HOUR,
            current % MINUTES_PER_HOUR,
            true
        ).show()
    }

    /** What the passcode row says, which changes whenever a dialog on this screen closes. */
    private fun updatePasscodeRow() {
        val passcodes = appGraph.passcodes
        binding.passcodeSummary.text = if (passcodes.isConfigured()) {
            getString(R.string.settings_passcode_summary_on, delayLabel(passcodes.delay))
        } else {
            getString(R.string.settings_passcode_summary_off)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Avatars.cancel(binding.accountPhoto)
    }

    private fun render(state: SettingsScreenState) {
        binding.hidePhoneSwitch.isChecked = state.settings.hidePhoneNumbers
        binding.hideOnlineSwitch.isChecked = state.settings.hideOnlineStatus
        binding.notificationsSwitch.isChecked = state.settings.notificationsEnabled
        binding.themeValue.setText(
            when (state.settings.theme) {
                ThemeMode.SYSTEM -> R.string.settings_theme_system
                ThemeMode.LIGHT -> R.string.settings_theme_light
                ThemeMode.DARK -> R.string.settings_theme_dark
            }
        )

        val account = state.account
        binding.accountName.text = account?.displayName
            ?: getString(R.string.settings_storage_calculating)
        binding.accountPhone.isVisible = !account?.phoneNumber.isNullOrBlank()
        binding.accountPhone.text = account?.phoneNumber.orEmpty()
        binding.accountUsername.isVisible = !account?.username.isNullOrBlank()
        binding.accountUsername.text = account?.username?.let { "@$it" }.orEmpty()
        binding.accountBio.isVisible = !account?.bio.isNullOrBlank()
        binding.accountBio.text = account?.bio.orEmpty()
        if (account != null) {
            Avatars.bind(
                image = binding.accountPhoto,
                initialsView = binding.accountInitials,
                name = account.displayName,
                peerId = account.userId,
                photo = account.photo,
                loader = appGraph.thumbnails,
                scope = lifecycleScope,
                sizePx = resources.getDimensionPixelSize(R.dimen.avatar_small)
            )
        }

        val storage = state.storage
        binding.storageDatabaseRow.text = if (state.isCalculatingStorage) {
            getString(R.string.settings_storage_calculating)
        } else {
            getString(
                R.string.settings_storage_database,
                Sizes.bytesOrZero(storage?.databaseBytes ?: 0L)
            )
        }
        binding.storageFilesRow.text = if (state.isCalculatingStorage) {
            getString(R.string.settings_storage_calculating)
        } else {
            getString(
                R.string.settings_storage_files,
                Sizes.bytesOrZero(storage?.filesBytes ?: 0L),
                storage?.fileCount ?: 0
            )
        }
        binding.storageTotalRow.text = getString(
            R.string.settings_storage_total,
            Sizes.bytesOrZero(storage?.totalBytes ?: 0L)
        )
        binding.clearCacheButton.isEnabled = !state.isClearingCache
    }

    /** The passcode row: set one, or choose what to do with the one that is set. */
    private fun showPasscodeDialog() {
        if (!appGraph.passcodes.isConfigured()) {
            showPasscodeEditor(isChange = false)
            return
        }
        val actions = arrayOf(
            getString(R.string.settings_passcode_change_action),
            getString(R.string.settings_passcode_auto_lock),
            getString(R.string.settings_passcode_disable)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_passcode)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showPasscodeEditor(isChange = true)
                    1 -> showAutoLockDialog()
                    else -> confirmDisablePasscode()
                }
            }
            .show()
    }

    /** How long one unlock lasts. */
    private fun showAutoLockDialog() {
        val delays = AutoLockDelay.entries.toTypedArray()
        val labels = delays.map(::delayLabel).toTypedArray()
        val current = delays.indexOf(appGraph.passcodes.delay)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_passcode_auto_lock)
            .setSingleChoiceItems(labels, current) { dialog, index ->
                appGraph.passcodes.setDelay(delays[index])
                updatePasscodeRow()
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Sets or changes the passcode.
     *
     * The dialog stays open when the answer is refused, so the typing that was refused is still
     * on screen and only the reason has to be read.
     */
    private fun showPasscodeEditor(isChange: Boolean) {
        val fields = DialogPasscodeBinding.inflate(layoutInflater)
        fields.currentPasscodeField.isVisible = isChange
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isChange) R.string.settings_passcode_dialog_change_title
                else R.string.settings_passcode_dialog_set_title
            )
            .setView(fields.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(
                if (isChange) R.string.settings_passcode_change_action
                else R.string.settings_passcode_set_action,
                null
            )
            .create()
        // The listener is set once the dialog exists, so a refused answer can keep it open.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                submitPasscode(dialog, fields, isChange)
            }
        }
        dialog.show()
    }

    private fun submitPasscode(dialog: AlertDialog, fields: DialogPasscodeBinding, isChange: Boolean) {
        val current = if (isChange) fields.currentPasscodeInput.text?.toString().orEmpty() else null
        val next = fields.newPasscodeInput.text?.toString().orEmpty()
        val confirmation = fields.confirmPasscodeInput.text?.toString().orEmpty()
        lifecycleScope.launch {
            val result = appGraph.passcodes.change(current, next, confirmation)
            val error = when (result) {
                is PasscodeSetResult.Set -> null
                is PasscodeSetResult.ConfirmationMismatch -> getString(R.string.settings_passcode_error_mismatch)
                is PasscodeSetResult.CurrentPasscodeWrong ->
                    getString(R.string.settings_passcode_error_wrong_current)
                is PasscodeSetResult.Refused -> passcodeError(result.check)
            }
            if (error == null) {
                dialog.dismiss()
                updatePasscodeRow()
                binding.root.showSnackbar(
                    getString(
                        if (isChange) R.string.settings_passcode_changed
                        else R.string.settings_passcode_set_done
                    )
                )
            } else {
                fields.passcodeError.text = error
                fields.passcodeError.isVisible = true
            }
        }
    }

    /** Turning the passcode off needs the passcode, and guessing at it is throttled the same way. */
    private fun confirmDisablePasscode() {
        val fields = DialogPasscodeBinding.inflate(layoutInflater)
        fields.currentPasscodeField.isVisible = true
        fields.newPasscodeField.isVisible = false
        fields.confirmPasscodeField.isVisible = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_passcode_disable)
            .setMessage(R.string.settings_passcode_enter)
            .setView(fields.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.settings_passcode_disable, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                lifecycleScope.launch {
                    val attempt = fields.currentPasscodeInput.text?.toString().orEmpty()
                    when (appGraph.passcodes.unlock(attempt)) {
                        is UnlockResult.Opened -> {
                            appGraph.passcodes.clear()
                            dialog.dismiss()
                            updatePasscodeRow()
                            binding.root.showSnackbar(getString(R.string.settings_passcode_disabled))
                        }
                        is UnlockResult.Wrong -> {
                            fields.passcodeError.setText(R.string.settings_passcode_error_wrong_current)
                            fields.passcodeError.isVisible = true
                        }
                        is UnlockResult.Waiting -> {
                            fields.passcodeError.text = getString(R.string.lock_error_too_many)
                            fields.passcodeError.isVisible = true
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /** Why a passcode was refused, in the words of the rule that refused it. */
    private fun passcodeError(check: PasscodeCheck): String = getString(
        when (check) {
            is PasscodeCheck.TooShort -> R.string.settings_passcode_error_short
            is PasscodeCheck.TooLong -> R.string.settings_passcode_error_long
            is PasscodeCheck.RepeatedCharacter -> R.string.settings_passcode_error_repeated
            is PasscodeCheck.Sequential -> R.string.settings_passcode_error_sequential
            is PasscodeCheck.Unchanged -> R.string.settings_passcode_error_unchanged
            is PasscodeCheck.Accepted -> R.string.settings_passcode_set_done
        },
        if (check is PasscodeCheck.TooShort) PasscodePolicy.MIN_LENGTH else PasscodePolicy.MAX_LENGTH
    )

    private fun delayLabel(delay: AutoLockDelay): String = getString(
        when (delay) {
            AutoLockDelay.IMMEDIATELY -> R.string.delay_immediately
            AutoLockDelay.ONE_MINUTE -> R.string.delay_one_minute
            AutoLockDelay.FIVE_MINUTES -> R.string.delay_five_minutes
            AutoLockDelay.FIFTEEN_MINUTES -> R.string.delay_fifteen_minutes
            AutoLockDelay.ONE_HOUR -> R.string.delay_one_hour
        }
    )

    private fun showThemeDialog() {
        val modes = arrayOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
        val labels = modes.map { mode ->
            getString(
                when (mode) {
                    ThemeMode.SYSTEM -> R.string.settings_theme_system
                    ThemeMode.LIGHT -> R.string.settings_theme_light
                    ThemeMode.DARK -> R.string.settings_theme_dark
                }
            )
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_theme_dialog_title)
            .setSingleChoiceItems(labels, modes.indexOf(viewModel.state.value.settings.theme)) { dialog, index ->
                viewModel.setTheme(modes[index])
                dialog.dismiss()
            }
            .show()
    }

    private fun confirmClearCache() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_storage_clear_cache)
            .setMessage(R.string.settings_storage_clear_cache_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.settings_storage_clear_cache_confirm) { _, _ -> viewModel.clearCache() }
            .show()
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_logout_title)
            .setMessage(R.string.dialog_logout_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_logout_confirm) { _, _ -> logOut() }
            .show()
    }

    private fun logOut() {
        viewModel.logOut()
        startActivity(
            Intent(this, AuthActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finishAffinity()
    }

    private fun openEditProfile() {
        startActivity(EditProfileActivity.intent(this))
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
            append("\n\n")
            append(getString(R.string.about_source_title))
            append(": ")
            append(getString(R.string.about_source_text))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(message)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    /** Asks for the notification permission; the switch only reports a state that can be kept. */
    private fun requestNotificationPermission() {
        binding.notificationsSwitch.isChecked = false
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {

        /** Minutes in an hour, for turning a minute of the day into a clock reading. */
        private const val MINUTES_PER_HOUR = 60

        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
