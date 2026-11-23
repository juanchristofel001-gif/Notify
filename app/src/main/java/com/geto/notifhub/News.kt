package com.geto.notifhub

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/** Berita ekonomi & keuangan dari feed RSS Google News (judul + sumber + tautan saja). */
object News {
    data class Topic(val id: String, val label: String, val query: String)
    data class Item(val title: String, val source: String, val link: String, val time: Long)

    val TOPICS = listOf(
        Topic("ekonomi", "🇮🇩 Ekonomi RI", "ekonomi Indonesia"),
        Topic("saham", "📈 IHSG & Saham", "IHSG OR saham BEI"),
        Topic("rupiah", "💱 Rupiah & Kurs", "rupiah kurs dolar"),
        Topic("bank", "🏦 Perbankan & BI", "Bank Indonesia OR perbankan OR suku bunga BI"),
        Topic("investasi", "💰 Investasi", "investasi reksa dana OR obligasi OR emas"),
        Topic("global", "🌏 Ekonomi Global", "ekonomi global OR The Fed OR inflasi AS"),
    )

    private const val CACHE_MS = 15L * 60 * 1000

    private fun url(t: Topic): String =
        "https://news.google.com/rss/search?q=" + URLEncoder.encode("${t.query} when:2d", "UTF-8") +
            "&hl=id&gl=ID&ceid=ID:id"

    /** Dipanggil dari thread latar. */
    fun load(ctx: Context, topic: Topic, force: Boolean = false): List<Item> {
        val sp = ctx.getSharedPreferences("news", Context.MODE_PRIVATE)
        val key = "t_${topic.id}"
        if (!force && System.currentTimeMillis() - sp.getLong("${key}_time", 0) < CACHE_MS) {
            sp.getString(key, null)?.let { return fromJson(it) }
        }
        val c = URL(url(topic)).openConnection() as HttpURLConnection
        val items = try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NotifHub")
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.use { parse(it) }
        } finally {
            c.disconnect()
        }
        sp.edit().putString(key, toJson(items)).putLong("${key}_time", System.currentTimeMillis()).apply()
        return items
    }

    fun cached(ctx: Context, topic: Topic): List<Item> =
        ctx.getSharedPreferences("news", Context.MODE_PRIVATE).getString("t_${topic.id}", null)?.let { fromJson(it) }
            ?: emptyList()

    private fun parse(input: java.io.InputStream): List<Item> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        val out = ArrayList<Item>()
        val dateFmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        var inItem = false
        var title = ""; var link = ""; var source = ""; var pub = 0L
        var tag = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "item") { inItem = true; title = ""; link = ""; source = ""; pub = 0L }
                }
                XmlPullParser.TEXT -> if (inItem) {
                    val t = p.text.orEmpty()
                    when (tag) {
                        "title" -> title += t
                        "link" -> link += t.trim()
                        "source" -> source += t
                        "pubDate" -> pub = try { dateFmt.parse(t.trim())?.time ?: 0L } catch (e: Exception) { 0L }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item" && inItem) {
                        inItem = false
                        val src = source.trim()
                        // Judul Google News berakhiran " - Nama Media"; buang supaya rapi
                        val clean = if (src.isNotEmpty() && title.endsWith(" - $src")) title.removeSuffix(" - $src") else title
                        if (clean.isNotBlank() && link.isNotBlank()) out.add(Item(clean.trim(), src, link, pub))
                    }
                    tag = ""
                }
            }
        }
        return out.sortedByDescending { it.time }.take(40)
    }

    private fun toJson(items: List<Item>): String {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("t", it.title).put("s", it.source).put("l", it.link).put("p", it.time)) }
        return a.toString()
    }

    private fun fromJson(s: String): List<Item> = try {
        val a = JSONArray(s)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Item(o.optString("t"), o.optString("s"), o.optString("l"), o.optLong("p"))
        }
    } catch (e: Exception) {
        emptyList()
    }
}
