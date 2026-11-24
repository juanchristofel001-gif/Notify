package com.geto.notifhub

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GeminiClient {

    class Result(val id: Long, val priority: String, val reason: String)

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    fun classify(apiKey: String, model: String, systemPrompt: String, items: List<NotifItem>): List<Result> {
        val fmt = SimpleDateFormat("EEE dd MMM HH:mm", Locale("id", "ID"))
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("app", it.app)
                    .put("kategori", Categories.label(it.category))
                    .put("chat_grup", it.isGroup)
                    .put("pesan_langsung", it.isMessage)
                    .put("jenis", it.thread.takeIf { t -> !it.isMessage && t.isNotEmpty() } ?: "")
                    .put("judul", it.title.take(200))
                    .put("isi", it.body.take(600))
                    .put("waktu", fmt.format(Date(it.ts)))
            )
        }
        val userText = "Waktu sekarang: ${fmt.format(Date())}\n" +
            "Pilah notifikasi berikut:\n" + arr.toString()

        val text = generate(apiKey, model, systemPrompt, userText, json = true)
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("Jawaban AI bukan JSON: ${text.take(200)}")
        val results = JSONObject(text.substring(start, end + 1)).getJSONArray("results")

        val out = ArrayList<Result>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val p = r.optString("priority").trim().uppercase(Locale.ROOT)
            out.add(
                Result(
                    id = r.getLong("id"),
                    priority = if (p in Priority.LEVELS) p else Priority.NORMAL,
                    reason = r.optString("reason").take(120),
                )
            )
        }
        return out
    }

    /** Ringkasan satu percakapan + hal yang perlu ditindak. */
    fun summarize(apiKey: String, model: String, convName: String, items: List<NotifItem>): String {
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID"))
        val lines = items.takeLast(120).joinToString("\n") {
            val who = it.title.substringAfter(" — ", it.title)
            "[${fmt.format(Date(it.ts))}] $who: ${it.body.take(400)}"
        }
        val system = """
            Kamu membantu pengguna memahami percakapan/notifikasi yang menumpuk di HP-nya.
            Tulis dalam bahasa Indonesia santai dan ringkas, tanpa basa-basi pembuka.
            Format:
            Ringkasan:
            • (maksimal 4 poin, tiap poin 1 kalimat)
            Perlu kamu lakukan:
            • (hal yang menuntut tindakan/jawaban pengguna, sebut tenggat jika ada; tulis "Tidak ada" jika tidak ada)
        """.trimIndent()
        return generate(apiKey, model, system, "Percakapan: $convName\n$lines", json = false).trim()
    }

    /** Ringkasan berita dari judul-judul saja (tidak mengambil isi artikel). */
    fun newsBrief(apiKey: String, model: String, topic: String, items: List<News.Item>): String {
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID"))
        val lines = items.joinToString("\n") { "- [${if (it.time > 0) fmt.format(Date(it.time)) else "?"}] ${it.title} (${it.source})" }
        val system = """
            Kamu membantu pengguna memantau berita ekonomi dan keuangan Indonesia.
            Kamu HANYA menerima judul berita; jangan mengarang isi artikel, angka, atau kutipan yang tidak ada di judul.
            Tulis dalam bahasa Indonesia, ringkas, tanpa pembuka basa-basi. Format:
            Inti hari ini:
            • (3–5 poin: tema utama yang muncul dari judul-judul, gabungkan judul yang membahas hal sama)
            Yang perlu dipantau:
            • (1–3 hal yang mungkin berdampak ke keuangan pribadi, mis. suku bunga, kurs, harga; tulis "—" jika tidak ada)
            Catatan: ringkasan ini dari judul saja, baca artikel aslinya untuk detail.
        """.trimIndent()
        return generate(apiKey, model, system, "Topik: $topic\nJudul berita:\n$lines", json = false).trim()
    }

    /** 3 poin singkat inti berita (dari judul saja) untuk ringkasan harian. */
    fun newsBullets(apiKey: String, model: String, items: List<News.Item>): List<String> {
        val lines = items.joinToString("\n") { "- ${it.title} (${it.source})" }
        val system = """
            Dari daftar judul berita ekonomi Indonesia, tulis tepat 3 poin inti hari ini dalam bahasa Indonesia.
            Tiap poin maksimal 14 kata, gabungkan judul yang membahas hal sama, jangan mengarang angka yang tidak ada di judul.
            Balas JSON: {"points":["...","...","..."]}
        """.trimIndent()
        val text = generate(apiKey, model, system, lines, json = true)
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val arr = JSONObject(text.substring(start, end + 1)).optJSONArray("points") ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }.take(3)
    }

    /** Dipakai tombol "Tes koneksi". */
    fun test(apiKey: String, model: String): String =
        generate(apiKey, model, "Jawab sangat singkat.", "Balas dengan kata: OK", json = false)

    /** Error dari server Gemini, lengkap dengan kode HTTP-nya. */
    class ApiException(val code: Int, val apiMessage: String) : IOException("HTTP $code: $apiMessage")

    /** Model cadangan (stabil) kalau model pilihan sedang penuh atau tidak tersedia. */
    private val FALLBACK_MODELS = listOf("gemini-3.1-flash-lite", "gemini-3.8-flash")

    /** Kode yang biasanya sementara: layak dicoba ulang. */
    private fun transient(code: Int) = code == 429 || code == 500 || code == 502 || code == 503 || code == 504

    /** Pesan singkat & jelas untuk ditampilkan ke pengguna. */
    fun friendly(e: Throwable): String = when (e) {
        is ApiException -> when {
            e.code == 503 || e.code == 502 || e.code == 504 -> "Server Gemini sedang penuh (${e.code})"
            e.code == 429 -> "Kuota Gemini habis sementara (429)"
            e.code == 400 && e.apiMessage.contains("API key", true) -> "API key tidak valid"
            e.code == 403 -> "API key ditolak (403)"
            e.code == 404 -> "Model tidak ditemukan (404), cek nama model di Pengaturan"
            else -> "Gemini error ${e.code}: ${e.apiMessage.take(80)}"
        }
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
            "Tidak ada koneksi internet"
        else -> e.message?.take(100) ?: e.javaClass.simpleName
    }

    /**
     * Panggil Gemini dengan coba ulang otomatis (jeda 2 s lalu 6 s) untuk error sementara,
     * lalu pindah ke model cadangan kalau model pilihan tetap penuh / tidak ada.
     */
    fun generate(apiKey: String, model: String, system: String, user: String, json: Boolean): String {
        if (apiKey.isBlank()) throw IOException("API key belum diisi")
        val models = (listOf(model.trim()) + FALLBACK_MODELS).distinct()
        var last: Exception? = null
        for (m in models) {
            for (attempt in 0..2) {
                try {
                    return generateOnce(apiKey, m, system, user, json)
                } catch (e: ApiException) {
                    last = e
                    if (e.code == 400 || e.code == 401 || e.code == 403) throw e      // key salah: percuma diulang
                    if (e.code == 404) break                                           // model tidak ada → model berikutnya
                    if (!transient(e.code)) throw e
                } catch (e: IOException) {
                    last = e
                    if (attempt >= 1) break                                            // jaringan: cukup sekali ulang
                }
                if (attempt < 2) Thread.sleep(if (attempt == 0) 2_000L else 6_000L)
            }
        }
        throw last ?: IOException("Gemini tidak merespons")
    }

    private fun generateOnce(apiKey: String, model: String, system: String, user: String, json: Boolean): String {
        val gen = JSONObject().put("temperature", 0.2)
        if (json) gen.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))
                )
            )
            .put("generationConfig", gen)

        val conn = URL(BASE + model.trim() + ":generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(resp).optJSONObject("error")?.optString("message")
                } catch (e: Exception) { null } ?: resp.take(200)
                throw ApiException(code, msg)
            }

            val candidates = JSONObject(resp).optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw IOException("AI tidak memberi jawaban: ${resp.take(300)}")
            }
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException("Format jawaban tidak dikenal: ${resp.take(300)}")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text", ""))
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }
}
