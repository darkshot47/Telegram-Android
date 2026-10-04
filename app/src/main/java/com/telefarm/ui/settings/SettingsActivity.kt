package com.telefarm.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import com.telefarm.data.model.ThemeMode
import com.telefarm.databinding.ActivitySettingsBinding
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
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
