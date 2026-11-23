package com.geto.notifhub

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object Priority {
    const val PENDING = "PENDING"
    const val URGENT = "URGENT"
    const val PENTING = "PENTING"
    const val NORMAL = "NORMAL"
    const val RENDAH = "RENDAH"
    val LEVELS = listOf(URGENT, PENTING, NORMAL, RENDAH)
    val LABELS = listOf("Urgent", "Penting", "Normal", "Rendah")

    fun label(p: String): String {
        val i = LEVELS.indexOf(p)
        return if (i >= 0) LABELS[i] else "Menunggu"
    }
}

data class NotifItem(
    val id: Long,
    val pkg: String,
    val app: String,
    val title: String,
    val body: String,
    val ts: Long,
    val priority: String,
    val reason: String,
    val source: String,
    val read: Boolean,
    val category: String,
    val isGroup: Boolean,
    val isMessage: Boolean,
)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "notifhub.db", null, 2) {

    companion object {
        @Volatile
        private var instance: Db? = null

        fun get(ctx: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(ctx.applicationContext).also { instance = it }
            }

        private const val COLS =
            "id, pkg, app, title, body, ts, priority, reason, source, is_read, category, is_group, is_message"
        private const val KEEP_DAYS = 30L
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notifs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg TEXT NOT NULL,
                app TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                ts INTEGER NOT NULL,
                priority TEXT NOT NULL,
                reason TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT '',
                is_read INTEGER NOT NULL DEFAULT 0,
                hash TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT 'lainnya',
                is_group INTEGER NOT NULL DEFAULT 0,
                is_message INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX idx_hash ON notifs(hash)")
        db.execSQL("CREATE INDEX idx_prio_ts ON notifs(priority, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE notifs ADD COLUMN category TEXT NOT NULL DEFAULT 'lainnya'")
            db.execSQL("ALTER TABLE notifs ADD COLUMN is_group INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notifs ADD COLUMN is_message INTEGER NOT NULL DEFAULT 0")
            // Isi kategori data lama berdasarkan aplikasinya
            fun setCat(cat: String, pkgs: Set<String>) {
                val marks = pkgs.joinToString(",") { "?" }
                db.execSQL("UPDATE notifs SET category = '$cat' WHERE pkg IN ($marks)", pkgs.toTypedArray())
            }
            setCat(Categories.KOMUNIKASI, Categories.COMM_PKGS)
            setCat(Categories.SOSMED, Categories.SOCIAL_PKGS)
            db.execSQL("UPDATE notifs SET is_group = 1 WHERE category = 'komunikasi' AND title LIKE '% — %'")
            db.execSQL("UPDATE notifs SET is_message = 1 WHERE hash LIKE 'm|%'")
        }
    }

    private fun Cursor.toItem() = NotifItem(
        id = getLong(0),
        pkg = getString(1),
        app = getString(2),
        title = getString(3),
        body = getString(4),
        ts = getLong(5),
        priority = getString(6),
        reason = getString(7),
        source = getString(8),
        read = getInt(9) != 0,
        category = getString(10),
        isGroup = getInt(11) != 0,
        isMessage = getInt(12) != 0,
    )

    private fun list(sql: String, args: Array<String>): List<NotifItem> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<NotifItem>(c.count)
            while (c.moveToNext()) out.add(c.toItem())
            out
        }

    /** true jika notifikasi dengan hash yang sama sudah tersimpan sejak [since]. */
    fun exists(hash: String, since: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM notifs WHERE hash = ? AND ts >= ? LIMIT 1",
            arrayOf(hash, since.toString())
        ).use { it.moveToFirst() }

    fun insert(
        pkg: String, app: String, title: String, body: String, ts: Long, hash: String,
        category: String, isGroup: Boolean, isMessage: Boolean,
    ): Long {
        val v = ContentValues().apply {
            put("pkg", pkg)
            put("app", app)
            put("title", title)
            put("body", body)
            put("ts", ts)
            put("priority", Priority.PENDING)
            put("hash", hash)
            put("category", category)
            put("is_group", if (isGroup) 1 else 0)
            put("is_message", if (isMessage) 1 else 0)
        }
        val id = writableDatabase.insert("notifs", null, v)
        if (id % 50L == 0L) purgeOld()
        return id
    }

    /** category / priority = null berarti semua. */
    fun query(category: String?, priority: String?): List<NotifItem> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (category != null) { where += "category = ?"; args += category }
        if (priority != null) { where += "priority = ?"; args += priority }
        val w = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        return list("SELECT $COLS FROM notifs $w ORDER BY ts DESC LIMIT 500", args.toTypedArray())
    }

    /** Jumlah belum dibaca per prioritas (opsional: hanya dalam satu kategori). */
    fun unreadCounts(category: String? = null): Map<String, Int> {
        val sql = if (category == null)
            "SELECT priority, COUNT(*) FROM notifs WHERE is_read = 0 GROUP BY priority"
        else
            "SELECT priority, COUNT(*) FROM notifs WHERE is_read = 0 AND category = ? GROUP BY priority"
        val args = if (category == null) emptyArray<String>() else arrayOf(category)
        return readableDatabase.rawQuery(sql, args).use { c ->
            val m = HashMap<String, Int>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }
    }

    /** Jumlah belum dibaca per kategori. */
    fun unreadByCategory(): Map<String, Int> =
        readableDatabase.rawQuery(
            "SELECT category, COUNT(*) FROM notifs WHERE is_read = 0 GROUP BY category",
            emptyArray<String>()
        ).use { c ->
            val m = HashMap<String, Int>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }

    fun pending(limit: Int): List<NotifItem> =
        list(
            "SELECT $COLS FROM notifs WHERE priority = ? ORDER BY ts ASC LIMIT $limit",
            arrayOf(Priority.PENDING)
        )

    /** Koreksi manual terakhir dari pengguna — dipakai sebagai contoh untuk AI. */
    fun recentCorrections(limit: Int): List<NotifItem> =
        list(
            "SELECT $COLS FROM notifs WHERE source = 'manual' ORDER BY ts DESC LIMIT $limit",
            emptyArray<String>()
        )

    fun setPriority(id: Long, priority: String, reason: String, source: String) {
        val v = ContentValues().apply {
            put("priority", priority)
            put("reason", reason)
            put("source", source)
        }
        writableDatabase.update("notifs", v, "id = ?", arrayOf(id.toString()))
    }

    fun markRead(id: Long) {
        writableDatabase.execSQL("UPDATE notifs SET is_read = 1 WHERE id = ?", arrayOf(id))
    }

    fun markAllRead() {
        writableDatabase.execSQL("UPDATE notifs SET is_read = 1")
    }

    fun deleteAll() {
        writableDatabase.execSQL("DELETE FROM notifs")
    }

    /** Kembalikan yang tadinya dipilah pakai aturan (karena AI gagal) ke antrean AI. */
    fun resetRuleBased(): Int {
        val v = ContentValues().apply {
            put("priority", Priority.PENDING)
            put("reason", "")
            put("source", "")
        }
        return writableDatabase.update("notifs", v, "source = 'aturan'", null)
    }

    private fun purgeOld() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * 60 * 1000
        writableDatabase.execSQL("DELETE FROM notifs WHERE ts < ?", arrayOf(cutoff))
    }
}
