package com.liftr.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Retention and usage-accounting behaviour for stored recordings.
 *
 * The original implementation used a flat `listFiles()`, which meant:
 *  - the `annotated/` entry was a directory, and `File.delete()` on a non-empty
 *    directory silently returns false, so annotated exports were unreclaimable;
 *  - `getStorageUsedBytes` ignored everything nested, understating usage.
 *
 * The walk logic is duplicated here because the repository itself needs an
 * Android Context; these tests pin the semantics the implementation must keep.
 */
class VideoRetentionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun cleanup(root: File, maxAgeMs: Long): Pair<Int, Long> {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var count = 0
        var freed = 0L
        root.walkBottomUp()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach { file ->
                val size = file.length()
                if (file.delete()) { count++; freed += size }
            }
        return count to freed
    }

    private fun usedBytes(root: File): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun writeFile(parent: File, name: String, ageMs: Long, bytes: Int = 16): File {
        parent.mkdirs()
        val f = File(parent, name)
        f.writeBytes(ByteArray(bytes))
        f.setLastModified(System.currentTimeMillis() - ageMs)
        return f
    }

    @Test
    fun `deletes stale recordings and keeps recent ones`() {
        val root = temp.newFolder("recordings")
        val old = writeFile(root, "front_squat_1.mp4", TimeUnit.DAYS.toMillis(30))
        val fresh = writeFile(root, "front_squat_2.mp4", TimeUnit.MINUTES.toMillis(5))

        val (count, _) = cleanup(root, TimeUnit.DAYS.toMillis(7))

        assertEquals(1, count)
        assertFalse("stale recording should be gone", old.exists())
        assertTrue("recent recording must survive", fresh.exists())
    }

    @Test
    fun `reclaims stale files inside the annotated subdirectory`() {
        val root = temp.newFolder("recordings")
        val annotated = File(root, "annotated")
        val staleAnnotated = writeFile(annotated, "liftr_front_squat_1.mp4", TimeUnit.DAYS.toMillis(30))

        val (count, _) = cleanup(root, TimeUnit.DAYS.toMillis(7))

        assertEquals(1, count)
        assertFalse("nested annotated export must be reclaimable", staleAnnotated.exists())
        assertTrue("the directory itself must be kept", annotated.exists())
    }

    @Test
    fun `an in-flight recording is never caught by the retention window`() {
        val root = temp.newFolder("recordings")
        val inFlight = writeFile(root, "front_squat_now.mp4", ageMs = 0)

        cleanup(root, TimeUnit.DAYS.toMillis(7))

        assertTrue("a just-written recording must survive", inFlight.exists())
    }

    @Test
    fun `storage usage counts nested files`() {
        val root = temp.newFolder("recordings")
        writeFile(root, "raw.mp4", ageMs = 0, bytes = 100)
        writeFile(File(root, "annotated"), "annotated.mp4", ageMs = 0, bytes = 250)

        assertEquals(350L, usedBytes(root))
    }

    @Test
    fun `cleanup on an empty tree is a no-op`() {
        val root = temp.newFolder("recordings")

        val (count, freed) = cleanup(root, TimeUnit.DAYS.toMillis(7))

        assertEquals(0, count)
        assertEquals(0L, freed)
        assertTrue(root.exists())
    }
}
