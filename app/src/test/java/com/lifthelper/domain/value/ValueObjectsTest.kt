package com.lifthelper.domain.value

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KilogramsTest {

    @Test
    fun `should create Kilograms with valid positive value`() {
        // Given
        val value = 100f

        // When
        val weight = Kilograms(value)

        // Then
        assertEquals(100f, weight.value, 0.001f)
    }

    @Test
    fun `should create Kilograms with zero value`() {
        // Given
        val value = 0f

        // When
        val weight = Kilograms(value)

        // Then
        assertEquals(0f, weight.value, 0.001f)
    }

    @Test
    fun `should throw exception when creating Kilograms with negative value`() {
        // Given
        val value = -10f

        // When / Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Kilograms(value)
        }
        assertTrue(exception.message!!.contains("Weight cannot be negative"))
    }

    @Test
    fun `should add two Kilograms correctly`() {
        // Given
        val weight1 = Kilograms(50f)
        val weight2 = Kilograms(30f)

        // When
        val result = weight1 + weight2

        // Then
        assertEquals(80f, result.value, 0.001f)
    }

    @Test
    fun `should subtract two Kilograms correctly`() {
        // Given
        val weight1 = Kilograms(100f)
        val weight2 = Kilograms(30f)

        // When
        val result = weight1 - weight2

        // Then
        assertEquals(70f, result.value, 0.001f)
    }

    @Test
    fun `should multiply Kilograms by scalar correctly`() {
        // Given
        val weight = Kilograms(50f)
        val scalar = 2f

        // When
        val result = weight * scalar

        // Then
        assertEquals(100f, result.value, 0.001f)
    }

    @Test
    fun `should divide Kilograms by scalar correctly`() {
        // Given
        val weight = Kilograms(100f)
        val scalar = 2f

        // When
        val result = weight / scalar

        // Then
        assertEquals(50f, result.value, 0.001f)
    }

    @Test
    fun `should compare Kilograms correctly`() {
        // Given
        val weight1 = Kilograms(100f)
        val weight2 = Kilograms(50f)
        val weight3 = Kilograms(100f)

        // Then
        assertTrue(weight1 > weight2)
        assertTrue(weight2 < weight1)
        assertTrue(weight1 >= weight3)
        assertTrue(weight1 <= weight3)
    }
}

class MetersTest {

    @Test
    fun `should create Meters with valid positive value`() {
        // Given
        val value = 1.5f

        // When
        val distance = Meters(value)

        // Then
        assertEquals(1.5f, distance.value, 0.001f)
    }

    @Test
    fun `should create Meters with zero value`() {
        // Given
        val value = 0f

        // When
        val distance = Meters(value)

        // Then
        assertEquals(0f, distance.value, 0.001f)
    }

    @Test
    fun `should throw exception when creating Meters with negative value`() {
        // Given
        val value = -5f

        // When / Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Meters(value)
        }
        assertTrue(exception.message!!.contains("Distance cannot be negative"))
    }

    @Test
    fun `should add two Meters correctly`() {
        // Given
        val distance1 = Meters(1.5f)
        val distance2 = Meters(2.5f)

        // When
        val result = distance1 + distance2

        // Then
        assertEquals(4.0f, result.value, 0.001f)
    }

    @Test
    fun `should subtract two Meters correctly`() {
        // Given
        val distance1 = Meters(5.0f)
        val distance2 = Meters(2.0f)

        // When
        val result = distance1 - distance2

        // Then
        assertEquals(3.0f, result.value, 0.001f)
    }

    @Test
    fun `should multiply Meters by scalar correctly`() {
        // Given
        val distance = Meters(2.5f)
        val scalar = 3f

        // When
        val result = distance * scalar

        // Then
        assertEquals(7.5f, result.value, 0.001f)
    }

    @Test
    fun `should divide Meters by scalar correctly`() {
        // Given
        val distance = Meters(10.0f)
        val scalar = 2f

        // When
        val result = distance / scalar

        // Then
        assertEquals(5.0f, result.value, 0.001f)
    }

    @Test
    fun `should compare Meters correctly`() {
        // Given
        val distance1 = Meters(10.0f)
        val distance2 = Meters(5.0f)
        val distance3 = Meters(10.0f)

        // Then
        assertTrue(distance1 > distance2)
        assertTrue(distance2 < distance1)
        assertTrue(distance1 >= distance3)
        assertTrue(distance1 <= distance3)
    }
}

class MetersPerSecondTest {

    @Test
    fun `should create MetersPerSecond with positive value`() {
        // Given
        val value = 1.5f

        // When
        val velocity = MetersPerSecond(value)

        // Then
        assertEquals(1.5f, velocity.value, 0.001f)
    }

    @Test
    fun `should create MetersPerSecond with negative value`() {
        // Given
        val value = -2.0f

        // When
        val velocity = MetersPerSecond(value)

        // Then
        assertEquals(-2.0f, velocity.value, 0.001f)
    }

    @Test
    fun `should create MetersPerSecond with zero value`() {
        // Given
        val value = 0f

        // When
        val velocity = MetersPerSecond(value)

        // Then
        assertEquals(0f, velocity.value, 0.001f)
    }

    @Test
    fun `should calculate absolute value correctly for positive velocity`() {
        // Given
        val velocity = MetersPerSecond(1.5f)

        // When
        val result = velocity.abs()

        // Then
        assertEquals(1.5f, result.value, 0.001f)
    }

    @Test
    fun `should calculate absolute value correctly for negative velocity`() {
        // Given
        val velocity = MetersPerSecond(-2.5f)

        // When
        val result = velocity.abs()

        // Then
        assertEquals(2.5f, result.value, 0.001f)
    }

    @Test
    fun `should add two MetersPerSecond correctly`() {
        // Given
        val velocity1 = MetersPerSecond(1.5f)
        val velocity2 = MetersPerSecond(2.5f)

        // When
        val result = velocity1 + velocity2

        // Then
        assertEquals(4.0f, result.value, 0.001f)
    }

    @Test
    fun `should subtract two MetersPerSecond correctly`() {
        // Given
        val velocity1 = MetersPerSecond(5.0f)
        val velocity2 = MetersPerSecond(2.0f)

        // When
        val result = velocity1 - velocity2

        // Then
        assertEquals(3.0f, result.value, 0.001f)
    }

    @Test
    fun `should multiply MetersPerSecond by scalar correctly`() {
        // Given
        val velocity = MetersPerSecond(2.5f)
        val scalar = 3f

        // When
        val result = velocity * scalar

        // Then
        assertEquals(7.5f, result.value, 0.001f)
    }

    @Test
    fun `should divide MetersPerSecond by scalar correctly`() {
        // Given
        val velocity = MetersPerSecond(10.0f)
        val scalar = 2f

        // When
        val result = velocity / scalar

        // Then
        assertEquals(5.0f, result.value, 0.001f)
    }

    @Test
    fun `should negate MetersPerSecond correctly`() {
        // Given
        val velocity = MetersPerSecond(5.0f)

        // When
        val result = -velocity

        // Then
        assertEquals(-5.0f, result.value, 0.001f)
    }

    @Test
    fun `should compare MetersPerSecond correctly`() {
        // Given
        val velocity1 = MetersPerSecond(10.0f)
        val velocity2 = MetersPerSecond(5.0f)
        val velocity3 = MetersPerSecond(10.0f)

        // Then
        assertTrue(velocity1 > velocity2)
        assertTrue(velocity2 < velocity1)
        assertTrue(velocity1 >= velocity3)
        assertTrue(velocity1 <= velocity3)
    }
}

class WattsTest {

    @Test
    fun `should create Watts with valid positive value`() {
        // Given
        val value = 500f

        // When
        val power = Watts(value)

        // Then
        assertEquals(500f, power.value, 0.001f)
    }

    @Test
    fun `should create Watts with zero value`() {
        // Given
        val value = 0f

        // When
        val power = Watts(value)

        // Then
        assertEquals(0f, power.value, 0.001f)
    }

    @Test
    fun `should throw exception when creating Watts with negative value`() {
        // Given
        val value = -100f

        // When / Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Watts(value)
        }
        assertTrue(exception.message!!.contains("Power cannot be negative"))
    }

    @Test
    fun `should add two Watts correctly`() {
        // Given
        val power1 = Watts(300f)
        val power2 = Watts(200f)

        // When
        val result = power1 + power2

        // Then
        assertEquals(500f, result.value, 0.001f)
    }

    @Test
    fun `should subtract two Watts correctly`() {
        // Given
        val power1 = Watts(500f)
        val power2 = Watts(200f)

        // When
        val result = power1 - power2

        // Then
        assertEquals(300f, result.value, 0.001f)
    }

    @Test
    fun `should multiply Watts by scalar correctly`() {
        // Given
        val power = Watts(100f)
        val scalar = 5f

        // When
        val result = power * scalar

        // Then
        assertEquals(500f, result.value, 0.001f)
    }

    @Test
    fun `should divide Watts by scalar correctly`() {
        // Given
        val power = Watts(1000f)
        val scalar = 2f

        // When
        val result = power / scalar

        // Then
        assertEquals(500f, result.value, 0.001f)
    }

    @Test
    fun `should compare Watts correctly`() {
        // Given
        val power1 = Watts(1000f)
        val power2 = Watts(500f)
        val power3 = Watts(1000f)

        // Then
        assertTrue(power1 > power2)
        assertTrue(power2 < power1)
        assertTrue(power1 >= power3)
        assertTrue(power1 <= power3)
    }
}

class JoulesTest {

    @Test
    fun `should create Joules with valid positive value`() {
        // Given
        val value = 250f

        // When
        val energy = Joules(value)

        // Then
        assertEquals(250f, energy.value, 0.001f)
    }

    @Test
    fun `should create Joules with zero value`() {
        // Given
        val value = 0f

        // When
        val energy = Joules(value)

        // Then
        assertEquals(0f, energy.value, 0.001f)
    }

    @Test
    fun `should throw exception when creating Joules with negative value`() {
        // Given
        val value = -50f

        // When / Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Joules(value)
        }
        assertTrue(exception.message!!.contains("Energy cannot be negative"))
    }

    @Test
    fun `should add two Joules correctly`() {
        // Given
        val energy1 = Joules(150f)
        val energy2 = Joules(100f)

        // When
        val result = energy1 + energy2

        // Then
        assertEquals(250f, result.value, 0.001f)
    }

    @Test
    fun `should subtract two Joules correctly`() {
        // Given
        val energy1 = Joules(250f)
        val energy2 = Joules(100f)

        // When
        val result = energy1 - energy2

        // Then
        assertEquals(150f, result.value, 0.001f)
    }

    @Test
    fun `should multiply Joules by scalar correctly`() {
        // Given
        val energy = Joules(50f)
        val scalar = 5f

        // When
        val result = energy * scalar

        // Then
        assertEquals(250f, result.value, 0.001f)
    }

    @Test
    fun `should divide Joules by scalar correctly`() {
        // Given
        val energy = Joules(500f)
        val scalar = 2f

        // When
        val result = energy / scalar

        // Then
        assertEquals(250f, result.value, 0.001f)
    }

    @Test
    fun `should compare Joules correctly`() {
        // Given
        val energy1 = Joules(500f)
        val energy2 = Joules(250f)
        val energy3 = Joules(500f)

        // Then
        assertTrue(energy1 > energy2)
        assertTrue(energy2 < energy1)
        assertTrue(energy1 >= energy3)
        assertTrue(energy1 <= energy3)
    }
}

class RepCountTest {

    @Test
    fun `should create RepCount with valid positive value`() {
        // Given
        val value = 10

        // When
        val reps = RepCount(value)

        // Then
        assertEquals(10, reps.value)
    }

    @Test
    fun `should create RepCount with zero value`() {
        // Given
        val value = 0

        // When
        val reps = RepCount(value)

        // Then
        assertEquals(0, reps.value)
    }

    @Test
    fun `should throw exception when creating RepCount with negative value`() {
        // Given
        val value = -5

        // When / Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            RepCount(value)
        }
        assertTrue(exception.message!!.contains("Rep count cannot be negative"))
    }

    @Test
    fun `should increment RepCount correctly`() {
        // Given
        val reps = RepCount(5)

        // When
        val result = reps.inc()

        // Then
        assertEquals(6, result.value)
    }

    @Test
    fun `should decrement RepCount correctly`() {
        // Given
        val reps = RepCount(5)

        // When
        val result = reps.dec()

        // Then
        assertEquals(4, result.value)
    }

    @Test
    fun `should add two RepCount correctly`() {
        // Given
        val reps1 = RepCount(5)
        val reps2 = RepCount(3)

        // When
        val result = reps1 + reps2

        // Then
        assertEquals(8, result.value)
    }

    @Test
    fun `should subtract two RepCount correctly`() {
        // Given
        val reps1 = RepCount(10)
        val reps2 = RepCount(3)

        // When
        val result = reps1 - reps2

        // Then
        assertEquals(7, result.value)
    }

    @Test
    fun `should compare RepCount correctly`() {
        // Given
        val reps1 = RepCount(10)
        val reps2 = RepCount(5)
        val reps3 = RepCount(10)

        // Then
        assertTrue(reps1 > reps2)
        assertTrue(reps2 < reps1)
        assertTrue(reps1 >= reps3)
        assertTrue(reps1 <= reps3)
    }
}

class PercentageTest {

    @Test
    fun `should create Percentage with valid value in range`() {
        // Given
        val value = 50f

        // When
        val percentage = Percentage(value)

        // Then
        assertEquals(50f, percentage.value, 0.001f)
    }

    @Test
    fun `should create Percentage with zero value`() {
        // Given
        val value = 0f

        // When
        val percentage = Percentage(value)

        // Then
        assertEquals(0f, percentage.value, 0.001f)
    }

    @Test
    fun `should create Percentage with maximum value`() {
        // Given
        val value = 100f

        // When
        val percentage = Percentage(value)

        // Then
        assertEquals(100f, percentage.value, 0.001f)
    }

    @Test
    fun `should throw exception when creating Percentage with negative value`() {
        // Given
        val value = -10f

        // When / Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Percentage(value)
        }
        assertTrue(exception.message!!.contains("Percentage must be 0-100"))
    }

    @Test
    fun `should throw exception when creating Percentage with value over 100`() {
        // Given
        val value = 150f

        // When / Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Percentage(value)
        }
        assertTrue(exception.message!!.contains("Percentage must be 0-100"))
    }

    @Test
    fun `should add two Percentage correctly when result is in range`() {
        // Given
        val percentage1 = Percentage(30f)
        val percentage2 = Percentage(20f)

        // When
        val result = percentage1 + percentage2

        // Then
        assertEquals(50f, result.value, 0.001f)
    }

    @Test
    fun `should subtract two Percentage correctly when result is in range`() {
        // Given
        val percentage1 = Percentage(70f)
        val percentage2 = Percentage(20f)

        // When
        val result = percentage1 - percentage2

        // Then
        assertEquals(50f, result.value, 0.001f)
    }

    @Test
    fun `should multiply Percentage by scalar correctly when result is in range`() {
        // Given
        val percentage = Percentage(25f)
        val scalar = 2f

        // When
        val result = percentage * scalar

        // Then
        assertEquals(50f, result.value, 0.001f)
    }

    @Test
    fun `should divide Percentage by scalar correctly when result is in range`() {
        // Given
        val percentage = Percentage(100f)
        val scalar = 2f

        // When
        val result = percentage / scalar

        // Then
        assertEquals(50f, result.value, 0.001f)
    }

    @Test
    fun `should compare Percentage correctly`() {
        // Given
        val percentage1 = Percentage(75f)
        val percentage2 = Percentage(50f)
        val percentage3 = Percentage(75f)

        // Then
        assertTrue(percentage1 > percentage2)
        assertTrue(percentage2 < percentage1)
        assertTrue(percentage1 >= percentage3)
        assertTrue(percentage1 <= percentage3)
    }
}
