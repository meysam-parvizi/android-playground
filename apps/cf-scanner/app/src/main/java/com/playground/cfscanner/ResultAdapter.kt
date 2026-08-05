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
        val speed: TextView = view.findViewById(R.id.rowSpeed)
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

        // A move does not rebind its ViewHolder, so a position-derived rank would
        // otherwise keep the old number. That produced repeated/out-of-order
        // ranks after re-sorting even though the list itself was correct. Refresh
        // rank text across the range with a lightweight payload; RecyclerView only
        // binds attached rows, and no metric/card work is repeated.
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, RANK_PAYLOAD)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false)

        // Direction comes from the selected language, not from any View.
        //
        // Two view-derived sources were tried and both failed: parent
        // .layoutDirection is a *resolved* value that reports LTR before
        // resolution runs, and Configuration.layoutDirection depends on which
        // context the inflater happened to carry. Both left the first holders
        // disagreeing with later ones, so direction tracked scroll position.
        // Format.layoutDirection is set before any view exists.
        v.layoutDirection = Format.layoutDirection
        return Holder(v)
    }

    /**
     * Partial rebind used when only a row's rank changed.
     *
     * A move leaves the row's data identical, so redrawing the whole card would
     * be wasted work; only the rank text depends on position.
     */
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(RANK_PAYLOAD)) {
            bindRank(holder, position)
            holder.itemView.contentDescription = describe(
                holder.itemView.context, items[position], position,
            )
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = items[position]
        val ctx = holder.itemView.context

        // Reasserted on every bind, not just on creation. A holder created
        // before the language was applied is reused afterwards, and a stale
        // direction would otherwise survive in the recycled view.
        holder.itemView.layoutDirection = Format.layoutDirection
        val score = r.score()
        val scoreTextColour = themeColour(
            ctx, com.google.android.material.R.attr.colorOnSurface,
        )
        val tint = ctx.getColor(gradeTint(score))

        bindRank(holder, position)

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
        holder.score.setTextColor(scoreTextColour)
        (holder.score.background?.mutate() as? GradientDrawable)?.setColor(tint)

        // Location is metadata on its own line, so variable-length addresses do
        // not push the datacenter and score into ragged positions.
        val context = contextLine(r)
        holder.context.text = context
        holder.context.visibility = if (context.isEmpty()) View.GONE else View.VISIBLE

        // Four equal micro-stats. A localised label names each value explicitly;
        // no icon vocabulary or memorised column order is required.
        val bright = themeColour(ctx, com.google.android.material.R.attr.colorOnSurface)
        val dim = themeColour(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant)

        val pingValue = ctx.getString(R.string.chip_ping, Format.millis(r.avgMs()))
        val jitterValue = ctx.getString(R.string.chip_jitter, Format.millis(r.jitterMs()))
        val lossValue = ctx.getString(R.string.chip_loss, Format.percent(r.loss().toInt()))
        val hasSpeed = r.throughputBps > 0
        val speedValue = if (hasSpeed) {
            ctx.getString(R.string.chip_speed, Format.speed(r.throughputBps))
        } else {
            ctx.getString(R.string.metric_absent)
        }

        holder.ping.text = metricBlock(
            ctx.getString(R.string.metric_label_ping), pingValue, bright,
        )
        holder.jitter.text = metricBlock(
            ctx.getString(R.string.metric_label_jitter), jitterValue, bright,
        )
        holder.loss.text = metricBlock(
            ctx.getString(R.string.metric_label_loss),
            lossValue,
            if (r.loss() > 0) ctx.getColor(R.color.grade_weak) else bright,
        )
        holder.speed.text = metricBlock(
            ctx.getString(R.string.metric_label_speed), speedValue,
            if (hasSpeed) bright else dim,
        )

        // One screen-reader stop for the whole row.
        holder.itemView.contentDescription = describe(ctx, r, position)

        holder.itemView.setOnClickListener { onRowClick(r) }
    }

    /** Full spoken description, recomputed when either data or rank changes. */
    private fun describe(ctx: Context, r: ScanResult, position: Int): String = buildString {
        append(ctx.getString(R.string.result_rank, Format.number(position + 1)))
        append(", ")
        append(Format.ip(r.ip))
        append(", ")
        val grade = ctx.getString(r.gradeRes())
        append(
            if (r.wsOk) ctx.getString(
                R.string.score_grade_ws, Format.number(r.score()), grade,
            ) else ctx.getString(
                R.string.score_and_grade, Format.number(r.score()), grade,
            ),
        )
        append(", ")
        append(spokenMetric(
            ctx, R.string.metric_label_ping,
            ctx.getString(R.string.chip_ping, Format.millis(r.avgMs())),
        ))
        append(", ")
        append(spokenMetric(
            ctx, R.string.metric_label_jitter,
            ctx.getString(R.string.chip_jitter, Format.millis(r.jitterMs())),
        ))
        append(", ")
        append(spokenMetric(
            ctx, R.string.metric_label_loss,
            ctx.getString(R.string.chip_loss, Format.percent(r.loss().toInt())),
        ))
        if (r.throughputBps > 0) {
            append(", ")
            append(spokenMetric(
                ctx, R.string.metric_label_speed,
                ctx.getString(R.string.chip_speed, Format.speed(r.throughputBps)),
            ))
        }
        if (r.wsOk) {
            append(", ")
            append(ctx.getString(R.string.chip_ws))
        }
        if (r.colo.isNotEmpty()) {
            append(", ")
            append(r.colo)
        }
    }

    /** The rank is neutral metadata; quality colour belongs to the score pill. */
    private fun bindRank(holder: Holder, position: Int) {
        val ctx = holder.itemView.context
        holder.rank.text = ctx.getString(R.string.result_rank, Format.number(position + 1))
        holder.rank.setTextColor(
            themeColour(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant),
        )
    }

    /** Datacenter code shown as secondary metadata below the address. */
    /**
     * The datacenter code, isolated.
     *
     * A bare "VIE" is pure Latin, so an unisolated run lets the bidi algorithm
     * reorder it against the Persian text beside it. Format.isolate wraps it in
     * FSI/PDI so the code renders as one unit wherever it sits.
     */
    private fun contextLine(r: ScanResult): CharSequence =
        if (r.colo.isEmpty()) "" else Format.isolate(r.colo)

    /** Resolves a colour from the current theme so it follows light/dark. */
    private fun themeColour(ctx: Context, attr: Int): Int {
        val typed = android.util.TypedValue()
        ctx.theme.resolveAttribute(attr, typed, true)
        return typed.data
    }

    /** Builds one fixed two-line stat: muted label, prominent value. */
    private fun metricBlock(label: String, value: String, valueColour: Int): CharSequence {
        val valueToken = Format.isolate(value)
        return SpannableStringBuilder(label)
            .append('\n')
            .append(valueToken)
            .apply {
                val start = length - valueToken.length
                setSpan(
                    ForegroundColorSpan(valueColour),
                    start,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                setSpan(
                    android.text.style.RelativeSizeSpan(1.08f),
                    start,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
    }

    /** Spoken form of a visual label/value block. */
    private fun spokenMetric(ctx: Context, labelRes: Int, value: String): String =
        "${ctx.getString(labelRes)} ${Format.isolate(value)}"

    override fun getItemCount(): Int = items.size

    /**
     * Diff over the ranked list.
     *
     * Identity is the address and content comparison covers displayed data.
     * Position-derived rank is refreshed separately after dispatch, because a
     * move event does not rebind its ViewHolder.
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
         * The rank text does depend on position, so it is refreshed separately
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
    }

    private companion object {

        /**
         * Marks a rebind that only needs the rank text redrawn.
         *
         * A plain object identity is enough; the payload carries no data.
         */
        val RANK_PAYLOAD = Any()


        fun gradeTint(score: Int): Int = when (score) {
            in 90..100 -> R.color.grade_excellent_bg
            in 75..89 -> R.color.grade_good_bg
            in 55..74 -> R.color.grade_fair_bg
            in 1..54 -> R.color.grade_weak_bg
            else -> R.color.grade_bad_bg
        }
    }
}
