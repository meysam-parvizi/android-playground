package com.playground.sensormonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the sensor state model.
 *
 * The distinction that matters here is between a sensor the device does not have
 * and one that simply has not reported yet. Collapsing the two would leave a
 * blank row, and the user could not tell a missing thermometer from a broken one.
 */
class SensorModelTest {

    private fun reading(kind: SensorKind, vararg values: Float) =
        SensorReading(kind = kind, values = values.toList().toFloatArray())

    @Test
    fun startsWaitingForEverySensor() {
        val s = MonitorState()
        for (kind in SensorKind.entries) {
            assertTrue(
                "$kind should start as waiting",
                s.stateFor(kind) is SensorState.WaitingForFirstReading,
            )
        }
        assertFalse(s.hasAnyLiveSensor)
    }

    @Test
    fun aReadingMakesOnlyItsOwnSensorLive() {
        val s = MonitorState().withReading(reading(SensorKind.ACCELEROMETER, 0f, 0f, 9.81f))

        assertTrue(s.accelerometer is SensorState.Live)
        // The others must be untouched, not reset or blanked.
        assertTrue(s.gyroscope is SensorState.WaitingForFirstReading)
        assertTrue(s.temperature is SensorState.WaitingForFirstReading)
        assertTrue(s.hasAnyLiveSensor)
    }

    @Test
    fun unavailableIsDistinctFromWaiting() {
        val s = MonitorState().markUnavailable(SensorKind.TEMPERATURE)

        assertTrue(s.temperature is SensorState.Unavailable)
        assertTrue(
            "marking one sensor absent must not affect the others",
            s.accelerometer is SensorState.WaitingForFirstReading,
        )
        assertFalse("an absent sensor is not a live one", s.hasAnyLiveSensor)
    }

    @Test
    fun laterReadingsReplaceEarlierOnes() {
        val s = MonitorState()
            .withReading(reading(SensorKind.GYROSCOPE, 1f, 1f, 1f))
            .withReading(reading(SensorKind.GYROSCOPE, 2f, 2f, 2f))

        val live = s.gyroscope as SensorState.Live
        assertEquals(2f, live.reading.values[0], 0.0001f)
    }

    @Test
    fun magnitudeOfAVectorIsItsLength() {
        // A phone lying still reads roughly one g on a single axis.
        val r = reading(SensorKind.ACCELEROMETER, 0f, 0f, 9.81f)
        assertEquals(9.81f, r.magnitude(), 0.001f)

        // 3-4-5 triangle, so the result is exactly 5.
        val r2 = reading(SensorKind.ACCELEROMETER, 3f, 4f, 0f)
        assertEquals(5f, r2.magnitude(), 0.0001f)
    }

    @Test
    fun magnitudeIsOrientationIndependent() {
        // However the phone is held, gravity's magnitude is the same. This is the
        // property that makes the figure worth showing at all.
        //
        // The tilted vector is gravity split equally across all three axes
        // (9.81/sqrt(3) each), so it represents the same 1g in a different
        // orientation rather than an arbitrary triple.
        val flat = reading(SensorKind.ACCELEROMETER, 0f, 0f, 9.81f)
        val tiltedThreeWay = reading(SensorKind.ACCELEROMETER, 5.66f, 5.66f, 5.66f)
        val onItsEdge = reading(SensorKind.ACCELEROMETER, 6.94f, 6.94f, 0f)

        assertEquals(flat.magnitude(), tiltedThreeWay.magnitude(), 0.01f)
        assertEquals(flat.magnitude(), onItsEdge.magnitude(), 0.01f)
    }

    @Test
    fun magnitudeOfAScalarIsItsAbsoluteValue() {
        assertEquals(23.5f, reading(SensorKind.TEMPERATURE, 23.5f).magnitude(), 0.0001f)
        // Below freezing, magnitude is a length and so never negative.
        assertEquals(4f, reading(SensorKind.TEMPERATURE, -4f).magnitude(), 0.0001f)
    }

    @Test
    fun axisCountsMatchTheSensorShape() {
        assertEquals(3, SensorKind.ACCELEROMETER.axisCount)
        assertEquals(3, SensorKind.GYROSCOPE.axisCount)
        assertEquals(1, SensorKind.TEMPERATURE.axisCount)
    }

    /**
     * FloatArray compares by reference by default, so without an explicit
     * equals two identical readings would be unequal and diffing would misfire.
     */
    @Test
    fun readingsWithTheSameValuesAreEqual() {
        val a = reading(SensorKind.GYROSCOPE, 0.1f, 0.2f, 0.3f)
        val b = reading(SensorKind.GYROSCOPE, 0.1f, 0.2f, 0.3f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val c = reading(SensorKind.GYROSCOPE, 0.1f, 0.2f, 0.4f)
        assertFalse(a == c)
    }

    @Test
    fun pausingIsSeparateFromSensorAvailability() {
        val s = MonitorState()
            .withReading(reading(SensorKind.ACCELEROMETER, 1f, 2f, 3f))
            .copy(streaming = false)

        // Pausing freezes updates; it must not discard the last known values.
        assertTrue(s.accelerometer is SensorState.Live)
        assertFalse(s.streaming)
    }
}
