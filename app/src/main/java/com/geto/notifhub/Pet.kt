package com.geto.notifhub

import android.content.Context

/**
 * Status maskot:
 * - mood (0–100): naik saat dielus/dikasih camilan/rutinitas selesai, turun pelan seiring waktu
 * - afeksi (XP → level): HANYA naik dari pencapaian yang diklaim
 */
object Pet {
    private const val DECAY_PER_HOUR = 2.5f
    private const val PAT_COOLDOWN_MS = 6_000L
    private const val FEED_COOLDOWN_MS = 3L * 60 * 60 * 1000

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("pet", Context.MODE_PRIVATE)

    fun mood(ctx: Context): Int {
        val s = sp(ctx)
        val m = s.getFloat("mood", 70f)
        val last = s.getLong("t", System.currentTimeMillis())
        val hours = (System.currentTimeMillis() - last) / 3_600_000f
        return (m - hours * DECAY_PER_HOUR).coerceIn(0f, 100f).toInt()
    }

    private fun changeMood(ctx: Context, delta: Int) {
        val now = mood(ctx)
        sp(ctx).edit()
            .putFloat("mood", (now + delta).coerceIn(0, 100).toFloat())
            .putLong("t", System.currentTimeMillis())
            .apply()
    }

    fun xp(ctx: Context): Int = sp(ctx).getInt("xp", 0)
    fun level(ctx: Context): Int = xp(ctx) / 100 + 1
    fun levelProgress(ctx: Context): Int = xp(ctx) % 100

    fun addAffection(ctx: Context, n: Int) = addXp(ctx, n)

    private fun addXp(ctx: Context, n: Int) {
        sp(ctx).edit().putInt("xp", (xp(ctx) + n).coerceAtLeast(0)).apply()
    }

    /** Elus: selalu bikin senang; mood & XP hanya naik kalau tidak di-spam. */
    fun pat(ctx: Context): Boolean {
        val s = sp(ctx)
        val now = System.currentTimeMillis()
        if (now - s.getLong("last_pat", 0L) < PAT_COOLDOWN_MS) return false
        s.edit().putLong("last_pat", now).apply()
        changeMood(ctx, 4)
        return true
    }

    /** Camilan: sekali tiap 3 jam. Mengembalikan sisa waktu tunggu (0 = berhasil). */
    fun feed(ctx: Context): Long {
        val s = sp(ctx)
        val now = System.currentTimeMillis()
        val wait = FEED_COOLDOWN_MS - (now - s.getLong("last_feed", 0L))
        if (wait > 0) return wait
        s.edit().putLong("last_feed", now).apply()
        changeMood(ctx, 20)
        return 0L
    }

    fun habitToggled(ctx: Context, done: Boolean) {
        changeMood(ctx, if (done) 12 else -12)
    }

    fun moodLabel(m: Int): String = when {
        m >= 80 -> "Bahagia banget"
        m >= 55 -> "Senang"
        m >= 30 -> "Biasa aja"
        m >= 15 -> "Kangen kamu"
        else -> "Sedih"
    }
}
