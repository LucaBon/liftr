package com.liftr.domain.calculator

import com.liftr.domain.model.JumpRepRecord
import com.liftr.domain.model.RepJumpData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Samozino jump force-velocity profile: the fit, its rejections and the optimal slope. */
class ForceVelocityProfileTest {

    private val bodyMass = 80f

    /** A jump at [loadKg] with mean force [relativeForce] N/kg of body mass at [velocity]. */
    private fun jump(loadKg: Float, relativeForce: Float, velocity: Float, pushOffCm: Float = 40f) = JumpRepRecord(
        timestamp = 0L,
        setId = 1L,
        repNumber = 1,
        jump = RepJumpData(
            flightHeightCm = 30f,
            pushOffCm = pushOffCm,
            meanForceN = relativeForce * bodyMass,
            meanVelocity = velocity,
            meanPowerW = relativeForce * bodyMass * velocity,
            systemMassKg = bodyMass + loadKg
        ),
        loadKg = loadKg
    )

    /** Jumps exactly on F = [f0] + [sfv]·v, one per (load, velocity). */
    private fun onLine(f0: Float, sfv: Float, vararg loadAndVelocity: Pair<Float, Float>, pushOffCm: Float = 40f) =
        loadAndVelocity.map { (load, v) -> jump(load, f0 + sfv * v, v, pushOffCm) }

    private fun fitted(jumps: List<JumpRepRecord>) =
        (ForceVelocityProfile.fit(jumps) as ForceVelocityProfileResult.Fitted).profile

    // --- Fit ---

    @Test
    fun `points on a known line recover F0 V0 Pmax and a perfect fit`() {
        val p = fitted(onLine(30f, -10f, 60f to 0.8f, 40f to 1.0f, 20f to 1.2f, 0f to 1.4f))

        assertEquals(30f, p.f0, 1e-3f)
        assertEquals(-10f, p.sfv, 1e-3f)
        assertEquals(3f, p.v0, 1e-3f)
        assertEquals(22.5f, p.pmax, 1e-3f)
        assertEquals(1f, p.rSquared, 1e-5f)
        assertEquals(4, p.points.size)
        assertFalse(p.lowConfidence)
    }

    @Test
    fun `noisy points fit worse and are low confidence`() {
        // F = 30 − 10·v, ±1 N/kg: R² = 1 − 4/24
        val p = fitted(listOf(jump(60f, 23f, 0.8f), jump(40f, 19f, 1.0f), jump(20f, 17f, 1.2f), jump(0f, 17f, 1.4f)))

        assertEquals(1f - 4f / 24f, p.rSquared, 1e-4f)
        assertEquals(-10f, p.sfv, 1e-3f)
        assertTrue(p.rSquared < ForceVelocityProfile.MIN_R_SQUARED)
        assertTrue(p.lowConfidence)
    }

    @Test
    fun `two loads fit but are low confidence while three good loads are not`() {
        assertTrue(fitted(onLine(30f, -10f, 40f to 1.0f, 0f to 1.4f)).lowConfidence)
        assertFalse(fitted(onLine(30f, -10f, 40f to 1.0f, 20f to 1.2f, 0f to 1.4f)).lowConfidence)
    }

    @Test
    fun `force is per kilogram of body mass, not of system mass`() {
        val p = fitted(onLine(30f, -10f, 40f to 1.0f, 0f to 1.4f))

        assertEquals(20f, p.points.single { it.loadKg == 40f }.relativeForce, 1e-4f)
    }

    // --- Rejections ---

    @Test
    fun `fewer than two loads is rejected`() {
        val oneLoad = onLine(30f, -10f, 20f to 1.2f, 20f to 1.1f, 20f to 1.0f)
        val result = ForceVelocityProfile.fit(oneLoad)

        assertEquals(ForceVelocityProfileResult.Rejected(ForceVelocityRejection.TOO_FEW_LOADS, 1), result)
        assertNull(result.profileOrNull)
        assertNull(ForceVelocityProfile.fit(emptyList()).profileOrNull)
    }

    @Test
    fun `jumps without push-off mechanics are ignored`() {
        val bare = JumpRepRecord(0L, 2L, 1, RepJumpData(flightHeightCm = 35f), loadKg = 0f)
        val result = ForceVelocityProfile.fit(listOf(bare) + onLine(30f, -10f, 40f to 1.0f))

        assertEquals(ForceVelocityRejection.TOO_FEW_LOADS, (result as ForceVelocityProfileResult.Rejected).reason)
    }

    @Test
    fun `a positive slope is rejected with a reason`() {
        val result = ForceVelocityProfile.fit(onLine(15f, 5f, 40f to 1.0f, 0f to 1.4f))

        assertEquals(ForceVelocityRejection.NON_NEGATIVE_SLOPE, (result as ForceVelocityProfileResult.Rejected).reason)
        assertNull(result.profileOrNull)
    }

    @Test
    fun `the same velocity at every load is rejected`() {
        val result = ForceVelocityProfile.fit(listOf(jump(40f, 20f, 1.0f), jump(0f, 15f, 1.0f)))

        assertEquals(ForceVelocityRejection.NON_NEGATIVE_SLOPE, (result as ForceVelocityProfileResult.Rejected).reason)
    }

    @Test
    fun `F0 at or below gravity is rejected with a reason`() {
        val result = ForceVelocityProfile.fit(onLine(9f, -1f, 40f to 1.0f, 0f to 2.0f))

        assertEquals(ForceVelocityRejection.FORCE_BELOW_GRAVITY, (result as ForceVelocityProfileResult.Rejected).reason)
    }

    // --- Points ---

    @Test
    fun `the best jump per load is the point`() {
        val jumps = onLine(30f, -10f, 40f to 1.0f, 0f to 1.4f) +
            jump(40f, 18f, 0.9f) + // slower jump at the same load: dropped
            jump(0f, 10f, 1.3f)    // slower bodyweight jump: dropped
        val p = fitted(jumps)

        assertEquals(2, p.points.size)
        assertEquals(1.0f, p.points.single { it.loadKg == 40f }.meanVelocity, 0f)
        assertEquals(1.4f, p.points.single { it.loadKg == 0f }.meanVelocity, 0f)
        assertEquals(30f, p.f0, 1e-3f)
    }

    @Test
    fun `bodyweight jumps logged as loaded jumps count as a point`() {
        val p = fitted(onLine(30f, -10f, 20f to 1.2f, 0f to 1.4f))

        assertEquals(listOf(0f, 20f), p.points.map { it.loadKg })
    }

    @Test
    fun `loads are grouped to the nearest half kilogram`() {
        assertEquals(20f, ForceVelocityProfile.roundLoad(20.2f), 0f)
        assertEquals(20f, ForceVelocityProfile.roundLoad(19.8f), 0f)
        assertEquals(20.5f, ForceVelocityProfile.roundLoad(20.3f), 0f)
        assertEquals(0f, ForceVelocityProfile.roundLoad(0.1f), 0f)

        val p = fitted(onLine(30f, -10f, 20.2f to 1.2f, 19.9f to 1.1f, 0f to 1.4f))
        assertEquals(listOf(0f, 20f), p.points.map { it.loadKg })
        assertEquals(1.2f, p.points.single { it.loadKg == 20f }.meanVelocity, 0f)
    }

    @Test
    fun `push-off for the optimum is the median of the points`() {
        val p = fitted(
            listOf(jump(40f, 20f, 1.0f, 30f), jump(20f, 18f, 1.2f, 40f), jump(0f, 16f, 1.4f, 45f))
        )

        assertEquals(0.40f, p.pushOffM, 1e-6f)
    }

    // --- Model ---

    @Test
    fun `predicted unloaded jump lies on the line by Samozino's simple method`() {
        // The height the model predicts, fed back through JumpMechanics, lands on F = F0 + SFV·v.
        val hPO = 0.40
        val h = ForceVelocityProfile.jumpHeight(sfv = -10.0, pmax = 22.5, pushOffM = hPO)
        val m = JumpMechanics.of(h.toFloat(), hPO.toFloat(), 1f)!!

        assertEquals(0.3158, h, 1e-3)
        assertEquals(30f - 10f * m.meanVelocity, m.relativeForce, 1e-3f)
    }

    @Test
    fun `no jump where F0 cannot lift the body`() {
        // F0 = 2·√(Pmax·|SFV|) = 2·√(20·1) ≈ 8.9 N/kg < g
        assertEquals(Double.NEGATIVE_INFINITY, ForceVelocityProfile.jumpHeight(-1.0, 20.0, 0.4), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, ForceVelocityProfile.jumpHeight(1.0, 20.0, 0.4), 0.0)
    }

    // --- Optimum ---

    private val pushOffs = listOf(0.30, 0.35, 0.40, 0.45)
    private val powers = listOf(20.0, 25.0, 30.0)

    @Test
    fun `SFVopt maximizes the unloaded jump`() {
        for (hPO in pushOffs) for (pmax in powers) {
            val opt = ForceVelocityProfile.optimalSlope(pmax, hPO)
            val best = ForceVelocityProfile.jumpHeight(opt, pmax, hPO)
            assertTrue(best >= ForceVelocityProfile.jumpHeight(opt * 0.95, pmax, hPO))
            assertTrue(best >= ForceVelocityProfile.jumpHeight(opt * 1.05, pmax, hPO))
            assertTrue(best >= ForceVelocityProfile.jumpHeight(opt * 0.999, pmax, hPO))
            assertTrue(best >= ForceVelocityProfile.jumpHeight(opt * 1.001, pmax, hPO))
        }
    }

    @Test
    fun `SFVopt matches a dense brute-force search`() {
        for (hPO in pushOffs) for (pmax in powers) {
            val brute = (1..40_000).map { -it / 1000.0 }.maxBy { ForceVelocityProfile.jumpHeight(it, pmax, hPO) }
            assertEquals(brute, ForceVelocityProfile.optimalSlope(pmax, hPO), 2e-3)
        }
    }

    @Test
    fun `SFVopt lies in the published range`() {
        for (hPO in pushOffs) for (pmax in powers) {
            val opt = ForceVelocityProfile.optimalSlope(pmax, hPO)
            assertTrue("SFVopt $opt for hPO $hPO, Pmax $pmax", opt in -20.0..-8.0)
        }
    }

    @Test
    fun `a shorter push-off needs a steeper optimal profile`() {
        for (pmax in powers) {
            val slopes = pushOffs.map { ForceVelocityProfile.optimalSlope(pmax, it) }
            slopes.zipWithNext().forEach { (shorter, longer) -> assertTrue(shorter < longer) }
        }
    }

    // --- Imbalance ---

    /** A fitted profile whose line has slope [ratio]·SFVopt at Pmax 25 W/kg and a 40 cm push-off. */
    private fun profileAt(ratio: Double): ForceVelocityProfile {
        val pmax = 25.0
        val sfv = ratio * ForceVelocityProfile.optimalSlope(pmax, 0.40)
        val f0 = 2.0 * sqrt(-pmax * sfv)
        return fitted(onLine(f0.toFloat(), sfv.toFloat(), 40f to 0.8f, 20f to 1.1f, 0f to 1.4f))
    }

    @Test
    fun `imbalance is zero at the optimum`() {
        val p = profileAt(1.0)

        assertEquals(25f, p.pmax, 1e-2f)
        assertEquals(0f, p.imbalancePercent, 0.05f)
        assertTrue(p.isWellBalanced)
        assertEquals(p.optimalHeightM, p.profileHeightM, 1e-4f)
    }

    @Test
    fun `a flatter profile than optimal is a force deficit`() {
        val p = profileAt(0.7)

        assertEquals(ForceVelocityDeficit.FORCE, p.deficit)
        assertEquals(30f, p.imbalancePercent, 0.1f)
        assertFalse(p.isWellBalanced)
        assertTrue(p.profileHeightM < p.optimalHeightM)
    }

    @Test
    fun `a steeper profile than optimal is a velocity deficit`() {
        val p = profileAt(1.3)

        assertEquals(ForceVelocityDeficit.VELOCITY, p.deficit)
        assertEquals(30f, p.imbalancePercent, 0.1f)
        assertTrue(p.profileHeightM < p.optimalHeightM)
    }

    @Test
    fun `deficit direction compares steepness`() {
        assertEquals(ForceVelocityDeficit.FORCE, ForceVelocityProfile.deficitOf(-8.0, -14.0))
        assertEquals(ForceVelocityDeficit.VELOCITY, ForceVelocityProfile.deficitOf(-20.0, -14.0))
    }
}
