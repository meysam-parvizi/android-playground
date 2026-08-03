package com.playground.cfscanner

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        /**
         * The chip background before any per-result tint.
         *
         * Captured so a recycled WebSocket chip can be returned to its unstyled
         * look; without it the green highlight persisted onto rows that never
         * carried a WebSocket.
         */
        var defaultChipBackground: ColorStateList? = null
    }

    /**
     * Replaces the visible list, dispatching only the rows that actually changed.
     *
     * The caller passes a list already sorted off the main thread, and the diff
     * itself is computed off the main thread too — `calculateDiff` is O(N·D) and
     * each comparison recomputes four derived metrics, which is far too much to
     * run on the main thread several times a second during a scan.
     */
    suspend fun submit(newItems: List<ScanResult>) {
        val snapshot = items.toList()
        val diff = withContext(Dispatchers.Default) {
            // detectMoves is on: ranking reorders constantly as results stream
            // in, and a move is much cheaper to dispatch than a rebind.
            DiffUtil.calculateDiff(Diff(snapshot, newItems), true)
        }
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false)
        return Holder(v).also {
            it.defaultChipTextColor = it.chipPing.currentTextColor
            it.defaultChipBackground = it.chipWs.chipBackgroundColor
        }
    }

    /**
     * Partial rebind used when only a row's rank changed.
     *
     * A move leaves the row's data identical, so redrawing the whole card would
     * be wasted work; only the badge depends on position.
     */
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(RANK_PAYLOAD)) {
            bindRank(holder, items[position], position)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = items[position]
        val ctx = holder.itemView.context
        val score = r.score()
        val colour = ctx.getColor(gradeColour(score))
        val tint = ctx.getColor(gradeTint(score))

        bindRank(holder, r, position)

        // Latin digits, isolated: an IP is copied into configs, so its digits
        // must stay ASCII and its ordering must survive the RTL layout.
        holder.ip.text = Format.ip(r.ip)

        holder.score.text =
            ctx.getString(R.string.score_and_grade, Format.number(score), ctx.getString(r.gradeRes()))
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
        } else {
            // Reset explicitly: without this the green tint stayed on a recycled
            // view and rows that never carried a WebSocket inherited the styling
            // of whichever row previously used that holder.
            holder.chipWs.setTextColor(holder.defaultChipTextColor)
            holder.chipWs.chipBackgroundColor = holder.defaultChipBackground
        }

        // One combined description for the whole row. Each chip is excluded from
        // accessibility (see Widget.CfScanner.MetricChip), so a screen reader
        // makes a single stop per result that announces everything and offers the
        // copy action, instead of six stops that announce a value and do nothing.
        holder.itemView.contentDescription = describe(ctx, r, position + 1)

        holder.itemView.setOnClickListener { onRowClick(r) }
    }

    /**
     * Draws the rank badge, which is the only part of a row that depends on its
     * position in the list.
     */
    private fun bindRank(holder: Holder, r: ScanResult, position: Int) {
        val ctx = holder.itemView.context
        val score = r.score()
        holder.rank.text = Format.number(position + 1)
        holder.rank.setTextColor(ctx.getColor(gradeColour(score)))
        // Tint the shared oval per grade rather than shipping five drawables.
        (holder.rank.background?.mutate() as? GradientDrawable)
            ?.setColor(ctx.getColor(gradeTint(score)))
    }

    /**
     * Builds the spoken description of a result row.
     *
     * Reads as a sentence rather than a list of labels, and includes only the
     * metrics actually shown, so the announcement matches the visible row.
     */
    private fun describe(ctx: Context, r: ScanResult, rank: Int): CharSequence = buildString {
        append(ctx.getString(R.string.a11y_result_rank, Format.number(rank)))
        append(", ")
        append(Format.ip(r.ip))
        append(", ")
        append(ctx.getString(R.string.score_and_grade, Format.number(r.score()), ctx.getString(r.gradeRes())))
        append(", ")
        append(ctx.getString(R.string.chip_ping, Format.millis(r.avgMs())))
        append(", ")
        append(ctx.getString(R.string.chip_jitter, Format.millis(r.jitterMs())))
        append(", ")
        append(ctx.getString(R.string.chip_loss, Format.percent(r.loss().toInt())))
        if (r.colo.isNotEmpty()) {
            append(", ")
            append(ctx.getString(R.string.chip_colo, r.colo))
        }
        if (r.throughputBps > 0) {
            append(", ")
            append(ctx.getString(R.string.chip_speed, Format.speed(r.throughputBps)))
        }
        if (r.wsOk) {
            append(", ")
            append(ctx.getString(R.string.chip_ws))
        }
    }

    override fun getItemCount(): Int = items.size

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

        /**
         * Compares displayed data only — deliberately not position.
         *
         * Folding position in here meant every row below an insertion counted as
         * changed, so a single new result rebound the whole visible list. With
         * ranking reordering constantly that made DiffUtil equivalent to
         * `notifyDataSetChanged`, plus the cost of computing the diff first.
         *
         * The rank badge does depend on position, so it is refreshed separately
         * through [RANK_PAYLOAD] rather than by forcing a full rebind.
         */
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
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

        /**
         * Asks for a rank-only update when a row moved but its data did not.
         *
         * Returning a payload keeps `onBindViewHolder` from doing the full bind:
         * only the badge is redrawn.
         */
        override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any =
            RANK_PAYLOAD
    }

    private companion object {
        /**
         * Marks a rebind that only needs the rank badge redrawn.
         *
         * A plain object identity is enough; the payload carries no data.
         */
        val RANK_PAYLOAD = Any()

        /** Grade bands mirror [ScanResult.gradeRes]. */
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
