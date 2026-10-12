package com.telefarm.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.core.privacy.PrivacyAudience
import com.telefarm.core.privacy.PrivacyRulesUi
import com.telefarm.data.PrivacyRepository
import com.telefarm.data.model.PrivacySettingUi
import com.telefarm.databinding.ActivityPrivacyBinding
import com.telefarm.databinding.ItemPrivacyBinding
import com.telefarm.ui.common.showSnackbar
import kotlinx.coroutines.launch

/** One row of the privacy screen. */
private data class PrivacyRow(
    val setting: PrivacySettingUi,
    val rules: PrivacyRulesUi?
)

/**
 * The privacy settings of the account.
 *
 * Unlike the switches on the settings screen, which change what this device draws, these change
 * what other people see - on every client. The screen therefore shows what Telegram answered
 * rather than what was last tapped here, and says so at the top.
 *
 * A setting whose rules do not reduce to one of the three choices is shown as custom and is left
 * alone until it is changed, because rounding somebody's rules to the nearest choice would
 * quietly change who can reach them.
 */
class PrivacyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyBinding
    private lateinit var adapter: PrivacyAdapter

    private val privacy: PrivacyRepository get() = appGraph.privacy

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = PrivacyAdapter(::showAudienceDialog)
        binding.privacyList.layoutManager = LinearLayoutManager(this)
        binding.privacyList.adapter = adapter
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.stateAction.setOnClickListener { reload() }

        observe()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch { privacy.load() }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    privacy.rules.collect { rules ->
                        adapter.submitList(PrivacySettingUi.entries.map { PrivacyRow(it, rules[it]) })
                        binding.stateContainer.isVisible = rules.isEmpty() && !privacy.isLoading.value
                    }
                }
                launch {
                    privacy.isLoading.collect { loading ->
                        binding.stateProgress.isVisible = loading
                        if (loading) binding.stateText.setText(R.string.privacy_loading)
                    }
                }
                launch {
                    privacy.error.collect { message ->
                        if (message != null && privacy.rules.value.isEmpty()) {
                            binding.stateContainer.isVisible = true
                            binding.stateProgress.isVisible = false
                            binding.stateText.text = message
                            binding.stateAction.isVisible = true
                        }
                    }
                }
            }
        }
    }

    /** The three choices, and what the setting holds now. */
    private fun showAudienceDialog(row: PrivacyRow) {
        val audiences = PrivacyAudience.entries.toTypedArray()
        val labels = audiences.map { getString(labelRes(it)) }.toTypedArray()
        val current = row.rules?.takeIf { !it.isCustom }?.audience
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes(row.setting))
            .setSingleChoiceItems(labels, audiences.indexOf(current)) { dialog, index ->
                dialog.dismiss()
                apply(row.setting, audiences[index])
            }
            .show()
    }

    private fun apply(setting: PrivacySettingUi, audience: PrivacyAudience) {
        lifecycleScope.launch {
            val done = privacy.set(setting, audience)
            binding.root.showSnackbar(
                getString(if (done) R.string.privacy_saved else R.string.privacy_save_failed)
            )
        }
    }

    private class PrivacyAdapter(
        private val onClick: (PrivacyRow) -> Unit
    ) : ListAdapter<PrivacyRow, PrivacyAdapter.Holder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            ItemPrivacyBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onClick
        )

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        class Holder(
            private val binding: ItemPrivacyBinding,
            private val onClick: (PrivacyRow) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(row: PrivacyRow) {
                binding.privacyName.setText(titleRes(row.setting))
                binding.privacyValue.setText(valueRes(row.rules))
                binding.root.setOnClickListener { onClick(row) }
            }
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<PrivacyRow>() {
                override fun areItemsTheSame(oldItem: PrivacyRow, newItem: PrivacyRow): Boolean =
                    oldItem.setting == newItem.setting

                override fun areContentsTheSame(oldItem: PrivacyRow, newItem: PrivacyRow): Boolean =
                    oldItem == newItem
            }
        }
    }

    private companion object {

        @StringRes
        fun titleRes(setting: PrivacySettingUi): Int = when (setting) {
            PrivacySettingUi.LAST_SEEN -> R.string.privacy_last_seen
            PrivacySettingUi.PHONE_NUMBER -> R.string.privacy_phone_number
            PrivacySettingUi.PROFILE_PHOTO -> R.string.privacy_profile_photo
            PrivacySettingUi.CALLS -> R.string.privacy_calls
            PrivacySettingUi.GROUP_INVITES -> R.string.privacy_group_invites
        }

        /** What the row says: the choice, or that Telegram's rules are not one of the three. */
        @StringRes
        fun valueRes(rules: PrivacyRulesUi?): Int = when {
            rules == null -> R.string.privacy_unknown
            rules.isCustom -> R.string.privacy_custom
            rules.audience == PrivacyAudience.EVERYONE -> R.string.privacy_audience_everyone
            rules.audience == PrivacyAudience.CONTACTS -> R.string.privacy_audience_contacts
            else -> R.string.privacy_audience_nobody
        }

        @StringRes
        fun labelRes(audience: PrivacyAudience): Int = when (audience) {
            PrivacyAudience.EVERYONE -> R.string.privacy_audience_everyone
            PrivacyAudience.CONTACTS -> R.string.privacy_audience_contacts
            PrivacyAudience.NOBODY -> R.string.privacy_audience_nobody
        }
    }
}
