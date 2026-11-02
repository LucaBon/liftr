package com.liftr.data.mapper

import com.liftr.domain.model.Rep
import com.liftr.domain.model.RepScoreParts
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepMapperScorePartsTest {

    private val mapper = RepMapper()

    private fun rep(parts: RepScoreParts?) = Rep(
        setId = 1, repNumber = 1,
        peakVelocity = MetersPerSecond(1f), avgVelocity = MetersPerSecond(0.8f),
        timeUnderTension = 2f, rangeOfMotion = Meters(0f),
        startPosition = Meters(0f), endPosition = Meters(0f),
        eccentricDuration = 1f, concentricDuration = 1f,
        velocityProfile = emptyList(), positionProfile = emptyList(),
        timestamp = 0L, qualityScore = 55, scoreParts = parts
    )

    @Test
    fun `score parts survive a round trip through the entity`() {
        val parts = RepScoreParts(depth = 30, barPath = 12, tempo = 13, form = 0)

        assertEquals(parts, mapper.toDomain(mapper.toEntity(rep(parts))).scoreParts)
    }

    @Test
    fun `reps saved without parts read back without parts`() {
        assertNull(mapper.toDomain(mapper.toEntity(rep(null))).scoreParts)
    }

    @Test
    fun `a partly stored set of parts counts as none`() {
        val entity = mapper.toEntity(rep(RepScoreParts(30, 12, 13, 0))).copy(formScore = null)

        assertNull(mapper.scoreParts(entity))
    }
}
