package com.playground.sensormonitor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Streams raw readings from the device's sensors.
 *
 * Values are passed through exactly as the platform reports them — no
 * smoothing, no filtering, no unit conversion. The point of this app is to show
 * what the hardware actually measures, and any processing would quietly
 * misrepresent that.
 *
 * Registration is deliberately per-sensor: a device missing one sensor must
 * still stream the others, so a failure to find the thermometer cannot take the
 * accelerometer down with it.
 */
class SensorReader(context: Context) : SensorEventListener {

    private val manager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Invoked on the main thread for each reading. */
    var onReading: ((SensorReading) -> Unit)? = null

    /** Invoked once per sensor the device does not have. */
    var onUnavailable: ((SensorKind) -> Unit)? = null

    private var listening = false

    /**
     * Resolves each sensor, reporting the ones this device lacks.
     *
     * `TYPE_AMBIENT_TEMPERATURE` is absent on a large share of phones, so it is
     * treated as an expected absence rather than an error.
     */
    private fun sensorFor(kind: SensorKind): Sensor? {
        val type = when (kind) {
            SensorKind.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
            SensorKind.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
            SensorKind.TEMPERATURE -> Sensor.TYPE_AMBIENT_TEMPERATURE
        }
        return manager?.getDefaultSensor(type)
    }

    /**
     * Starts streaming.
     *
     * Uses `SENSOR_DELAY_UI` rather than the fastest rate: at `SENSOR_DELAY_FASTEST`
     * the accelerometer can fire hundreds of times per second, far beyond what a
     * screen can show or an eye can follow, and the surplus events cost battery
     * and main-thread time for no visible gain.
     */
    fun start() {
        if (listening) return
        val mgr = manager
        if (mgr == null) {
            // No sensor service at all: report every sensor as absent so the UI
            // explains itself instead of waiting forever for a first reading.
            SensorKind.entries.forEach { onUnavailable?.invoke(it) }
            return
        }

        for (kind in SensorKind.entries) {
            val sensor = sensorFor(kind)
            if (sensor == null) {
                onUnavailable?.invoke(kind)
                continue
            }
            mgr.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        listening = true
    }

    /** Stops streaming. Safe to call when already stopped. */
    fun stop() {
        if (!listening) return
        manager?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        val kind = kindFor(e.sensor?.type ?: return) ?: return

        // Copy the values: the platform reuses the event's array between
        // callbacks, so holding a reference would let earlier readings mutate
        // underneath the UI.
        val expected = kind.axisCount
        val copied = FloatArray(expected) { i -> e.values.getOrElse(i) { 0f } }

        onReading?.invoke(
            SensorReading(
                kind = kind,
                values = copied,
                timestampNs = e.timestamp,
                accuracy = e.accuracy,
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Accuracy arrives with each reading, so nothing extra is needed here.
    }

    private fun kindFor(type: Int): SensorKind? = when (type) {
        Sensor.TYPE_ACCELEROMETER -> SensorKind.ACCELEROMETER
        Sensor.TYPE_GYROSCOPE -> SensorKind.GYROSCOPE
        Sensor.TYPE_AMBIENT_TEMPERATURE -> SensorKind.TEMPERATURE
        else -> null
    }
}
