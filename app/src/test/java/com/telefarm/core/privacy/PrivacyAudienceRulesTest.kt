package com.telefarm.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading Telegram's privacy rules as the three choices they are offered as. */
class PrivacyAudienceRulesTest {

    @Test
    fun `allow all is everybody`() {
        val read = PrivacyAudienceRules.read(listOf(rule(PrivacyRuleKind.ALLOW_ALL)))
        assertEquals(PrivacyAudience.EVERYONE, read.audience)
        assertFalse(read.isCustom)
    }

    @Test
    fun `allow contacts then refuse everybody is my contacts`() {
        val read = PrivacyAudienceRules.read(
            listOf(rule(PrivacyRuleKind.ALLOW_CONTACTS), rule(PrivacyRuleKind.RESTRICT_ALL))
        )
        assertEquals(PrivacyAudience.CONTACTS, read.audience)
        assertFalse(read.isCustom)
    }

    @Test
    fun `refuse everybody is nobody`() {
        val read = PrivacyAudienceRules.read(listOf(rule(PrivacyRuleKind.RESTRICT_ALL)))
        assertEquals(PrivacyAudience.NOBODY, read.audience)
        assertFalse(read.isCustom)
    }

    @Test
    fun `an empty rule list allows nothing`() {
        val read = PrivacyAudienceRules.read(emptyList())
        assertEquals(PrivacyAudience.NOBODY, read.audience)
        assertFalse(read.isCustom)
    }

    @Test
    fun `named exceptions are counted and do not change the choice`() {
        val read = PrivacyAudienceRules.read(
            listOf(
                PrivacyRuleUi(PrivacyRuleKind.ALLOW_USERS, listOf(1L, 2L)),
                rule(PrivacyRuleKind.ALLOW_CONTACTS),
                rule(PrivacyRuleKind.RESTRICT_ALL),
                PrivacyRuleUi(PrivacyRuleKind.RESTRICT_USERS, listOf(9L))
            )
        )
        assertEquals(PrivacyAudience.CONTACTS, read.audience)
        assertEquals(2, read.alwaysAllowed)
        assertEquals(1, read.neverAllowed)
        assertFalse(read.isCustom)
    }

    @Test
    fun `a rule this client does not know is reported as custom, not guessed at`() {
        val read = PrivacyAudienceRules.read(
            listOf(rule(PrivacyRuleKind.UNKNOWN), rule(PrivacyRuleKind.RESTRICT_ALL))
        )
        assertTrue(read.isCustom)
    }

    @Test
    fun `a list of only named people has no single answer`() {
        val read = PrivacyAudienceRules.read(listOf(PrivacyRuleUi(PrivacyRuleKind.ALLOW_USERS, listOf(1L))))
        assertTrue(read.isCustom)
        assertEquals(1, read.alwaysAllowed)
    }

    @Test
    fun `refusing your contacts while allowing everybody is still everybody`() {
        val read = PrivacyAudienceRules.read(
            listOf(rule(PrivacyRuleKind.ALLOW_ALL), rule(PrivacyRuleKind.RESTRICT_CONTACTS))
        )
        assertEquals(PrivacyAudience.EVERYONE, read.audience)
        assertTrue(read.isCustom)
    }

    @Test
    fun `what is written for a choice reads back as that choice`() {
        for (audience in PrivacyAudience.entries) {
            val written = PrivacyAudienceRules.write(audience)
            val read = PrivacyAudienceRules.read(written)
            assertEquals(audience, read.audience)
            assertFalse("$audience came back custom", read.isCustom)
            assertEquals(0, read.alwaysAllowed)
            assertEquals(0, read.neverAllowed)
        }
    }

    @Test
    fun `everybody needs one rule and the other two need a refusal behind them`() {
        assertEquals(1, PrivacyAudienceRules.write(PrivacyAudience.EVERYONE).size)
        assertEquals(2, PrivacyAudienceRules.write(PrivacyAudience.CONTACTS).size)
        assertEquals(1, PrivacyAudienceRules.write(PrivacyAudience.NOBODY).size)
        assertEquals(
            listOf(PrivacyRuleKind.ALLOW_CONTACTS, PrivacyRuleKind.RESTRICT_ALL),
            PrivacyAudienceRules.write(PrivacyAudience.CONTACTS).map { it.kind }
        )
    }

    @Test
    fun `a written rule carries no users`() {
        for (audience in PrivacyAudience.entries) {
            assertTrue(PrivacyAudienceRules.write(audience).all { it.userIds.isEmpty() })
        }
    }

    private fun rule(kind: PrivacyRuleKind) = PrivacyRuleUi(kind)
}
