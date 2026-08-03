package com.playground.cfscanner

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
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
        val context: TextView = view.findViewById(R.id.rowContext)
        val score: TextView = view.findViewById(R.id.rowScore)
        val ping: TextView = view.findViewById(R.id.rowPing)
        val jitter: TextView = view.findViewById(R.id.rowJitter)
        val loss: TextView = view.findViewById(R.id.rowLoss)
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
        return Holder(v)
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

        // WebSocket support is a pass/fail capability rather than a measurement,
        // so it sits with the grade instead of competing for room among the
        // metrics. Spelled out rather than abbreviated to "WS", which would be
        // jargon in an app that otherwise states things plainly.
        val grade = ctx.getString(r.gradeRes())
        holder.score.text = if (r.wsOk) {
            ctx.getString(R.string.score_grade_ws, Format.number(score), grade)
        } else {
            ctx.getString(R.string.score_and_grade, Format.number(score), grade)
        }
        holder.score.setTextColor(colour)

        // Split by kind: where the edge is and what it can carry sits beside the
        // address, while the three numbers the ranking is built from get their
        // own line. Putting all five in one run needed roughly 460dp against 248
        // available, so it wrapped and hid values.
        val context = contextLine(r)
        holder.context.text = context
        holder.context.visibility = if (context.isEmpty()) View.GONE else View.VISIBLE

        // Three fixed columns rather than one run of text. Inline, the fields
        // shift with every change in digit count, so comparing jitter across
        // rows means locating the label again on each line.
        val bright = themeColour(ctx, com.google.android.material.R.attr.colorOnSurface)
        holder.ping.text = metric(
            ctx.getString(R.string.chip_ping, Format.millis(r.avgMs())),
            Format.millis(r.avgMs()),
            bright,
        )
        holder.jitter.text = metric(
            ctx.getString(R.string.chip_jitter, Format.millis(r.jitterMs())),
            Format.millis(r.jitterMs()),
            bright,
        )
        val lossValue = Format.percent(r.loss().toInt())
        holder.loss.text = metric(
            ctx.getString(R.string.chip_loss, lossValue),
            lossValue,
            // Loss is the metric worth flagging; the others stay neutral.
            if (r.loss() > 0) ctx.getColor(R.color.grade_weak) else bright,
        )

        // The row is one accessibility stop announcing everything, rather than
        // a card plus six focusable chips that each said a value and did nothing.
        holder.itemView.contentDescription = buildString {
            append(ctx.getString(R.string.a11y_result_rank, Format.number(position + 1)))
            append(", ")
            append(Format.ip(r.ip))
            append(", ")
            append(holder.score.text)
            append(", ")
            append(holder.ping.text)
            append(", ")
            append(holder.jitter.text)
            append(", ")
            append(holder.loss.text)
            // The grade carries a ⚡ for WebSocket support, which a screen
            // reader does not announce, so it is stated in words here.
            if (r.wsOk) {
                append(", ")
                append(ctx.getString(R.string.chip_ws))
            }
            if (context.isNotEmpty()) {
                append(", ")
                append(context)
            }
        }

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
     * Where this edge is and what it can carry.
     *
     * Context rather than measurement, so it sits beside the address and stays
     * dim. Absent values are omitted entirely rather than rendered empty.
     */
    /**
     * The fourth column: where the edge answered from.
     *
     * Throughput used to appear here too, but "AMS · Speed 2.1 Mbit" needs about
     * 110dp in a 62dp column, so it truncated the location it sat beside. It is
     * also the least-consulted number on the row, and it already does its real
     * work as a gate: an IP that stalls mid-transfer is rejected and never
     * appears in the list at all. The About screen explains that.
     */
    private fun contextLine(r: ScanResult): CharSequence = r.colo

    /** Resolves a colour from the current theme so it follows light/dark. */
    private fun themeColour(ctx: Context, attr: Int): Int {
        val typed = android.util.TypedValue()
        ctx.theme.resolveAttribute(attr, typed, true)
        return typed.data
    }

    /**
     * Formats one metric with only its value brightened.
     *
     * The label stays in the dim column colour and the number is lifted to the
     * surface colour — a 1.77x luminance step. Without it "Ping 41" reads as one
     * undifferentiated blob at label size.
     *
     * The value is located inside the already-formatted string rather than
     * assumed to sit at the end, so this holds for any language whose phrasing
     * puts the number first.
     */
    private fun metric(formatted: String, value: String, colour: Int): CharSequence {
        val at = formatted.lastIndexOf(value)
        if (at < 0) return formatted
        return SpannableStringBuilder(formatted).apply {
            setSpan(
                ForegroundColorSpan(colour),
                at,
                at + value.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
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
         * Separator between metrics. A spaced en dash, not a middle dot: beside
         * Persian digits a middle dot sits at the same height as ۰ and is easily
         * read as one. The score line dropped it for the same reason.
         */
        const val SEPARATOR = "   ·   "

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
