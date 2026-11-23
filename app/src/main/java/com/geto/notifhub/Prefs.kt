package com.geto.notifhub

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("notifhub", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "").orEmpty().trim()
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL).orEmpty().trim().ifEmpty { DEFAULT_MODEL }
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var aboutMe: String
        get() = sp.getString("about", "").orEmpty()
        set(v) = sp.edit().putString("about", v).apply()

    var vipRaw: String
        get() = sp.getString("vip", "").orEmpty()
        set(v) = sp.edit().putString("vip", v).apply()

    val vip: List<String>
        get() = vipRaw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    var allApps: Boolean
        get() = sp.getBoolean("all_apps", true)
        set(v) = sp.edit().putBoolean("all_apps", v).apply()

    var alertUrgent: Boolean
        get() = sp.getBoolean("alert_urgent", true)
        set(v) = sp.edit().putBoolean("alert_urgent", v).apply()

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

        /** Nama rapi untuk aplikasi populer; dipakai juga kalau mode "semua aplikasi" dimatikan. */
        val KNOWN_APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WA Business",
            "com.facebook.katana" to "Facebook",
            "com.facebook.lite" to "Facebook Lite",
            "com.facebook.orca" to "Messenger",
            "com.facebook.mlite" to "Messenger Lite",
            "com.google.android.gm" to "Gmail",
            "com.microsoft.office.outlook" to "Outlook",
            "com.yahoo.mobile.client.android.mail" to "Yahoo Mail",
            "com.instagram.android" to "Instagram",
            "org.telegram.messenger" to "Telegram",
            "jp.naver.line.android" to "LINE",
            "com.twitter.android" to "X",
            "com.linkedin.android" to "LinkedIn",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.google.android.youtube" to "YouTube",
            "com.shopee.id" to "Shopee",
            "com.tokopedia.tkpd" to "Tokopedia",
            "com.gojek.app" to "Gojek",
            "com.grabtaxi.passenger" to "Grab",
            "com.discord" to "Discord",
            "com.google.android.apps.messaging" to "Pesan (SMS)",
        )

        /** Aplikasi sistem yang notifikasinya hampir selalu tidak berguna untuk dipilah. */
        val IGNORED_APPS = setOf(
            "android",
            "com.android.systemui",
            "com.android.providers.downloads",
            "com.google.android.gms",
            "com.android.vending",
        )
    }
}
