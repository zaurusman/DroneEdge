package com.yotam.droneedge.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionRenamerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── sanitizeSessionName ───────────────────────────────────────────────────

    @Test
    fun `empty string returns null`() {
        assertNull(sanitizeSessionName(""))
    }

    @Test
    fun `blank string returns null`() {
        assertNull(sanitizeSessionName("   "))
    }

    @Test
    fun `normal name passes through unchanged`() {
        assertEquals("morning patrol", sanitizeSessionName("morning patrol"))
    }

    @Test
    fun `leading and trailing spaces are stripped`() {
        assertEquals("test", sanitizeSessionName("  test  "))
    }

    @Test
    fun `forward slashes replaced with dashes`() {
        assertEquals("a-b-c", sanitizeSessionName("a/b/c"))
    }

    @Test
    fun `name with only slashes becomes null after sanitize`() {
        assertNull(sanitizeSessionName("/"))
    }

    // ── countDetectionLines ───────────────────────────────────────────────────

    @Test
    fun `non-existent file returns -1`() {
        val file = tmp.newFolder().resolve("missing.json")
        assertEquals(-1, countDetectionLines(file))
    }

    @Test
    fun `file with three non-empty lines returns 3`() {
        val file = tmp.newFile("detections.json")
        file.writeText("{\"frameIndex\":0}\n{\"frameIndex\":1}\n{\"frameIndex\":2}\n")
        assertEquals(3, countDetectionLines(file))
    }

    @Test
    fun `blank lines are not counted`() {
        val file = tmp.newFile("blank.json")
        file.writeText("{\"frameIndex\":0}\n\n{\"frameIndex\":1}\n\n")
        assertEquals(2, countDetectionLines(file))
    }

    @Test
    fun `empty file returns 0`() {
        val file = tmp.newFile("empty.json")
        file.writeText("")
        assertEquals(0, countDetectionLines(file))
    }
}
