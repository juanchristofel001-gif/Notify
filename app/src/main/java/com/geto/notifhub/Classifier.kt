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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Antrean pemilah: notifikasi baru ditampung beberapa detik, lalu dikirim
 * sekaligus (maks. 25 per permintaan) ke Gemini supaya hemat kuota.
 * Kalau AI gagal / tidak ada API key, dipakai aturan kata kunci.
 */
object Classifier {
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var future: ScheduledFuture<*>? = null

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun schedule(ctx: Context, delaySec: Long = 4) {
        val app = ctx.applicationContext
        future?.cancel(false)
        future = exec.schedule(Runnable {
            try {
                process(app)
            } catch (e: Exception) {
                Log.w("NotifHub", "Pemilahan gagal", e)
            }
        }, delaySec, TimeUnit.SECONDS)
    }

    private fun process(ctx: Context) {
        val db = Db.get(ctx)
        val prefs = Prefs(ctx)
        repeat(20) {
            val batch = db.pending(25)
            if (batch.isEmpty()) return

            val aiResults: Map<Long, GeminiClient.Result> =
                if (prefs.apiKey.isEmpty()) {
                    emptyMap()
                } else {
                    try {
                        val prompt = buildPrompt(prefs, db.recentCorrections(15))
                        val r = GeminiClient.classify(prefs.apiKey, prefs.model, prompt, batch)
                        lastError = null
                        r.associateBy { it.id }
                    } catch (e: Exception) {
                        lastError = e.message ?: e.javaClass.simpleName
                        emptyMap()
                    }
                }

            for (item in batch) {
                val ai = aiResults[item.id]
                val (priority, reason, source) = if (ai != null) {
                    Triple(ai.priority, ai.reason, "ai")
                } else {
                    val (p, r) = Rules.classify(item, prefs.vip)
                    Triple(p, r, "aturan")
                }
                db.setPriority(item.id, priority, reason, source)
                if (priority == Priority.URGENT && prefs.alertUrgent) alert(ctx, item, reason)
            }
            Bus.changed()
        }
    }

    private fun buildPrompt(prefs: Prefs, corrections: List<NotifItem>): String {
        val sb = StringBuilder()
        sb.append(
            """
            Kamu memilah notifikasi HP milik satu orang berdasarkan seberapa cepat ia perlu memperhatikannya.
            Setiap notifikasi punya "kategori" (Komunikasi, Sosmed, Game, Lainnya), "chat_grup" (true jika dari grup), dan "pesan_langsung" (true jika berupa pesan/DM dari seseorang).

            Level:
            - URGENT: perlu ditindak dalam hitungan menit sampai beberapa jam (OTP/kode verifikasi, peringatan keamanan akun, transaksi mencurigakan, keadaan darurat, tenggat hari ini, atasan/dosen/HRD/keluarga menanyakan hal mendesak).
            - PENTING: perlu dibaca atau dibalas hari ini.
            - NORMAL: berguna tapi tidak mendesak.
            - RENDAH: aman diabaikan.

            Aturan per kategori:
            - Komunikasi, chat pribadi (chat_grup=false): pesan dari orang yang butuh jawaban → PENTING; basa-basi/stiker/"ok" → NORMAL; mendesak → URGENT. Email kerja/kampus/rekrutmen → PENTING; newsletter/promo email → RENDAH.
            - Komunikasi, chat grup (chat_grup=true): default RENDAH (obrolan ramai) atau NORMAL (info yang relevan). Naikkan ke PENTING/URGENT HANYA jika menyebut/me-mention pengguna, berisi tenggat hari ini, pengumuman resmi penting (kelas batal, rapat dipindah), keadaan darurat, atau dikirim kontak VIP.
            - Sosmed: DM/pesan langsung (pesan_langsung=true atau isinya pesan dari seseorang) → PENTING (URGENT jika mendesak); komentar/mention langsung ke pengguna → NORMAL; like, follow, saran teman, live, rekomendasi video, "kenangan" → RENDAH.
            - Game: default RENDAH (energi penuh, event, hadiah harian, ajakan main). Hanya keamanan akun atau transaksi/top-up → PENTING atau URGENT.
            - Lainnya: transaksi bank/e-wallet dan OTP → URGENT; status paket/ojol/janji temu → NORMAL atau PENTING; promo/diskon → RENDAH.

            Kalau ragu antara dua level, pilih yang lebih rendah, kecuali menyangkut keamanan akun atau uang.
            """.trimIndent()
        )
        if (prefs.aboutMe.isNotBlank()) {
            sb.append("\n\nTentang pengguna (pakai untuk menilai relevansi):\n").append(prefs.aboutMe.trim())
        }
        if (prefs.vip.isNotEmpty()) {
            sb.append("\n\nKontak/pengirim VIP (minimal PENTING jika pesannya butuh tanggapan): ")
                .append(prefs.vip.joinToString(", "))
        }
        if (corrections.isNotEmpty()) {
            sb.append("\n\nKoreksi pengguna sebelumnya (ikuti polanya):")
            for (c in corrections) {
                sb.append("\n- [").append(c.app).append("] ").append(c.title.take(60))
                    .append(": ").append(c.body.take(80).replace('\n', ' '))
                    .append(" => ").append(c.priority)
            }
        }
        sb.append(
            "\n\nBalas HANYA JSON dengan bentuk " +
                "{\"results\":[{\"id\":<id>,\"priority\":\"URGENT|PENTING|NORMAL|RENDAH\"," +
                "\"reason\":\"alasan singkat maks. 10 kata, bahasa Indonesia\"}]} " +
                "dan sertakan setiap id yang diberikan."
        )
        return sb.toString()
    }

    @SuppressLint("MissingPermission")
    private fun alert(ctx: Context, item: NotifItem, reason: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("urgent") == null) {
            nm.createNotificationChannel(
                NotificationChannel("urgent", "Notifikasi urgent", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, "urgent")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("URGENT · ${item.app}: ${item.title}")
            .setContentText(reason.ifBlank { item.body })
            .setStyle(NotificationCompat.BigTextStyle().bigText("${item.body}\n\n${reason}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify((item.id % Int.MAX_VALUE).toInt(), n)
        } catch (e: SecurityException) {
            Log.w("NotifHub", "Izin notifikasi tidak ada", e)
        }
    }
}

/** Cadangan tanpa AI: aturan kata kunci sederhana. */
object Rules {
    private val urgentKw = listOf(
        "otp", "kode verifikasi", "kode keamanan", "verification code", "security code",
        "security alert", "peringatan keamanan", "login baru", "new sign-in", "new login",
        "darurat", "urgent", "transaksi mencurigakan",
    )
    private val lowKw = listOf(
        "promo", "diskon", "cashback", "voucher", "flash sale", "newsletter", "unsubscribe",
        "menyukai", "likes your", "reacted to", "commented on", "mengomentari", "suggested",
        "saran teman", "is live", "sedang live", "kenangan", "memories",
    )

    fun classify(item: NotifItem, vip: List<String>): Pair<String, String> {
        val text = "${item.title} ${item.body}".lowercase(Locale.ROOT)
        val title = item.title.lowercase(Locale.ROOT)
        val isVip = vip.any { title.contains(it.lowercase(Locale.ROOT)) }
        return when {
            urgentKw.any { text.contains(it) } -> Priority.URGENT to "Kata kunci mendesak"
            isVip -> Priority.PENTING to "Kontak VIP"
            lowKw.any { text.contains(it) } -> Priority.RENDAH to "Promo / aktivitas sosmed"
            item.category == Categories.GAME -> Priority.RENDAH to "Notifikasi game"
            item.category == Categories.KOMUNIKASI && item.isGroup -> Priority.RENDAH to "Obrolan grup"
            item.category == Categories.KOMUNIKASI && item.isMessage -> Priority.PENTING to "Chat pribadi"
            item.category == Categories.SOSMED && item.isMessage -> Priority.PENTING to "DM sosmed"
            item.category == Categories.SOSMED -> Priority.RENDAH to "Aktivitas sosmed"
            else -> Priority.NORMAL to "Tidak ada tanda khusus"
        }
    }
}
