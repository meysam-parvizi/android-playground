package com.playground.cfscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single screen: controls, placeholder, and ranked results in one list.
 *
 * Responsibilities are deliberately narrow — this class owns the scan lifecycle
 * and the [HeaderState], while the adapters own all rendering and
 * [EmptyStateRules] owns the placeholder logic. Keeping those apart is what makes
 * it impossible for the placeholder to contradict the list, which was the source
 * of the "no healthy IP found" message appearing above real results.
 */
class MainActivity : AppCompatActivity(), HeaderAdapter.Callbacks {

    private lateinit var headerAdapter: HeaderAdapter
    private lateinit var emptyAdapter: EmptyStateAdapter
    private lateinit var resultAdapter: ResultAdapter

    private var scanJob: Job? = null
    private var resortJob: Job? = null

    private val found = mutableListOf<ScanResult>()
    private var state = HeaderState()

    private val countOptions = listOf(100, 200, 300, 500, 800)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_about) {
                showAbout()
                true
            } else {
                false
            }
        }

        headerAdapter = HeaderAdapter(
            countOptions = countOptions,
            sortLabels = SortBy.entries.map { it.label },
            callbacks = this,
        )
        emptyAdapter = EmptyStateAdapter()
        resultAdapter = ResultAdapter(onRowClick = ::copySingle)

        findViewById<RecyclerView>(R.id.contentList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            // Header, placeholder, and results as one scrolling list, so rows are
            // genuinely recycled instead of all being held in memory.
            adapter = ConcatAdapter(headerAdapter, emptyAdapter, resultAdapter)
            // Ranks shift constantly during a scan; animating every move is both
            // distracting and needless main-thread work.
            itemAnimator = null
            setItemViewCacheSize(12)
        }

        render()
    }

    // region rendering

    /** Pushes the current state into the adapters. */
    private fun render() {
        headerAdapter.update(state)
        emptyAdapter.show(EmptyStateRules.contentFor(state.phase, state.resultCount))
    }

    private fun setState(transform: (HeaderState) -> HeaderState) {
        state = transform(state)
        render()
    }

    // endregion

    // region header callbacks

    override fun onScanToggle() {
        if (state.isScanning) stopScan() else startScan()
    }

    override fun onCopy() {
        val items = resultAdapter.currentItems()
        if (items.isEmpty()) {
            snack(getString(R.string.toast_nothing))
            return
        }
        copyToClipboard(resultAdapter.exportText())
        snack(getString(R.string.toast_copied, Format.number(items.size)))
    }

    override fun onCountSelected(index: Int) = setState { it.copy(countIndex = index) }

    override fun onSortSelected(index: Int) {
        setState { it.copy(sortIndex = index) }
        // User-driven, so re-rank at once rather than after the debounce.
        resort(immediate = true)
    }

    override fun onIranModeChanged(enabled: Boolean) = setState { it.copy(iranMode = enabled) }

    // endregion

    // region scanning

    private fun currentSort(): SortBy =
        SortBy.entries.getOrElse(state.sortIndex) { SortBy.SCORE }

    private fun startScan() {
        found.clear()
        resultAdapter.submit(emptyList())

        val count = countOptions.getOrElse(state.countIndex) { 300 }
        setState {
            it.copy(phase = ScanPhase.SCANNING, probed = 0, total = count, healthy = 0, resultCount = 0)
        }

        val iranMode = state.iranMode
        val config = ScanConfig(
            targetCount = count,
            preferIranFriendlyRanges = iranMode,
            // The idle hold proves an IP survives DPI, so keep it generous in
            // restricted-network mode and quick otherwise.
            idleHoldMs = if (iranMode) 2500 else 1200,
            testWebSocket = iranMode,
        )

        scanJob = lifecycleScope.launch {
            try {
                val all = ScanEngine(config).scan(
                    onProgress = { p ->
                        setState {
                            it.copy(probed = p.probed, total = p.total, healthy = p.healthy)
                        }
                    },
                    onResult = { r ->
                        found.add(r)
                        setState { it.copy(resultCount = found.size) }
                        resort() // debounced; sorting happens off the main thread
                    },
                )
                // Flush any debounced re-rank so the final list is complete.
                resort(immediate = true)
                setState {
                    // Keep the counts the engine last reported: neighbour
                    // expansion means the number probed is not the number
                    // originally requested, and showing "334 of 300" is nonsense.
                    it.copy(
                        phase = ScanPhase.FINISHED,
                        probed = all.size,
                        total = all.size,
                        healthy = Ranking.healthy(all).size,
                        resultCount = found.size,
                    )
                }
            } catch (_: CancellationException) {
                setState { it.copy(phase = ScanPhase.STOPPED) }
            } catch (_: Exception) {
                setState { it.copy(phase = ScanPhase.ERROR) }
            }
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        setState { it.copy(phase = ScanPhase.STOPPED) }
    }

    /**
     * Re-ranks and redraws the results, coalescing bursts.
     *
     * Sorting runs on [Dispatchers.Default]; doing it inline on every hit stalled
     * the main thread. Hits also arrive in bursts, so a short debounce collapses
     * them into one sort plus one diff.
     *
     * @param immediate skip the debounce for user-driven changes, where any delay
     *   reads as lag.
     */
    private fun resort(immediate: Boolean = false) {
        resortJob?.cancel()
        if (found.isEmpty()) {
            resultAdapter.submit(emptyList())
            return
        }
        val criterion = currentSort()
        resortJob = lifecycleScope.launch {
            if (!immediate) delay(RESORT_DEBOUNCE_MS)
            val snapshot = found.toList()
            val ranked = withContext(Dispatchers.Default) { Ranking.sort(snapshot, criterion) }
            resultAdapter.submit(ranked)
        }
    }

    // endregion

    // region clipboard and dialogs

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

    // endregion

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
