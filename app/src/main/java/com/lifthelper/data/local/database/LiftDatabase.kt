package com.lifthelper.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lifthelper.data.local.database.dao.RepDao
import com.lifthelper.data.local.database.dao.SetDao
import com.lifthelper.data.local.database.dao.WorkoutDao
import com.lifthelper.data.local.database.entities.RepEntity
import com.lifthelper.data.local.database.entities.SetEntity
import com.lifthelper.data.local.database.entities.WorkoutEntity

@Database(
    entities = [
        WorkoutEntity::class,
        SetEntity::class,
        RepEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class LiftDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun setDao(): SetDao
    abstract fun repDao(): RepDao
}
