package com.playground.cfscanner

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip

/**
 * Renders ranked scan results, best first.
 *
 * Updates go through [DiffUtil] rather than `notifyDataSetChanged()`. Results
 * arrive continuously during a scan, and a blanket invalidation rebound every
 * visible row each time, which is what made the list stutter while scrolling.
 */
class ResultAdapter(
    /** Invoked when a row is tapped; used to copy that single IP. */
    private val onRowClick: (ScanResult) -> Unit = {},
) : RecyclerView.Adapter<ResultAdapter.Holder>() {

    private val items = mutableListOf<ScanResult>()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val rank: TextView = view.findViewById(R.id.rowRank)
        val ip: TextView = view.findViewById(R.id.rowIp)
        val score: TextView = view.findViewById(R.id.rowScore)
        val chipPing: Chip = view.findViewById(R.id.chipPing)
        val chipJitter: Chip = view.findViewById(R.id.chipJitter)
        val chipLoss: Chip = view.findViewById(R.id.chipLoss)
        val chipColo: Chip = view.findViewById(R.id.chipColo)
        val chipSpeed: Chip = view.findViewById(R.id.chipSpeed)
        val chipWs: Chip = view.findViewById(R.id.chipWs)

        /** Cached so the default chip colour is not re-read on every bind. */
        var defaultChipTextColor: Int = 0
    }

    /**
     * Replaces the visible list, dispatching only the rows that actually changed.
     *
     * The caller passes a list already sorted off the main thread; this method
     * just diffs and swaps.
     */
    fun submit(newItems: List<ScanResult>) {
        val diff = DiffUtil.calculateDiff(Diff(items, newItems), false)
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun clear() {
        val had = items.size
        items.clear()
        if (had > 0) notifyItemRangeRemoved(0, had)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false)
        return Holder(v).also { it.defaultChipTextColor = it.chipPing.currentTextColor }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = items[position]
        val ctx = holder.itemView.context
        val score = r.score()
        val colour = ctx.getColor(gradeColour(score))
        val tint = ctx.getColor(gradeTint(score))

        holder.rank.text = Format.number(position + 1)
        holder.rank.setTextColor(colour)
        // Tint the shared oval per grade rather than shipping five drawables.
        (holder.rank.background?.mutate() as? GradientDrawable)?.setColor(tint)

        // Latin digits, isolated: an IP is copied into configs, so its digits
        // must stay ASCII and its ordering must survive the RTL layout.
        holder.ip.text = Format.ip(r.ip)

        holder.score.text = ctx.getString(R.string.score_and_grade, Format.number(score), r.grade())
        holder.score.setTextColor(colour)

        holder.chipPing.text = ctx.getString(R.string.chip_ping, Format.millis(r.avgMs()))
        holder.chipJitter.text = ctx.getString(R.string.chip_jitter, Format.millis(r.jitterMs()))
        holder.chipLoss.text = ctx.getString(R.string.chip_loss, Format.percent(r.loss().toInt()))

        // Loss is the metric worth calling out, so colour it when non-zero.
        holder.chipLoss.setTextColor(
            if (r.loss() > 0) ctx.getColor(R.color.grade_weak) else holder.defaultChipTextColor,
        )

        holder.chipColo.text = ctx.getString(R.string.chip_colo, r.colo)
        holder.chipColo.visibility = if (r.colo.isEmpty()) View.GONE else View.VISIBLE

        // Throughput, only when a transfer was actually measured.
        if (r.throughputBps > 0) {
            holder.chipSpeed.text = ctx.getString(R.string.chip_speed, Format.speed(r.throughputBps))
            holder.chipSpeed.visibility = View.VISIBLE
        } else {
            holder.chipSpeed.visibility = View.GONE
        }

        // WebSocket carry is a real capability signal, so highlight it.
        holder.chipWs.visibility = if (r.wsOk) View.VISIBLE else View.GONE
        if (r.wsOk) {
            holder.chipWs.setTextColor(ctx.getColor(R.color.grade_excellent))
            holder.chipWs.chipBackgroundColor =
                ColorStateList.valueOf(ctx.getColor(R.color.grade_excellent_bg))
        }

        holder.itemView.setOnClickListener { onRowClick(r) }
    }

    override fun getItemCount(): Int = items.size

    /** Snapshot of the results currently displayed, in display order. */
    fun currentItems(): List<ScanResult> = items.toList()

    /**
     * Bare list of IPs in the order shown, best first — see [ResultExport.ipList].
     */
    fun exportText(): String = ResultExport.ipList(items)

    /**
     * Diff over the ranked list.
     *
     * Identity is the address, but position matters too: the rank badge shows the
     * row's place in the ranking, so a row that moved has to be rebound even
     * though its measurements are unchanged.
     */
    private class Diff(
        private val old: List<ScanResult>,
        private val new: List<ScanResult>,
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = old.size

        override fun getNewListSize(): Int = new.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            old[oldItemPosition].ip == new[newItemPosition].ip

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            // Same position and same displayed values means nothing to redraw.
            if (oldItemPosition != newItemPosition) return false
            val a = old[oldItemPosition]
            val b = new[newItemPosition]
            return a.score() == b.score() &&
                a.avgMs() == b.avgMs() &&
                a.jitterMs() == b.jitterMs() &&
                a.loss() == b.loss() &&
                a.colo == b.colo &&
                a.wsOk == b.wsOk &&
                a.throughputBps == b.throughputBps
        }
    }

    private companion object {
        /** Grade bands mirror [ScanResult.grade]. */
        fun gradeColour(score: Int): Int = when (score) {
            in 90..100 -> R.color.grade_excellent
            in 75..89 -> R.color.grade_good
            in 55..74 -> R.color.grade_fair
            in 1..54 -> R.color.grade_weak
            else -> R.color.grade_bad
        }

        fun gradeTint(score: Int): Int = when (score) {
            in 90..100 -> R.color.grade_excellent_bg
            in 75..89 -> R.color.grade_good_bg
            in 55..74 -> R.color.grade_fair_bg
            in 1..54 -> R.color.grade_weak_bg
            else -> R.color.grade_bad_bg
        }
    }
}
