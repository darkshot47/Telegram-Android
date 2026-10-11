package com.telefarm.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Turns a passcode into something that can be stored.
 *
 * A passcode is short - four to a few dozen characters - so a fast hash would let anyone holding
 * the store try the whole space in minutes. PBKDF2 slows each guess down instead: the cost is paid
 * once when the passcode is set and once on every unlock, which a person will not notice, while a
 * brute force pays it for every guess it makes.
 *
 * The salt is random per passcode, so the same passcode on two devices does not produce the same
 * stored value and a table of ready made answers is of no use.
 */
object PasscodeHash {

    /** Key stretching rounds. Chosen so a single check stays under a second on a phone. */
    const val ITERATIONS = 120_000

    /** Bytes of random salt. */
    const val SALT_LENGTH = 16

    /** Bytes of derived key. */
    const val KEY_LENGTH_BITS = 256

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    private val HEX = "0123456789abcdef".toCharArray()

    /** Fresh random salt. */
    fun newSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /** Value stored for [passcode]; the same input and salt always give the same value. */
    fun create(passcode: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray =
        derive(passcode, salt, iterations)

    /**
     * True when [passcode] is the one [expected] was made from.
     *
     * Every byte is compared before returning, so the answer does not come back faster when the
     * first characters are wrong. That timing difference is how stored hashes get guessed one
     * character at a time.
     */
    fun verify(passcode: String, salt: ByteArray, expected: ByteArray, iterations: Int = ITERATIONS): Boolean {
        if (passcode.isEmpty() || salt.isEmpty() || expected.isEmpty()) return false
        val candidate = derive(passcode, salt, iterations)
        return MessageDigest.isEqual(candidate, expected)
    }

    /** Hex form, for a store that only holds text. */
    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            out[index * 2] = HEX[value ushr 4]
            out[index * 2 + 1] = HEX[value and 0x0F]
        }
        return String(out)
    }

    /** Bytes of a hex form; null when the text is not hex. */
    fun fromHex(text: String): ByteArray? {
        if (text.isEmpty() || text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (index in out.indices) {
            val high = Character.digit(text[index * 2], 16)
            val low = Character.digit(text[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private fun derive(passcode: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance(ALGORITHM)
            .generateSecret(PBEKeySpec(passcode.toCharArray(), salt, iterations, KEY_LENGTH_BITS))
            .encoded
}

/**
 * Whether a passcode is worth accepting.
 *
 * Only what a person typing it needs to be told is checked here. Length and the two shapes a
 * thief would try first - one character repeated, and a run of consecutive digits.
 */
sealed interface PasscodeCheck {

    /** The passcode is acceptable. */
    data object Accepted : PasscodeCheck

    /** Shorter than [PasscodePolicy.MIN_LENGTH]. */
    data object TooShort : PasscodeCheck

    /** Longer than [PasscodePolicy.MAX_LENGTH]. */
    data object TooLong : PasscodeCheck

    /** Every character the same, such as 0000. */
    data object RepeatedCharacter : PasscodeCheck

    /** Consecutive digits going up or down, such as 1234 or 4321. */
    data object Sequential : PasscodeCheck

    /** The passcode is the one already in use. */
    data object Unchanged : PasscodeCheck
}

object PasscodePolicy {

    /** Fewest characters a passcode may have. */
    const val MIN_LENGTH = 4

    /** Most characters a passcode may have. */
    const val MAX_LENGTH = 32

    /**
     * Checks [passcode], and against [current] when a passcode is being changed.
     */
    fun check(passcode: String, current: String? = null): PasscodeCheck = when {
        passcode.length < MIN_LENGTH -> PasscodeCheck.TooShort
        passcode.length > MAX_LENGTH -> PasscodeCheck.TooLong
        passcode.toSet().size == 1 -> PasscodeCheck.RepeatedCharacter
        isSequential(passcode) -> PasscodeCheck.Sequential
        current != null && passcode == current -> PasscodeCheck.Unchanged
        else -> PasscodeCheck.Accepted
    }

    /** True when [passcode] is nothing but digits running up or down, such as 1234 or 8901. */
    private fun isSequential(passcode: String): Boolean {
        if (passcode.length < MIN_LENGTH) return false
        if (passcode.any { it !in '0'..'9' }) return false
        val first = passcode[0].digitToInt()
        val up = passcode.indices.all { index -> passcode[index] == '0' + (first + index).mod(10) }
        val down = passcode.indices.all { index -> passcode[index] == '0' + (first - index).mod(10) }
        return up || down
    }
}
