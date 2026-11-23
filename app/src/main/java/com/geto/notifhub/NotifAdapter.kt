package com.geto.notifhub

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotifAdapter(
    private val onClick: (NotifItem) -> Unit,
    private val onLongClick: (NotifItem) -> Unit,
) : ListAdapter<NotifItem, NotifAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NotifItem>() {
            override fun areItemsTheSame(a: NotifItem, b: NotifItem) = a.id == b.id
            override fun areContentsTheSame(a: NotifItem, b: NotifItem) = a == b
        }

        /** Warna avatar per aplikasi. */
        private val AVATAR_COLORS = intArrayOf(
            Color.parseColor("#25D366"), Color.parseColor("#4C8DF6"), Color.parseColor("#E8557F"),
            Color.parseColor("#7B5CF0"), Color.parseColor("#F08C00"), Color.parseColor("#14B8A6"),
            Color.parseColor("#EF4444"), Color.parseColor("#64748B"),
        )
        private val FIXED_COLORS = mapOf(
            "com.whatsapp" to Color.parseColor("#25D366"),
            "com.whatsapp.w4b" to Color.parseColor("#128C7E"),
            "com.facebook.katana" to Color.parseColor("#1877F2"),
            "com.facebook.lite" to Color.parseColor("#1877F2"),
            "com.facebook.orca" to Color.parseColor("#A033FF"),
            "com.google.android.gm" to Color.parseColor("#EA4335"),
            "com.instagram.android" to Color.parseColor("#E1306C"),
            "org.telegram.messenger" to Color.parseColor("#229ED9"),
        )
    }

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd MMM", Locale.getDefault())

    fun itemAt(pos: Int): NotifItem? = if (pos in 0 until itemCount) getItem(pos) else null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
        val app: TextView = v.findViewById(R.id.app)
        val time: TextView = v.findViewById(R.id.time)
        val dot: View = v.findViewById(R.id.dot)
        val title: TextView = v.findViewById(R.id.title)
        val body: TextView = v.findViewById(R.id.body)
        val pill: TextView = v.findViewById(R.id.pill)
        val reason: TextView = v.findViewById(R.id.reason)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_notif, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = getItem(position)
        val ctx = h.itemView.context

        val avatarColor = FIXED_COLORS[item.pkg]
            ?: AVATAR_COLORS[Math.floorMod(item.pkg.hashCode(), AVATAR_COLORS.size)]
        h.avatar.text = item.app.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        h.avatar.backgroundTintList = ColorStateList.valueOf(avatarColor)

        h.app.text = when {
            item.isGroup -> "${item.app} · Grup"
            item.category == Categories.SOSMED && item.isMessage -> "${item.app} · DM"
            else -> item.app
        }
        h.time.text = if (DateUtils.isToday(item.ts)) timeFmt.format(Date(item.ts))
        else dateFmt.format(Date(item.ts))
        h.dot.visibility = if (item.read) View.GONE else View.VISIBLE

        h.title.text = item.title
        h.body.text = item.body
        h.body.visibility = if (item.body.isEmpty()) View.GONE else View.VISIBLE

        val colorRes = when (item.priority) {
            Priority.URGENT -> R.color.p_urgent
            Priority.PENTING -> R.color.p_penting
            Priority.NORMAL -> R.color.p_normal
            Priority.RENDAH -> R.color.p_rendah
            else -> R.color.p_pending
        }
        val color = ContextCompat.getColor(ctx, colorRes)
        h.pill.text = if (item.priority == Priority.PENDING) "MEMILAH…"
        else Priority.label(item.priority).uppercase(Locale.ROOT)
        h.pill.setTextColor(color)
        h.pill.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 38))

        h.reason.text = when {
            item.priority == Priority.PENDING -> ""
            item.source == "aturan" -> "${item.reason} · aturan"
            item.source == "manual" -> "Diubah manual"
            else -> item.reason
        }

        h.itemView.alpha = if (item.read) 0.55f else 1f
        h.itemView.setOnClickListener { onClick(item) }
        h.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}
