package com.telefarm.core.folders

import com.telefarm.data.model.ChatFolder
import com.telefarm.data.model.FolderRule
import com.telefarm.data.model.FolderRuleMode

/**
 * The stored form of the folders of this device.
 *
 * Folders are device local, so they live in a preference file. The file holds no credential -
 * only tab names, rule names and chat ids - but it is read on every start and was written by an
 * older version of the application, so it is parsed defensively: anything that cannot be
 * understood is dropped instead of throwing, because a corrupt preference must never stop the
 * chat list from opening.
 *
 * The layout is deliberately compact and needs no JSON library:
 *
 * ```
 * tf1~<id>|<name>|<mode>|<rule,rule>|<chat;chat>|<chat;chat>#<next folder>
 * ```
 *
 * Names are percent encoded, so the separators can never be produced by user input.
 */
object FolderCodec {

    /** Version marker; a different prefix means the text was written by another version. */
    const val PREFIX = "tf1~"

    private const val FOLDER_SEPARATOR = '#'
    private const val FIELD_SEPARATOR = '|'
    private const val RULE_SEPARATOR = ','
    private const val ID_SEPARATOR = ';'
    private const val FIELD_COUNT = 6
    private const val ESCAPE_CHARACTER = '%'
    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** Folders that must be escaped because they are separators of this format. */
    private val RESERVED = setOf(FOLDER_SEPARATOR, FIELD_SEPARATOR, RULE_SEPARATOR, ID_SEPARATOR, ESCAPE_CHARACTER)

    /** Writes [folders]; the result is what [decode] reads back. */
    fun encode(folders: List<ChatFolder>): String {
        if (folders.isEmpty()) return ""
        val builder = StringBuilder(PREFIX)
        folders.take(ChatFolder.MAX_FOLDERS).forEachIndexed { index, folder ->
            if (index > 0) builder.append(FOLDER_SEPARATOR)
            builder
                .append(folder.id)
                .append(FIELD_SEPARATOR)
                .append(encodeName(folder.name))
                .append(FIELD_SEPARATOR)
                .append(folder.ruleMode.name)
                .append(FIELD_SEPARATOR)
                .append(folder.rules.joinToString(RULE_SEPARATOR.toString()) { it.name })
                .append(FIELD_SEPARATOR)
                .append(folder.includeChatIds.joinToString(ID_SEPARATOR.toString()))
                .append(FIELD_SEPARATOR)
                .append(folder.excludeChatIds.joinToString(ID_SEPARATOR.toString()))
        }
        return builder.toString()
    }

    /** Reads what [encode] wrote; never throws and never returns more than the allowed folders. */
    fun decode(stored: String?): List<ChatFolder> {
        if (stored.isNullOrEmpty() || !stored.startsWith(PREFIX)) return emptyList()
        val body = stored.substring(PREFIX.length)
        if (body.isBlank()) return emptyList()

        val folders = ArrayList<ChatFolder>()
        val usedIds = HashSet<Long>()
        for (record in body.split(FOLDER_SEPARATOR)) {
            val folder = folderOf(record) ?: continue
            if (!usedIds.add(folder.id)) continue
            folders += folder
            if (folders.size == ChatFolder.MAX_FOLDERS) break
        }
        return folders
    }

    /** True when [stored] is exactly what [encode] produces from the same folders. */
    fun isIntact(stored: String?, folders: List<ChatFolder>): Boolean =
        decode(stored) == folders

    private fun folderOf(record: String): ChatFolder? {
        val fields = record.split(FIELD_SEPARATOR)
        if (fields.size != FIELD_COUNT) return null
        val id = fields[0].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val name = decodeName(fields[1]).trim().take(ChatFolder.MAX_NAME_LENGTH)
        if (name.isEmpty()) return null
        return ChatFolder(
            id = id,
            name = name,
            rules = rulesOf(fields[3]),
            ruleMode = modeOf(fields[2]),
            includeChatIds = idsOf(fields[4]),
            excludeChatIds = idsOf(fields[5])
        )
    }

    private fun modeOf(raw: String): FolderRuleMode =
        FolderRuleMode.entries.firstOrNull { it.name == raw } ?: FolderRuleMode.ANY

    /** Unknown rule names are dropped; a rule that no longer exists must not break the folder. */
    private fun rulesOf(raw: String): Set<FolderRule> {
        if (raw.isEmpty()) return emptySet()
        val rules = LinkedHashSet<FolderRule>()
        for (name in raw.split(RULE_SEPARATOR)) {
            FolderRule.entries.firstOrNull { it.name == name }?.let { rules += it }
        }
        return rules
    }

    private fun idsOf(raw: String): Set<Long> {
        if (raw.isEmpty()) return emptySet()
        val ids = LinkedHashSet<Long>()
        for (part in raw.split(ID_SEPARATOR)) {
            val id = part.toLongOrNull() ?: continue
            if (id == 0L) continue
            ids += id
            if (ids.size == ChatFolder.MAX_NAMED_CHATS) break
        }
        return ids
    }

    /** Escapes the separators of this format and everything that is not printable. */
    private fun encodeName(name: String): String {
        val builder = StringBuilder(name.length + ESCAPE_PADDING)
        for (character in name) {
            when {
                character in RESERVED -> escape(builder, character.code)
                character.code < PRINTABLE_MINIMUM || character.code == NEWLINE || character.code == CARRIAGE_RETURN ->
                    escape(builder, character.code)

                else -> builder.append(character)
            }
        }
        return builder.toString()
    }

    private fun escape(builder: StringBuilder, code: Int) {
        builder.append(ESCAPE_CHARACTER)
        builder.append(HEX_DIGITS[(code shr 4) and 0x0F])
        builder.append(HEX_DIGITS[code and 0x0F])
    }

    /** Reverses [encodeName]; an escape that is not well formed is taken literally. */
    private fun decodeName(encoded: String): String {
        if (!encoded.contains(ESCAPE_CHARACTER)) return encoded
        val builder = StringBuilder(encoded.length)
        var index = 0
        while (index < encoded.length) {
            val character = encoded[index]
            // A complete escape is '%' plus two digits, so three characters have to be left.
            if (character != ESCAPE_CHARACTER || index + ESCAPE_LENGTH > encoded.length) {
                builder.append(character)
                index++
                continue
            }
            val high = HEX_DIGITS.indexOf(encoded[index + 1].uppercaseChar())
            val low = HEX_DIGITS.indexOf(encoded[index + 2].uppercaseChar())
            if (high < 0 || low < 0) {
                builder.append(character)
                index++
                continue
            }
            builder.append(((high shl 4) or low).toChar())
            index += ESCAPE_LENGTH
        }
        return builder.toString()
    }

    private const val ESCAPE_PADDING = 8
    private const val ESCAPE_LENGTH = 3
    private const val PRINTABLE_MINIMUM = 0x20
    private const val NEWLINE = 0x0A
    private const val CARRIAGE_RETURN = 0x0D
}
