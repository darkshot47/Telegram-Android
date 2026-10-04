package com.telefarm.ui.common

import android.content.Context
import com.telefarm.data.model.UiMessage

/**
 * Resolves a UI facing message with the current configuration.
 *
 * Arguments may themselves be [UiMessage]s, which allows composing sentences such as
 * "last seen today at 14:05" from several resources.
 */
fun UiMessage.resolve(context: Context): String = when (this) {
    is UiMessage.Res -> {
        val resolved = args.map { if (it is UiMessage) it.resolve(context) else it }
        if (resolved.isEmpty()) context.getString(resId) else context.getString(resId, *resolved.toTypedArray())
    }
    is UiMessage.Raw -> text
}

/** Convenience overload for messages that are known to exist. */
fun UiMessage?.resolveOrNull(context: Context): String? = this?.resolve(context)
