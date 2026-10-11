package com.telefarm.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Storing and checking a passcode. */
class PasscodeHashTest {

    private val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)

    /** Fewer rounds than the app uses, so the whole class runs in well under a second. */
    private val fast = 1_000

    @Test
    fun `the same passcode and salt give the same value`() {
        val first = PasscodeHash.create("2468", salt, fast)
        val second = PasscodeHash.create("2468", salt, fast)
        assertArrayEquals(first, second)
    }

    @Test
    fun `a different passcode gives a different value`() {
        assertFalse(
            PasscodeHash.create("2468", salt, fast).contentEquals(PasscodeHash.create("2469", salt, fast))
        )
    }

    @Test
    fun `a different salt gives a different value`() {
        val otherSalt = ByteArray(salt.size) { index -> (salt[index] + 1).toByte() }
        assertFalse(
            PasscodeHash.create("2468", salt, fast).contentEquals(PasscodeHash.create("2468", otherSalt, fast))
        )
    }

    @Test
    fun `the right passcode verifies and a near one does not`() {
        val stored = PasscodeHash.create("2468", salt, fast)
        assertTrue(PasscodeHash.verify("2468", salt, stored, fast))
        assertFalse(PasscodeHash.verify("2469", salt, stored, fast))
        assertFalse(PasscodeHash.verify("246", salt, stored, fast))
        assertFalse(PasscodeHash.verify("2468 ", salt, stored, fast))
    }

    @Test
    fun `nothing verifies against empty input`() {
        assertFalse(PasscodeHash.verify("", salt, PasscodeHash.create("2468", salt, fast), fast))
        assertFalse(PasscodeHash.verify("2468", ByteArray(0), ByteArray(32), fast))
        assertFalse(PasscodeHash.verify("2468", salt, ByteArray(0), fast))
    }

    @Test
    fun `the default number of rounds produces a key that verifies`() {
        val stored = PasscodeHash.create("2468", salt)
        assertEquals(PasscodeHash.KEY_LENGTH_BITS / 8, stored.size)
        assertTrue(PasscodeHash.verify("2468", salt, stored))
        assertFalse(PasscodeHash.verify("2467", salt, stored))
    }

    @Test
    fun `a wrong number of rounds does not verify`() {
        val stored = PasscodeHash.create("2468", salt, 2_000)
        assertFalse(PasscodeHash.verify("2468", salt, stored, 1_000))
    }

    @Test
    fun `salts are fresh every time`() {
        val first = PasscodeHash.newSalt()
        val second = PasscodeHash.newSalt()
        assertEquals(PasscodeHash.SALT_LENGTH, first.size)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `hex survives the round trip`() {
        val bytes = PasscodeHash.create("2468", salt, fast)
        assertEquals(bytes.size * 2, PasscodeHash.toHex(bytes).length)
        assertTrue(PasscodeHash.toHex(bytes).all { it in '0'..'9' || it in 'a'..'f' })
        assertArrayEquals(bytes, PasscodeHash.fromHex(PasscodeHash.toHex(bytes)))
    }

    @Test
    fun `text that is not hex reads as nothing`() {
        assertNull(PasscodeHash.fromHex(""))
        assertNull(PasscodeHash.fromHex("abc"))
        assertNull(PasscodeHash.fromHex("zzzz"))
        assertNull(PasscodeHash.fromHex(" 12"))
    }

    @Test
    fun `a passcode of any allowed character works`() {
        val passcode = "अभी 12 \$%&#"
        val stored = PasscodeHash.create(passcode, salt, fast)
        assertTrue(PasscodeHash.verify(passcode, salt, stored, fast))
    }
}

/** Which passcodes are worth accepting. */
class PasscodePolicyTest {

    @Test
    fun `length is bounded`() {
        assertEquals(PasscodeCheck.TooShort, PasscodePolicy.check(""))
        assertEquals(PasscodeCheck.TooShort, PasscodePolicy.check("137"))
        assertEquals(PasscodeCheck.Accepted, PasscodePolicy.check("1379"))
        // A varied passcode, so the length is what is being tested and not the repetition rule.
        val longest = "ab12".repeat(PasscodePolicy.MAX_LENGTH / 4)
        assertEquals(PasscodePolicy.MAX_LENGTH, longest.length)
        assertEquals(PasscodeCheck.Accepted, PasscodePolicy.check(longest))
        assertEquals(PasscodeCheck.TooLong, PasscodePolicy.check("${longest}x"))
    }

    @Test
    fun `one character repeated is refused`() {
        assertEquals(PasscodeCheck.RepeatedCharacter, PasscodePolicy.check("0000"))
        assertEquals(PasscodeCheck.RepeatedCharacter, PasscodePolicy.check("aaaa"))
    }

    @Test
    fun `consecutive digits are refused in both directions and across the wrap`() {
        assertEquals(PasscodeCheck.Sequential, PasscodePolicy.check("1234"))
        assertEquals(PasscodeCheck.Sequential, PasscodePolicy.check("0123"))
        assertEquals(PasscodeCheck.Sequential, PasscodePolicy.check("4321"))
        assertEquals(PasscodeCheck.Sequential, PasscodePolicy.check("8901"))
        assertEquals(PasscodeCheck.Sequential, PasscodePolicy.check("12345"))
    }

    @Test
    fun `digits that are not consecutive are accepted`() {
        assertEquals(PasscodeCheck.Accepted, PasscodePolicy.check("2468"))
        assertEquals(PasscodeCheck.Accepted, PasscodePolicy.check("1379"))
        assertEquals(PasscodeCheck.Accepted, PasscodePolicy.check("9753"))
    }

    @Test
    fun `a run of letters is not a digit run`() {
        assertEquals(PasscodeCheck.Accepted, PasscodePolicy.check("abcd"))
    }

    @Test
    fun `a short run of digits is caught by length first`() {
        assertEquals(PasscodeCheck.TooShort, PasscodePolicy.check("12"))
    }

    @Test
    fun `changing to the same passcode is refused`() {
        assertEquals(PasscodeCheck.Unchanged, PasscodePolicy.check("2468", "2468"))
        assertEquals(PasscodeCheck.Accepted, PasscodePolicy.check("1379", "2468"))
    }

    @Test
    fun `a repeated character is caught before a change`() {
        assertEquals(PasscodeCheck.RepeatedCharacter, PasscodePolicy.check("0000", "0000"))
    }
}
