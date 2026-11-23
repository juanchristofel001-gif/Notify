package com.geto.notifhub

import android.app.Notification
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

/** Penghubung sederhana antara service dan layar utama (satu proses). */
object Bus {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var listener: (() -> Unit)? = null

    fun changed() {
        main.post { listener?.invoke() }
    }
}

/** PendingIntent asli tiap notifikasi, supaya ketukan bisa membuka chat/email yang tepat. */
object IntentStore {
    val map = ConcurrentHashMap<Long, PendingIntent>()
}

class NotifListener : NotificationListenerService() {

    companion object {
        private val SKIP_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM,
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w("NotifHub", "Gagal memproses notifikasi", e)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg in Prefs.IGNORED_APPS) return

        val prefs = Prefs(this)
        if (!prefs.allApps && pkg !in Prefs.KNOWN_APPS) return

        val n = sbn.notification
        // Ringkasan grup ("5 pesan dari 3 chat") dan notifikasi berjalan (musik, upload) dilewati
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        // Pemutar musik, progres unduhan, service latar belakang, dll.
        if (n.category in SKIP_CATEGORIES) return

        val app = appLabel(pkg)
        val category = Categories.of(this, pkg, n)
        val db = Db.get(this)
        val newIds = ArrayList<Long>()
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()

        // 1) Aplikasi chat (WA, Messenger) memakai MessagingStyle: ambil per pesan
        var handledAsMessages = false
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val conv = style.conversationTitle?.toString()?.let { Db.cleanThread(it) }
            val isGroup = style.isGroupConversation
            for (m in style.messages) {
                // person == null artinya pesan dari diri sendiri (balasan kita)
                val sender = m.person?.name?.toString()?.trim() ?: continue
                handledAsMessages = true
                val text = m.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val t = if (!conv.isNullOrEmpty() && conv != sender) "$conv — $sender" else sender
                val ts = if (m.timestamp > 0) m.timestamp else sbn.postTime
                val hash = "m|$pkg|$t|$text|$ts"
                if (db.exists(hash, 0)) continue
                val thread = if (!conv.isNullOrEmpty()) conv else sender
                newIds.add(db.insert(pkg, app, t, text, ts, hash, category, isGroup, true, thread))
            }
        }

        // 2) Notifikasi biasa (email, Facebook, dll.)
        if (!handledAsMessages) {
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return
            val hash = "n|$pkg|$title|$text"
            // Aplikasi sering memperbarui notifikasi yang sama; abaikan duplikat dalam 30 menit
            if (db.exists(hash, sbn.postTime - 30L * 60 * 1000)) return
            val isMessage = n.category == Notification.CATEGORY_MESSAGE
            newIds.add(
                db.insert(
                    pkg, app, title.ifEmpty { app }, text, sbn.postTime, hash, category, false, isMessage,
                    // Chat/email dikelompokkan per pengirim; notifikasi lain per aplikasi
                    if (category == Categories.KOMUNIKASI || isMessage) Db.cleanThread(title) else "",
                )
            )
        }

        if (newIds.isEmpty()) return
        n.contentIntent?.let { pi -> newIds.forEach { IntentStore.map[it] = pi } }
        Bus.changed()
        Classifier.schedule(this)
    }

    private fun appLabel(pkg: String): String =
        Prefs.KNOWN_APPS[pkg] ?: try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg
        }
}
