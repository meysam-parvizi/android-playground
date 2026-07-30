package com.playground.cfscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-screen UI: choose how many IPs to scan, press scan, watch ranked results
 * appear.
 *
 * The scan runs on background dispatchers inside [ScanEngine]; this class only
 * renders. Ranking happens off the main thread because sorting on every hit was
 * enough to make the app unresponsive.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusText: TextView
    private lateinit var subStatusText: TextView
    private lateinit var healthyBadge: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var scanButton: MaterialButton
    private lateinit var copyButton: MaterialButton
    private lateinit var countInput: MaterialAutoCompleteTextView
    private lateinit var sortInput: MaterialAutoCompleteTextView
    private lateinit var iranModeSwitch: MaterialSwitch
    private lateinit var resultsHeader: TextView
    private lateinit var emptyState: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var resultAdapter: ResultAdapter

    private var scanJob: Job? = null
    private var resortJob: Job? = null
    private val found = mutableListOf<ScanResult>()
    private var hasScanned = false

    private val countOptions = listOf(100, 200, 300, 500, 800)
    private var selectedCountIndex = 2   // default 300
    private var selectedSortIndex = 0    // default SCORE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setUpToolbar()
        setUpDropdowns()
        setUpList()

        scanButton.setOnClickListener { if (scanJob?.isActive == true) stopScan() else startScan() }
        copyButton.setOnClickListener { copyResults() }

        renderEmptyState()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        statusText = findViewById(R.id.statusText)
        subStatusText = findViewById(R.id.subStatusText)
        healthyBadge = findViewById(R.id.healthyBadge)
        progressBar = findViewById(R.id.progressBar)
        scanButton = findViewById(R.id.scanButton)
        copyButton = findViewById(R.id.copyButton)
        countInput = findViewById(R.id.countInput)
        sortInput = findViewById(R.id.sortInput)
        iranModeSwitch = findViewById(R.id.iranModeSwitch)
        resultsHeader = findViewById(R.id.resultsHeader)
        emptyState = findViewById(R.id.emptyState)
        emptyTitle = emptyState.findViewById(R.id.emptyTitle)
        emptyHint = emptyState.findViewById(R.id.emptyHint)
    }

    private fun setUpToolbar() {
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_about) {
                showAbout()
                true
            } else {
                false
            }
        }
    }

    private fun setUpDropdowns() {
        // Bare Persian numerals: "۳۰۰ آی‌پی" reordered badly in the RTL field, and
        // the hint label already says what the number counts.
        val counts = countOptions.map { Format.number(it) }
        countInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, counts),
        )
        countInput.setText(counts[selectedCountIndex], false)
        countInput.setOnItemClickListener { _, _, position, _ -> selectedCountIndex = position }

        val sorts = SortBy.entries.map { it.label }
        sortInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, sorts),
        )
        sortInput.setText(sorts[selectedSortIndex], false)
        sortInput.setOnItemClickListener { _, _, position, _ ->
            selectedSortIndex = position
            // User-driven, so re-rank at once rather than after the debounce.
            resort(immediate = true)
        }
    }

    private fun setUpList() {
        resultAdapter = ResultAdapter(onRowClick = { copySingle(it) })
        findViewById<RecyclerView>(R.id.resultList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = resultAdapter
            // The list lives inside a NestedScrollView. Left at wrap_content it
            // would lay out every row at full height and recycle nothing, which
            // is what made scrolling stutter during a scan. Fixing the height and
            // letting it scroll itself restores recycling.
            isNestedScrollingEnabled = false
            setHasFixedSize(false)
            // Rows are uniform, so keeping a few extra off-screen holders avoids
            // re-inflating them as results stream in.
            setItemViewCacheSize(12)
            // Ranks shift constantly while scanning; animating every move is
            // both distracting and needless main-thread work.
            itemAnimator = null
        }
    }

    private fun currentSort(): SortBy =
        SortBy.entries.getOrElse(selectedSortIndex) { SortBy.SCORE }

    /**
     * Re-ranks and redraws the visible list, coalescing bursts of results.
     *
     * Sorting runs on [Dispatchers.Default] because doing it inline on every hit
     * stalled the main thread. On top of that, hits arrive in bursts, so a short
     * debounce collapses them into one sort plus one diff — re-ranking per hit is
     * what made the list stutter while scrolling.
     *
     * @param immediate skip the debounce, for user-driven changes like switching
     *   the sort criterion where any delay feels like lag.
     */
    private fun resort(immediate: Boolean = false) {
        if (found.isEmpty()) {
            renderEmptyState()
            renderResultsHeader()
            return
        }
        val criterion = currentSort()

        resortJob?.cancel()
        resortJob = lifecycleScope.launch {
            if (!immediate) delay(RESORT_DEBOUNCE_MS)
            val snapshot = found.toList()
            val ranked = withContext(Dispatchers.Default) { Ranking.sort(snapshot, criterion) }
            resultAdapter.submit(ranked)
            renderEmptyState()
            renderResultsHeader()
        }
    }

    private fun startScan() {
        found.clear()
        resultAdapter.clear()
        hasScanned = true
        copyButton.isEnabled = false
        progressBar.progress = 0
        healthyBadge.visibility = View.GONE
        subStatusText.visibility = View.GONE
        scanButton.setText(R.string.action_stop)
        scanButton.setIconResource(R.drawable.ic_stop)
        setControlsEnabled(false)
        renderEmptyState()
        renderResultsHeader()

        val count = countOptions.getOrElse(selectedCountIndex) { 300 }
        val iranMode = iranModeSwitch.isChecked

        val config = ScanConfig(
            targetCount = count,
            preferIranFriendlyRanges = iranMode,
            // The idle hold proves an IP survives DPI, so keep it generous in
            // restricted-network mode and quick otherwise.
            idleHoldMs = if (iranMode) 2500 else 1200,
            testWebSocket = iranMode,
        )

        statusText.setText(R.string.status_scanning)

        scanJob = lifecycleScope.launch {
            try {
                val all = ScanEngine(config).scan(
                    onProgress = { p -> renderProgress(p) },
                    onResult = { r ->
                        found.add(r)
                        copyButton.isEnabled = true
                        // Hide the empty block the instant a result exists, so the
                        // screen never claims nothing was found while listing hits.
                        emptyState.visibility = View.GONE
                        resort() // debounced; sorting happens off the main thread
                    },
                )
                val healthy = Ranking.healthy(all)
                // Flush any debounced re-rank so the final list is complete.
                resort(immediate = true)
                statusText.setText(R.string.status_done)
                subStatusText.text = getString(
                    R.string.status_done_detail,
                    Format.number(all.size),
                    Format.number(healthy.size),
                )
                subStatusText.visibility = View.VISIBLE
                progressBar.progress = 100
            } catch (_: CancellationException) {
                statusText.setText(R.string.status_stopped)
            } catch (_: Exception) {
                statusText.setText(R.string.status_error)
            } finally {
                scanButton.setText(R.string.action_scan)
                scanButton.setIconResource(R.drawable.ic_radar)
                setControlsEnabled(true)
                renderEmptyState()
            }
        }
    }

    private fun renderProgress(p: ScanProgress) {
        progressBar.progress =
            if (p.total > 0) ((p.probed * 100) / p.total).coerceIn(0, 100) else 0
        subStatusText.text = getString(
            R.string.status_progress,
            Format.number(p.probed),
            Format.number(p.total),
        )
        subStatusText.visibility = View.VISIBLE
        if (p.healthy > 0) {
            healthyBadge.text = getString(R.string.status_healthy_badge, Format.number(p.healthy))
            healthyBadge.visibility = View.VISIBLE
        }
    }

    private fun renderResultsHeader() {
        resultsHeader.text = if (found.isEmpty()) {
            getString(R.string.label_results)
        } else {
            getString(R.string.label_results_count, Format.number(found.size))
        }
    }

    /**
     * Shows the right empty message, or hides the block once there are rows.
     *
     * The "found nothing" wording is only ever shown after a scan has actually
     * finished. While a scan is running the block is hidden the moment the first
     * result lands, and before that it shows the neutral prompt — otherwise the
     * screen claimed no IPs were found while listing several of them.
     */
    private fun renderEmptyState() {
        if (found.isNotEmpty()) {
            emptyState.visibility = View.GONE
            return
        }
        emptyState.visibility = View.VISIBLE
        val scanning = scanJob?.isActive == true
        if (hasScanned && !scanning) {
            emptyTitle.setText(R.string.empty_none_found)
            emptyHint.setText(R.string.empty_none_found_hint)
        } else {
            emptyTitle.setText(R.string.empty_title)
            emptyHint.setText(R.string.empty_hint)
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        countInput.isEnabled = enabled
        iranModeSwitch.isEnabled = enabled
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanButton.setText(R.string.action_scan)
        scanButton.setIconResource(R.drawable.ic_radar)
        statusText.setText(R.string.status_stopped)
        setControlsEnabled(true)
        renderEmptyState()
    }

    private fun copyResults() {
        val items = resultAdapter.currentItems()
        if (items.isEmpty()) {
            snack(getString(R.string.toast_nothing))
            return
        }
        copyToClipboard(resultAdapter.exportText())
        snack(getString(R.string.toast_copied, Format.number(items.size)))
    }

    /** Tapping one row copies just that address — handy for a quick single test. */
    private fun copySingle(r: ScanResult) {
        copyToClipboard(r.ip)
        snack(getString(R.string.toast_copied_one, Format.ip(r.ip)))
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("clean-cf-ips", text))
    }

    private fun snack(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_body)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        resortJob?.cancel()
        super.onDestroy()
    }

    private companion object {
        /**
         * Debounce window for re-ranking while results stream in.
         *
         * Long enough to collapse a burst of hits into a single sort and diff,
         * short enough that the list still feels live.
         */
        const val RESORT_DEBOUNCE_MS = 250L
    }
}
