package com.telefarm.core.prefs

import com.telefarm.core.security.AutoLockDelay
import com.telefarm.core.security.LockState
import com.telefarm.core.security.PasscodeHash
import com.telefarm.core.security.SecureStore

/** What the passcode of this device is made of. */
data class StoredPasscode(

    /** Random bytes the passcode was stretched with; empty when no passcode is set. */
    val salt: ByteArray,

    /** Stretched passcode; empty when no passcode is set. */
    val hash: ByteArray,

    /** Attempts, waits and the grace period, as [LockState]. */
    val state: LockState
) {

    /** True when a passcode protects this device. */
    val isConfigured: Boolean get() = salt.isNotEmpty() && hash.isNotEmpty()

    companion object {

        /** No passcode. */
        val NONE = StoredPasscode(ByteArray(0), ByteArray(0), LockState())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredPasscode) return false
        return salt.contentEquals(other.salt) && hash.contentEquals(other.hash) && state == other.state
    }

    override fun hashCode(): Int = 31 * (31 * salt.contentHashCode() + hash.contentHashCode()) + state.hashCode()
}

/**
 * The passcode of this device, in the secure store.
 *
 * Only the stretched passcode and its salt are kept, never the passcode itself, so a device that
 * is taken apart does not hand one over. The store is the one already encrypted with a key in the
 * Android keystore, which is the same place the Telegram session lives.
 */
class PasscodeStore(private val secureStore: SecureStore) {

    /** What is stored, or [StoredPasscode.NONE] when no passcode is set. */
    fun load(): StoredPasscode {
        val salt = secureStore.get(KEY_SALT)?.let(PasscodeHash::fromHex) ?: return StoredPasscode.NONE
        val hash = secureStore.get(KEY_HASH)?.let(PasscodeHash::fromHex) ?: return StoredPasscode.NONE
        if (salt.isEmpty() || hash.isEmpty()) return StoredPasscode.NONE
        val state = LockState.decode(secureStore.get(KEY_STATE))
        return StoredPasscode(salt, hash, state)
    }

    /** Writes a passcode and the state that goes with it. */
    fun save(passcode: StoredPasscode) {
        secureStore.put(KEY_SALT, PasscodeHash.toHex(passcode.salt))
        secureStore.put(KEY_HASH, PasscodeHash.toHex(passcode.hash))
        secureStore.put(KEY_STATE, LockState.encode(passcode.state))
    }

    /** Writes only the state, which changes on every attempt. */
    fun saveState(state: LockState) {
        secureStore.put(KEY_STATE, LockState.encode(state))
    }

    /** The grace period setting, kept even when no passcode is set. */
    fun loadAutoLockDelay(): AutoLockDelay =
        secureStore.get(KEY_DELAY)?.toLongOrNull()?.let(AutoLockDelay::of) ?: AutoLockDelay.IMMEDIATELY

    fun saveAutoLockDelay(delay: AutoLockDelay) {
        secureStore.put(KEY_DELAY, delay.durationMs.toString())
    }

    /** Removes the passcode. Telegram's own session is not touched. */
    fun clear() {
        secureStore.remove(KEY_SALT)
        secureStore.remove(KEY_HASH)
        secureStore.remove(KEY_STATE)
    }

    private companion object {
        const val KEY_SALT = "passcode.salt"
        const val KEY_HASH = "passcode.hash"
        const val KEY_STATE = "passcode.state"
        const val KEY_DELAY = "passcode.delay"
    }
}
