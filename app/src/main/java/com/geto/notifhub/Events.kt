package com.geto.notifhub

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.Calendar

/** Acara terdekat: kalender HP + ulang tahun pengguna + tanggal pribadi (berulang tiap tahun). */
object Events {
    data class Event(
        val title: String,
        val emoji: String,
        val time: Long,          // awal hari (lokal) atau jam mulai
        val allDay: Boolean,
        val daysAway: Int,
        val personalId: Long?,   // non-null = tanggal pribadi (bisa dihapus)
        val fromCalendar: Boolean,
    )

    private fun startOfToday(): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun daysBetween(from: Calendar, to: Long): Int =
        ((to - from.timeInMillis) / 86_400_000L).toInt()

    /** Kemunculan berikutnya dari tanggal bulan/hari (hari ini dihitung). */
    private fun nextOccurrence(month: Int, day: Int): Calendar {
        val today = startOfToday()
        val c = startOfToday().apply { set(Calendar.MONTH, month - 1); set(Calendar.DAY_OF_MONTH, day) }
        if (c.before(today)) c.add(Calendar.YEAR, 1)
        return c
    }

    fun hasCalendarAccess(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun upcoming(ctx: Context, daysAhead: Int = 30, max: Int = 8): List<Event> {
        val today = startOfToday()
        val out = ArrayList<Event>()

        // Ulang tahun pengguna
        Prefs(ctx).birthday?.let { (_, m, d) ->
            val c = nextOccurrence(m, d)
            val days = daysBetween(today, c.timeInMillis)
            if (days <= 365) out.add(Event("Ulang tahunmu", "🎂", c.timeInMillis, true, days, null, false))
        }

        // Tanggal pribadi
        for (pd in Db.get(ctx).personalDates()) {
            val c = if (pd.yearly) nextOccurrence(pd.month, pd.day) else startOfToday().apply {
                set(pd.year, pd.month - 1, pd.day)
            }
            val days = daysBetween(today, c.timeInMillis)
            if (days in 0..365) out.add(Event(pd.name, pd.emoji, c.timeInMillis, true, days, pd.id, false))
        }

        // Kalender HP (Google Calendar, Samsung Calendar, dll.)
        if (hasCalendarAccess(ctx)) {
            try {
                val begin = System.currentTimeMillis()
                val end = today.timeInMillis + daysAhead * 86_400_000L
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                    ContentUris.appendId(it, begin)
                    ContentUris.appendId(it, end)
                }.build()
                ctx.contentResolver.query(
                    uri,
                    arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                    null, null, CalendarContract.Instances.BEGIN + " ASC",
                )?.use { c ->
                    while (c.moveToNext() && out.size < 60) {
                        val title = c.getString(0)?.trim().orEmpty()
                        if (title.isEmpty()) continue
                        val t = c.getLong(1)
                        val allDay = c.getInt(2) == 1
                        out.add(Event(title, "📅", t, allDay, daysBetween(today, t).coerceAtLeast(0), null, true))
                    }
                }
            } catch (e: Exception) {
                // abaikan: izin dicabut atau penyedia kalender bermasalah
            }
        }
        return out.filter { it.daysAway <= daysAhead || it.emoji == "🎂" }
            .sortedWith(compareBy({ it.daysAway }, { it.time }))
            .take(max)
    }

    fun whenLabel(e: Event): String = when (e.daysAway) {
        0 -> if (e.allDay) "Hari ini" else "Hari ini · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(e.time)
        1 -> if (e.allDay) "Besok" else "Besok · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(e.time)
        else -> "${e.daysAway} hari lagi · " + java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale("id", "ID")).format(e.time)
    }
}
