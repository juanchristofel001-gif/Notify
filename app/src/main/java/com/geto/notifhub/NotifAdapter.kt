package com.geto.notifhub

import android.graphics.Typeface
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotifAdapter(
    private val onClick: (NotifItem) -> Unit,
    private val onLongClick: (NotifItem) -> Unit,
) : RecyclerView.Adapter<NotifAdapter.VH>() {

    private var items: List<NotifItem> = emptyList()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

    fun submit(list: List<NotifItem>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val bar: View = v.findViewById(R.id.bar)
        val meta: TextView = v.findViewById(R.id.meta)
        val title: TextView = v.findViewById(R.id.title)
        val body: TextView = v.findViewById(R.id.body)
        val reason: TextView = v.findViewById(R.id.reason)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_notif, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        val ctx = h.itemView.context
        val time = if (DateUtils.isToday(it.ts)) timeFmt.format(Date(it.ts)) else dateFmt.format(Date(it.ts))

        h.meta.text = "${it.app} · $time"
        h.title.text = it.title
        h.title.setTypeface(null, if (it.read) Typeface.NORMAL else Typeface.BOLD)
        h.body.text = it.body
        h.body.visibility = if (it.body.isEmpty()) View.GONE else View.VISIBLE

        h.reason.text = if (it.priority == Priority.PENDING) {
            "Menunggu dipilah…"
        } else {
            val src = when (it.source) {
                "aturan" -> " (aturan, bukan AI)"
                "manual" -> " (diubah manual)"
                else -> ""
            }
            "${Priority.label(it.priority)} · ${it.reason}$src"
        }

        val color = when (it.priority) {
            Priority.URGENT -> R.color.p_urgent
            Priority.PENTING -> R.color.p_penting
            Priority.NORMAL -> R.color.p_normal
            Priority.RENDAH -> R.color.p_rendah
            else -> R.color.p_pending
        }
        h.bar.setBackgroundColor(ContextCompat.getColor(ctx, color))
        h.reason.setTextColor(ContextCompat.getColor(ctx, color))
        h.itemView.alpha = if (it.read) 0.6f else 1f

        h.itemView.setOnClickListener { _ -> onClick(it) }
        h.itemView.setOnLongClickListener { _ ->
            onLongClick(it)
            true
        }
    }
}
