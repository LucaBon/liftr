package com.liftr.data.repository

import com.liftr.data.local.database.dao.RepDao
import com.liftr.data.local.database.dao.SetDao
import com.liftr.data.local.database.dao.WorkoutDao
import com.liftr.data.local.database.entities.RepEntity
import com.liftr.data.local.database.entities.SetEntity
import com.liftr.data.mapper.RepMapper
import com.liftr.data.mapper.SetMapper
import com.liftr.data.mapper.WorkoutMapper
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.RepJumpData
import com.liftr.domain.model.TimeRange
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Jump reps carry their set's load and date, for the force-velocity profile. */
class AnalyticsRepositoryJumpRepsTest {

    private val setDao = mockk<SetDao>()
    private val repDao = mockk<RepDao>()
    private val repository = AnalyticsRepositoryImpl(
        setDao, repDao, mockk<WorkoutDao>(), SetMapper(), RepMapper(), WorkoutMapper()
    )

    private fun set(id: Long, weight: Float, timestamp: Long, jumpHeight: Float? = null) = SetEntity(
        id = id, workoutId = 1, exerciseType = ExerciseType.LOADED_JUMP, weight = weight,
        targetReps = 3, completedReps = 3, avgVelocity = 0f, peakVelocity = 0f,
        jumpHeight = jumpHeight, timestamp = timestamp
    )

    private fun rep(setId: Long, repNumber: Int, jumpData: RepJumpData?) = RepEntity(
        setId = setId, repNumber = repNumber, peakVelocity = 0f, avgVelocity = 0f, timeUnderTension = 0f,
        rangeOfMotion = 0f, startPosition = 0f, endPosition = 0f, eccentricDuration = 0f,
        concentricDuration = 0f, velocityProfile = emptyList(), positionProfile = emptyList(),
        timestamp = 0L, jumpData = jumpData
    )

    @Test
    fun `each jump carries its set's load and timestamp`() = runTest {
        coEvery { setDao.getSetsForExerciseInRange(ExerciseType.LOADED_JUMP, 0L) } returns
            listOf(set(2, 20f, 2_000L), set(1, 0f, 1_000L), set(3, 40f, 3_000L, jumpHeight = 0.25f))
        coEvery { repDao.getRepsForSetSync(1) } returns listOf(rep(1, 1, RepJumpData(40f)))
        coEvery { repDao.getRepsForSetSync(2) } returns listOf(rep(2, 2, RepJumpData(32f)), rep(2, 1, RepJumpData(30f)))
        // Saved before per-jump data: only the set's best height.
        coEvery { repDao.getRepsForSetSync(3) } returns listOf(rep(3, 1, null))

        val jumps = repository.getJumpReps(ExerciseType.LOADED_JUMP, TimeRange.ALL)

        assertEquals(listOf(0f, 20f, 20f, 40f), jumps.map { it.loadKg })
        assertEquals(listOf(1_000L, 2_000L, 2_000L, 3_000L), jumps.map { it.timestamp })
        assertEquals(listOf(1, 1, 2, 1), jumps.map { it.repNumber })
        assertEquals(25f, jumps.last().jump.flightHeightCm, 1e-4f)
    }
}
