package com.geto.notifhub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.random.Random

/**
 * Kumpulan kalimat maskot. Diisi Gemini per batch (hemat kuota), dipakai satu per satu,
 * dan diisi ulang otomatis saat menipis. Tanpa API key → kalimat cadangan.
 * Yang dikirim ke AI hanya konteks ringkas (jam, mood, jumlah rutinitas/urgent), bukan isi notifikasi.
 */
object LineBank {
    val CATEGORIES = listOf("normal", "happy", "surprised", "angry", "cry", "sleepy", "pat", "feed", "habit", "allhabits", "achievement")

    private val FALLBACK = mapOf(
        "normal" to listOf("Hm? Ada apa colek-colek?", "Aku lagi jagain notifikasimu loh.", "Hai! Udah minum air belum?"),
        "happy" to listOf("Yeay, kamu dateng!", "Hehe, seneng deh ditemenin.", "Hari ini pasti seru!"),
        "surprised" to listOf("Eh?! Kaget tau!", "Waa, jangan tiba-tiba gitu dong!", "!? Ada apa ada apa?"),
        "angry" to listOf("Ihh, jangan dicolek terus!", "Hmph! Aku ngambek dikit.", "Baca dulu notif urgent-nya, baru main!"),
        "cry" to listOf("Huhu… perhatiin aku dong…", "Jangan dicuekin terus ya…", "Aku kangen kamu tau…"),
        "sleepy" to listOf("Hoaam… lima menit lagi…", "Zzz… aku ngantuk…", "Udah malem, kamu juga istirahat ya."),
        "pat" to listOf("Ehehe, geli…", "Lagi dong elusnya~", "Makasih udah sayang aku!"),
        "feed" to listOf("Nyam! Enak banget!", "Camilan! Kamu yang terbaik!", "Kenyang… makasih ya~"),
        "habit" to listOf("Keren! Satu rutinitas beres!", "Mantap, pertahankan ya!", "Aku bangga sama kamu!"),
        "allhabits" to listOf("SEMUA BERES! Kamu hebat banget hari ini!", "Rutinitas lengkap! Waktunya santai~"),
        "achievement" to listOf("Waaah, pencapaian baru! Aku makin sayang kamu!", "Kamu keren banget hari ini, sumpah!", "Bangga banget sama kamu! Peluk dulu sini!"),
    )

    @Volatile
    private var refilling = false

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("linebank", Context.MODE_PRIVATE)

    fun next(ctx: Context, category: String): String {
        val sp = sp(ctx)
        val bank = try { JSONObject(sp.getString("bank", "{}")!!) } catch (e: Exception) { JSONObject() }
        val arr = bank.optJSONArray(category)
        val line: String? = if (arr != null && arr.length() > 0) {
            val l = arr.optString(0)
            arr.remove(0)
            sp.edit().putString("bank", bank.toString()).apply()
            l
        } else null
        if ((arr?.length() ?: 0) < 2) refill(ctx)
        return line?.takeIf { it.isNotBlank() } ?: (FALLBACK[category] ?: FALLBACK.getValue("normal")).random(Random)
    }

    fun refill(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (prefs.apiKey.isEmpty() || refilling) return
        // jangan terlalu sering (minimal 2 menit antar permintaan)
        val last = sp(app).getLong("last_refill", 0L)
        if (System.currentTimeMillis() - last < 120_000L) return
        refilling = true
        sp(app).edit().putLong("last_refill", System.currentTimeMillis()).apply()
        Thread {
            try {
                val db = Db.get(app)
                val (done, total) = db.habitProgress()
                val urgent = db.unreadCounts(null)[Priority.URGENT] ?: 0
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val system = """
                    Kamu menulis dialog singkat untuk maskot chibi yang imut di aplikasi notifikasi pribadi bernama NotifHub.
                    Gaya: bahasa Indonesia santai (boleh selipan bahasa Inggris), manis, ceria, sedikit manja; saat "angry" ngambek lucu ala tsundere, bukan kasar.
                    Aturan: tiap kalimat maksimal 12 kata, maksimal 1 emoji, semua kalimat berbeda, jangan menyebut diri sebagai AI,
                    jangan menyalin dialog dari game, anime, atau karya lain mana pun.
                """.trimIndent()
                val user = """
                    Konteks sekarang: jam $hour, mood maskot ${Pet.mood(app)}/100 (${Pet.moodLabel(Pet.mood(app))}),
                    level kedekatan ${Pet.level(app)}, rutinitas hari ini $done dari $total selesai, notifikasi urgent belum dibaca: $urgent.
                    Buat 6 kalimat untuk setiap kategori:
                    normal (maskot dicolek biasa), happy (senang), surprised (kaget), angry (ngambek lucu karena dicolek terus atau ada notif urgent),
                    cry (sedih/manja minta diperhatikan), sleepy (ngantuk), pat (sedang dielus), feed (dikasih camilan),
                    habit (memuji pengguna yang baru menyelesaikan satu rutinitas), allhabits (merayakan semua rutinitas hari ini selesai),
                    achievement (sangat bangga dan makin sayang karena pengguna meraih pencapaian penting, misalnya selesai magang hari itu atau menyelesaikan konten).
                    Balas JSON: {"normal":[...],"happy":[...],"surprised":[...],"angry":[...],"cry":[...],"sleepy":[...],"pat":[...],"feed":[...],"habit":[...],"allhabits":[...],"achievement":[...]}
                """.trimIndent()
                val text = GeminiClient.generate(prefs.apiKey, prefs.model, system, user, json = true)
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start >= 0 && end > start) {
                    val fresh = JSONObject(text.substring(start, end + 1))
                    val sp = sp(app)
                    val bank = try { JSONObject(sp.getString("bank", "{}")!!) } catch (e: Exception) { JSONObject() }
                    for (cat in CATEGORIES) {
                        val add = fresh.optJSONArray(cat) ?: continue
                        val merged = JSONArray()
                        val old = bank.optJSONArray(cat)
                        if (old != null) for (i in 0 until old.length()) merged.put(old.optString(i))
                        for (i in 0 until add.length()) {
                            val l = add.optString(i).trim()
                            if (l.isNotEmpty() && l.length <= 90) merged.put(l)
                        }
                        bank.put(cat, merged)
                    }
                    sp.edit().putString("bank", bank.toString()).apply()
                }
            } catch (e: Exception) {
                // diam saja: kalimat cadangan tetap dipakai
            } finally {
                refilling = false
            }
        }.start()
    }
}
