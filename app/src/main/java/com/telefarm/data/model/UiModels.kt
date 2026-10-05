package com.telefarm.data.model

/**
 * Text that the UI resolves later.
 *
 * Models never hold localized strings: they either carry raw text from Telegram or a resource
 * id that the screen resolves with its own context.
 */
sealed interface UiMessage {
    data class Res(val resId: Int, val args: List<Any> = emptyList()) : UiMessage
    data class Raw(val text: String) : UiMessage
}
