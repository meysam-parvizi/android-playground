package com.playground.cfscanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the scan and everything derived from it.
 *
 * Everything here used to live in the Activity, which meant a rotation, a
 * dark-mode toggle, or the app's own `recreate()` on a language switch destroyed
 * a scan that can take minutes, discarding every result with no warning. A
 * ViewModel outlives configuration changes, so the scan simply keeps running and
 * the rebuilt Activity re-attaches to it.
 *
 * The Activity keeps only view wiring; all scan state and policy is here, which
 * also makes it testable without an Android device.
 */
class ScanViewModel : ViewModel() {

    private val _state = MutableStateFlow(HeaderState())

    /** Header/status state, replayed to whichever Activity is currently attached. */
    val state: StateFlow<HeaderState> = _state.asStateFlow()

    private val _results = MutableStateFlow<List<ScanResult>>(emptyList())

    /** Ranked results, already sorted for display. */
    val results: StateFlow<List<ScanResult>> = _results.asStateFlow()

    /** Healthy IPs in arrival order; ranking is applied when they are published. */
    private val found = mutableListOf<ScanResult>()

    private var scanJob: Job? = null
    private var resortJob: Job? = null

    /**
     * Whether the VPN warning has been shown.
     *
     * Lives here so it survives a rotation: as an Activity field it reappeared
     * after every configuration change, contradicting its "once per session"
     * contract.
     */
    var vpnWarningShown = false
        private set

    fun markVpnWarningShown() {
        vpnWarningShown = true
    }

    /**
     * Whether the app left the foreground while a scan was running.
     *
     * The scan deliberately continues when backgrounded — it takes minutes, and
     * cancelling it every time a notification is pulled down would make the app
     * unusable. But Android may throttle background network access, and a probe
     * that times out because the OS deferred it is recorded as packet loss just
     * like a genuinely blocked address. Those measurements describe the
     * throttling, not the network, and can push a good IP down the ranking or
     * out of it entirely.
     *
     * Rather than silently ranking on them, the fact is recorded and surfaced so
     * the user knows the results may understate what is available.
     */
    private var interrupted = false

    /** True when the finished scan ran partly in the background. */
    val measurementInterrupted: Boolean get() = interrupted

    /** Called when the app stops being visible. */
    fun onEnteredBackground() {
        if (isScanning) interrupted = true
    }

    val isScanning: Boolean get() = _state.value.isScanning

    fun setCount(index: Int) = update { it.copy(countIndex = index) }

    fun setIranMode(enabled: Boolean) = update { it.copy(iranMode = enabled) }

    fun setSort(index: Int) {
        update { it.copy(sortIndex = index) }
        // User-driven, so re-rank at once rather than after the debounce.
        resort(immediate = true)
    }

    private fun update(transform: (HeaderState) -> HeaderState) {
        _state.value = transform(_state.value)
    }

    private fun currentSort(): SortBy =
        SortBy.entries.getOrElse(_state.value.sortIndex) { SortBy.SCORE }

    fun startScan(countOptions: List<Int>) {
        // Guard against a double tap racing two scans onto the same state.
        if (scanJob?.isActive == true) return

        found.clear()
        _results.value = emptyList()
        // Cleared per scan: an interruption during an earlier run says nothing
        // about this one.
        interrupted = false

        // Falls back to the first option so the fallback cannot silently disagree
        // with the default shown in the UI.
        val count = countOptions.getOrElse(_state.value.countIndex) { countOptions.first() }
        update {
            it.copy(
                phase = ScanPhase.SCANNING,
                probed = 0,
                total = count,
                healthy = 0,
                resultCount = 0,
                requested = count,
            )
        }

        val config = ScanConfig.forMode(count, iranMode = _state.value.iranMode)

        scanJob = viewModelScope.launch {
            try {
                val outcome = ScanEngine(config).scan(
                    onProgress = { p ->
                        update { it.copy(probed = p.probed, total = p.total, healthy = p.healthy) }
                    },
                    onResult = { r ->
                        found.add(r)
                        update { it.copy(resultCount = found.size) }
                        resort()
                    },
                )
                // Flush any debounced re-rank so the final list is complete.
                resort(immediate = true)
                publishFinished(outcome)
            } catch (ce: CancellationException) {
                // Do not report a phase here: cancellation happens both when the
                // user stops the scan (stopScan already sets STOPPED) and when the
                // ViewModel is cleared, where touching state is pointless.
                throw ce
            } catch (_: Exception) {
                update { it.copy(phase = ScanPhase.ERROR) }
            }
        }
    }

    /**
     * Records the end of a scan.
     *
     * A scan where every probe failed is reported as [ScanPhase.ERROR] rather than
     * "finished with no results". Both look identical from the counts, but the
     * causes are opposite: no connectivity versus a network where no Cloudflare
     * IP happens to be usable. Showing "try more IPs" to someone with Wi-Fi off
     * is unhelpful.
     */
    private fun publishFinished(outcome: ScanOutcome) {
        val all = outcome.results
        update {
            it.copy(
                // Keep the counts the engine last reported: neighbour expansion
                // means the number probed is not the number originally requested,
                // and showing "334 of 300" is nonsense.
                phase = if (outcome.everyProbeFailed) ScanPhase.ERROR else ScanPhase.FINISHED,
                probed = all.size,
                total = all.size,
                healthy = Ranking.healthy(all).size,
                resultCount = found.size,
            )
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        update { it.copy(phase = ScanPhase.STOPPED) }
    }

    /**
     * Re-ranks and republishes the results, coalescing bursts.
     *
     * Sorting runs on [Dispatchers.Default]; doing it inline on every hit stalled
     * the main thread. Hits also arrive in bursts, so a short debounce collapses
     * them into one sort.
     *
     * @param immediate skip the debounce for user-driven changes, where any delay
     *   reads as lag.
     */
    private fun resort(immediate: Boolean = false) {
        resortJob?.cancel()
        if (found.isEmpty()) {
            _results.value = emptyList()
            return
        }
        val criterion = currentSort()
        resortJob = viewModelScope.launch {
            if (!immediate) delay(RESORT_DEBOUNCE_MS)
            val snapshot = found.toList()
            _results.value = withContext(Dispatchers.Default) { Ranking.sort(snapshot, criterion) }
        }
    }

    private companion object {
        /**
         * Long enough to collapse a burst of hits into one sort, short enough that
         * the list still feels live.
         */
        const val RESORT_DEBOUNCE_MS = 250L
    }
}
