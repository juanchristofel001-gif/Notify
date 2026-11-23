package com.geto.notifhub

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Process

/** Shortcut aplikasi: yang di-pin pengguna + yang paling sering dibuka 7 hari terakhir. */
object QuickApps {
    data class App(val pkg: String, val label: String, val icon: Drawable?, val pinned: Boolean)

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Semua aplikasi yang bisa dibuka dari launcher (untuk dialog pin). */
    fun launchable(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    fun load(ctx: Context, max: Int = 8): List<App> {
        val pm = ctx.packageManager
        val pinned = Prefs(ctx).pinnedApps
        val result = LinkedHashMap<String, Boolean>()
        pinned.forEach { result[it] = true }
        if (hasUsageAccess(ctx) && result.size < max) {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 7L * 24 * 3600 * 1000, end)
            stats.groupBy { it.packageName }
                .mapValues { (_, l) -> l.sumOf { it.totalTimeInForeground } }
                .entries.sortedByDescending { it.value }
                .map { it.key }
                .filter { it != ctx.packageName && pm.getLaunchIntentForPackage(it) != null && it !in result }
                .filterNot { Categories.of(ctx, it, null) == Categories.SISTEM && !it.contains("camera") }
                .take(max - result.size)
                .forEach { result[it] = false }
        }
        return result.mapNotNull { (pkg, isPinned) ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                App(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info), isPinned)
            } catch (e: Exception) {
                null
            }
        }
    }
}
