package com.telefarm.core.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session strings of the clients that are used in practice.
 *
 * Every accepted string in this test was produced by the real implementation of that client
 * (Telethon 1.45.0, GramJS and Hydrogram session writers), so the parser is verified against the
 * formats users actually paste and not only against its own writer.
 */
class SessionStringTest {

    @Test
    fun `parses a telethon session string`() {
        val session = SessionString.parse(TELETHON_STRING)
        assertEquals(2, session.dcId)
        assertEquals("149.154.167.51", session.address)
        assertEquals(443, session.port)
        assertArrayEquals(REFERENCE_KEY, session.authorizationKey)
        assertEquals(SessionFormat.TELETHON, session.format)
    }

    @Test
    fun `parses a telethon session string with an IPv6 address`() {
        val session = SessionString.parse(TELETHON_IPV6_STRING)
        assertEquals(4, session.dcId)
        assertEquals("2001:67c:4e8:f004:0:0:0:9", session.address)
        assertEquals(443, session.port)
        assertArrayEquals(REFERENCE_KEY, session.authorizationKey)
    }

    @Test
    fun `parses a gramjs session string`() {
        val session = SessionString.parse(GRAM_JS_STRING)
        assertEquals(4, session.dcId)
        assertEquals("149.154.167.91", session.address)
        assertEquals(443, session.port)
        assertArrayEquals(REFERENCE_KEY, session.authorizationKey)
        assertEquals(SessionFormat.GRAM_JS, session.format)
    }

    @Test
    fun `parses a gramjs session string with a host name`() {
        val session = SessionString.parse(GRAM_JS_HOSTNAME_STRING)
        assertEquals(3, session.dcId)
        assertEquals("pluto.web.telegram.org", session.address)
        assertEquals(443, session.port)
        assertArrayEquals(REFERENCE_KEY, session.authorizationKey)
    }

    @Test
    fun `parses a hydrogram session string`() {
        val session = SessionString.parse(HYDROGRAM_STRING)
        assertEquals(5, session.dcId)
        assertEquals(2040, session.applicationId)
        assertEquals(SessionFormat.HYDROGRAM, session.format)
        assertNull(session.address)
        assertArrayEquals(REFERENCE_KEY, session.authorizationKey)
    }

    @Test
    fun `parses the older session layout`() {
        val session = SessionString.parse(LEGACY_STRING)
        assertEquals(1, session.dcId)
        assertEquals(2040, session.applicationId)
        assertEquals(SessionFormat.LEGACY, session.format)
        assertArrayEquals(REFERENCE_KEY, session.authorizationKey)
    }

    @Test
    fun `accepts a prefixed session and surrounding whitespace`() {
        val session = SessionString.parse("  telefarm: $TELETHON_STRING \n")
        assertEquals(2, session.dcId)
        assertEquals(SessionFormat.TELEFARM, session.format)
        assertArrayEquals(REFERENCE_KEY, session.authorizationKey)
    }

    @Test
    fun `accepts a session string whose padding was lost`() {
        val withoutPadding = TELETHON_STRING.trimEnd('=')
        assertArrayEquals(REFERENCE_KEY, SessionString.parse(withoutPadding).authorizationKey)
    }

    @Test
    fun `round trips its own encoding`() {
        val encoded = SessionString.encode(SessionString.parse(TELETHON_STRING))
        val session = SessionString.parse(encoded)
        assertEquals(2, session.dcId)
        assertEquals("149.154.167.51", session.address)
        assertEquals(443, session.port)
        assertArrayEquals(REFERENCE_KEY, session.authorizationKey)
    }

    @Test
    fun `description names the data center but never a secret`() {
        val session = SessionString.parse(TELETHON_STRING)
        val description = session.describe()
        assertTrue(description.contains("149.154.167.51:443"))
        assertTrue(description.contains("telethon"))
        // No fragment of the authorization key may appear in text shown to the user.
        assertTrue(!description.contains(String(session.authorizationKey.copyOfRange(0, 8), Charsets.ISO_8859_1)))
    }

    @Test
    fun `rejects empty input`() {
        assertReason(SessionStringReason.EMPTY, "   ")
    }

    @Test
    fun `rejects text that is not base64`() {
        assertReason(SessionStringReason.NOT_BASE64, "not a session at all!!")
    }

    @Test
    fun `rejects a session of the test environment`() {
        assertReason(SessionStringReason.TEST_MODE, TEST_MODE_STRING)
    }

    @Test
    fun `rejects a data center that does not exist`() {
        assertReason(SessionStringReason.UNKNOWN_FORMAT, DC_NINE_STRING)
    }

    private fun assertReason(expected: SessionStringReason, value: String) {
        try {
            SessionString.parse(value)
            throw AssertionError("parsing should have failed with $expected")
        } catch (error: SessionStringException) {
            assertEquals(expected, error.reason)
        }
    }

    private companion object {
        /** Fixed 256 byte key of the reference vectors: `(index * 3 + 1) % 256`. */
        val REFERENCE_KEY = ByteArray(256) { ((it * 3 + 1) % 256).toByte() }

        /** Telethon 1.45.0 `StringSession`, data center 2, `149.154.167.51:443`. */
        const val TELETHON_STRING =
            "1ApWapzMBuwEEBwoNEBMWGRwfIiUoKy4xNDc6PUBDRklMT1JVWFteYWRnam1wc3Z5fH-ChYiLjpGUl5qdoKOmqayvsrW4u77BxMfKzdDT1tnc3-Ll6Ovu8fT3-v0AAwYJDA8SFRgbHiEkJyotMDM2OTw_QkVIS05RVFdaXWBjZmlsb3J1eHt-gYSHio2Qk5aZnJ-ipairrrG0t7q9wMPGyczP0tXY297h5Ofq7fDz9vn8_wIFCAsOERQXGh0gIyYpLC8yNTg7PkFER0pNUFNWWVxfYmVoa25xdHd6fYCDhomMj5KVmJueoaSnqq2ws7a5vL_CxcjLztHU19rd4OPm6ezv8vX4-_4="

        /** Telethon `StringSession`, data center 4, IPv6 address. */
        const val TELETHON_IPV6_STRING =
            "1BCABBnwE6PAEAAAAAAAAAAkBuwEEBwoNEBMWGRwfIiUoKy4xNDc6PUBDRklMT1JVWFteYWRnam1wc3Z5fH-ChYiLjpGUl5qdoKOmqayvsrW4u77BxMfKzdDT1tnc3-Ll6Ovu8fT3-v0AAwYJDA8SFRgbHiEkJyotMDM2OTw_QkVIS05RVFdaXWBjZmlsb3J1eHt-gYSHio2Qk5aZnJ-ipairrrG0t7q9wMPGyczP0tXY297h5Ofq7fDz9vn8_wIFCAsOERQXGh0gIyYpLC8yNTg7PkFER0pNUFNWWVxfYmVoa25xdHd6fYCDhomMj5KVmJueoaSnqq2ws7a5vL_CxcjLztHU19rd4OPm6ezv8vX4-_4="

        /** GramJS `StringSession`, data center 4. */
        const val GRAM_JS_STRING =
            "1BAAOMTQ5LjE1NC4xNjcuOTEBuwEEBwoNEBMWGRwfIiUoKy4xNDc6PUBDRklMT1JVWFteYWRnam1wc3Z5fH-ChYiLjpGUl5qdoKOmqayvsrW4u77BxMfKzdDT1tnc3-Ll6Ovu8fT3-v0AAwYJDA8SFRgbHiEkJyotMDM2OTw_QkVIS05RVFdaXWBjZmlsb3J1eHt-gYSHio2Qk5aZnJ-ipairrrG0t7q9wMPGyczP0tXY297h5Ofq7fDz9vn8_wIFCAsOERQXGh0gIyYpLC8yNTg7PkFER0pNUFNWWVxfYmVoa25xdHd6fYCDhomMj5KVmJueoaSnqq2ws7a5vL_CxcjLztHU19rd4OPm6ezv8vX4-_4="

        /** GramJS `StringSession`, data center 3, with a host name instead of an IP address. */
        const val GRAM_JS_HOSTNAME_STRING =
            "1AwAWcGx1dG8ud2ViLnRlbGVncmFtLm9yZwG7AQQHCg0QExYZHB8iJSgrLjE0Nzo9QENGSUxPUlVYW15hZGdqbXBzdnl8f4KFiIuOkZSXmp2go6aprK-ytbi7vsHEx8rN0NPW2dzf4uXo6-7x9Pf6_QADBgkMDxIVGBseISQnKi0wMzY5PD9CRUhLTlFUV1pdYGNmaWxvcnV4e36BhIeKjZCTlpmcn6KlqKuusbS3ur3Aw8bJzM_S1djb3uHk5-rt8PP2-fz_AgUICw4RFBcaHSAjJiksLzI1ODs-QURHSk1QU1ZZXF9iZWhrbnF0d3p9gIOGiYyPkpWYm56hpKeqrbCztrm8v8LFyMvO0dTX2t3g4-bp7O_y9fj7_g=="

        /** Hydrogram `export_session_string()`, data center 5. */
        const val HYDROGRAM_STRING =
            "BQAAB_gAAQQHCg0QExYZHB8iJSgrLjE0Nzo9QENGSUxPUlVYW15hZGdqbXBzdnl8f4KFiIuOkZSXmp2go6aprK-ytbi7vsHEx8rN0NPW2dzf4uXo6-7x9Pf6_QADBgkMDxIVGBseISQnKi0wMzY5PD9CRUhLTlFUV1pdYGNmaWxvcnV4e36BhIeKjZCTlpmcn6KlqKuusbS3ur3Aw8bJzM_S1djb3uHk5-rt8PP2-fz_AgUICw4RFBcaHSAjJiksLzI1ODs-QURHSk1QU1ZZXF9iZWhrbnF0d3p9gIOGiYyPkpWYm56hpKeqrbCztrm8v8LFyMvO0dTX2t3g4-bp7O_y9fj7_gAAAAAAAAAAAA"

        /** Older layout that stores the application id: data center 1, application 2040. */
        const val LEGACY_STRING =
            "AQAAB_gBBAcKDRATFhkcHyIlKCsuMTQ3Oj1AQ0ZJTE9SVVhbXmFkZ2ptcHN2eXx_goWIi46RlJeanaCjpqmsr7K1uLu-wcTHys3Q09bZ3N_i5ejr7vH09_r9AAMGCQwPEhUYGx4hJCcqLTAzNjk8P0JFSEtOUVRXWl1gY2ZpbG9ydXh7foGEh4qNkJOWmZyfoqWoq66xtLe6vcDDxsnMz9LV2Nve4eTn6u3w8_b5_P8CBQgLDhEUFxodICMmKSwvMjU4Oz5BREdKTVBTVllcX2JlaGtucXR3en2Ag4aJjI-SlZibnqGkp6qtsLO2uby_wsXIy87R1Nfa3eDj5uns7_L1-Pv-AA"

        /** Same layout with the test environment flag set; those sessions cannot be used here. */
        const val TEST_MODE_STRING =
            "AgAAB_gBBAcKDRATFhkcHyIlKCsuMTQ3Oj1AQ0ZJTE9SVVhbXmFkZ2ptcHN2eXx_goWIi46RlJeanaCjpqmsr7K1uLu-wcTHys3Q09bZ3N_i5ejr7vH09_r9AAMGCQwPEhUYGx4hJCcqLTAzNjk8P0JFSEtOUVRXWl1gY2ZpbG9ydXh7foGEh4qNkJOWmZyfoqWoq66xtLe6vcDDxsnMz9LV2Nve4eTn6u3w8_b5_P8CBQgLDhEUFxodICMmKSwvMjU4Oz5BREdKTVBTVllcX2JlaGtucXR3en2Ag4aJjI-SlZibnqGkp6qtsLO2uby_wsXIy87R1Nfa3eDj5uns7_L1-Pv-AQ"
        /** Telethon layout with data center 9. */
        const val DC_NINE_STRING =
            "1CZWapzMBuwEEBwoNEBMWGRwfIiUoKy4xNDc6PUBDRklMT1JVWFteYWRnam1wc3Z5fH-ChYiLjpGUl5qdoKOmqayvsrW4u77BxMfKzdDT1tnc3-Ll6Ovu8fT3-v0AAwYJDA8SFRgbHiEkJyotMDM2OTw_QkVIS05RVFdaXWBjZmlsb3J1eHt-gYSHio2Qk5aZnJ-ipairrrG0t7q9wMPGyczP0tXY297h5Ofq7fDz9vn8_wIFCAsOERQXGh0gIyYpLC8yNTg7PkFER0pNUFNWWVxfYmVoa25xdHd6fYCDhomMj5KVmJueoaSnqq2ws7a5vL_CxcjLztHU19rd4OPm6ezv8vX4-_4="
    }
}
