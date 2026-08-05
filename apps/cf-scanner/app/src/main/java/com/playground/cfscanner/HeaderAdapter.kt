package com.playground.cfscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/**
 * The controls block, rendered as the first row of the results list.
 *
 * Making the header part of the RecyclerView instead of wrapping the list in a
 * NestedScrollView is what keeps scrolling smooth: a RecyclerView measured at
 * `wrap_content` inside a scroll container lays out every row at full height and
 * recycles nothing, so a long result list stays entirely in memory and every
 * update touches every row.
 *
 * Callbacks are hoisted so this adapter holds no scan logic — it renders
 * [HeaderState] and reports intent.
 */
class HeaderAdapter(
    private val countOptions: List<Int>,
    private val sortLabels: List<String>,
    private val speedSizeLabels: List<String>,
    private val callbacks: Callbacks,
) : RecyclerView.Adapter<HeaderAdapter.Holder>() {

    /** Intent reported upward; the activity owns all behaviour. */
    interface Callbacks {
        fun onScanToggle()
        fun onCopy()
        fun onCountSelected(index: Int)
        fun onSortSelected(index: Int)
        fun onSpeedTestToggled(enabled: Boolean)
        fun onSpeedSizeSelected(index: Int)
        fun onIranModeChanged(enabled: Boolean)
    }

    private var state = HeaderState()

    /**
     * Applies a new state.
     *
     * Deliberately rebinds in place rather than calling `notifyItemChanged`: the
     * header is a single always-visible row, so re-inflating it on every progress
     * tick would be wasted work and would also drop dropdown focus.
     */
    fun update(newState: HeaderState) {
        state = newState
        boundHolder?.let { bind(it) }
    }

    private var boundHolder: Holder? = null

    /**
     * True while the adapter is writing the switch itself.
     *
     * Distinguishes a programmatic write from a user action. The previous guard
     * used `View.isPressed`, which is only set by a physical touch and therefore
     * ignored TalkBack, keyboard and D-pad activations entirely.
     */
    private var suppressSwitchCallback = false

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val statusText: TextView = view.findViewById(R.id.statusText)
        val subStatusText: TextView = view.findViewById(R.id.subStatusText)
        val expandedNote: TextView = view.findViewById(R.id.expandedNote)
        val healthyBadge: TextView = view.findViewById(R.id.healthyBadge)
        val progressBar: LinearProgressIndicator = view.findViewById(R.id.progressBar)
        val scanButton: MaterialButton = view.findViewById(R.id.scanButton)
        val copyButton: MaterialButton = view.findViewById(R.id.copyButton)
        val countInput: MaterialAutoCompleteTextView = view.findViewById(R.id.countInput)
        val sortInput: MaterialAutoCompleteTextView = view.findViewById(R.id.sortInput)
        val speedTestSwitch: MaterialSwitch = view.findViewById(R.id.speedTestSwitch)
        val speedOptions: View = view.findViewById(R.id.speedOptions)
        val speedSizeInput: MaterialAutoCompleteTextView =
            view.findViewById(R.id.speedSizeInput)
        val iranModeSwitch: MaterialSwitch = view.findViewById(R.id.iranModeSwitch)
        val resultsHeader: TextView = view.findViewById(R.id.resultsHeader)
        var dropdownsReady = false
    }

    override fun getItemCount(): Int = 1

    override fun getItemViewType(position: Int): Int = VIEW_TYPE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_header, parent, false)
        Format.applyDirection(v)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        boundHolder = holder
        setUpDropdownsOnce(holder)
        bind(holder)
    }

    override fun onViewRecycled(holder: Holder) {
        if (boundHolder === holder) boundHolder = null
        super.onViewRecycled(holder)
    }

    /** Adapters and listeners are attached once; only values change afterwards. */
    private fun setUpDropdownsOnce(holder: Holder) {
        if (holder.dropdownsReady) return
        val ctx = holder.itemView.context

        // Bare Persian numerals: the field's own label already says what the
        // number counts, and "۳۰۰ آی‌پی" reordered badly in a right-to-left field.
        val counts = countOptions.map { Format.number(it) }
        holder.countInput.setAdapter(
            ArrayAdapter(ctx, android.R.layout.simple_list_item_1, counts),
        )
        holder.countInput.setOnItemClickListener { _, _, index, _ ->
            callbacks.onCountSelected(index)
        }

        holder.sortInput.setAdapter(
            ArrayAdapter(ctx, android.R.layout.simple_list_item_1, sortLabels),
        )
        holder.sortInput.setOnItemClickListener { _, _, index, _ ->
            callbacks.onSortSelected(index)
        }

        holder.speedTestSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchCallback) callbacks.onSpeedTestToggled(checked)
        }

        holder.speedSizeInput.setAdapter(
            ArrayAdapter(ctx, android.R.layout.simple_list_item_1, speedSizeLabels),
        )
        holder.speedSizeInput.setOnItemClickListener { _, _, index, _ ->
            callbacks.onSpeedSizeSelected(index)
        }

        holder.scanButton.setOnClickListener { callbacks.onScanToggle() }
        holder.copyButton.setOnClickListener { callbacks.onCopy() }
        holder.iranModeSwitch.setOnCheckedChangeListener { _, checked ->
            // Guarded by a flag rather than by View.isPressed. isPressed is only
            // true for a physical touch, so a TalkBack double-tap, a keyboard or
            // D-pad activation, or any accessibility ACTION_CLICK moved the
            // switch visually but never reached this callback — the setting
            // silently snapped back on the next bind, leaving the option
            // unreachable for screen-reader users.
            if (!suppressSwitchCallback) callbacks.onIranModeChanged(checked)
        }

        holder.dropdownsReady = true
    }

    private fun bind(holder: Holder) {
        val ctx = holder.itemView.context
        val s = state

        // Dropdown values: set without filtering so the list is not narrowed.
        val counts = countOptions.map { Format.number(it) }
        counts.getOrNull(s.countIndex)?.let { holder.countInput.setText(it, false) }
        sortLabels.getOrNull(s.sortIndex)?.let { holder.sortInput.setText(it, false) }
        speedSizeLabels.getOrNull(s.speedSizeIndex)
            ?.let { holder.speedSizeInput.setText(it, false) }

        // The two dropdowns only exist to configure the benchmark, so they are
        // hidden while it is off rather than shown with no effect.
        holder.speedOptions.visibility = if (s.speedTestEnabled) View.VISIBLE else View.GONE
        if (holder.speedTestSwitch.isChecked != s.speedTestEnabled) {
            suppressSwitchCallback = true
            holder.speedTestSwitch.isChecked = s.speedTestEnabled
            suppressSwitchCallback = false
        }

        if (holder.iranModeSwitch.isChecked != s.iranMode) {
            // Suppressed so this programmatic write is not mistaken for the user
            // toggling the switch, which would echo straight back into the state.
            suppressSwitchCallback = true
            holder.iranModeSwitch.isChecked = s.iranMode
            suppressSwitchCallback = false
        }

        holder.statusText.setText(statusTextRes(s.phase))

        // Sub-status shows scan counts, and only once there is something to count.
        if (s.total > 0) {
            holder.subStatusText.text = if (s.isScanning) {
                ctx.getString(
                    R.string.status_progress,
                    Format.number(s.probed),
                    Format.number(s.total),
                )
            } else {
                // Spell out the split rather than leaving the user to subtract:
                // "125 tested: 61 healthy, 64 unhealthy".
                ctx.getString(
                    R.string.status_done_detail,
                    Format.number(s.probed),
                    Format.number(s.healthy),
                    Format.number(s.unhealthy),
                )
            }
            holder.subStatusText.visibility = View.VISIBLE
        } else {
            holder.subStatusText.visibility = View.GONE
        }

        // Explain a count that overshot the request, which otherwise reads as a bug.
        if (!s.isScanning && s.wasExpanded) {
            holder.expandedNote.text =
                ctx.getString(R.string.status_expanded_note, Format.number(s.expandedBy))
            holder.expandedNote.visibility = View.VISIBLE
        } else {
            holder.expandedNote.visibility = View.GONE
        }

        holder.progressBar.progress = s.progressPercent

        if (s.healthy > 0) {
            holder.healthyBadge.text =
                ctx.getString(R.string.status_healthy_badge, Format.number(s.healthy))
            holder.healthyBadge.visibility = View.VISIBLE
        } else {
            holder.healthyBadge.visibility = View.GONE
        }

        holder.scanButton.setText(if (s.isScanning) R.string.action_stop else R.string.action_scan)
        holder.scanButton.setIconResource(
            if (s.isScanning) R.drawable.ic_stop else R.drawable.ic_radar,
        )

        holder.copyButton.isEnabled = s.resultCount > 0

        holder.resultsHeader.text = if (s.resultCount > 0) {
            ctx.getString(R.string.label_results_count, Format.number(s.resultCount))
        } else {
            ctx.getString(R.string.label_results)
        }

        // Settings that would invalidate a running scan are locked while it runs.
        val editable = !s.isScanning
        holder.countInput.isEnabled = editable
        holder.speedTestSwitch.isEnabled = editable
        holder.speedSizeInput.isEnabled = editable
        holder.iranModeSwitch.isEnabled = editable
    }

    private fun statusTextRes(phase: ScanPhase): Int = when (phase) {
        ScanPhase.IDLE -> R.string.status_idle
        ScanPhase.SCANNING -> R.string.status_scanning
        ScanPhase.FINISHED -> R.string.status_done
        ScanPhase.STOPPED -> R.string.status_stopped
        ScanPhase.ERROR -> R.string.status_error
    }

    private companion object {
        const val VIEW_TYPE = 1_000
    }
}
