package com.lifthelper.data.repository

import com.lifthelper.domain.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip of the `{exercise}_{timestamp}.mp4` recording filename convention.
 *
 * Parsing used to split on the FIRST underscore, but exercise names contain
 * underscores themselves (front_squat, bench_press). "front_squat_123" then
 * yielded "front" — not a valid ExerciseType — so every multi-word exercise
 * silently fell back to BACK_SQUAT with the file's mtime as its timestamp.
 *
 * The parsing mirrors VideoStorageRepositoryImpl.getAllRecordings; it is
 * duplicated here because the repository itself needs an Android Context.
 */
class RecordingFileNamingTest {

    private fun buildName(type: ExerciseType, timestamp: Long) =
        "${type.name.lowercase()}_$timestamp"

    private fun parse(nameWithoutExtension: String): Pair<ExerciseType, Long?> {
        val type = runCatching {
            ExerciseType.valueOf(nameWithoutExtension.substringBeforeLast('_').uppercase())
        }.getOrDefault(ExerciseType.BACK_SQUAT)
        val timestamp = nameWithoutExtension.substringAfterLast('_').toLongOrNull()
        return type to timestamp
    }

    @Test
    fun `every exercise type round-trips through the filename`() {
        val timestamp = 1_712_345_678_000L
        for (type in ExerciseType.entries) {
            val (parsedType, parsedTimestamp) = parse(buildName(type, timestamp))
            assertEquals("Exercise type for $type", type, parsedType)
            assertEquals("Timestamp for $type", timestamp, parsedTimestamp)
        }
    }

    @Test
    fun `multi word exercise names are not truncated at the first underscore`() {
        val (type, timestamp) = parse("front_squat_1712345678000")
        assertEquals(ExerciseType.FRONT_SQUAT, type)
        assertEquals(1_712_345_678_000L, timestamp)
    }

    @Test
    fun `bench press round-trips`() {
        val (type, timestamp) = parse("bench_press_999")
        assertEquals(ExerciseType.BENCH_PRESS, type)
        assertEquals(999L, timestamp)
    }

    @Test
    fun `unparseable name falls back without throwing`() {
        val (type, timestamp) = parse("garbage")
        assertEquals(ExerciseType.BACK_SQUAT, type)
        assertEquals(null, timestamp)
    }
}
