package com.liftr.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.liftr.data.local.database.dao.RepDao
import com.liftr.data.local.database.dao.SetDao
import com.liftr.data.local.database.dao.WorkoutDao
import com.liftr.data.local.database.entities.RepEntity
import com.liftr.data.local.database.entities.SetEntity
import com.liftr.data.local.database.entities.WorkoutEntity

@Database(
    entities = [
        WorkoutEntity::class,
        SetEntity::class,
        RepEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class LiftDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun setDao(): SetDao
    abstract fun repDao(): RepDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reps ADD COLUMN qualityScore INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sets ADD COLUMN isManual INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sets ADD COLUMN exerciseName TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reps ADD COLUMN jumpData TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reps ADD COLUMN depthScore INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE reps ADD COLUMN barPathScore INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE reps ADD COLUMN tempoScore INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE reps ADD COLUMN formScore INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reps ADD COLUMN catchType TEXT DEFAULT NULL")
            }
        }
    }
}
