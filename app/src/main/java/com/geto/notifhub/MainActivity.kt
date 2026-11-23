package com.geto.notifhub

import android.Manifest
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var db: Db
    private lateinit var adapter: NotifAdapter
    private lateinit var tabs: TabLayout
    private lateinit var banner: TextView
    private lateinit var errorView: TextView
    private lateinit var empty: TextView

    /** Urutan tab: Semua, Urgent, Penting, Normal, Rendah */
    private val filters: List<String?> = listOf(null) + Priority.LEVELS
    private val tabLabels = listOf("Semua") + Priority.LABELS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Db.get(this)

        banner = findViewById(R.id.banner)
        errorView = findViewById(R.id.error)
        empty = findViewById(R.id.empty)
        tabs = findViewById(R.id.tabs)
        val list = findViewById<RecyclerView>(R.id.list)

        adapter = NotifAdapter(::open, ::changePriority)
        list.layoutManager = LinearLayoutManager(this)
        list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        list.adapter = adapter

        tabLabels.forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = refresh()
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        banner.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        banner.visibility = if (granted) View.GONE else View.VISIBLE
        Bus.listener = { refresh() }
        refresh()
        Classifier.schedule(this, 0)
    }

    override fun onPause() {
        super.onPause()
        Bus.listener = null
    }

    private fun refresh() {
        val pos = tabs.selectedTabPosition.coerceAtLeast(0)
        val filter = filters[pos]
        io.execute {
            val items = db.query(filter)
            val counts = db.unreadCounts()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submit(items)
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                for (i in 1 until tabLabels.size) {
                    val c = counts[filters[i]] ?: 0
                    tabs.getTabAt(i)?.text = if (c > 0) "${tabLabels[i]} ($c)" else tabLabels[i]
                }
                val err = Classifier.lastError
                if (err != null && Prefs(this).apiKey.isNotEmpty()) {
                    errorView.text = "AI gagal, sementara pakai aturan kata kunci: ${err.take(160)}"
                    errorView.visibility = View.VISIBLE
                } else {
                    errorView.visibility = View.GONE
                }
            }
        }
    }

    private fun open(item: NotifItem) {
        io.execute { db.markRead(item.id) }
        val pi = IntentStore.map[item.id]
        var opened = false
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    pi.send(this, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
                opened = true
            } catch (e: PendingIntent.CanceledException) {
                // notifikasi asli sudah dihapus; buka aplikasinya saja
            }
        }
        if (!opened) {
            val launch = packageManager.getLaunchIntentForPackage(item.pkg)
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "Tidak bisa membuka ${item.app}", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun changePriority(item: NotifItem) {
        AlertDialog.Builder(this)
            .setTitle("Pindahkan ke…")
            .setItems(Priority.LABELS.toTypedArray()) { _, which ->
                io.execute {
                    db.setPriority(item.id, Priority.LEVELS[which], "Diubah manual", "manual")
                    runOnUiThread { refresh() }
                }
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_reprocess -> io.execute {
                val n = db.resetRuleBased()
                Classifier.schedule(this, 0)
                runOnUiThread {
                    Toast.makeText(this, "$n notifikasi dikirim ulang ke AI", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            R.id.action_read_all -> io.execute {
                db.markAllRead()
                runOnUiThread { refresh() }
            }
            R.id.action_clear -> AlertDialog.Builder(this)
                .setMessage("Hapus semua notifikasi yang tersimpan di NotifHub?")
                .setPositiveButton("Hapus") { _, _ ->
                    io.execute {
                        db.deleteAll()
                        IntentStore.map.clear()
                        runOnUiThread { refresh() }
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
