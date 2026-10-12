package com.telefarm.core.privacy

/** Who a privacy rule is about. */
enum class PrivacyRuleKind {

    /** Everybody. */
    ALLOW_ALL,

    /** Only the people in your contacts. */
    ALLOW_CONTACTS,

    /** Named people, allowed whatever the rest of the rules say. */
    ALLOW_USERS,

    /** Everybody is refused. */
    RESTRICT_ALL,

    /** Your contacts are refused. */
    RESTRICT_CONTACTS,

    /** Named people, refused whatever the rest of the rules say. */
    RESTRICT_USERS,

    /** A rule this client does not know about yet. */
    UNKNOWN
}

/** One rule of a Telegram privacy setting. */
data class PrivacyRuleUi(
    val kind: PrivacyRuleKind,

    /** The named users of an [PrivacyRuleKind.ALLOW_USERS] or [PrivacyRuleKind.RESTRICT_USERS] rule. */
    val userIds: List<Long> = emptyList()
)

/** Who can see the thing: everybody, only contacts, or nobody. */
enum class PrivacyAudience {

    EVERYONE,

    CONTACTS,

    /** Also what an empty rule list means: nothing is allowed, so nothing is shown. */
    NOBODY
}

/** A privacy setting read into the three choices a person is actually offered. */
data class PrivacyRulesUi(

    val audience: PrivacyAudience,

    /** Named people let through whatever the audience says. */
    val alwaysAllowed: Int,

    /** Named people kept out whatever the audience says. */
    val neverAllowed: Int,

    /**
     * True when Telegram's rules do not reduce to one of the three choices.
     *
     * A rule list can be written in ways this screen does not offer, and guessing which of the
     * three it means would be a lie about what the account does. The screen says so instead, and
     * leaves the setting alone until it is changed.
     */
    val isCustom: Boolean
)

/**
 * The three choices a privacy setting is offered as.
 *
 * Telegram stores a privacy setting as an ordered list of rules and applies the last one that
 * matches, which is more than a screen of three radio buttons can show. This is the translation
 * between the two, in both directions.
 *
 * The canonical lists written back are the ones Telegram itself writes, so a setting changed here
 * and then looked at in another client reads the same there:
 *
 * - everybody: allow all
 * - my contacts: allow contacts, then refuse everybody else
 * - nobody: refuse everybody
 *
 * Anything else on the way in - named exceptions, a list in an unusual order, a rule this client
 * has not heard of - is reported as custom rather than rounded to the nearest choice.
 */
object PrivacyAudienceRules {

    /** Reads Telegram's rules into the three choices, or reports them as custom. */
    fun read(rules: List<PrivacyRuleUi>): PrivacyRulesUi {
        val kinds = rules.map { it.kind }
        val audience = when {
            kinds.isEmpty() -> PrivacyAudience.NOBODY
            PrivacyRuleKind.ALLOW_ALL in kinds -> PrivacyAudience.EVERYONE
            PrivacyRuleKind.ALLOW_CONTACTS in kinds -> PrivacyAudience.CONTACTS
            PrivacyRuleKind.RESTRICT_ALL in kinds -> PrivacyAudience.NOBODY
            // A list that is only named users, with no rule for everybody else, has no single
            // answer, and neither does one holding a rule this client does not know.
            else -> null
        }
        val alwaysAllowed = rules.filter { it.kind == PrivacyRuleKind.ALLOW_USERS }
            .sumOf { it.userIds.size }
        val neverAllowed = rules.filter { it.kind == PrivacyRuleKind.RESTRICT_USERS }
            .sumOf { it.userIds.size }

        if (audience == null) {
            return PrivacyRulesUi(PrivacyAudience.NOBODY, alwaysAllowed, neverAllowed, isCustom = true)
        }
        // A canonical list has the audience rule and, for contacts and nobody, the refusal that
        // gives it its meaning. Anything more is an exception the screen cannot show.
        val expected = expectedKinds(audience)
        val extras = kinds.filterNot { it in expected }
        val isCustom = extras.any { it != PrivacyRuleKind.ALLOW_USERS && it != PrivacyRuleKind.RESTRICT_USERS }
        return PrivacyRulesUi(audience, alwaysAllowed, neverAllowed, isCustom)
    }

    /** The rule list to send for one of the three choices. */
    fun write(audience: PrivacyAudience): List<PrivacyRuleUi> = when (audience) {
        PrivacyAudience.EVERYONE -> listOf(PrivacyRuleUi(PrivacyRuleKind.ALLOW_ALL))
        PrivacyAudience.CONTACTS -> listOf(
            PrivacyRuleUi(PrivacyRuleKind.ALLOW_CONTACTS),
            PrivacyRuleUi(PrivacyRuleKind.RESTRICT_ALL)
        )
        PrivacyAudience.NOBODY -> listOf(PrivacyRuleUi(PrivacyRuleKind.RESTRICT_ALL))
    }

    /** What [write] produces is what [read] reads back, for every choice. */
    private fun expectedKinds(audience: PrivacyAudience): Set<PrivacyRuleKind> =
        write(audience).map { it.kind }.toSet()
}
