package com.telefarm.core.config

import com.telefarm.core.security.SecureStore

/** A pair of application credentials that was stored on this device, when there is one. */
data class StoredApiCredentials(val apiId: Int?, val apiHash: String?) {

    val isPresent: Boolean get() = apiId != null && !apiHash.isNullOrBlank()
}

/**
 * The Telegram application credentials of this device.
 *
 * The application ships with a shared identity so that it can be installed and used without
 * registering anything first. Everybody who prefers a personal pair - which is what the Telegram
 * terms ask for - can enter it on the sign in screen, and it is kept here.
 *
 * The values are stored in the secure store rather than in plain preferences: an application hash
 * is not an account credential, but it identifies the application and does not belong in a file
 * that any backup or rooted device can read without effort.
 */
class ApiCredentialStore(private val secureStore: SecureStore) {

    /** Credentials of this device, or an empty holder when none were entered. */
    fun load(): StoredApiCredentials {
        val apiId = secureStore.get(KEY_API_ID)?.toIntOrNull()
        val apiHash = secureStore.get(KEY_API_HASH)
        return StoredApiCredentials(apiId, apiHash)
    }

    /** True when this device carries its own pair. */
    fun isPresent(): Boolean = load().isPresent

    /** Keeps a validated pair; anything else has to be rejected before it arrives here. */
    fun save(apiId: Int, apiHash: String) {
        secureStore.put(KEY_API_ID, apiId.toString())
        secureStore.put(KEY_API_HASH, apiHash)
    }

    /** Drops the pair, so the application falls back to the identity it ships with. */
    fun clear() {
        secureStore.remove(KEY_API_ID)
        secureStore.remove(KEY_API_HASH)
    }

    private companion object {
        const val KEY_API_ID = "telegram.api.id"
        const val KEY_API_HASH = "telegram.api.hash"
    }
}
