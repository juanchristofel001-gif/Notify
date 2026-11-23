package com.geto.notifhub

import android.content.res.ColorStateList
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

class GroupAdapter(
    private val onHeader: (Row.AppHeader) -> Unit,
    private val onThread: (Row.Thread) -> Unit,
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_THREAD = 1
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd MMM", Locale.getDefault())

    fun rowAt(pos: Int): Row? = if (pos in 0 until itemCount) getItem(pos) else null

    override fun getItemViewType(position: Int) =
        if (getItem(position) is Row.AppHeader) TYPE_HEADER else TYPE_THREAD

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
        val app: TextView = v.findViewById(R.id.app)
        val count: TextView = v.findViewById(R.id.count)
        val chevron: TextView = v.findViewById(R.id.chevron)
    }

    class ThreadVH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
        val name: TextView = v.findViewById(R.id.name)
        val time: TextView = v.findViewById(R.id.time)
        val preview: TextView = v.findViewById(R.id.preview)
        val pill: TextView = v.findViewById(R.id.pill)
        val tag: TextView = v.findViewById(R.id.tag)
        val badge: TextView = v.findViewById(R.id.badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) HeaderVH(inf.inflate(R.layout.item_app_header, parent, false))
        else ThreadVH(inf.inflate(R.layout.item_thread, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.AppHeader -> bindHeader(holder as HeaderVH, row)
            is Row.Thread -> bindThread(holder as ThreadVH, row)
        }
    }

    private fun bindHeader(h: HeaderVH, r: Row.AppHeader) {
        h.avatar.text = r.app.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        h.avatar.backgroundTintList = ColorStateList.valueOf(NotifAdapter.Colors.forApp(r.pkg))
        h.app.text = r.app
        val conv = if (r.threads > 1) "${r.threads} percakapan · " else ""
        h.count.text = if (r.unread > 0) "$conv${r.unread} baru" else "$conv${r.total}"
        h.chevron.animate().rotation(if (r.collapsed) -90f else 0f).setDuration(200).start()
        h.itemView.setOnClickListener { onHeader(r) }
    }

    private fun bindThread(h: ThreadVH, r: Row.Thread) {
        val ctx = h.itemView.context
        h.avatar.text = r.displayName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
        h.avatar.backgroundTintList = ColorStateList.valueOf(
            ColorUtils.blendARGB(NotifAdapter.Colors.forApp(r.pkg), android.graphics.Color.BLACK, if (r.isGroup) 0.25f else 0f)
        )
        h.name.text = r.displayName
        h.time.text = if (DateUtils.isToday(r.last.ts)) timeFmt.format(Date(r.last.ts)) else dateFmt.format(Date(r.last.ts))

        // pratinjau: "Pengirim: isi" untuk grup, isi saja untuk chat pribadi
        val sender = r.last.title.substringAfter(" — ", "")
        h.preview.text = when {
            r.isGroup && sender.isNotEmpty() -> "$sender: ${r.last.body}"
            r.thread.isEmpty() -> "${r.last.title}: ${r.last.body}".trim(' ', ':')
            else -> r.last.body
        }
        h.preview.setTextColor(
            ContextCompat.getColor(ctx, if (r.unread > 0) R.color.text_primary else R.color.text_secondary)
        )

        val colorRes = when (r.topPriority) {
            Priority.URGENT -> R.color.p_urgent
            Priority.PENTING -> R.color.p_penting
            Priority.NORMAL -> R.color.p_normal
            Priority.RENDAH -> R.color.p_rendah
            else -> R.color.p_pending
        }
        val color = ContextCompat.getColor(ctx, colorRes)
        h.pill.text = if (r.topPriority == Priority.PENDING) "MEMILAH…" else Priority.label(r.topPriority).uppercase(Locale.ROOT)
        h.pill.setTextColor(color)
        h.pill.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 38))

        h.tag.text = buildString {
            append(
                when {
                    r.isGroup -> "Grup"
                    r.category == Categories.SOSMED && r.isMessage -> "DM"
                    r.isMessage -> "Chat pribadi"
                    r.thread.isEmpty() -> "Notifikasi aplikasi"
                    else -> Categories.label(r.category)
                }
            )
            append(" · ").append(r.total).append(if (r.total == 1) " pesan" else " pesan")
        }
        h.badge.visibility = if (r.unread > 0) View.VISIBLE else View.GONE
        h.badge.text = if (r.unread > 99) "99+" else r.unread.toString()
        h.itemView.alpha = if (r.unread > 0) 1f else 0.6f
        h.itemView.setOnClickListener { onThread(r) }
    }
}
