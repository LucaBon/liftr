package com.liftr.vision.camera

import androidx.camera.video.VideoRecordEvent.Finalize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoverableFinalizeErrorTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `interruption errors with a non-empty file are recoverable`() {
        val f = tmp.newFile().apply { writeText("data") }
        listOf(
            Finalize.ERROR_SOURCE_INACTIVE,
            Finalize.ERROR_INSUFFICIENT_STORAGE,
            Finalize.ERROR_DURATION_LIMIT_REACHED,
            Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        ).forEach { assertTrue(isRecoverableFinalizeError(it, f)) }
    }

    @Test
    fun `no valid data is never recoverable`() {
        val f = tmp.newFile().apply { writeText("data") }
        assertFalse(isRecoverableFinalizeError(Finalize.ERROR_NO_VALID_DATA, f))
    }

    @Test
    fun `empty or missing file is not recoverable`() {
        val empty = tmp.newFile()
        assertFalse(isRecoverableFinalizeError(Finalize.ERROR_SOURCE_INACTIVE, empty))
        assertFalse(isRecoverableFinalizeError(Finalize.ERROR_SOURCE_INACTIVE, java.io.File(tmp.root, "nope")))
    }
}
