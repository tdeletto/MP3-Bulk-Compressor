package com.tdeletto.mp3bulk.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageRulesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun pickerBlockedFolders() {
        assertTrue(isSafBlockedPath(""))
        assertTrue(isSafBlockedPath("Download"))
        assertTrue(isSafBlockedPath("download/"))
        assertTrue(isSafBlockedPath("Android/data"))
        assertTrue(isSafBlockedPath("Android/obb/com.example"))
    }

    @Test fun pickerAllowedFolders() {
        assertFalse(isSafBlockedPath("Download/Podcasts"))
        assertFalse(isSafBlockedPath("Music"))
        assertFalse(isSafBlockedPath("Music/TestMusic"))
        assertFalse(isSafBlockedPath("Android"))
        assertFalse(isSafBlockedPath("Downloads Old"))
    }

    @Test fun uniqueChildNeverReusesATakenName() {
        val dir = tmp.newFolder()
        assertEquals("song - SHRUNK.mp3", uniqueChild(dir, "song - SHRUNK.mp3").name)
        File(dir, "song - SHRUNK.mp3").createNewFile()
        assertEquals("song - SHRUNK (1).mp3", uniqueChild(dir, "song - SHRUNK.mp3").name)
        File(dir, "song - SHRUNK (1).mp3").createNewFile()
        assertEquals("song - SHRUNK (2).mp3", uniqueChild(dir, "song - SHRUNK.mp3").name)
    }

    @Test fun uniqueChildWithoutExtension() {
        val dir = tmp.newFolder()
        File(dir, "notes").createNewFile()
        assertEquals("notes (1)", uniqueChild(dir, "notes").name)
    }
}
