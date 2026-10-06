package com.telefarm.core.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The login link that TDLib reports while it waits for another device.
 *
 * The expectation for the url safe form follows the official Android client, which replaces `/`
 * with `_` and `+` with `-` before it decodes the token.
 */
class LoginTokenTest {

    @Test
    fun `reads the token of a login link`() {
        assertArrayEquals(REFERENCE_TOKEN, LoginToken.fromLink(LINK))
    }

    @Test
    fun `accepts a link that uses the standard alphabet`() {
        // The same bytes written with the standard base64 alphabet and with padding.
        assertArrayEquals(REFERENCE_TOKEN, LoginToken.fromLink(STANDARD_LINK))
        assertArrayEquals(REFERENCE_TOKEN, LoginToken.fromLink("$LINK="))
    }

    @Test
    fun `accepts the prefix in any case and surrounding whitespace`() {
        assertArrayEquals(REFERENCE_TOKEN, LoginToken.fromLink("  TG://Login?Token=${BASE64_URL}  "))
    }

    @Test
    fun `recognizes a login link`() {
        assertTrue(LoginToken.matches(LINK))
        assertFalse(LoginToken.matches("https://telegram.org"))
        assertFalse(LoginToken.matches(""))
    }

    @Test
    fun `rejects text that is not a login link`() {
        assertReason(SessionStringReason.UNKNOWN_FORMAT, "tg://resolve?domain=telegram")
    }

    @Test
    fun `rejects a link without a token`() {
        assertReason(SessionStringReason.EMPTY, "tg://login?token=")
    }

    @Test
    fun `rejects a token that is not base64`() {
        assertReason(SessionStringReason.NOT_BASE64, "tg://login?token=not a token!")
    }

    private fun assertReason(expected: SessionStringReason, value: String) {
        try {
            LoginToken.fromLink(value)
            throw AssertionError("reading the link should have failed with $expected")
        } catch (error: SessionStringException) {
            assertEquals(expected, error.reason)
        }
    }

    private companion object {
        /** A token that needs both alphabets to be told apart: it contains `+` and `/`. */
        val REFERENCE_TOKEN = byteArrayOf(
            0xFB.toByte(), 0xEF.toByte(), 0xBE.toByte(), 0x00, 0x10, 0x2F, 0x3F, 0x40, 0x50, 0x60,
            0x7F, 0x80.toByte(), 0xA5.toByte(), 0xC3.toByte(), 0xE7.toByte(), 0x19
        )

        const val BASE64_URL = "----ABAvP0BQYH-ApcPnGQ"

        const val BASE64_STANDARD = "++++ABAvP0BQYH+ApcPnGQ"

        const val LINK = "tg://login?token=$BASE64_URL"

        const val STANDARD_LINK = "tg://login?token=$BASE64_STANDARD"
    }
}
