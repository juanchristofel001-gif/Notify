package com.geto.notifhub

/** Baris di tampilan berkelompok: judul aplikasi, lalu percakapan-percakapannya. */
sealed class Row {
    abstract val key: String

    data class AppHeader(
        val pkg: String,
        val app: String,
        val unread: Int,
        val total: Int,
        val threads: Int,
        val collapsed: Boolean,
    ) : Row() {
        override val key get() = "app|$pkg"
    }

    data class Thread(
        val pkg: String,
        val app: String,
        val thread: String,           // "" = notifikasi lain dari aplikasi ini
        val isGroup: Boolean,
        val isMessage: Boolean,
        val category: String,
        val last: NotifItem,
        val unread: Int,
        val total: Int,
        val topPriority: String,      // prioritas tertinggi di antara yang belum dibaca (atau semua kalau sudah dibaca)
    ) : Row() {
        override val key get() = "t|$pkg|$thread"
        val displayName get() = thread.ifEmpty { "Notifikasi $app" }
    }
}

object Grouping {
    private fun rank(p: String) = when (p) {
        Priority.URGENT -> 0
        Priority.PENTING -> 1
        Priority.NORMAL -> 2
        Priority.RENDAH -> 3
        else -> 4
    }

    /** items sudah terfilter (kategori/prioritas). Diurutkan: prioritas tertinggi dulu, lalu yang terbaru. */
    fun build(items: List<NotifItem>, collapsed: Set<String>): List<Row> {
        val byThread = items.groupBy { it.pkg to it.thread }
        val threads = byThread.map { (k, list) ->
            val unreadItems = list.filter { !it.read }
            val pool = unreadItems.ifEmpty { list }
            val top = pool.minByOrNull { rank(it.priority) }!!.priority
            val last = list.maxByOrNull { it.ts }!!
            Row.Thread(
                pkg = k.first, app = last.app, thread = k.second,
                isGroup = list.any { it.isGroup }, isMessage = list.any { it.isMessage },
                category = last.category, last = last,
                unread = unreadItems.size, total = list.size, topPriority = top,
            )
        }
        val threadOrder = compareBy<Row.Thread>({ if (it.unread > 0) 0 else 1 }, { rank(it.topPriority) }, { -it.last.ts })

        val byApp = threads.groupBy { it.pkg }
        val apps = byApp.entries.sortedWith(
            compareBy<Map.Entry<String, List<Row.Thread>>>({ e -> if (e.value.any { it.unread > 0 }) 0 else 1 },
                { e -> e.value.minOf { rank(it.topPriority) } },
                { e -> -e.value.maxOf { it.last.ts } })
        )

        val out = ArrayList<Row>()
        for ((pkg, ts) in apps) {
            val isCollapsed = pkg in collapsed
            out += Row.AppHeader(
                pkg = pkg, app = ts.first().app,
                unread = ts.sumOf { it.unread }, total = ts.sumOf { it.total },
                threads = ts.size, collapsed = isCollapsed,
            )
            if (!isCollapsed) out += ts.sortedWith(threadOrder)
        }
        return out
    }
}
