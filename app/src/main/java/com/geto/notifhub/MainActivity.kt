package com.geto.notifhub

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var db: Db
    private lateinit var adapter: NotifAdapter
    private lateinit var list: RecyclerView
    private lateinit var chips: ChipGroup
    private lateinit var banner: TextView
    private lateinit var errorView: TextView
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var greeting: TextView
    private lateinit var headline: TextView
    private lateinit var sub: TextView
    private lateinit var bubble: TextView
    private lateinit var mascot: ImageView

    private var filter: String? = null
    private val chipByFilter = LinkedHashMap<String?, Chip>()
    private val animators = ArrayList<ValueAnimator>()
    private var countAnim: ValueAnimator? = null
    private var shownUnread = 0
    private var lastUrgent = -1
    private var idleIdx = 0
    private var lastCounts: Map<String, Int> = emptyMap()
    private var hasAccess = true

    private val idleLines = listOf(
        "Semua aman~ santai dulu aja.",
        "Belum ada yang mendesak kok.",
        "Aku jagain notifikasimu ya!",
        "Tekan lama kartu kalau aku salah milih prioritas.",
        "Geser kartu ke samping untuk tandai sudah dibaca.",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Db.get(this)

        list = findViewById(R.id.list)
        chips = findViewById(R.id.chips)
        banner = findViewById(R.id.banner)
        errorView = findViewById(R.id.error)
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.empty_text)
        greeting = findViewById(R.id.greeting)
        headline = findViewById(R.id.headline)
        sub = findViewById(R.id.sub)
        bubble = findViewById(R.id.bubble)
        mascot = findViewById(R.id.mascot)

        adapter = NotifAdapter(::open, ::changePriority)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        attachSwipe()
        buildChips()

        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_more).setOnClickListener { showMore(it) }
        banner.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        mascot.setOnClickListener {
            bounceMascot()
            idleIdx++
            updateBubble(force = true)
        }

        greeting.text = greetingText()
        headline.text = "Memuat…"
        startMascotIdle()
        playIntro()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        refresh(animateList = true)
    }

    override fun onResume() {
        super.onResume()
        hasAccess = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        banner.visibility = if (hasAccess) View.GONE else View.VISIBLE
        greeting.text = greetingText()
        Bus.listener = { refresh() }
        refresh()
        Classifier.schedule(this, 0)
    }

    override fun onPause() {
        super.onPause()
        Bus.listener = null
    }

    override fun onDestroy() {
        animators.forEach { it.cancel() }
        countAnim?.cancel()
        super.onDestroy()
    }

    // ---------- Data ----------

    private fun refresh(animateList: Boolean = false) {
        val f = filter
        io.execute {
            val items = db.query(f)
            val counts = db.unreadCounts()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submitList(items) {
                    if (animateList) list.scheduleLayoutAnimation()
                }
                showEmpty(items.isEmpty())
                updateChips(counts)
                updateHeader(counts)
                updateError()
            }
        }
    }

    private fun showEmpty(isEmpty: Boolean) {
        emptyText.text = when (filter) {
            null -> "Belum ada notifikasi.\nYang baru akan muncul otomatis di sini."
            Priority.URGENT -> "Tidak ada yang urgent. Aman!"
            else -> "Kosong di kategori ini."
        }
        if (isEmpty && empty.visibility != View.VISIBLE) {
            empty.alpha = 0f
            empty.visibility = View.VISIBLE
            empty.animate().alpha(1f).setDuration(300).start()
        } else if (!isEmpty) {
            empty.visibility = View.GONE
        }
    }

    // ---------- Header & maskot ----------

    private fun updateHeader(counts: Map<String, Int>) {
        lastCounts = counts
        val unread = counts.values.sum()
        val urgent = counts[Priority.URGENT] ?: 0
        val penting = counts[Priority.PENTING] ?: 0

        animateCount(unread)
        sub.text = if (unread == 0) "Kotak notifikasi bersih" else "$urgent urgent · $penting penting"

        if (lastUrgent >= 0 && urgent > lastUrgent) bounceMascot()
        lastUrgent = urgent
        updateBubble(force = false)
    }

    private fun animateCount(target: Int) {
        countAnim?.cancel()
        if (target == 0) {
            shownUnread = 0
            headline.text = "Semua beres"
            return
        }
        countAnim = ValueAnimator.ofInt(shownUnread, target).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Int
                shownUnread = v
                headline.text = "$v belum dibaca"
            }
            start()
        }
    }

    private fun bubbleMessage(): String {
        val urgent = lastCounts[Priority.URGENT] ?: 0
        val penting = lastCounts[Priority.PENTING] ?: 0
        return when {
            !hasAccess -> "Nyalakan akses notifikasi dulu ya, biar aku bisa bantu!"
            Classifier.lastError != null && Prefs(this).apiKey.isNotEmpty() ->
                "Gemini lagi error, sementara aku pakai aturan dulu."
            urgent > 0 -> "Ada $urgent yang urgent, cek sekarang ya!"
            penting > 0 -> "$penting pesan penting nunggu dibalas."
            else -> idleLines[idleIdx % idleLines.size]
        }
    }

    private fun updateBubble(force: Boolean) {
        val msg = bubbleMessage()
        if (!force && bubble.text.toString() == msg) return
        bubble.animate().cancel()
        bubble.animate().alpha(0f).translationY(6f).setStartDelay(0).setDuration(120).withEndAction {
            bubble.text = msg
            bubble.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private fun startMascotIdle() {
        val dp = resources.displayMetrics.density
        animators += ObjectAnimator.ofFloat(mascot, View.TRANSLATION_Y, 0f, -8 * dp).apply {
            duration = 1800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        animators += ObjectAnimator.ofFloat(mascot, View.ROTATION, -2.5f, 2.5f).apply {
            duration = 2600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun bounceMascot() {
        mascot.animate().cancel()
        mascot.animate().scaleX(1.15f).scaleY(1.15f).setStartDelay(0).setDuration(140)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                mascot.animate().scaleX(1f).scaleY(1f).setDuration(420)
                    .setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
    }

    private fun playIntro() {
        val dp = resources.displayMetrics.density
        mascot.alpha = 0f
        mascot.translationX = 40 * dp
        mascot.animate().alpha(1f).translationX(0f).setStartDelay(150).setDuration(600)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
        listOf(greeting, headline, sub, bubble).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 12 * dp
            v.animate().alpha(if (v === greeting || v === sub) 0.85f else 1f).translationY(0f)
                .setStartDelay(80L * i).setDuration(400).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun greetingText(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 4..10 -> "Selamat pagi"
        in 11..14 -> "Selamat siang"
        in 15..17 -> "Selamat sore"
        else -> "Selamat malam"
    }

    // ---------- Filter chip ----------

    private fun buildChips() {
        val filters: List<String?> = listOf(null) + Priority.LEVELS
        filters.forEach { f ->
            val chip = layoutInflater.inflate(R.layout.chip_filter, chips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = chipLabel(f, 0)
            chips.addView(chip)
            chipByFilter[f] = chip
        }
        chipByFilter[null]?.isChecked = true
        chips.setOnCheckedStateChangeListener { group, ids ->
            val checked = ids.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            val newFilter = chipByFilter.entries.firstOrNull { it.value === checked }?.key
            if (newFilter != filter) {
                filter = newFilter
                adapter.submitList(null)
                refresh(animateList = true)
            }
        }
    }

    private fun chipLabel(f: String?, count: Int): String {
        val base = if (f == null) "Semua" else Priority.label(f)
        return if (count > 0) "$base  $count" else base
    }

    private fun updateChips(counts: Map<String, Int>) {
        chipByFilter.forEach { (f, chip) ->
            val c = if (f == null) counts.values.sum() else counts[f] ?: 0
            chip.text = chipLabel(f, c)
        }
    }

    private fun updateError() {
        val err = Classifier.lastError
        if (err != null && Prefs(this).apiKey.isNotEmpty()) {
            errorView.text = "Gemini gagal, sementara dipilah pakai aturan kata kunci.\n${err.take(160)}"
            errorView.visibility = View.VISIBLE
        } else {
            errorView.visibility = View.GONE
        }
    }

    // ---------- Aksi kartu ----------

    private fun attachSwipe() {
        val cb = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                val item = adapter.itemAt(pos)
                adapter.notifyItemChanged(pos)
                if (item == null) return
                io.execute {
                    db.markRead(item.id)
                    runOnUiThread { refresh() }
                }
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(list)
    }

    private fun open(item: NotifItem) {
        io.execute {
            db.markRead(item.id)
            runOnUiThread { refresh() }
        }
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
                // notifikasi asli sudah hilang; buka aplikasinya saja
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
                    runOnUiThread { refresh() }
                }
            }
            .show()
    }

    private fun showMore(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.menu.removeItem(R.id.action_settings)
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
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
                R.id.action_clear -> MaterialAlertDialogBuilder(this)
                    .setMessage("Hapus semua notifikasi yang tersimpan di NotifHub?")
                    .setPositiveButton("Hapus") { _, _ ->
                        io.execute {
                            db.deleteAll()
                            IntentStore.map.clear()
                            runOnUiThread { refresh(animateList = true) }
                        }
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            true
        }
        popup.show()
    }
}
