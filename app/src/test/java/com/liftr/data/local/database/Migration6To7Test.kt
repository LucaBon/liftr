package com.liftr.data.local.database

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.mockk
import io.mockk.slot
import io.mockk.every
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repo has no instrumented tests, so the migration is checked as SQL against the
 * exported schemas: the statements must add exactly the columns v7 has over v6.
 */
class Migration6To7Test {

    private fun reps(version: Int): Map<String, String> {
        val text = File("schemas/com.liftr.data.local.database.LiftDatabase/$version.json").readText()
        val fields = Regex("""\{\s*"fieldPath":\s*"(\w+)",\s*"columnName":\s*"\w+",\s*"affinity":\s*"(\w+)"""")
        val repsBlock = text.substringAfter("\"tableName\": \"reps\"").substringBefore("\"primaryKey\"")
        return fields.findAll(repsBlock).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `migration adds exactly the new nullable columns`() {
        val statements = mutableListOf<String>()
        val db = mockk<SupportSQLiteDatabase>()
        val sql = slot<String>()
        every { db.execSQL(capture(sql)) } answers { statements += sql.captured }

        LiftDatabase.MIGRATION_6_7.migrate(db)

        assertEquals(6, LiftDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, LiftDatabase.MIGRATION_6_7.endVersion)
        assertEquals(
            listOf(
                "ALTER TABLE reps ADD COLUMN meanEccentricVelocity REAL DEFAULT NULL",
                "ALTER TABLE reps ADD COLUMN bottomPauseMs INTEGER DEFAULT NULL"
            ),
            statements
        )
    }

    @Test
    fun `migrated columns match the difference between the v6 and v7 schemas`() {
        val added = reps(7) - reps(6).keys
        assertEquals(mapOf("meanEccentricVelocity" to "REAL", "bottomPauseMs" to "INTEGER"), added)
        assertTrue(reps(6).keys.all { it in reps(7) })
    }
}
