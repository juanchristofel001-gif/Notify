package com.geto.notifhub

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import java.util.Locale

object Categories {
    const val KOMUNIKASI = "komunikasi"
    const val SOSMED = "sosmed"
    const val KEUANGAN = "keuangan"
    const val BELANJA = "belanja"
    const val TRANSPORT = "transport"
    const val GAME = "game"
    const val SISTEM = "sistem"
    const val LAINNYA = "lainnya"

    val ALL = listOf(KOMUNIKASI, KEUANGAN, BELANJA, TRANSPORT, SOSMED, GAME, SISTEM, LAINNYA)

    fun label(c: String): String = when (c) {
        KOMUNIKASI -> "Komunikasi"
        SOSMED -> "Sosmed"
        KEUANGAN -> "Keuangan"
        BELANJA -> "Belanja"
        TRANSPORT -> "Transport & Makan"
        GAME -> "Game"
        SISTEM -> "Sistem"
        else -> "Lainnya"
    }

    fun emoji(c: String?): String = when (c) {
        KOMUNIKASI -> "💬"
        SOSMED -> "📱"
        KEUANGAN -> "💳"
        BELANJA -> "🛒"
        TRANSPORT -> "🛵"
        GAME -> "🎮"
        SISTEM -> "⚙️"
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

    private val FINANCE_PKGS = setOf(
        "com.bca", "com.bca.mybca.omni.android", "id.co.bri.brimo", "id.bmri.livin", "src.com.bni",
        "com.bcadigital.blu", "com.jago.digitalBanking", "id.co.bankbkemobile.digitalbank",
        "ovo.id", "id.dana", "com.gojek.gopay", "com.telkom.mwallet", "id.flip",
        "com.bibit.bibitid", "com.btpn.dc", "com.kredivo.userapp", "io.akulaku",
        "com.paypal.android.p2pmobile", "com.google.android.apps.walletnfcrel",
    )
    /** Potongan nama paket yang menandakan aplikasi keuangan. */
    private val FINANCE_TOKENS = listOf(
        "bank", "wallet", "gopay", "ovo", "brimo", "livin", "mandiri", "seabank", "jenius",
        "digibank", "kredivo", "akulaku", "bibit", "ajaib", "pluang", "stockbit", "linkaja", "paypal",
    )

    private val SHOP_PKGS = setOf(
        "com.shopee.id", "com.tokopedia.tkpd", "com.lazada.android", "blibli.mobile.commerce",
        "com.bukalapak.android", "com.zalora.android", "com.amazon.mShop.android.shopping",
        "com.alibaba.aliexpresshd", "com.tiket.gits", "com.traveloka.android",
    )

    private val TRANSPORT_PKGS = setOf(
        "com.gojek.app", "com.grabtaxi.passenger", "com.taxsee.taxsee", "sinet.startup.inDriver",
        "com.maxim.taxi", "com.bluebird.mobile", "com.kai.kaiticketing",
    )

    private val SYSTEM_PREFIXES = listOf(
        "android", "com.android.", "com.samsung.", "com.sec.", "com.google.android.gms",
        "com.google.android.apps.photos", "com.google.android.packageinstaller", "com.miui.",
        "com.xiaomi.", "com.coloros.", "com.oplus.", "com.oppo.", "com.vivo.", "com.huawei.",
        "com.transsion.", "com.realme.",
    )

    // ---------- Kata kunci isi notifikasi ----------
    private val PROMO_KW = listOf(
        "promo", "diskon", "cashback", "voucher", "flash sale", "gratis ongkir", "kupon", "% off",
        "penawaran", "spesial untukmu", "khusus kamu", "buruan", "jangan sampai ketinggalan",
    )
    private val SECURITY_KW = listOf(
        "otp", "kode verifikasi", "kode keamanan", "verification code", "login baru", "new login",
        "perangkat baru", "transaksi mencurigakan", "jangan berikan kode", "kode pin", "pin kamu",
    )
    private val PAYMENT_KW = listOf(
        "pembayaran berhasil", "pembayaran sukses", "payment successful", "payment received",
        "berhasil dibayar", "telah dibayar", "transaksi berhasil", "pembelian berhasil", "kamu membayar",
    )
    private val TRANSFER_KW = listOf(
        "transfer masuk", "dana masuk", "uang masuk", "kamu menerima", "you received", "saldo",
        "top up", "topup", "isi saldo", "mutasi", "transfer keluar", "berhasil transfer", "debit", "kredit masuk",
    )
    private val BILL_KW = listOf("tagihan", "jatuh tempo", "cicilan", "bill", "due date", "belum dibayar", "segera bayar")
    private val ORDER_KW = listOf(
        "pesanan", "paket", "dikirim", "kurir", "resi", "sedang diantar", "telah tiba", "diterima oleh",
        "order", "pengiriman", "dikemas",
    )
    private val TRIP_KW = listOf("driver", "pengemudi", "perjalanan", "sudah tiba", "menjemput", "makananmu", "pesanan makanan")
    private val SOCIAL_INTERACT_KW = listOf(
        "menyukai", "likes your", "liked your", "mengomentari", "commented", "membalas", "replied",
        "menyebut", "mentioned", "tagged", "menandai", "mengikuti", "followed", "started following",
    )
    private val SOCIAL_RECO_KW = listOf("live", "siaran", "suggested", "saran", "rekomendasi", "kenangan", "memories", "trending", "video baru", "new video")

    private fun hasAny(text: String, kws: List<String>) = kws.any { text.contains(it) }

    private fun isFinancePkg(pkg: String): Boolean {
        if (pkg in FINANCE_PKGS) return true
        val p = pkg.lowercase(Locale.ROOT)
        return FINANCE_TOKENS.any { p.contains(it) } || p.split('.').any { it == "dana" || it == "bca" || it == "bni" || it == "bri" || it == "bsi" }
    }

    private fun isFinanceText(t: String): Boolean =
        !hasAny(t, PROMO_KW) && (hasAny(t, PAYMENT_KW) || hasAny(t, TRANSFER_KW) || hasAny(t, BILL_KW))

    /** Kategori notifikasi berdasarkan aplikasi + isi. */
    fun of(ctx: Context, pkg: String, n: Notification?, title: String = "", body: String = ""): String {
        val text = "$title $body".lowercase(Locale.ROOT)
        if (pkg in COMM_PKGS) return KOMUNIKASI
        if (pkg in SOCIAL_PKGS) return SOSMED
        if (isFinancePkg(pkg)) return KEUANGAN
        // Pembayaran/transfer/tagihan dari aplikasi apa pun (Shopee, Gojek, dll.) → Keuangan
        if (isFinanceText(text)) return KEUANGAN
        if (pkg in SHOP_PKGS) return BELANJA
        if (pkg in TRANSPORT_PKGS) return TRANSPORT

        val appCategory = try {
            ctx.packageManager.getApplicationInfo(pkg, 0).category
        } catch (e: Exception) {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
        if (appCategory == ApplicationInfo.CATEGORY_GAME) return GAME
        if (appCategory == ApplicationInfo.CATEGORY_SOCIAL) return SOSMED
        if (SYSTEM_PREFIXES.any { pkg == it.trimEnd('.') || pkg.startsWith(it) }) return SISTEM

        val nc = n?.category
        if (nc == Notification.CATEGORY_MESSAGE || nc == Notification.CATEGORY_EMAIL ||
            nc == Notification.CATEGORY_CALL || nc == Notification.CATEGORY_MISSED_CALL
        ) return KOMUNIKASI
        if (hasAny(text, ORDER_KW)) return BELANJA
        return LAINNYA
    }

    /**
     * Sub-kelompok untuk notifikasi non-chat, supaya di dalam satu aplikasi
     * notifikasinya terpisah per jenis (mis. Shopee: "Pembayaran", "Pesanan & Pengiriman", "Promo").
     * String kosong = digabung per aplikasi saja.
     */
    fun kind(category: String, title: String, body: String): String {
        val t = "$title $body".lowercase(Locale.ROOT)
        if (hasAny(t, SECURITY_KW)) return "Keamanan & OTP"
        return when (category) {
            KEUANGAN -> when {
                hasAny(t, PROMO_KW) -> "Promo"
                hasAny(t, BILL_KW) -> "Tagihan"
                hasAny(t, PAYMENT_KW) -> "Pembayaran"
                hasAny(t, TRANSFER_KW) -> "Transfer & Saldo"
                else -> "Info akun"
            }
            BELANJA -> when {
                hasAny(t, PROMO_KW) -> "Promo"
                hasAny(t, ORDER_KW) -> "Pesanan & Pengiriman"
                else -> ""
            }
            TRANSPORT -> when {
                hasAny(t, PROMO_KW) -> "Promo"
                hasAny(t, TRIP_KW) || hasAny(t, ORDER_KW) -> "Perjalanan & Pesanan"
                else -> ""
            }
            SOSMED -> when {
                hasAny(t, SOCIAL_INTERACT_KW) -> "Interaksi"
                hasAny(t, SOCIAL_RECO_KW) -> "Rekomendasi & Live"
                hasAny(t, PROMO_KW) -> "Promo"
                else -> ""
            }
            else -> if (hasAny(t, PROMO_KW)) "Promo" else ""
        }
    }
}
