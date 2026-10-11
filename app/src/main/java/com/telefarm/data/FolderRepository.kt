package com.telefarm.data

import com.telefarm.core.prefs.FolderStore
import com.telefarm.data.model.ChatFolder
import com.telefarm.data.model.FolderRule
import com.telefarm.data.model.FolderRuleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Result of creating a folder. */
sealed interface FolderCreateResult {

    /** The folder exists now and is returned so the caller can select it. */
    data class Created(val folder: ChatFolder) : FolderCreateResult

    /** Nothing was typed. */
    data object EmptyName : FolderCreateResult

    /** The tab strip is full. */
    data object TooManyFolders : FolderCreateResult
}

/**
 * The folders of the chat list and which one is open.
 *
 * Everything here is device local and synchronous: a folder is a filter over the chats that are
 * already loaded, so creating, renaming or deleting one never talks to Telegram and never waits.
 */
class FolderRepository(
    private val store: FolderStore,
    scope: CoroutineScope
) {

    private val _folders = MutableStateFlow(store.load())

    /** Folders in tab order. */
    val folders: StateFlow<List<ChatFolder>> = _folders.asStateFlow()

    private val _activeFolderId = MutableStateFlow<Long?>(null)

    /** Id of the folder that is shown, or null while the whole list is shown. */
    val activeFolderId: StateFlow<Long?> = _activeFolderId.asStateFlow()

    /**
     * The folder that is shown, or null.
     *
     * Derived, so deleting the folder that is open sends the screen back to the whole list
     * instead of leaving it on a tab that no longer exists.
     */
    val activeFolder: StateFlow<ChatFolder?> =
        combine(_folders, _activeFolderId) { all, id -> all.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** True when the tab strip has something to show. */
    val hasFolders: Boolean get() = _folders.value.isNotEmpty()

    /** Shows the folder with [folderId], or the whole list when it is null or unknown. */
    fun select(folderId: Long?) {
        _activeFolderId.value = folderId?.takeIf { id -> _folders.value.any { it.id == id } }
    }

    /** Adds a folder; [FolderCreateResult] says what happened. */
    fun create(name: String, rules: Set<FolderRule>, mode: FolderRuleMode): FolderCreateResult {
        val trimmed = name.trim().take(ChatFolder.MAX_NAME_LENGTH)
        if (trimmed.isEmpty()) return FolderCreateResult.EmptyName
        val current = _folders.value
        if (current.size >= ChatFolder.MAX_FOLDERS) return FolderCreateResult.TooManyFolders
        val folder = ChatFolder(
            id = nextId(current),
            name = trimmed,
            rules = rules,
            ruleMode = mode
        )
        commit(current + folder)
        return FolderCreateResult.Created(folder)
    }

    /** New label of a folder; an empty name is ignored. */
    fun rename(folderId: Long, name: String) {
        val trimmed = name.trim().take(ChatFolder.MAX_NAME_LENGTH)
        if (trimmed.isEmpty()) return
        update(folderId) { it.copy(name = trimmed) }
    }

    /** New rules of a folder. */
    fun setRules(folderId: Long, rules: Set<FolderRule>, mode: FolderRuleMode) {
        update(folderId) { it.copy(rules = rules, ruleMode = mode) }
    }

    /** Removes a folder; the chat list itself is untouched. */
    fun delete(folderId: Long) {
        commit(_folders.value.filterNot { it.id == folderId })
        if (_activeFolderId.value == folderId) {
            _activeFolderId.value = null
        }
    }

    /** Puts one chat into a folder by hand. */
    fun addChat(folderId: Long, chatId: Long) {
        update(folderId) { folder ->
            if (folder.includeChatIds.size >= ChatFolder.MAX_NAMED_CHATS) {
                folder
            } else {
                folder.copy(
                    includeChatIds = folder.includeChatIds + chatId,
                    excludeChatIds = folder.excludeChatIds - chatId
                )
            }
        }
    }

    /** Takes a chat that was added by hand out of a folder again. */
    fun removeChat(folderId: Long, chatId: Long) {
        update(folderId) { folder -> folder.copy(includeChatIds = folder.includeChatIds - chatId) }
    }

    /** Keeps a chat out of a folder even when the rules of the folder want it. */
    fun excludeChat(folderId: Long, chatId: Long) {
        update(folderId) { folder ->
            folder.copy(
                excludeChatIds = folder.excludeChatIds + chatId,
                includeChatIds = folder.includeChatIds - chatId
            )
        }
    }

    /** Folds a folder one place towards the front of the tab strip. */
    fun moveEarlier(folderId: Long) {
        val current = _folders.value
        val index = current.indexOfFirst { it.id == folderId }
        if (index <= 0) return
        commit(current.toMutableList().also { it.add(index - 1, it.removeAt(index)) })
    }

    /** Folds a folder one place towards the end of the tab strip. */
    fun moveLater(folderId: Long) {
        val current = _folders.value
        val index = current.indexOfFirst { it.id == folderId }
        if (index < 0 || index == current.lastIndex) return
        commit(current.toMutableList().also { it.add(index + 1, it.removeAt(index)) })
    }

    private fun update(folderId: Long, block: (ChatFolder) -> ChatFolder) {
        commit(_folders.value.map { if (it.id == folderId) block(it) else it })
    }

    private fun commit(next: List<ChatFolder>) {
        _folders.value = next
        store.save(next)
    }

    /** Ids only have to be unique among the folders of this device. */
    private fun nextId(current: List<ChatFolder>): Long =
        (current.maxOfOrNull { it.id } ?: 0L) + 1L
}
