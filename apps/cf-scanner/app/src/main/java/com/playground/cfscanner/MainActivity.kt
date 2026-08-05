package com.playground.cfscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The single screen: controls, placeholder, and ranked results in one list.
 *
 * Deliberately thin. All scan state lives in [ScanViewModel], so a rotation, a
 * dark-mode change, or the app's own `recreate()` on a language switch no longer
 * destroys a scan that can take minutes. This class only wires views to that
 * state and back.
 */
class MainActivity : AppCompatActivity(), HeaderAdapter.Callbacks {

    private lateinit var headerAdapter: HeaderAdapter
    private lateinit var emptyAdapter: EmptyStateAdapter
    private lateinit var resultAdapter: ResultAdapter

    private val viewModel: ScanViewModel by viewModels()

    private val countOptions = COUNT_OPTIONS

    /**
     * Applies the language to this activity's own configuration.
     *
     * This is the one place the override is needed: every view is inflated from
     * the activity's context, so rewriting the configuration here is what makes
     * the language stick even where `setApplicationLocales` is ignored.
     *
     * Wrapped in a catch because it runs before anything is on screen. A failure
     * that propagates from here kills the app on launch with no way to report
     * why; falling through to the unmodified context merely shows the wrong
     * language.
     */
    override fun attachBaseContext(newBase: Context) {
        val wrapped = try {
            LocaleContext.wrap(newBase, LocaleRegistry.preferred(newBase))
        } catch (_: Throwable) {
            newBase
        }
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Taken from the resolved configuration rather than the stored preference,
        // so digit shapes always agree with the text on screen. Reading the stored
        // value here is what produced Persian numerals inside an English UI when
        // the framework overrode the requested language.
        Format.setLocale(LocaleContext.effectiveLocale(this) ?: LocaleRegistry.DEFAULT)

        setContentView(R.layout.activity_main)

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_about -> {
                    showAbout(); true
                }
                R.id.action_language -> {
                    showLanguagePicker(); true
                }
                else -> false
            }
        }

        headerAdapter = HeaderAdapter(
            countOptions = countOptions,
            // Resolved here rather than held on the enum, so the dropdown follows
            // the selected language.
            sortLabels = SortBy.entries.map { getString(it.labelRes) },
            // Same reason: localised here so the labels follow the language, and
            // the digits follow the locale's numerals.
            speedSizeLabels = SpeedSizeOptions.VALUES.map { bytes ->
                // Whole megabytes read better than "1024 KB".
                if (bytes % (1024 * 1024) == 0) {
                    getString(R.string.speed_size_mb, Format.number(bytes / (1024 * 1024)))
                } else {
                    getString(R.string.speed_size_kb, Format.number(bytes / 1024))
                }
            },
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

        observeViewModel()
    }

    /**
     * Renders whatever the ViewModel currently holds.
     *
     * Collected with `repeatOnLifecycle(STARTED)` so no rendering happens while
     * the app is backgrounded: the scan keeps running, but binding views for a
     * screen nobody is looking at is wasted main-thread work and battery.
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collectLatest { state ->
                        headerAdapter.update(state)
                        emptyAdapter.show(
                            EmptyStateRules.contentFor(state.phase, state.resultCount),
                        )
                        announceIfInterrupted(state)
                    }
                }
                launch {
                    viewModel.results.collectLatest { resultAdapter.submit(it) }
                }
            }
        }
    }

    /**
     * Notes that the app is no longer visible.
     *
     * The scan keeps running — it takes minutes, and stopping it whenever the
     * user glances at a notification would defeat the point. But Android may
     * throttle network access for a background process, and probes deferred that
     * way are recorded as packet loss, so the ViewModel remembers that this
     * scan's measurements are not wholly trustworthy.
     */
    override fun onStop() {
        // isChangingConfigurations excludes a rotation or language switch, where
        // the activity is rebuilt immediately and the process never actually
        // leaves the foreground — flagging those would warn about an
        // interruption that did not happen.
        if (!isChangingConfigurations) viewModel.onEnteredBackground()
        super.onStop()
    }

    /** Tracks which finished scan has already been reported as interrupted. */
    private var interruptionNoticeShown = false

    /**
     * Warns once when a completed scan ran partly in the background.
     *
     * Those probes may have been throttled by the system, so a good address can
     * look lossy. Saying so is more honest than silently ranking on it.
     */
    private fun announceIfInterrupted(state: HeaderState) {
        if (state.isScanning) {
            interruptionNoticeShown = false
            return
        }
        if (state.phase != ScanPhase.FINISHED) return
        if (!viewModel.measurementInterrupted || interruptionNoticeShown) return

        interruptionNoticeShown = true
        snack(getString(R.string.scan_interrupted_warning))
    }

    // region header callbacks

    override fun onScanToggle() {
        if (viewModel.isScanning) viewModel.stopScan() else confirmThenScan()
    }

    /**
     * Shows the VPN warning before the first scan of the session, then scans.
     *
     * Warned once per session rather than every time: a scan is often repeated
     * several times in a row, and a dialog on each would just be dismissed
     * reflexively. If a VPN is actually detected the warning is shown again, since
     * in that case it is not advice but a concrete problem with the results.
     */
    private fun confirmThenScan() {
        val vpnActive = VpnDetector.isActive(this)
        if (viewModel.vpnWarningShown && !vpnActive) {
            viewModel.startScan(countOptions)
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vpn_dialog_title)
            .setMessage(R.string.vpn_dialog_body)
            .setPositiveButton(R.string.vpn_dialog_scan) { _, _ ->
                viewModel.markVpnWarningShown()
                viewModel.startScan(countOptions)
            }
            .setNegativeButton(R.string.vpn_dialog_cancel, null)
            .show()

        if (vpnActive) snack(getString(R.string.vpn_active_warning))
    }

    override fun onCopy() {
        val items = viewModel.results.value
        if (items.isEmpty()) {
            snack(getString(R.string.toast_nothing))
            return
        }
        copyToClipboard(ResultExport.ipList(items))
        snack(getString(R.string.toast_copied, Format.number(items.size)))
    }

    override fun onSave() {
        if (viewModel.results.value.isEmpty()) {
            snack(getString(R.string.toast_nothing))
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_dialog_title)
            .setItems(
                arrayOf(
                    getString(R.string.save_option_ips),
                    getString(R.string.save_option_detailed),
                ),
            ) { _, which ->
                // Remembered until the picker returns: the system file dialog is
                // a separate activity, so the choice cannot be passed through it.
                pendingSaveDetailed = which == 1
                val name = if (pendingSaveDetailed) "cf-scan-details.txt" else "cf-clean-ips.txt"
                try {
                    createDocument.launch(name)
                } catch (_: Exception) {
                    // No document provider on the device; nothing was written.
                    snack(getString(R.string.save_failed))
                }
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    override fun onCountSelected(index: Int) = viewModel.setCount(index)

    override fun onSortSelected(index: Int) = viewModel.setSort(index)

    override fun onSpeedTestToggled(enabled: Boolean) =
        viewModel.setSpeedTestEnabled(enabled)

    override fun onSpeedSizeSelected(index: Int) = viewModel.setSpeedSizeIndex(index)

    override fun onIranModeChanged(enabled: Boolean) = viewModel.setIranMode(enabled)

    // endregion

    // region clipboard and dialogs

    /** Tapping one row copies just that address — handy for a quick single test. */
    private fun copySingle(r: ScanResult) {
        copyToClipboard(r.ip)
        snack(getString(R.string.toast_copied_one, Format.ip(r.ip)))
    }

    /**
     * Which export the user chose, held across the file-picker round trip.
     *
     * The system picker is another activity, so the choice cannot travel with the
     * intent. Defaulting to the plain list means a restored-process edge case
     * writes addresses rather than nothing.
     */
    private var pendingSaveDetailed = false

    /**
     * Writes the results to a location the user picks.
     *
     * Uses the Storage Access Framework rather than a path plus a storage
     * permission: the user names the destination themselves, the app needs no
     * permission at all, and it works on every version this app supports.
     */
    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        // Null means the user backed out, which is not a failure worth reporting.
        if (uri == null) return@registerForActivityResult

        val items = viewModel.results.value
        val text = if (pendingSaveDetailed) {
            ResultExport.detailed(items)
        } else {
            ResultExport.ipList(items)
        }

        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw java.io.IOException("no stream")
            snack(getString(R.string.save_done, Format.number(items.size)))
        } catch (_: Exception) {
            // Reported rather than swallowed: the user believes a file exists.
            snack(getString(R.string.save_failed))
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("clean-cf-ips", text))
    }

    private fun snack(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
    }

    /**
     * Lets the user pick the interface language.
     *
     * Options come from [LocaleRegistry.SUPPORTED], so adding a language to that
     * list is enough to make it appear here — no change is needed in this file.
     *
     * Each language is listed under its own name in its own script, so someone
     * who cannot read the current interface language can still find theirs.
     */
    private fun showLanguagePicker() {
        val options = LocaleRegistry.SUPPORTED
        val labels = options.map { it.endonym }.toTypedArray()
        // The user's stored choice, not the effective locale: the tick should show
        // what they selected even on a device that failed to apply it.
        val currentIndex = options.indexOf(LocaleRegistry.preferred(this)).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                dialog.dismiss()
                val chosen = options[which]
                if (chosen.tag != LocaleRegistry.preferred(this).tag) {
                    LocaleRegistry.apply(this, chosen)
                    // Recreated explicitly rather than relying on
                    // setApplicationLocales to do it: on devices that ignore that
                    // call nothing would happen, and the language would appear
                    // stuck until the next launch. attachBaseContext applies the
                    // new choice as the activity is rebuilt, and the scan survives
                    // because it lives in the ViewModel.
                    recreate()
                }
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    /**
     * The installed version name.
     *
     * Read from the package manager rather than BuildConfig: it reports what is
     * actually installed on the device, and it avoids enabling the buildConfig
     * feature (which adds a javac step to an otherwise pure-Kotlin module).
     * Falls back to empty rather than crashing the dialog, since a missing
     * version string is a cosmetic loss.
     */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun showAbout() {
        // No dialog title. It repeated the app name the user just tapped from,
        // put a Latin product name in the middle of a Persian line, and ate the
        // vertical room this dialog needs for actual text. The version goes at
        // the end of the body instead, where it is a fact about the build rather
        // than a heading.
        // getText, not getString: the body carries <b> section headings, and
        // getString would flatten them to plain text.
        val body = android.text.SpannableStringBuilder(getText(R.string.about_body))
            .append("\n\n")
            .append(getString(R.string.about_version, Format.isolate(appVersionName())))

        MaterialAlertDialogBuilder(this)
            .setMessage(body)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    // endregion

    private companion object {
        /** Scan sizes offered in the dropdown, smallest first. */
        val COUNT_OPTIONS = listOf(100, 200, 300, 500, 800)
    }
}
