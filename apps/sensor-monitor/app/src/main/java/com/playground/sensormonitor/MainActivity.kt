package com.playground.sensormonitor

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Single screen showing live raw sensor values.
 *
 * Sensor callbacks arrive far faster than a screen can usefully render — the
 * accelerometer alone can fire tens of times a second — so the newest reading is
 * kept and the views are refreshed on a fixed cadence. Binding on every callback
 * would burn main-thread time redrawing frames nobody can read, and would make
 * the digits blur rather than inform.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var reader: SensorReader
    private lateinit var statusText: TextView
    private lateinit var rateText: TextView
    private lateinit var liveDot: View
    private lateinit var pauseButton: MaterialButton

    private lateinit var cards: Map<SensorKind, SensorCardViews>

    private var state = MonitorState()

    /** Refresh cadence: fast enough to look live, slow enough to stay readable. */
    private val refreshIntervalMs = 100L

    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderReadings()
            if (state.streaming) {
                pauseButton.postDelayed(this, refreshIntervalMs)
            }
        }
    }

    /** The views inside one included sensor card. */
    private class SensorCardViews(root: View) {
        val title: TextView = root.findViewById(R.id.sensorTitle)
        val unit: TextView = root.findViewById(R.id.sensorUnit)
        val axisContainer: View = root.findViewById(R.id.axisContainer)
        val rowY: View = root.findViewById(R.id.rowY)
        val rowZ: View = root.findViewById(R.id.rowZ)
        val labelX: TextView = root.findViewById(R.id.labelX)
        val labelY: TextView = root.findViewById(R.id.labelY)
        val labelZ: TextView = root.findViewById(R.id.labelZ)
        val valueX: TextView = root.findViewById(R.id.valueX)
        val valueY: TextView = root.findViewById(R.id.valueY)
        val valueZ: TextView = root.findViewById(R.id.valueZ)
        val magnitude: TextView = root.findViewById(R.id.magnitudeText)
        val placeholder: TextView = root.findViewById(R.id.placeholderText)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_about) {
                showAbout(); true
            } else {
                false
            }
        }

        statusText = findViewById(R.id.statusText)
        rateText = findViewById(R.id.rateText)
        liveDot = findViewById(R.id.liveDot)
        pauseButton = findViewById(R.id.pauseButton)

        cards = mapOf(
            SensorKind.ACCELEROMETER to SensorCardViews(findViewById<MaterialCardView>(R.id.cardAccelerometer)),
            SensorKind.GYROSCOPE to SensorCardViews(findViewById<MaterialCardView>(R.id.cardGyroscope)),
            SensorKind.TEMPERATURE to SensorCardViews(findViewById<MaterialCardView>(R.id.cardTemperature)),
        )

        setUpStaticLabels()

        pauseButton.setOnClickListener { toggleStreaming() }

        reader = SensorReader(this).apply {
            onReading = { reading ->
                // Store only; the periodic refresh does the drawing.
                if (state.streaming) state = state.withReading(reading)
            }
            onUnavailable = { kind ->
                state = state.markUnavailable(kind)
                renderReadings()
            }
        }

        renderStatus()
        renderReadings()
    }

    /** Titles, units and axis labels never change, so they are set once. */
    private fun setUpStaticLabels() {
        cards[SensorKind.ACCELEROMETER]?.apply {
            title.setText(R.string.sensor_accelerometer)
            unit.setText(R.string.unit_accelerometer)
        }
        cards[SensorKind.GYROSCOPE]?.apply {
            title.setText(R.string.sensor_gyroscope)
            unit.setText(R.string.unit_gyroscope)
        }
        cards[SensorKind.TEMPERATURE]?.apply {
            title.setText(R.string.sensor_temperature)
            unit.setText(R.string.unit_temperature)
        }

        for ((kind, card) in cards) {
            if (kind.axisCount == 3) {
                card.labelX.setText(R.string.axis_x)
                card.labelY.setText(R.string.axis_y)
                card.labelZ.setText(R.string.axis_z)
            } else {
                // A scalar sensor has one figure, so the X row is relabelled and
                // the other two are removed rather than shown empty.
                card.labelX.setText(R.string.axis_value)
                card.labelX.setTextColor(card.magnitude.currentTextColor)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (state.streaming) {
            reader.start()
            scheduleRefresh()
        }
    }

    override fun onPause() {
        // Always unregister when leaving the foreground: a sensor left running in
        // the background drains the battery for readings nobody is looking at.
        reader.stop()
        pauseButton.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun scheduleRefresh() {
        pauseButton.removeCallbacks(refreshRunnable)
        pauseButton.postDelayed(refreshRunnable, refreshIntervalMs)
    }

    private fun toggleStreaming() {
        state = state.copy(streaming = !state.streaming)
        if (state.streaming) {
            reader.start()
            scheduleRefresh()
        } else {
            reader.stop()
            pauseButton.removeCallbacks(refreshRunnable)
        }
        renderStatus()
        renderReadings()
    }

    private fun renderStatus() {
        val live = state.streaming
        statusText.setText(if (live) R.string.status_live else R.string.status_paused)
        pauseButton.setText(if (live) R.string.action_pause else R.string.action_resume)
        pauseButton.setIconResource(if (live) R.drawable.ic_pause else R.drawable.ic_play)
        liveDot.background?.mutate()?.setTint(
            getColor(if (live) R.color.live_green else R.color.paused_grey),
        )
    }

    private fun renderReadings() {
        for ((kind, card) in cards) {
            when (val s = state.stateFor(kind)) {
                is SensorState.Unavailable -> showPlaceholder(
                    card,
                    if (kind == SensorKind.TEMPERATURE) {
                        R.string.placeholder_unavailable_temperature
                    } else {
                        R.string.placeholder_unavailable
                    },
                )
                is SensorState.WaitingForFirstReading ->
                    showPlaceholder(card, R.string.placeholder_waiting)
                is SensorState.Live -> showReading(card, kind, s.reading)
            }
        }
    }

    private fun showPlaceholder(card: SensorCardViews, textRes: Int) {
        card.axisContainer.visibility = View.GONE
        card.magnitude.visibility = View.GONE
        card.placeholder.visibility = View.VISIBLE
        card.placeholder.setText(textRes)
    }

    private fun showReading(card: SensorCardViews, kind: SensorKind, reading: SensorReading) {
        card.placeholder.visibility = View.GONE
        card.axisContainer.visibility = View.VISIBLE

        card.valueX.text = Format.reading(reading.values.getOrElse(0) { 0f })

        if (kind.axisCount == 3) {
            card.rowY.visibility = View.VISIBLE
            card.rowZ.visibility = View.VISIBLE
            card.valueY.text = Format.reading(reading.values.getOrElse(1) { 0f })
            card.valueZ.text = Format.reading(reading.values.getOrElse(2) { 0f })

            card.magnitude.visibility = View.VISIBLE
            card.magnitude.text = getString(R.string.magnitude, Format.reading(reading.magnitude()))
        } else {
            card.rowY.visibility = View.GONE
            card.rowZ.visibility = View.GONE
            // Magnitude of a single value is that value, so showing it twice
            // would add noise rather than information.
            card.magnitude.visibility = View.GONE
        }
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_body)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }
}
