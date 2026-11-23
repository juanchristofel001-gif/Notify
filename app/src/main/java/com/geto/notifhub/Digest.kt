package com.geto.notifhub

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Ringkasan harian: cuaca, acara, notifikasi penting, inti berita — dikirim sebagai satu notifikasi. */
object Digest {
    private const val WORK = "daily_digest"
    private const val CHANNEL = "digest"

    /** Jadwalkan kiriman berikutnya pada jam yang diatur (atau batalkan kalau dimatikan). */
    fun schedule(ctx: Context) {
        val prefs = Prefs(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (!prefs.digestEnabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prefs.digestHour)
            set(Calendar.MINUTE, prefs.digestMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val req = OneTimeWorkRequestBuilder<DigestWorker>()
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    /** Kirim sekarang juga (tombol tes). Dipanggil dari thread latar. */
    fun sendNow(ctx: Context) = post(ctx, build(ctx))

    data class Content(val title: String, val summary: String, val lines: List<String>)

    fun build(ctx: Context): Content {
        val prefs = Prefs(ctx)
        val lines = ArrayList<String>()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val salam = when (hour) { in 4..10 -> "Selamat pagi"; in 11..14 -> "Selamat siang"; in 15..17 -> "Selamat sore"; else -> "Selamat malam" }
        val title = if (prefs.nickname.isNotEmpty()) "$salam, ${prefs.nickname}! Ringkasan hari ini" else "$salam! Ringkasan hari ini"

        // Cuaca
        var weatherShort = ""
        try {
            val w = Weather.load(ctx)
            val (emo, label) = Weather.describe(w.code)
            val today = w.days.firstOrNull()
            weatherShort = "$emo ${w.temp.toInt()}°"
            lines += "$emo Cuaca ${w.place}: $label, ${w.temp.toInt()}°" +
                (today?.let { " (${it.min.toInt()}–${it.max.toInt()}°)" } ?: "")
        } catch (e: Exception) {
            lines += "🌡️ Cuaca belum bisa dimuat"
        }

        // Acara hari ini & besok
        val events = try { Events.upcoming(ctx, daysAhead = 1, max = 4).filter { it.daysAway <= 1 } } catch (e: Exception) { emptyList() }
        if (events.isEmpty()) lines += "📅 Tidak ada acara hari ini & besok"
        events.forEach { lines += "${it.emoji} ${it.title} · ${Events.whenLabel(it)}" }

        // Notifikasi penting yang belum dibaca
        val db = Db.get(ctx)
        val counts = db.unreadCounts(null)
        val urgent = counts[Priority.URGENT] ?: 0
        val penting = counts[Priority.PENTING] ?: 0
        if (urgent + penting == 0) {
            lines += "🔔 Tidak ada notifikasi penting yang tertunda"
        } else {
            lines += "🔔 $urgent urgent · $penting penting belum dibaca"
            Grouping.build(
                db.query(null, null).filter { !it.read && (it.priority == Priority.URGENT || it.priority == Priority.PENTING) },
                emptySet(),
            ).filterIsInstance<Row.Thread>().take(3).forEach { t ->
                lines += "   • ${t.displayName} (${t.app})"
            }
        }

        // Rutinitas
        val (done, total) = db.habitProgress()
        if (total > 0) lines += "✅ Rutinitas kemarin→hari ini: $done/$total sudah dicentang"

        // Berita
        try {
            val news = News.load(ctx, News.TOPICS.first())
            if (prefs.apiKey.isNotEmpty() && news.isNotEmpty()) {
                val bullets = GeminiClient.newsBullets(prefs.apiKey, prefs.model, news.take(25))
                lines += "📰 Inti berita ekonomi:"
                bullets.forEach { lines += "   • $it" }
            } else {
                news.take(3).forEach { lines += "📰 ${it.title}" }
            }
        } catch (e: Exception) {
            lines += "📰 Berita belum bisa dimuat"
        }

        val summary = listOf(
            weatherShort,
            if (events.isNotEmpty()) "${events.size} acara" else "",
            if (urgent + penting > 0) "${urgent + penting} notif penting" else "",
        ).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Ketuk untuk melihat" }
        return Content(title, summary, lines)
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, c: Content) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ringkasan harian", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(c.title)
            .setContentText(c.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(777_001, n)
        } catch (e: SecurityException) {
            // izin notifikasi dicabut
        }
    }

    internal fun run(ctx: Context) {
        try {
            post(ctx, build(ctx))
        } finally {
            schedule(ctx)   // jadwalkan untuk besok
        }
    }

    @Suppress("unused")
    private val locale = Locale("id", "ID")
}

class DigestWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): androidx.work.ListenableWorker.Result {
        Digest.run(applicationContext)
        return androidx.work.ListenableWorker.Result.success()
    }
}
