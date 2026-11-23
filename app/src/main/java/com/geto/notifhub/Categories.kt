package com.geto.notifhub

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo

object Categories {
    const val KOMUNIKASI = "komunikasi"
    const val SOSMED = "sosmed"
    const val GAME = "game"
    const val LAINNYA = "lainnya"

    val ALL = listOf(KOMUNIKASI, SOSMED, GAME, LAINNYA)

    fun label(c: String): String = when (c) {
        KOMUNIKASI -> "Komunikasi"
        SOSMED -> "Sosmed"
        GAME -> "Game"
        else -> "Lainnya"
    }

    fun emoji(c: String?): String = when (c) {
        KOMUNIKASI -> "💬"
        SOSMED -> "📱"
        GAME -> "🎮"
        LAINNYA -> "📦"
        else -> "✨"
    }

    val COMM_PKGS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram",
        "jp.naver.line.android", "com.facebook.orca", "com.facebook.mlite",
        "com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.discord", "com.Slack", "com.microsoft.teams", "us.zoom.videomeetings",
        "com.google.android.dialer", "com.samsung.android.dialer", "com.google.android.apps.googlevoice",
        "com.viber.voip", "com.skype.raider", "org.thoughtcrime.securesms",
    )

    val SOCIAL_PKGS = setOf(
        "com.facebook.katana", "com.facebook.lite", "com.instagram.android", "com.instagram.barcelona",
        "com.twitter.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.linkedin.android", "com.google.android.youtube", "com.pinterest",
        "com.snapchat.android", "com.reddit.frontpage", "com.tumblr",
    )

    fun of(ctx: Context, pkg: String, n: Notification?): String {
        if (pkg in COMM_PKGS) return KOMUNIKASI
        if (pkg in SOCIAL_PKGS) return SOSMED
        val appCategory = try {
            ctx.packageManager.getApplicationInfo(pkg, 0).category
        } catch (e: Exception) {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
        if (appCategory == ApplicationInfo.CATEGORY_GAME) return GAME
        if (appCategory == ApplicationInfo.CATEGORY_SOCIAL) return SOSMED
        val nc = n?.category
        if (nc == Notification.CATEGORY_MESSAGE || nc == Notification.CATEGORY_EMAIL ||
            nc == Notification.CATEGORY_CALL || nc == Notification.CATEGORY_MISSED_CALL
        ) return KOMUNIKASI
        return LAINNYA
    }
}
