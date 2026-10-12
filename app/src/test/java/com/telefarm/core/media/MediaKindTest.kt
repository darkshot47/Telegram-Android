package com.telefarm.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Working out what a picked file is, and what to call the copy of it. */
class MediaKindTest {

    @Test
    fun `the type is believed before the name`() {
        assertEquals(MediaKind.PHOTO, MediaFiles.of("image/jpeg", "notes.txt"))
    }

    @Test
    fun `an animation claims to be an image but is not sent as one`() {
        assertEquals(MediaKind.ANIMATION, MediaFiles.of("image/gif", "laugh.gif"))
    }

    @Test
    fun `each type family maps to its kind`() {
        assertEquals(MediaKind.PHOTO, MediaFiles.of("image/png", null))
        assertEquals(MediaKind.VIDEO, MediaFiles.of("video/mp4", null))
        assertEquals(MediaKind.AUDIO, MediaFiles.of("audio/mpeg", null))
        assertEquals(MediaKind.DOCUMENT, MediaFiles.of("application/pdf", null))
    }

    @Test
    fun `a type of nothing falls back to the extension`() {
        assertEquals(MediaKind.PHOTO, MediaFiles.of(null, "holiday.jpg"))
        assertEquals(MediaKind.VIDEO, MediaFiles.of(null, "clip.mp4"))
        assertEquals(MediaKind.AUDIO, MediaFiles.of(null, "song.mp3"))
        assertEquals(MediaKind.ANIMATION, MediaFiles.of(null, "funny.gif"))
    }

    @Test
    fun `the fallback type says nothing, so the extension is used`() {
        assertEquals(MediaKind.PHOTO, MediaFiles.of("application/octet-stream", "pic.png"))
    }

    @Test
    fun `neither type nor known extension means a file`() {
        assertEquals(MediaKind.DOCUMENT, MediaFiles.of(null, null))
        assertEquals(MediaKind.DOCUMENT, MediaFiles.of(null, "archive.xyz"))
    }

    @Test
    fun `the extension is read case insensitively`() {
        assertEquals(MediaKind.PHOTO, MediaFiles.of(null, "PHOTO.JPG"))
        assertEquals("jpg", MediaFiles.extensionOf("PHOTO.JPG"))
    }

    @Test
    fun `a hidden file's leading dot is not an extension`() {
        assertEquals("", MediaFiles.extensionOf(".gitignore"))
        assertEquals(MediaKind.DOCUMENT, MediaFiles.of(null, ".gitignore"))
    }

    @Test
    fun `a trailing dot is not an extension either`() {
        assertEquals("", MediaFiles.extensionOf("report."))
    }

    @Test
    fun `a name with no dot has no extension`() {
        assertEquals("", MediaFiles.extensionOf("README"))
    }

    @Test
    fun `the last dot separates the extension`() {
        assertEquals("gz", MediaFiles.extensionOf("backup.tar.gz"))
    }

    @Test
    fun `the copy keeps the name it came with`() {
        assertEquals("report-7.pdf", MediaFiles.cacheFileName("report.pdf", "application/pdf", 7))
    }

    @Test
    fun `two files with the same name are told apart`() {
        val first = MediaFiles.cacheFileName("photo.jpg", "image/jpeg", 1)
        val second = MediaFiles.cacheFileName("photo.jpg", "image/jpeg", 2)
        assertFalse(first == second)
    }

    @Test
    fun `a name that tries to leave the directory cannot`() {
        val name = MediaFiles.cacheFileName("../../../etc/passwd", null, 3)
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))
    }

    @Test
    fun `a path in the name is reduced to the file at the end of it`() {
        val name = MediaFiles.cacheFileName("/sdcard/Download/report.pdf", null, 4)
        assertEquals("report-4.pdf", name)
    }

    @Test
    fun `marks that mean nothing in a name are dropped`() {
        val name = MediaFiles.cacheFileName("a:b*c?.pdf", null, 5)
        // Marks become spaces rather than being deleted, so the shape of the name survives.
        assertEquals("a b c-5.pdf", name)
    }

    @Test
    fun `a file with no name still gets one`() {
        assertEquals("file-6.jpg", MediaFiles.cacheFileName(null, "image/jpeg", 6))
        assertEquals("file-8", MediaFiles.cacheFileName(null, null, 8))
    }

    @Test
    fun `a name of only marks still gets one`() {
        assertEquals("file-9", MediaFiles.cacheFileName("???", null, 9))
    }

    @Test
    fun `an over long name is cut rather than refused`() {
        val long = "a".repeat(400) + ".pdf"
        val name = MediaFiles.cacheFileName(long, null, 10)
        assertTrue(name.length <= MediaFiles.MAX_NAME_LENGTH + 8)
        assertTrue(name.endsWith("-10.pdf"))
    }

    @Test
    fun `the type supplies an extension when the name has none`() {
        assertEquals("video-11.mp4", MediaFiles.cacheFileName("video", "video/mp4", 11))
    }

    @Test
    fun `spaces in a name survive but runs of them do not`() {
        assertEquals("my holiday photo-12.jpg", MediaFiles.cacheFileName("my   holiday  photo.jpg", null, 12))
    }

    @Test
    fun `a picture within the limit is sent as a picture`() {
        assertEquals(MediaKind.PHOTO, MediaFiles.sendAs(MediaKind.PHOTO, MediaFiles.MAX_PHOTO_BYTES))
        assertEquals(MediaKind.PHOTO, MediaFiles.sendAs(MediaKind.PHOTO, 1024))
    }

    @Test
    fun `a picture past the limit goes as a file rather than not at all`() {
        assertEquals(
            MediaKind.DOCUMENT,
            MediaFiles.sendAs(MediaKind.PHOTO, MediaFiles.MAX_PHOTO_BYTES + 1)
        )
    }

    @Test
    fun `a video, a sound or a file goes the way it was recognised whatever its size`() {
        val huge = MediaFiles.MAX_PHOTO_BYTES * 40
        assertEquals(MediaKind.VIDEO, MediaFiles.sendAs(MediaKind.VIDEO, huge))
        assertEquals(MediaKind.AUDIO, MediaFiles.sendAs(MediaKind.AUDIO, huge))
        assertEquals(MediaKind.DOCUMENT, MediaFiles.sendAs(MediaKind.DOCUMENT, huge))
        assertEquals(MediaKind.ANIMATION, MediaFiles.sendAs(MediaKind.ANIMATION, huge))
    }

    @Test
    fun `the photo limit is ten megabytes`() {
        assertEquals(10L * 1_024 * 1_024, MediaFiles.MAX_PHOTO_BYTES)
    }

    @Test
    fun `letters outside the latin alphabet survive`() {
        assertEquals("रिपोर्ट-13.pdf", MediaFiles.cacheFileName("रिपोर्ट.pdf", null, 13))
    }
}
