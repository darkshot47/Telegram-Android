package com.telefarm.data.model

/**
 * A privacy setting that lives on Telegram, not on this device.
 *
 * These are the account's own settings: changing one changes what other people see, on every
 * client, not just what this app draws. The device local switches in Settings are labelled as
 * theirs; these are not.
 */
enum class PrivacySettingUi {

    /** Who can see when you were last online. */
    LAST_SEEN,

    /** Who can see your phone number. */
    PHONE_NUMBER,

    /** Who can see your profile photo. */
    PROFILE_PHOTO,

    /** Who can call you. */
    CALLS,

    /** Who can add you to groups and channels. */
    GROUP_INVITES
}
