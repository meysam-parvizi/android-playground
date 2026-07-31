package com.playground.cfscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Placeholder row shown in place of results.
 *
 * Its own adapter so it participates in the single scrolling list rather than
 * being a sibling view that has to be toggled in sync with the list — the sync
 * bug is what previously let "no healthy IP found" appear above real results.
 */
class EmptyStateAdapter : RecyclerView.Adapter<EmptyStateAdapter.Holder>() {

    private var content: EmptyContent? = null

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.emptyTitle)
        val hint: TextView = view.findViewById(R.id.emptyHint)
    }

    /** Passing `null` removes the row entirely. */
    fun show(newContent: EmptyContent?) {
        val had = content != null
        val wants = newContent != null
        val changed = content != newContent
        content = newContent

        when {
            !had && wants -> notifyItemInserted(0)
            had && !wants -> notifyItemRemoved(0)
            had && wants && changed -> notifyItemChanged(0)
        }
    }

    override fun getItemCount(): Int = if (content == null) 0 else 1

    override fun getItemViewType(position: Int): Int = VIEW_TYPE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_empty_state, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        content?.let {
            holder.title.setText(it.titleRes)
            holder.hint.setText(it.hintRes)
        }
    }

    private companion object {
        const val VIEW_TYPE = 2_000
    }
}
