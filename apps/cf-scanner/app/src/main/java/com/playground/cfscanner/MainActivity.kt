package com.playground.cfscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-screen UI: pick how many IPs to scan, press scan, watch ranked results
 * appear. Deliberately minimal.
 *
 * The scan itself runs on background dispatchers inside [ScanEngine]; this class
 * only renders. Ranking is done off the main thread because sorting on every hit
 * was enough to make the app unresponsive.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var scanButton: Button
    private lateinit var copyButton: Button
    private lateinit var countSpinner: Spinner
    private lateinit var sortSpinner: Spinner
    private lateinit var iranModeCheck: CheckBox
    private lateinit var resultAdapter: ResultAdapter

    private var scanJob: Job? = null
    private val found = mutableListOf<ScanResult>()

    private val countOptions = listOf(100, 200, 300, 500, 800)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        scanButton = findViewById(R.id.scanButton)
        copyButton = findViewById(R.id.copyButton)
        countSpinner = findViewById(R.id.countSpinner)
        sortSpinner = findViewById(R.id.sortSpinner)
        iranModeCheck = findViewById(R.id.iranModeCheck)

        resultAdapter = ResultAdapter()
        findViewById<RecyclerView>(R.id.resultList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = resultAdapter
            setHasFixedSize(false)
        }

        countSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            countOptions.map { "$it آی‌پی" },
        )
        countSpinner.setSelection(2) // default 300

        sortSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            SortBy.entries.map { it.label },
        )
        // Re-rank on a real selection event rather than guessing from a touch.
        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                resort()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        scanButton.setOnClickListener { if (scanJob?.isActive == true) stopScan() else startScan() }
        copyButton.setOnClickListener { copyResults() }
    }

    private fun currentSort(): SortBy =
        SortBy.entries.getOrElse(sortSpinner.selectedItemPosition) { SortBy.SCORE }

    /** Sorts on a background dispatcher, then swaps the list in on the main thread. */
    private fun resort() {
        if (found.isEmpty()) return
        val snapshot = found.toList()
        val criterion = currentSort()
        lifecycleScope.launch {
            val ranked = withContext(Dispatchers.Default) { Ranking.sort(snapshot, criterion) }
            resultAdapter.submit(ranked)
        }
    }

    private fun startScan() {
        found.clear()
        resultAdapter.clear()
        copyButton.isEnabled = false
        progressBar.progress = 0
        scanButton.setText(R.string.action_stop)
        setControlsEnabled(false)

        val count = countOptions.getOrElse(countSpinner.selectedItemPosition) { 300 }
        val iranMode = iranModeCheck.isChecked

        val config = ScanConfig(
            targetCount = count,
            preferIranFriendlyRanges = iranMode,
            // The idle hold proves an IP survives DPI, so keep it generous in
            // Iran mode and quick otherwise.
            idleHoldMs = if (iranMode) 2500 else 1200,
            testWebSocket = iranMode,
        )

        statusText.setText(R.string.status_scanning)

        scanJob = lifecycleScope.launch {
            try {
                val all = ScanEngine(config).scan(
                    onProgress = { p ->
                        progressBar.progress =
                            if (p.total > 0) ((p.probed * 100) / p.total).coerceIn(0, 100) else 0
                        statusText.text = getString(
                            R.string.status_progress, p.probed, p.total, p.healthy,
                        )
                    },
                    onResult = { r ->
                        found.add(r)
                        copyButton.isEnabled = true
                        // Sorting happens off the main thread inside resort().
                        resort()
                    },
                )
                val healthy = Ranking.healthy(all)
                statusText.text = getString(R.string.status_done, all.size, healthy.size)
            } catch (_: CancellationException) {
                statusText.setText(R.string.status_stopped)
            } catch (_: Exception) {
                statusText.setText(R.string.status_error)
            } finally {
                scanButton.setText(R.string.action_scan)
                setControlsEnabled(true)
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        countSpinner.isEnabled = enabled
        iranModeCheck.isEnabled = enabled
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanButton.setText(R.string.action_scan)
        statusText.setText(R.string.status_stopped)
        setControlsEnabled(true)
    }

    private fun copyResults() {
        if (found.isEmpty()) {
            Toast.makeText(this, R.string.toast_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("clean-cf-ips", resultAdapter.exportText()))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        super.onDestroy()
    }
}
