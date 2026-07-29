package com.playground.tapcounter

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * A single-screen app: one button in the middle of the screen.
 *
 * Every tap increments the counter shown above the button by 1.
 * When the counter is at 100, the next tap overflows it back to 1.
 */
class MainActivity : AppCompatActivity() {

    private var count = 0

    companion object {
        private const val MAX = 100
        private const val STATE_COUNT = "state_count"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Restore counter across rotation / process recreation.
        count = savedInstanceState?.getInt(STATE_COUNT, 0) ?: 0

        val counterText = findViewById<TextView>(R.id.counterText)
        val tapButton = findViewById<Button>(R.id.tapButton)

        render(counterText)

        tapButton.setOnClickListener {
            // At 100 the next tap overflows back to 1; otherwise +1.
            count = if (count >= MAX) 1 else count + 1
            render(counterText)
        }
    }

    private fun render(counterText: TextView) {
        counterText.text = count.toString()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_COUNT, count)
    }
}
