package com.playground.cfscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Renders ranked scan results, best first. */
class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {

    private val items = mutableListOf<ScanResult>()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val ip: TextView = view.findViewById(R.id.rowIp)
        val score: TextView = view.findViewById(R.id.rowScore)
        val detail: TextView = view.findViewById(R.id.rowDetail)
    }

    fun submit(newItems: List<ScanResult>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = items[position]
        val rank = position + 1

        holder.ip.text = "$rank. ${r.ip}"
        holder.score.text = "${r.score()} · ${r.grade()}"

        // Colour-code the score so the good IPs stand out at a glance.
        // Bands mirror ScanResult.grade().
        val colour = when (r.score()) {
            in 90..100 -> 0xFF2E7D32.toInt() // green
            in 75..89 -> 0xFF558B2F.toInt()  // light green
            in 55..74 -> 0xFFF9A825.toInt()  // amber
            in 1..54 -> 0xFFE65100.toInt()   // orange
            else -> 0xFFC62828.toInt()       // red
        }
        holder.score.setTextColor(colour)

        val flags = buildString {
            if (r.stableOk) append(" ✓پایدار")
            if (r.wsOk) append(" ✓WS")
        }
        holder.detail.text = buildString {
            append("پینگ ${r.avgMs()}ms")
            append(" · نوسان ${r.jitterMs()}ms")
            append(" · لاس ${r.loss().toInt()}%")
            if (r.colo.isNotEmpty()) append(" · ${r.colo}")
            append(flags)
        }
    }

    override fun getItemCount(): Int = items.size

    /** Plain-text export of the current list, newest ranking first. */
    fun exportText(): String = buildString {
        appendLine("# Clean Cloudflare IPs — CF Scanner")
        appendLine("# score/100 | avg ms | jitter ms | loss % | colo")
        items.forEach { r ->
            append(r.ip)
            append("  # ")
            append("${r.score()}/100")
            append(" | ${r.avgMs()}ms")
            append(" | ${r.jitterMs()}ms")
            append(" | ${r.loss().toInt()}%")
            if (r.colo.isNotEmpty()) append(" | ${r.colo}")
            appendLine()
        }
    }
}
