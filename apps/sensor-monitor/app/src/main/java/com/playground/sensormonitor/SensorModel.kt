package com.playground.sensormonitor

/**
 * The sensors this app reads.
 *
 * Kept as an enum rather than raw `Sensor.TYPE_*` ints so the UI, the reader and
 * the tests all refer to the same fixed set, and so a sensor's unit and axis
 * labels live next to its identity instead of being duplicated at each use.
 */
enum class SensorKind(
    /** Number of values a reading carries: 3 for vector sensors, 1 for scalar. */
    val axisCount: Int,
) {
    ACCELEROMETER(3),
    GYROSCOPE(3),
    TEMPERATURE(1),
}

/**
 * One reading from a sensor.
 *
 * [values] holds the raw figures exactly as the platform reported them — no
 * smoothing, filtering or unit conversion. The brief was raw data, and a
 * filtered value would silently misrepresent what the hardware actually
 * measured.
 */
data class SensorReading(
    val kind: SensorKind,
    val values: FloatArray,
    /** Nanosecond timestamp from the sensor event. */
    val timestampNs: Long = 0,
    /** Platform accuracy constant, or null when not reported. */
    val accuracy: Int? = null,
) {
    /** Magnitude of a vector reading; for scalar sensors this is the value itself. */
    fun magnitude(): Float {
        var sum = 0.0
        for (v in values) sum += v.toDouble() * v.toDouble()
        return kotlin.math.sqrt(sum).toFloat()
    }

    // FloatArray gives reference equality by default, which would make two
    // identical readings compare unequal and break test assertions.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensorReading) return false
        return kind == other.kind &&
            values.contentEquals(other.values) &&
            timestampNs == other.timestampNs &&
            accuracy == other.accuracy
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + values.contentHashCode()
        result = 31 * result + timestampNs.hashCode()
        result = 31 * result + (accuracy ?: 0)
        return result
    }
}

/**
 * What the UI knows about one sensor at a given moment.
 *
 * "Absent" is modelled explicitly rather than as a null reading. Ambient
 * temperature in particular is missing from a large share of phones, and showing
 * a blank row would leave the user unsure whether the sensor is broken, still
 * warming up, or simply not fitted.
 */
sealed interface SensorState {
    /** The device has no such sensor. */
    data object Unavailable : SensorState

    /** The sensor exists but has not reported a value yet. */
    data object WaitingForFirstReading : SensorState

    /** A live reading. */
    data class Live(val reading: SensorReading) : SensorState
}

/** Everything the screen renders. */
data class MonitorState(
    val accelerometer: SensorState = SensorState.WaitingForFirstReading,
    val gyroscope: SensorState = SensorState.WaitingForFirstReading,
    val temperature: SensorState = SensorState.WaitingForFirstReading,
    /** False while the user has paused the stream. */
    val streaming: Boolean = true,
) {
    fun stateFor(kind: SensorKind): SensorState = when (kind) {
        SensorKind.ACCELEROMETER -> accelerometer
        SensorKind.GYROSCOPE -> gyroscope
        SensorKind.TEMPERATURE -> temperature
    }

    fun withReading(reading: SensorReading): MonitorState = when (reading.kind) {
        SensorKind.ACCELEROMETER -> copy(accelerometer = SensorState.Live(reading))
        SensorKind.GYROSCOPE -> copy(gyroscope = SensorState.Live(reading))
        SensorKind.TEMPERATURE -> copy(temperature = SensorState.Live(reading))
    }

    fun markUnavailable(kind: SensorKind): MonitorState = when (kind) {
        SensorKind.ACCELEROMETER -> copy(accelerometer = SensorState.Unavailable)
        SensorKind.GYROSCOPE -> copy(gyroscope = SensorState.Unavailable)
        SensorKind.TEMPERATURE -> copy(temperature = SensorState.Unavailable)
    }

    /** True when at least one sensor is producing data. */
    val hasAnyLiveSensor: Boolean
        get() = listOf(accelerometer, gyroscope, temperature).any { it is SensorState.Live }
}
