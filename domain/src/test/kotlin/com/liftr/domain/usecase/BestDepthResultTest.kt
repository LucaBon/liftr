package com.liftr.domain.usecase

import com.liftr.domain.model.DepthValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BestDepthResultTest {

    private fun miss(cm: Float?, confidence: Float = 0.2f) =
        DepthValidationResult(isValid = false, confidence = confidence, methods = emptyList(), shortfallCm = cm)

    private val pass = DepthValidationResult(isValid = true, confidence = 0.8f, methods = emptyList())

    @Test
    fun `a frame that reaches depth wins`() {
        assertEquals(pass, bestDepthResult(listOf(miss(5f), pass, miss(1f))))
    }

    @Test
    fun `among equally confident misses the deepest frame counts, not the first`() {
        assertEquals(1.8f, bestDepthResult(listOf(miss(5.9f), miss(3f), miss(1.8f), miss(4f)))!!.shortfallCm!!, 0f)
    }

    @Test
    fun `a less confident frame that is deeper still counts`() {
        assertEquals(1f, bestDepthResult(listOf(miss(5f, 0.5f), miss(1f, 0.2f)))!!.shortfallCm!!, 0f)
    }

    @Test
    fun `without shortfalls the most confident miss counts`() {
        assertEquals(0.5f, bestDepthResult(listOf(miss(null, 0.2f), miss(null, 0.5f)))!!.confidence, 0f)
    }

    @Test
    fun `no frames give no verdict`() {
        assertNull(bestDepthResult(emptyList()))
    }
}
