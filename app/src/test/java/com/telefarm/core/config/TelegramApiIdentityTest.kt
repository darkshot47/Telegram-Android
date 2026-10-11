package com.telefarm.core.config

import com.telefarm.data.model.CredentialSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which credentials a build signs in with.
 *
 * The application must always end up with a usable pair, otherwise the sign in screen cannot be
 * reached at all - a fresh install has neither compiled in nor user provided credentials.
 */
class TelegramApiIdentityTest {

    @Test
    fun `falls back to the built in identity when nothing was provided`() {
        val credentials = TelegramApiIdentity.resolve(
            userApiId = null,
            userApiHash = null,
            buildApiId = 0,
            buildApiHash = ""
        )
        assertEquals(TelegramApiIdentity.FALLBACK_API_ID, credentials.apiId)
        assertEquals(TelegramApiIdentity.FALLBACK_API_HASH, credentials.apiHash)
        assertEquals(CredentialSource.BUILT_IN, credentials.source)
        // This is the case that used to leave the sign in screen unreachable.
        assertTrue(credentials.isConfigured)
    }

    @Test
    fun `compiled credentials win over the built in identity`() {
        val credentials = TelegramApiIdentity.resolve(
            userApiId = null,
            userApiHash = null,
            buildApiId = 123456,
            buildApiHash = "0123456789abcdef0123456789abcdef"
        )
        assertEquals(123456, credentials.apiId)
        assertEquals(CredentialSource.BUILD_CONFIG, credentials.source)
    }

    @Test
    fun `credentials of this device win over both`() {
        val credentials = TelegramApiIdentity.resolve(
            userApiId = 654321,
            userApiHash = "FEDCBA9876543210FEDCBA9876543210",
            buildApiId = 123456,
            buildApiHash = "0123456789abcdef0123456789abcdef"
        )
        assertEquals(654321, credentials.apiId)
        // The hash is normalized before it is sent to Telegram.
        assertEquals("fedcba9876543210fedcba9876543210", credentials.apiHash)
        assertEquals(CredentialSource.USER_PROVIDED, credentials.source)
    }

    @Test
    fun `a half stored pair is ignored instead of breaking the client`() {
        val credentials = TelegramApiIdentity.resolve(
            userApiId = 654321,
            userApiHash = null,
            buildApiId = 0,
            buildApiHash = ""
        )
        assertEquals(CredentialSource.BUILT_IN, credentials.source)
    }

    @Test
    fun `accepts the pair that was typed on the sign in screen`() {
        val result = TelegramApiIdentity.parseInput(
            rawApiId = "  2040 ",
            rawApiHash = " b18441a1ff607e10a989891a5462e627\n"
        )
        val valid = result as CredentialInputResult.Valid
        assertEquals(2040, valid.credentials.apiId)
        assertEquals("b18441a1ff607e10a989891a5462e627", valid.credentials.apiHash)
    }

    @Test
    fun `reports empty input so the built in credentials stay in use`() {
        assertEquals(CredentialInputResult.Empty, TelegramApiIdentity.parseInput("  ", "\n"))
    }

    @Test
    fun `rejects an id that is not a positive number`() {
        assertEquals(
            CredentialInputResult.InvalidApiId,
            TelegramApiIdentity.parseInput("0", "b18441a1ff607e10a989891a5462e627")
        )
        assertEquals(
            CredentialInputResult.InvalidApiId,
            TelegramApiIdentity.parseInput("abc", "b18441a1ff607e10a989891a5462e627")
        )
        assertEquals(
            CredentialInputResult.InvalidApiId,
            TelegramApiIdentity.parseInput("-4", "b18441a1ff607e10a989891a5462e627")
        )
    }

    @Test
    fun `rejects a hash that is not 32 hexadecimal characters`() {
        assertEquals(
            CredentialInputResult.InvalidApiHash,
            TelegramApiIdentity.parseInput("2040", "b18441a1ff607e10a989891a5462e62")
        )
        assertEquals(
            CredentialInputResult.InvalidApiHash,
            TelegramApiIdentity.parseInput("2040", "zzzzz41a1ff607e10a989891a5462e627")
        )
    }

    @Test
    fun `the built in identity is a usable pair`() {
        assertTrue(
            TelegramApiIdentity.isUsablePair(
                TelegramApiIdentity.FALLBACK_API_ID,
                TelegramApiIdentity.FALLBACK_API_HASH
            )
        )
        assertFalse(TelegramApiIdentity.isUsablePair(0, TelegramApiIdentity.FALLBACK_API_HASH))
        assertFalse(TelegramApiIdentity.isUsablePair(TelegramApiIdentity.FALLBACK_API_ID, ""))
    }

    @Test
    fun `the description never shows the hash`() {
        val description = TelegramApiIdentity.FALLBACK.describe()
        assertTrue(description.contains(TelegramApiIdentity.FALLBACK_API_ID.toString()))
        assertFalse(description.contains(TelegramApiIdentity.FALLBACK_API_HASH))
    }
}
