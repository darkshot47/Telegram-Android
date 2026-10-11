package com.telefarm.core.prefs

import android.content.Context
import com.telefarm.core.folders.FolderCodec
import com.telefarm.data.model.ChatFolder

/**
 * The chat folders of this device.
 *
 * Folders are a way to look at the chat list, not a Telegram object, so they live in a plain
 * preference file: nothing in them is sensitive - tab names, rule names and chat ids only. The
 * text is written by [FolderCodec], which also reads whatever an older version of the
 * application left behind.
 */
class FolderStore(context: Context) {

    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Folders in tab order; empty on a fresh install. */
    fun load(): List<ChatFolder> = FolderCodec.decode(preferences.getString(KEY_FOLDERS, null))

    /** Replaces the stored folders. */
    fun save(folders: List<ChatFolder>) {
        preferences.edit().putString(KEY_FOLDERS, FolderCodec.encode(folders)).apply()
    }

    private companion object {
        const val FILE_NAME = "telefarm.folders"
        const val KEY_FOLDERS = "folders"
    }
}
