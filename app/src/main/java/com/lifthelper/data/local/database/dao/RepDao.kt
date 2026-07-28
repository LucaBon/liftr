package com.lifthelper.data.local.database.dao

import androidx.room.*
import com.lifthelper.data.local.database.entities.RepEntity
import com.lifthelper.domain.model.ExerciseType
import kotlinx.coroutines.flow.Flow

@Dao
interface RepDao {
    @Insert
    suspend fun insertRep(rep: RepEntity): Long

    @Insert
    suspend fun insertReps(reps: List<RepEntity>)

    @Query("SELECT * FROM reps WHERE setId = :setId ORDER BY repNumber ASC")
    fun getRepsForSet(setId: Long): Flow<List<RepEntity>>

    @Query("SELECT * FROM reps WHERE setId = :setId ORDER BY repNumber ASC")
    suspend fun getRepsForSetSync(setId: Long): List<RepEntity>

    @Query("SELECT AVG(avgVelocity) FROM reps WHERE setId = :setId")
    suspend fun getAverageVelocityForSet(setId: Long): Float?

    @Query("SELECT MAX(peakVelocity) FROM reps WHERE setId = :setId")
    suspend fun getPeakVelocityForSet(setId: Long): Float?

    // Analytics queries

    // Manual sets are excluded: they carry a placeholder exerciseType and no
    // tracked rep data, so they must not compete for velocity/quality records.

    @Query("""
        SELECT r.* FROM reps r
        INNER JOIN sets s ON r.setId = s.id
        WHERE s.exerciseType = :exerciseType AND s.isManual = 0
        ORDER BY r.peakVelocity DESC
        LIMIT 1
    """)
    suspend fun getFastestRep(exerciseType: ExerciseType): RepEntity?

    @Query("""
        SELECT r.* FROM reps r
        INNER JOIN sets s ON r.setId = s.id
        WHERE s.exerciseType = :exerciseType AND s.isManual = 0
        ORDER BY r.qualityScore DESC
        LIMIT 1
    """)
    suspend fun getBestQualityRep(exerciseType: ExerciseType): RepEntity?
}
