package com.geto.notifhub

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Cuaca dari Open-Meteo (gratis, tanpa API key). Disimpan sementara 30 menit. */
object Weather {
    data class Day(val date: String, val code: Int, val max: Double, val min: Double)
    data class Report(
        val place: String, val temp: Double, val feels: Double, val code: Int,
        val humidity: Int, val days: List<Day>, val fetchedAt: Long,
    )

    private const val CACHE_MS = 30L * 60 * 1000

    fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "Cerah"
        1 -> "🌤️" to "Cerah berawan"
        2 -> "⛅" to "Berawan sebagian"
        3 -> "☁️" to "Berawan"
        45, 48 -> "🌫️" to "Berkabut"
        51, 53, 55, 56, 57 -> "🌦️" to "Gerimis"
        61, 63, 66 -> "🌧️" to "Hujan"
        65, 67 -> "🌧️" to "Hujan lebat"
        71, 73, 75, 77, 85, 86 -> "❄️" to "Salju"
        80, 81 -> "🌦️" to "Hujan lokal"
        82 -> "⛈️" to "Hujan lebat lokal"
        95, 96, 99 -> "⛈️" to "Badai petir"
        else -> "🌡️" to "—"
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(ctx: Context): Location? {
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
    }

    /** Pakai cache kalau masih baru; force = true untuk muat ulang. Dipanggil dari thread latar. */
    fun load(ctx: Context, force: Boolean = false): Report {
        val sp = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
        val cached = sp.getString("json", null)
        if (!force && cached != null && System.currentTimeMillis() - sp.getLong("t", 0) < CACHE_MS) {
            parse(JSONObject(cached), sp.getString("place", "").orEmpty(), sp.getLong("t", 0))?.let { return it }
        }

        var lat: Double; var lon: Double; var place: String
        val loc = lastLocation(ctx)
        if (loc != null) {
            lat = loc.latitude; lon = loc.longitude
            place = reverseName(ctx, lat, lon) ?: "Lokasimu"
        } else {
            val city = Prefs(ctx).city
            val geo = JSONObject(get(
                "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(city, "UTF-8") + "&count=1&language=id&format=json"
            ))
            val r = geo.optJSONArray("results")?.optJSONObject(0)
                ?: throw java.io.IOException("Kota \"$city\" tidak ditemukan")
            lat = r.getDouble("latitude"); lon = r.getDouble("longitude")
            place = r.optString("name", city)
        }

        val json = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=4&timezone=auto"
        )
        val now = System.currentTimeMillis()
        sp.edit().putString("json", json).putString("place", place).putLong("t", now).apply()
        return parse(JSONObject(json), place, now) ?: throw java.io.IOException("Data cuaca tidak lengkap")
    }

    private fun reverseName(ctx: Context, lat: Double, lon: Double): String? = try {
        @Suppress("DEPRECATION")
        android.location.Geocoder(ctx, java.util.Locale("id", "ID")).getFromLocation(lat, lon, 1)
            ?.firstOrNull()?.let { it.subAdminArea ?: it.locality ?: it.adminArea }
    } catch (e: Exception) {
        null
    }

    private fun parse(o: JSONObject, place: String, t: Long): Report? {
        val cur = o.optJSONObject("current") ?: return null
        val d = o.optJSONObject("daily") ?: return null
        val dates = d.getJSONArray("time")
        val days = (0 until dates.length()).map { i ->
            Day(
                dates.getString(i),
                d.getJSONArray("weather_code").optInt(i),
                d.getJSONArray("temperature_2m_max").optDouble(i),
                d.getJSONArray("temperature_2m_min").optDouble(i),
            )
        }
        return Report(
            place, cur.optDouble("temperature_2m"), cur.optDouble("apparent_temperature"),
            cur.optInt("weather_code"), cur.optInt("relative_humidity_2m"), days, t,
        )
    }
}
