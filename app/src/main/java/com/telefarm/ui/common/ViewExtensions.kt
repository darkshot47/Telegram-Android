package com.telefarm.ui.common

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar

fun View.setVisible(visible: Boolean) {
    isVisible = visible
}

fun View.showSnackbar(message: String, @StringRes actionRes: Int? = null, action: (() -> Unit)? = null) {
    val snackbar = Snackbar.make(this, message, Snackbar.LENGTH_LONG)
    if (actionRes != null && action != null) {
        snackbar.setAction(actionRes) { action() }
    }
    snackbar.show()
}

fun View.hideKeyboard() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(windowToken, 0)
}

/** Keeps content above the keyboard without resizing the whole window. */
fun View.applyImePadding() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(bottom = maxOf(ime.bottom, systemBars.bottom))
        insets
    }
}
