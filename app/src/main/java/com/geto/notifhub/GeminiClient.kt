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

    /** Dipakai tombol "Tes koneksi". */
    fun test(apiKey: String, model: String): String =
        generate(apiKey, model, "Jawab sangat singkat.", "Balas dengan kata: OK", json = false)

    fun generate(apiKey: String, model: String, system: String, user: String, json: Boolean): String {
        if (apiKey.isBlank()) throw IOException("API key belum diisi")
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
            if (code !in 200..299) throw IOException("HTTP $code: ${resp.take(300)}")

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
