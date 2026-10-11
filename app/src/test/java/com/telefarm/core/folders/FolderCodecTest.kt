package com.telefarm.core.folders

import com.telefarm.data.model.ChatFolder
import com.telefarm.data.model.FolderRule
import com.telefarm.data.model.FolderRuleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of the folders.
 *
 * The text is read on every start and was written by an older version, so the interesting cases
 * are the broken ones: they must cost a folder, never the chat list.
 */
class FolderCodecTest {

    @Test
    fun `round trips a folder with every part filled in`() {
        val folder = ChatFolder(
            id = 42,
            name = "Work",
            rules = setOf(FolderRule.UNMUTED, FolderRule.GROUPS),
            ruleMode = FolderRuleMode.ALL,
            includeChatIds = linkedSetOf(-1002345678901L, 5L),
            excludeChatIds = linkedSetOf(9L)
        )
        val stored = FolderCodec.encode(listOf(folder))
        assertEquals(listOf(folder), FolderCodec.decode(stored))
        assertTrue(FolderCodec.isIntact(stored, listOf(folder)))
    }

    @Test
    fun `round trips several folders in order`() {
        val folders = listOf(
            ChatFolder(1, "Unread", setOf(FolderRule.UNREAD)),
            ChatFolder(2, "Channels", setOf(FolderRule.CHANNELS), FolderRuleMode.ANY),
            ChatFolder(3, "Quiet", emptySet(), FolderRuleMode.ALL, emptySet(), setOf(7L))
        )
        assertEquals(folders, FolderCodec.decode(FolderCodec.encode(folders)))
    }

    @Test
    fun `a name may contain every separator of the format`() {
        val folder = ChatFolder(1, "a#b|c,d;e%f\ng")
        assertEquals(folder.name, FolderCodec.decode(FolderCodec.encode(listOf(folder))).single().name)
    }

    @Test
    fun `an empty list stores as an empty text`() {
        assertEquals("", FolderCodec.encode(emptyList()))
        assertEquals(emptyList<ChatFolder>(), FolderCodec.decode(""))
        assertEquals(emptyList<ChatFolder>(), FolderCodec.decode(null))
    }

    @Test
    fun `text of another version is ignored`() {
        assertEquals(emptyList<ChatFolder>(), FolderCodec.decode("tf2~1|Work|ANY|UNREAD||"))
        assertEquals(emptyList<ChatFolder>(), FolderCodec.decode("1|Work|ANY|UNREAD||"))
        assertEquals(emptyList<ChatFolder>(), FolderCodec.decode("garbage"))
    }

    @Test
    fun `a record with the wrong number of fields is dropped`() {
        val stored = FolderCodec.encode(listOf(ChatFolder(1, "Work", setOf(FolderRule.UNREAD))))
        val broken = "$stored#7|TooFew|ANY"
        assertEquals(listOf(ChatFolder(1, "Work", setOf(FolderRule.UNREAD))), FolderCodec.decode(broken))
    }

    @Test
    fun `a folder without a usable id or name is dropped`() {
        val stored = FolderCodec.encode(listOf(ChatFolder(1, "Work", setOf(FolderRule.UNREAD))))
        assertEquals(1, FolderCodec.decode("$stored#0|NoId|ANY|UNREAD||").size)
        assertEquals(1, FolderCodec.decode("$stored#5||ANY|UNREAD||").size)
        assertEquals(1, FolderCodec.decode("$stored#notanumber|NoId|ANY|UNREAD||").size)
    }

    @Test
    fun `a rule that no longer exists is dropped instead of breaking the folder`() {
        val stored = FolderCodec.encode(listOf(ChatFolder(1, "Work", setOf(FolderRule.UNREAD))))
        val withUnknownRule = stored.replace("UNREAD", "UNREAD,NEARBY_PEOPLE")
        val decoded = FolderCodec.decode(withUnknownRule).single()
        assertEquals(setOf(FolderRule.UNREAD), decoded.rules)
    }

    @Test
    fun `an unknown rule mode falls back to any rule`() {
        val stored = FolderCodec.encode(listOf(ChatFolder(1, "Work", setOf(FolderRule.UNREAD), FolderRuleMode.ALL)))
        assertEquals(FolderRuleMode.ALL, FolderCodec.decode(stored).single().ruleMode)
        val broken = stored.replace("|ALL|", "|WHATEVER|")
        assertEquals(FolderRuleMode.ANY, FolderCodec.decode(broken).single().ruleMode)
    }

    @Test
    fun `chat ids that are not numbers are dropped`() {
        val folder = ChatFolder(1, "Work", emptySet(), FolderRuleMode.ANY, linkedSetOf(5L))
        val stored = FolderCodec.encode(listOf(folder)).replace("|5|", "|5;abc;0;-3|")
        assertEquals(linkedSetOf(5L, -3L), FolderCodec.decode(stored).single().includeChatIds)
    }

    @Test
    fun `a truncated escape sequence is taken literally and does not throw`() {
        // The escape is incomplete: the '%' is one character away from the end of the name field.
        val truncated = "tf1~1|Work%2|ANY|UNREAD||"
        assertEquals("Work%2", FolderCodec.decode(truncated).single().name)
        val dangling = "tf1~1|Work%|ANY|UNREAD||"
        assertEquals("Work%", FolderCodec.decode(dangling).single().name)
        val notHex = "tf1~1|Work%ZZ|ANY|UNREAD||"
        assertEquals("Work%ZZ", FolderCodec.decode(notHex).single().name)
    }

    @Test
    fun `more folders than the tab strip can show are cut off`() {
        val folders = (1..(ChatFolder.MAX_FOLDERS + 5)).map { ChatFolder(it.toLong(), "Folder $it", setOf(FolderRule.UNREAD)) }
        assertEquals(ChatFolder.MAX_FOLDERS, FolderCodec.decode(FolderCodec.encode(folders)).size)
    }

    @Test
    fun `more named chats than allowed are cut off`() {
        val ids = (1..(ChatFolder.MAX_NAMED_CHATS + 50)).map { it.toLong() }.toSet()
        val folder = ChatFolder(1, "Work", emptySet(), FolderRuleMode.ANY, ids)
        assertEquals(ChatFolder.MAX_NAMED_CHATS, FolderCodec.decode(FolderCodec.encode(listOf(folder))).single().includeChatIds.size)
    }

    @Test
    fun `a name longer than a tab label is cut off`() {
        val folder = ChatFolder(1, "x".repeat(ChatFolder.MAX_NAME_LENGTH + 40))
        assertEquals(ChatFolder.MAX_NAME_LENGTH, FolderCodec.decode(FolderCodec.encode(listOf(folder))).single().name.length)
    }

    @Test
    fun `text that is only the marker holds no folders`() {
        assertEquals(emptyList<ChatFolder>(), FolderCodec.decode(FolderCodec.PREFIX))
        assertEquals(emptyList<ChatFolder>(), FolderCodec.decode(FolderCodec.PREFIX + "   "))
    }

    @Test
    fun `duplicate folder ids are kept once`() {
        val folders = listOf(ChatFolder(1, "First", setOf(FolderRule.UNREAD)), ChatFolder(1, "Second", setOf(FolderRule.MUTED)))
        val decoded = FolderCodec.decode(FolderCodec.encode(folders))
        assertEquals(1, decoded.size)
        assertEquals("First", decoded.single().name)
        assertFalse(decoded.single().rules.contains(FolderRule.MUTED))
    }
}
