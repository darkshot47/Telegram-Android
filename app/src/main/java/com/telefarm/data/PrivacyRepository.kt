package com.telefarm.data

import com.telefarm.core.privacy.PrivacyAudience
import com.telefarm.core.privacy.PrivacyAudienceRules
import com.telefarm.core.privacy.PrivacyRuleKind
import com.telefarm.core.privacy.PrivacyRuleUi
import com.telefarm.core.privacy.PrivacyRulesUi
import com.telefarm.core.td.TdLibClient
import com.telefarm.data.model.PrivacySettingUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi

/**
 * The privacy settings of the account.
 *
 * These live on Telegram, so reading them is a round trip and writing one changes what other
 * people see on every client. That is why the screen shows what Telegram answered rather than
 * what was last tapped here, and why a rule list this client cannot reduce to one of its three
 * choices is shown as custom instead of being rounded to the nearest one.
 */
class PrivacyRepository(private val td: TdLibClient) {

    private val _rules = MutableStateFlow<Map<PrivacySettingUi, PrivacyRulesUi>>(emptyMap())

    /** What Telegram answered for each setting; a setting is absent until it has been read. */
    val rules: StateFlow<Map<PrivacySettingUi, PrivacyRulesUi>> = _rules.asStateFlow()

    private val _isLoading = MutableStateFlow(false)

    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    val error: StateFlow<String?> = _error.asStateFlow()

    /** Reads all five settings. Asked for together, so the screen fills in at once. */
    suspend fun load() {
        _isLoading.value = true
        _error.value = null
        try {
            val loaded = coroutineScope {
                PrivacySettingUi.entries.map { setting ->
                    async { setting to read(setting) }
                }.map { it.await() }
            }
            _rules.value = loaded.toMap()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _error.value = error.message
        } finally {
            _isLoading.value = false
        }
    }

    /** Sets one setting and reads it back, so the row shows what Telegram now holds. */
    suspend fun set(setting: PrivacySettingUi, audience: PrivacyAudience): Boolean = try {
        td.send(
            TdApi.SetUserPrivacySettingRules().apply {
                this.setting = setting.toTd()
                this.rules = PrivacyAudienceRules.write(audience).map { it.toTd() }
                    .toTypedArray<TdApi.UserPrivacySettingRule>()
                    .let { TdApi.UserPrivacySettingRules().apply { rules = it } }
            }
        )
        _rules.value = _rules.value + (setting to read(setting))
        true
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        _error.value = error.message
        false
    }

    private suspend fun read(setting: PrivacySettingUi): PrivacyRulesUi {
        val answered = td.send(TdApi.GetUserPrivacySettingRules().apply { this.setting = setting.toTd() })
        return PrivacyAudienceRules.read(answered.rules.orEmpty().map { it.toUi() })
    }

    private fun PrivacySettingUi.toTd(): TdApi.UserPrivacySetting = when (this) {
        PrivacySettingUi.LAST_SEEN -> TdApi.UserPrivacySettingShowStatus()
        PrivacySettingUi.PHONE_NUMBER -> TdApi.UserPrivacySettingShowPhoneNumber()
        PrivacySettingUi.PROFILE_PHOTO -> TdApi.UserPrivacySettingShowProfilePhoto()
        PrivacySettingUi.CALLS -> TdApi.UserPrivacySettingAllowCalls()
        PrivacySettingUi.GROUP_INVITES -> TdApi.UserPrivacySettingAllowChatInvites()
    }

    private fun PrivacyRuleUi.toTd(): TdApi.UserPrivacySettingRule = when (kind) {
        PrivacyRuleKind.ALLOW_ALL -> TdApi.UserPrivacySettingRuleAllowAll()
        PrivacyRuleKind.ALLOW_CONTACTS -> TdApi.UserPrivacySettingRuleAllowContacts()
        PrivacyRuleKind.ALLOW_USERS -> TdApi.UserPrivacySettingRuleAllowUsers().apply {
            userIds = this@toTd.userIds.toLongArray()
        }
        PrivacyRuleKind.RESTRICT_ALL -> TdApi.UserPrivacySettingRuleRestrictAll()
        PrivacyRuleKind.RESTRICT_CONTACTS -> TdApi.UserPrivacySettingRuleRestrictContacts()
        PrivacyRuleKind.RESTRICT_USERS -> TdApi.UserPrivacySettingRuleRestrictUsers().apply {
            userIds = this@toTd.userIds.toLongArray()
        }
        // A rule this client does not know is never written back, so it cannot be sent as
        // something else by accident.
        PrivacyRuleKind.UNKNOWN -> TdApi.UserPrivacySettingRuleRestrictAll()
    }

    private fun TdApi.UserPrivacySettingRule.toUi(): PrivacyRuleUi = when (this) {
        is TdApi.UserPrivacySettingRuleAllowAll -> PrivacyRuleUi(PrivacyRuleKind.ALLOW_ALL)
        is TdApi.UserPrivacySettingRuleAllowContacts -> PrivacyRuleUi(PrivacyRuleKind.ALLOW_CONTACTS)
        is TdApi.UserPrivacySettingRuleAllowUsers ->
            PrivacyRuleUi(PrivacyRuleKind.ALLOW_USERS, userIds?.toList().orEmpty())
        is TdApi.UserPrivacySettingRuleRestrictAll -> PrivacyRuleUi(PrivacyRuleKind.RESTRICT_ALL)
        is TdApi.UserPrivacySettingRuleRestrictContacts ->
            PrivacyRuleUi(PrivacyRuleKind.RESTRICT_CONTACTS)
        is TdApi.UserPrivacySettingRuleRestrictUsers ->
            PrivacyRuleUi(PrivacyRuleKind.RESTRICT_USERS, userIds?.toList().orEmpty())
        else -> PrivacyRuleUi(PrivacyRuleKind.UNKNOWN)
    }
}
