package com.lifthelper.data.local.database.dao

import androidx.room.*
import com.lifthelper.data.local.database.entities.RepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RepDao {
    @Insert
    suspend fun insertRep(rep: RepEntity): Long

    @Insert
    suspend fun insertReps(reps: List<RepEntity>)

    @Query("SELECT * FROM reps WHERE setId = :setId ORDER BY repNumber ASC")
    fun getRepsForSet(setId: Long): Flow<List<RepEntity>>

    @Query("SELECT AVG(avgVelocity) FROM reps WHERE setId = :setId")
    suspend fun getAverageVelocityForSet(setId: Long): Float?

    @Query("SELECT MAX(peakVelocity) FROM reps WHERE setId = :setId")
    suspend fun getPeakVelocityForSet(setId: Long): Float?
}
