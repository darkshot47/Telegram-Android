package com.telefarm.ui.auth

/**
 * Normalises a phone number for Telegram.
 *
 * Telegram requires the international format: a leading plus sign followed by digits.
 * Spaces, dashes, parentheses and other separators typed by the user are removed.
 * Returns null when the input cannot be a phone number.
 */
object PhoneNumberFormatter {

    private const val MIN_DIGITS = 6
    private const val MAX_DIGITS = 15

    fun normalize(input: String): String? {
        val digits = input.filter { it.isDigit() }
        if (digits.length < MIN_DIGITS || digits.length > MAX_DIGITS) return null
        return "+$digits"
    }

    /** Formats input while the user types, keeping only digits and a leading plus. */
    fun asYouType(input: String): String {
        val hasPlus = input.trimStart().startsWith("+")
        val digits = input.filter { it.isDigit() }.take(MAX_DIGITS)
        return if (hasPlus) "+$digits" else digits
    }

    fun isValid(input: String): Boolean = normalize(input) != null
}
