package com.playground.cfscanner

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip

/** Renders ranked scan results, best first. */
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
        val chipWs: Chip = view.findViewById(R.id.chipWs)
    }

    /**
     * Replaces the visible list.
     *
     * Callers pass an already-sorted list computed off the main thread; this only
     * swaps it in. The list is short (tens of rows), so a full refresh is cheap —
     * the expensive part was the sorting, which no longer happens here.
     */
    fun submit(newItems: List<ScanResult>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun clear() {
        val had = items.size
        items.clear()
        if (had > 0) notifyItemRangeRemoved(0, had)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = items[position]
        val ctx = holder.itemView.context
        val score = r.score()
        val colour = ctx.getColor(gradeColour(score))
        val tint = ctx.getColor(gradeTint(score))

        holder.rank.text = "${position + 1}"
        holder.rank.setTextColor(colour)
        // Tint the shared oval per grade rather than shipping five drawables.
        (holder.rank.background?.mutate() as? GradientDrawable)?.setColor(tint)

        holder.ip.text = r.ip
        holder.score.text = "$score · ${r.grade()}"
        holder.score.setTextColor(colour)

        holder.chipPing.text = ctx.getString(R.string.chip_ping, r.avgMs())
        holder.chipJitter.text = ctx.getString(R.string.chip_jitter, r.jitterMs())

        holder.chipLoss.text = ctx.getString(R.string.chip_loss, r.loss().toInt())
        // Loss is the metric worth calling out, so colour it when non-zero.
        holder.chipLoss.setTextColor(
            if (r.loss() > 0) ctx.getColor(R.color.grade_weak) else holder.chipPing.currentTextColor,
        )

        holder.chipColo.text = r.colo
        holder.chipColo.visibility = if (r.colo.isEmpty()) View.GONE else View.VISIBLE

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
