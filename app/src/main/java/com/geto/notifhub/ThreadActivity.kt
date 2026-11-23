package com.geto.notifhub

import android.app.ActivityOptions
import android.app.PendingIntent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** Semua pesan dalam satu percakapan/aplikasi + ringkasan AI. */
class ThreadActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_THREAD = "thread"
        const val EXTRA_TITLE = "title"
    }

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var db: Db
    private lateinit var adapter: NotifAdapter
    private lateinit var pkg: String
    private lateinit var thread: String
    private var items: List<NotifItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_thread)
        db = Db.get(this)
        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        thread = intent.getStringExtra(EXTRA_THREAD).orEmpty()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        toolbar.setNavigationOnClickListener { finish() }

        adapter = NotifAdapter(::openItem, ::changePriority)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter

        findViewById<View>(R.id.btn_summary).setOnClickListener { summarize() }
        findViewById<View>(R.id.btn_open).setOnClickListener { items.lastOrNull()?.let { openItem(it) } }
        findViewById<View>(R.id.btn_read).setOnClickListener {
            io.execute {
                db.markThreadRead(pkg, thread)
                runOnUiThread {
                    Toast.makeText(this, "Ditandai sudah dibaca", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
        load()
    }

    private fun load() {
        io.execute {
            val data = db.threadItems(pkg, thread)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                items = data
                adapter.submitList(data)
                val unread = data.count { !it.read }
                findViewById<MaterialToolbar>(R.id.toolbar).subtitle =
                    "${data.size} pesan" + if (unread > 0) " · $unread belum dibaca" else ""
            }
        }
    }

    private fun summarize() {
        val prefs = Prefs(this)
        val card = findViewById<MaterialCardView>(R.id.summary_card)
        val text = findViewById<TextView>(R.id.summary)
        val btn = findViewById<MaterialButton>(R.id.btn_summary)
        if (prefs.apiKey.isEmpty()) {
            Toast.makeText(this, "Isi Gemini API key dulu di Pengaturan", Toast.LENGTH_LONG).show()
            return
        }
        if (items.isEmpty()) return
        btn.isEnabled = false
        btn.text = "Meringkas…"
        card.visibility = View.VISIBLE
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(200).start()
        text.text = "Sebentar, lagi aku baca semua pesannya…"
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val snapshot = items
        Thread {
            val result = try {
                GeminiClient.summarize(prefs.apiKey, prefs.model, title, snapshot)
            } catch (e: Exception) {
                "Gagal meringkas: ${e.message}"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                text.text = result
                btn.isEnabled = true
                btn.text = "✨ Ringkas ulang"
            }
        }.start()
    }

    private fun openItem(item: NotifItem) {
        io.execute { db.markRead(item.id) }
        val pi = IntentStore.map[item.id]
        var opened = false
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pi.send(this, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
                opened = true
            } catch (e: PendingIntent.CanceledException) {
                // notifikasi asli sudah hilang
            }
        }
        if (!opened) {
            val launch = packageManager.getLaunchIntentForPackage(item.pkg)
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "Tidak bisa membuka ${item.app}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun changePriority(item: NotifItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Pindahkan ke…")
            .setItems(Priority.LABELS.toTypedArray()) { _, which ->
                io.execute {
                    db.setPriority(item.id, Priority.LEVELS[which], "Diubah manual", "manual")
                    runOnUiThread { load() }
                }
            }
            .show()
    }
}
