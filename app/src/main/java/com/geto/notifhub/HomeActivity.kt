package com.geto.notifhub

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Beranda: tanggal, cuaca, aplikasi favorit, acara, ringkasan notifikasi, rawat, dan berita. */
class HomeActivity : AppCompatActivity() {

    private val io = Executors.newFixedThreadPool(3)
    private val idLocale = Locale("id", "ID")
    private lateinit var db: Db
    private lateinit var face: MascotFace
    private lateinit var motion: MascotController
    private lateinit var bubble: TextView
    private val appsAdapter = AppsAdapter { openApp(it) }
    private var lineUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        db = Db.get(this)

        bubble = findViewById(R.id.bubble)
        val mascot = findViewById<View>(R.id.mascot)
        face = MascotFace(findViewById(R.id.mascot_a), findViewById(R.id.mascot_b))
        motion = MascotController(mascot, findViewById(R.id.mascot_shadow))
        mascot.setOnClickListener {
            motion.react()
            val picked = face.flashRandom(2200)
            Pet.pat(this)
            say(LineBank.next(this, MascotFace.category(picked)), hold = true)
        }

        val apps = findViewById<RecyclerView>(R.id.apps)
        apps.layoutManager = GridLayoutManager(this, 4)
        apps.adapter = appsAdapter

        findViewById<View>(R.id.btn_settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.btn_weather_refresh).setOnClickListener { loadWeather(force = true) }
        findViewById<View>(R.id.btn_apps_edit).setOnClickListener { editPinnedApps() }
        findViewById<View>(R.id.usage_hint).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<View>(R.id.cal_hint).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CAL)
        }
        findViewById<View>(R.id.btn_add_date).setOnClickListener { addDateDialog() }

        val openNotif = View.OnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        findViewById<View>(R.id.card_notif).setOnClickListener(openNotif)
        findViewById<View>(R.id.btn_notif_open).setOnClickListener(openNotif)
        val openCare = View.OnClickListener { startActivity(Intent(this, HabitActivity::class.java)) }
        findViewById<View>(R.id.card_care).setOnClickListener(openCare)
        findViewById<View>(R.id.btn_care_open).setOnClickListener(openCare)
        val openNews = View.OnClickListener { startActivity(Intent(this, NewsActivity::class.java)) }
        findViewById<View>(R.id.card_news).setOnClickListener(openNews)
        findViewById<View>(R.id.btn_news_open).setOnClickListener(openNews)

        // Izin yang dibutuhkan Beranda (sekali minta; semuanya opsional)
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        val sp = getSharedPreferences("home", MODE_PRIVATE)
        if (need.isNotEmpty() && !sp.getBoolean("asked_perms", false)) {
            sp.edit().putBoolean("asked_perms", true).apply()
            requestPermissions(need.toTypedArray(), REQ_LOC)
        }

        playIntro()
    }

    override fun onResume() {
        super.onResume()
        motion.start()
        updateHeader()
        loadWeather(force = false)
        loadApps()
        loadEvents()
        loadNotifSummary()
        loadCare()
        loadNews()
        LineBank.refill(this)
        Classifier.schedule(this, 0)
    }

    override fun onPause() {
        super.onPause()
        motion.stop()
    }

    override fun onDestroy() {
        face.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC) loadWeather(force = true)
        if (requestCode == REQ_CAL) loadEvents()
    }

    companion object {
        private const val REQ_LOC = 10
        private const val REQ_CAL = 11
    }

    // ---------- Header ----------

    private fun updateHeader() {
        val prefs = Prefs(this)
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val salam = when (hour) {
            in 4..10 -> "Selamat pagi"
            in 11..14 -> "Selamat siang"
            in 15..17 -> "Selamat sore"
            else -> "Selamat malam"
        }
        findViewById<TextView>(R.id.greeting).text =
            if (prefs.nickname.isNotEmpty()) "$salam, ${prefs.nickname}!" else "$salam!"
        findViewById<TextView>(R.id.date_big).text = SimpleDateFormat("EEEE, d MMM", idLocale).format(now.time)
        findViewById<TextView>(R.id.date_sub).text = SimpleDateFormat("yyyy", idLocale).format(now.time) +
            " · minggu ke-" + now.get(Calendar.WEEK_OF_YEAR)

        val bday = prefs.birthday
        val isBirthday = bday != null && bday.second == now.get(Calendar.MONTH) + 1 && bday.third == now.get(Calendar.DAY_OF_MONTH)
        if (isBirthday) {
            val age = now.get(Calendar.YEAR) - bday!!.first
            face.setBase(R.drawable.mascot_happy)
            say("🎂 Selamat ulang tahun yang ke-$age! Hari ini harimu!", hold = false)
            motion.react(strong = true)
        } else {
            face.setBase(
                when {
                    Pet.mood(this) < 15 -> R.drawable.mascot_cry
                    hour >= 23 || hour < 5 -> R.drawable.mascot_sleepy
                    else -> R.drawable.mascot_normal
                }
            )
            if (System.currentTimeMillis() > lineUntil) say(LineBank.next(this, if (hour >= 23 || hour < 5) "sleepy" else "normal"), hold = false)
        }
    }

    private fun say(text: String, hold: Boolean) {
        if (hold) lineUntil = System.currentTimeMillis() + 5000
        bubble.animate().cancel()
        bubble.animate().alpha(0f).translationY(6f).setStartDelay(0).setDuration(110).withEndAction {
            bubble.text = text
            bubble.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private fun playIntro() {
        val dp = resources.displayMetrics.density
        val cards = listOf(
            R.id.card_weather, R.id.card_apps, R.id.card_events, R.id.card_notif, R.id.card_care, R.id.card_news,
        ).map { findViewById<View>(it) }
        cards.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 24 * dp
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i + 120).setDuration(380)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // ---------- Cuaca ----------

    private fun loadWeather(force: Boolean) {
        val icon = findViewById<TextView>(R.id.w_icon)
        val temp = findViewById<TextView>(R.id.w_temp)
        val desc = findViewById<TextView>(R.id.w_desc)
        val forecast = findViewById<LinearLayout>(R.id.w_forecast)
        io.execute {
            val r = try { Result.success(Weather.load(this, force)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess { w ->
                    val (emo, label) = Weather.describe(w.code)
                    icon.text = emo
                    temp.text = "${w.temp.toInt()}°"
                    desc.text = "$label · terasa ${w.feels.toInt()}° · lembap ${w.humidity}%\n📍 ${w.place}"
                    findViewById<TextView>(R.id.w_title).text = "Cuaca"
                    forecast.removeAllViews()
                    val dayFmt = SimpleDateFormat("EEE", idLocale)
                    val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    w.days.forEachIndexed { i, d ->
                        val tv = TextView(this)
                        val name = if (i == 0) "Hari ini" else parse.parse(d.date)?.let { dayFmt.format(it) } ?: d.date
                        tv.text = "$name\n${Weather.describe(d.code).first}\n${d.max.toInt()}° / ${d.min.toInt()}°"
                        tv.gravity = android.view.Gravity.CENTER
                        tv.textSize = 12f
                        tv.setTextColor(getColor(R.color.text_secondary))
                        forecast.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                }.onFailure { e ->
                    desc.text = "Cuaca belum bisa dimuat: ${e.message ?: "cek koneksi internet"}"
                }
            }
        }
    }

    // ---------- Aplikasi ----------

    private fun loadApps() {
        val hint = findViewById<View>(R.id.usage_hint)
        io.execute {
            val list = QuickApps.load(this)
            val usage = QuickApps.hasUsageAccess(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                appsAdapter.submit(list)
                hint.visibility = if (usage) View.GONE else View.VISIBLE
            }
        }
    }

    private fun openApp(app: QuickApps.App) {
        val i = packageManager.getLaunchIntentForPackage(app.pkg)
        if (i != null) startActivity(i) else Toast.makeText(this, "Tidak bisa membuka ${app.label}", Toast.LENGTH_SHORT).show()
    }

    private fun editPinnedApps() {
        io.execute {
            val all = QuickApps.launchable(this)
            runOnUiThread {
                val prefs = Prefs(this)
                val pinned = prefs.pinnedApps.toMutableSet()
                val labels = all.map { it.second }.toTypedArray()
                val checked = all.map { it.first in pinned }.toBooleanArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Pin aplikasi favorit")
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        if (isChecked) pinned += all[which].first else pinned -= all[which].first
                    }
                    .setPositiveButton("Simpan") { _, _ ->
                        // pertahankan urutan: yang sudah di-pin dulu, lalu yang baru
                        val old = prefs.pinnedApps.filter { it in pinned }
                        prefs.pinnedApps = old + pinned.filter { it !in old }
                        loadApps()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        }
    }

    private class AppsAdapter(val onClick: (QuickApps.App) -> Unit) : RecyclerView.Adapter<AppsAdapter.VH>() {
        private var items: List<QuickApps.App> = emptyList()
        fun submit(l: List<QuickApps.App>) {
            items = l
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val label: TextView = v.findViewById(R.id.label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_quick_app, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val a = items[position]
            h.icon.setImageDrawable(a.icon)
            h.label.text = (if (a.pinned) "📌 " else "") + a.label
            h.itemView.setOnClickListener { onClick(a) }
        }
    }

    // ---------- Acara ----------

    private fun row(container: LinearLayout, icon: String, title: String, sub: String, trailing: String = "", onClick: (() -> Unit)? = null, onLong: (() -> Unit)? = null) {
        val v = layoutInflater.inflate(R.layout.item_row, container, false)
        v.findViewById<TextView>(R.id.icon).text = icon
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.subtitle).text = sub
        v.findViewById<TextView>(R.id.trailing).text = trailing
        if (onClick != null) v.setOnClickListener { onClick() }
        if (onLong != null) v.setOnLongClickListener { onLong(); true }
        container.addView(v)
    }

    private fun loadEvents() {
        val box = findViewById<LinearLayout>(R.id.events)
        val hint = findViewById<View>(R.id.cal_hint)
        io.execute {
            val list = Events.upcoming(this)
            val calOk = Events.hasCalendarAccess(this)
            val hasBirthday = Prefs(this).birthday != null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                hint.visibility = if (calOk) View.GONE else View.VISIBLE
                box.removeAllViews()
                if (list.isEmpty()) {
                    row(box, "🗓️", "Belum ada acara dalam 30 hari", if (hasBirthday) "Tambah tanggal penting dengan + Tanggal" else "Isi tanggal lahirmu di Pengaturan")
                }
                list.forEach { e ->
                    val trailing = when (e.daysAway) { 0 -> "HARI INI"; 1 -> "BESOK"; else -> "" }
                    row(box, e.emoji, e.title, Events.whenLabel(e), trailing,
                        onLong = e.personalId?.let { id -> { confirmDeleteDate(id, e.title) } })
                }
            }
        }
    }

    private fun addDateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_date, null)
        val name = view.findViewById<EditText>(R.id.in_name)
        val emoji = view.findViewById<EditText>(R.id.in_emoji)
        val pick = view.findViewById<MaterialButton>(R.id.btn_pick_date)
        val yearly = view.findViewById<MaterialSwitch>(R.id.sw_yearly)
        val cal = Calendar.getInstance()
        var chosen = false
        pick.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d)
                chosen = true
                pick.text = SimpleDateFormat("d MMMM yyyy", idLocale).format(cal.time)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Tanggal penting")
            .setView(view)
            .setPositiveButton("Simpan") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty() || !chosen) {
                    Toast.makeText(this, "Isi nama dan pilih tanggalnya dulu", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val e = emoji.text.toString().trim().ifEmpty { "🎉" }
                io.execute {
                    db.addPersonalDate(n, e, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), yearly.isChecked)
                    runOnUiThread { loadEvents() }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmDeleteDate(id: Long, title: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage("Hapus \"$title\"?")
            .setPositiveButton("Hapus") { _, _ ->
                io.execute {
                    db.deletePersonalDate(id)
                    runOnUiThread { loadEvents() }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------- Ringkasan notifikasi, rawat, berita ----------

    private fun loadNotifSummary() {
        val box = findViewById<LinearLayout>(R.id.notif_preview)
        io.execute {
            val counts = db.unreadCounts(null)
            val unread = counts.values.sum()
            val urgent = counts[Priority.URGENT] ?: 0
            val penting = counts[Priority.PENTING] ?: 0
            val top = Grouping.build(
                db.query(null, null).filter { !it.read && (it.priority == Priority.URGENT || it.priority == Priority.PENTING) },
                emptySet(),
            ).filterIsInstance<Row.Thread>().take(3)
            val access = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                findViewById<TextView>(R.id.notif_title).text =
                    if (unread > 0) "🔔 $unread belum dibaca" else "🔔 Notifikasi bersih"
                box.removeAllViews()
                if (!access) {
                    row(box, "⚠️", "Akses notifikasi belum aktif", "Ketuk untuk mengaktifkan", onClick = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    })
                }
                if (urgent + penting > 0) {
                    row(box, "🚨", "$urgent urgent · $penting penting", "Prioritas tertinggi di bawah ini")
                }
                top.forEach { t ->
                    val preview = t.last.body.ifEmpty { t.last.title }
                    row(box, t.displayName.firstOrNull()?.toString() ?: "•", "${t.displayName} · ${t.app}", preview,
                        Priority.label(t.topPriority).uppercase(Locale.ROOT), onClick = {
                            startActivity(
                                Intent(this, ThreadActivity::class.java)
                                    .putExtra(ThreadActivity.EXTRA_PKG, t.pkg)
                                    .putExtra(ThreadActivity.EXTRA_THREAD, t.thread)
                                    .putExtra(ThreadActivity.EXTRA_TITLE, "${t.displayName} · ${t.app}")
                            )
                        })
                }
            }
        }
    }

    private fun loadCare() {
        val box = findViewById<LinearLayout>(R.id.care_preview)
        io.execute {
            val (done, total) = db.habitProgress()
            val mood = Pet.mood(this)
            val level = Pet.level(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                box.removeAllViews()
                row(box, "✅", if (total > 0) "Rutinitas $done/$total selesai" else "Belum ada rutinitas", "Ketuk untuk kerjain bareng")
                row(box, if (mood < 30) "💔" else "💗", "Mood: ${Pet.moodLabel(mood)} · Afeksi Lv. $level", "Afeksi naik dari pencapaian")
            }
        }
    }

    private fun loadNews() {
        val box = findViewById<LinearLayout>(R.id.news_preview)
        val topic = News.TOPICS.first()
        fun render(items: List<News.Item>, err: String?) {
            box.removeAllViews()
            if (items.isEmpty()) {
                row(box, "📰", if (err != null) "Berita belum bisa dimuat" else "Memuat berita…", err ?: "")
                return
            }
            items.take(3).forEach { n ->
                row(box, "📰", n.title, "${n.source} · ${relTime(n.time)}", onClick = { openLink(n.link) })
            }
        }
        render(News.cached(this, topic), null)
        io.execute {
            val r = try { Result.success(News.load(this, topic)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(r.getOrDefault(News.cached(this, topic)), r.exceptionOrNull()?.message)
            }
        }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak ada browser untuk membuka tautan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun relTime(t: Long): String {
        if (t <= 0) return ""
        val min = (System.currentTimeMillis() - t) / 60_000
        return when {
            min < 1 -> "baru saja"
            min < 60 -> "$min menit lalu"
            min < 24 * 60 -> "${min / 60} jam lalu"
            else -> SimpleDateFormat("d MMM", idLocale).format(Date(t))
        }
    }
}
