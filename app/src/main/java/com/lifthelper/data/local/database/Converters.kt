package com.lifthelper.data.local.database

import androidx.room.TypeConverter
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PositionProfile
import com.lifthelper.domain.model.VelocityProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class Converters {
    @TypeConverter
    fun fromExerciseType(value: ExerciseType): String = value.name

    @TypeConverter
    fun toExerciseType(value: String): ExerciseType = ExerciseType.valueOf(value)

    @TypeConverter
    fun fromVelocityProfileList(value: List<VelocityProfile>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toVelocityProfileList(value: String): List<VelocityProfile> {
        return Json.decodeFromString(value)
    }

    @TypeConverter
    fun fromPositionProfileList(value: List<PositionProfile>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toPositionProfileList(value: String): List<PositionProfile> {
        return Json.decodeFromString(value)
    }
}
