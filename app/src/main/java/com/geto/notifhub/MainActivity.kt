package com.geto.notifhub

import android.Manifest
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var db: Db
    private lateinit var adapter: NotifAdapter
    private lateinit var groupAdapter: GroupAdapter
    private var grouped = true
    private val collapsedApps = HashSet<String>()
    private var lastItems: List<NotifItem> = emptyList()
    private lateinit var list: RecyclerView
    private lateinit var chips: ChipGroup
    private lateinit var catChips: ChipGroup
    private lateinit var mascotCtl: MascotController
    private lateinit var banner: TextView
    private lateinit var errorView: TextView
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var greeting: TextView
    private lateinit var headline: TextView
    private lateinit var sub: TextView
    private lateinit var bubble: TextView
    private lateinit var mascot: View
    private lateinit var face: MascotFace
    private lateinit var careBtn: com.google.android.material.button.MaterialButton

    private val ui = Handler(Looper.getMainLooper())
    private val debouncedRefresh = Runnable { refresh() }
    private var filter: String? = null
    private var category: String? = null
    private val chipByFilter = LinkedHashMap<String?, Chip>()
    private val chipByCategory = LinkedHashMap<String?, Chip>()
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
        catChips = findViewById(R.id.cat_chips)
        banner = findViewById(R.id.banner)
        errorView = findViewById(R.id.error)
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.empty_text)
        greeting = findViewById(R.id.greeting)
        headline = findViewById(R.id.headline)
        sub = findViewById(R.id.sub)
        bubble = findViewById(R.id.bubble)
        mascot = findViewById(R.id.mascot)
        face = MascotFace(findViewById(R.id.mascot_a), findViewById(R.id.mascot_b))
        careBtn = findViewById(R.id.btn_care)
        careBtn.setOnClickListener { startActivity(Intent(this, HabitActivity::class.java)) }

        adapter = NotifAdapter(::open, ::changePriority)
        groupAdapter = GroupAdapter(::toggleApp, ::openThread)
        grouped = Prefs(this).groupedView
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = if (grouped) groupAdapter else adapter
        list.itemAnimator = DefaultItemAnimator().apply {
            supportsChangeAnimations = false      // tidak berkedip saat isi kartu berubah
            addDuration = 260
            removeDuration = 200
            moveDuration = 280
        }
        // Header menggulung pelan: konten header memudar saat discroll
        val header = findViewById<View>(R.id.header)
        findViewById<AppBarLayout>(R.id.appbar).addOnOffsetChangedListener { bar, offset ->
            val range = bar.totalScrollRange.coerceAtLeast(1)
            val p = (-offset).toFloat() / range
            header.alpha = 1f - 0.5f * p
        }
        attachSwipe()
        buildCategoryChips()
        buildChips()
        mascotCtl = MascotController(mascot, findViewById(R.id.mascot_shadow))

        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_more).setOnClickListener { showMore(it) }
        banner.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        mascot.setOnClickListener {
            mascotCtl.react()
            val picked = face.flashRandom(2200)
            Pet.pat(this)
            showLine(LineBank.next(this, MascotFace.category(picked)))
        }

        greeting.text = greetingText()
        headline.text = "Memuat…"
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
        Bus.listener = {
            ui.removeCallbacks(debouncedRefresh)
            ui.postDelayed(debouncedRefresh, 150)
        }
        mascotCtl.start()
        refresh()
        Classifier.schedule(this, 0)
        LineBank.refill(this)
        updateCareButton()
    }

    override fun onPause() {
        super.onPause()
        Bus.listener = null
        mascotCtl.stop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        face.release()
        countAnim?.cancel()
        super.onDestroy()
    }

    // ---------- Data ----------

    private fun refresh(animateList: Boolean = false) {
        val f = filter
        val cat = category
        io.execute {
            val items = db.query(cat, f)
            val counts = db.unreadCounts(cat)
            val total = db.unreadCounts(null)
            val byCat = db.unreadByCategory()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                submitItems(items) {
                    if (animateList) list.scheduleLayoutAnimation()
                }
                showEmpty(items.isEmpty())
                updateChips(counts)
                updateCategoryChips(byCat, total.values.sum())
                updateHeader(total)
                updateError()
            }
        }
    }

    private fun submitItems(items: List<NotifItem>, after: () -> Unit) {
        lastItems = items
        if (grouped) groupAdapter.submitList(Grouping.build(items, collapsedApps)) { after() }
        else adapter.submitList(items) { after() }
    }

    private fun toggleApp(h: Row.AppHeader) {
        if (!collapsedApps.remove(h.pkg)) collapsedApps.add(h.pkg)
        groupAdapter.submitList(Grouping.build(lastItems, collapsedApps))
    }

    private fun openThread(t: Row.Thread) {
        startActivity(
            Intent(this, ThreadActivity::class.java)
                .putExtra(ThreadActivity.EXTRA_PKG, t.pkg)
                .putExtra(ThreadActivity.EXTRA_THREAD, t.thread)
                .putExtra(ThreadActivity.EXTRA_TITLE, "${t.displayName} · ${t.app}")
        )
    }

    private fun toggleView() {
        grouped = !grouped
        Prefs(this).groupedView = grouped
        list.adapter = if (grouped) groupAdapter else adapter
        Toast.makeText(this, if (grouped) "Tampilan per percakapan" else "Tampilan linimasa", Toast.LENGTH_SHORT).show()
        switchList()
    }

    /** Ganti isi list dengan fade halus, tanpa kedip kosong. */
    private fun switchList() {
        val f = filter
        val cat = category
        val dp = resources.displayMetrics.density
        list.animate().cancel()
        list.animate().alpha(0f).translationY(8 * dp).setDuration(110).withEndAction {
            io.execute {
                val items = db.query(cat, f)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    list.itemAnimator?.endAnimations()
                    submitItems(items) {
                        list.scrollToPosition(0)
                        list.animate().alpha(1f).translationY(0f).setDuration(240)
                            .setInterpolator(DecelerateInterpolator()).start()
                    }
                    showEmpty(items.isEmpty())
                }
            }
        }.start()
        refreshCountsOnly()
    }

    private fun refreshCountsOnly() {
        val cat = category
        io.execute {
            val counts = db.unreadCounts(cat)
            val total = db.unreadCounts(null)
            val byCat = db.unreadByCategory()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateChips(counts)
                updateCategoryChips(byCat, total.values.sum())
                updateHeader(total)
            }
        }
    }

    private fun showEmpty(isEmpty: Boolean) {
        emptyText.text = when {
            filter == null && category == null -> "Belum ada notifikasi.\nYang baru akan muncul otomatis di sini."
            filter == Priority.URGENT -> "Tidak ada yang urgent. Aman!"
            else -> "Kosong di sini."
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

        if (lastUrgent >= 0 && urgent > lastUrgent) {
            mascotCtl.react(strong = true)
            face.flash(R.drawable.mascot_surprised, 2600)
        }
        lastUrgent = urgent
        face.setBase(faceFor(counts))
        updateCareButton()
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

    // ---------- Ekspresi maskot ----------

    private fun faceFor(counts: Map<String, Int>): Int {
        val unread = counts.values.sum()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            !hasAccess -> R.drawable.mascot_cry
            Classifier.lastError != null && Prefs(this).apiKey.isNotEmpty() -> R.drawable.mascot_cry
            (counts[Priority.URGENT] ?: 0) > 0 -> R.drawable.mascot_angry
            Pet.mood(this) < 15 -> R.drawable.mascot_cry
            unread == 0 && (hour >= 22 || hour < 5) -> R.drawable.mascot_sleepy
            unread == 0 -> R.drawable.mascot_happy
            else -> R.drawable.mascot_normal
        }
    }

    /** Kalimat dari maskot; tetap tampil beberapa detik sebelum pesan status kembali. */
    private var lineUntil = 0L

    private fun showLine(text: String) {
        lineUntil = System.currentTimeMillis() + 4500
        bubble.animate().cancel()
        bubble.animate().alpha(0f).translationY(6f).setStartDelay(0).setDuration(110).withEndAction {
            bubble.text = text
            bubble.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private fun updateCareButton() {
        io.execute {
            val (done, total) = db.habitProgress()
            val mood = Pet.mood(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val heart = if (mood < 30) "💔" else "💗"
                careBtn.text = if (total > 0) "$heart Rawat · $done/$total rutinitas" else "$heart Rawat & Rutinitas"
            }
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
            Pet.mood(this) < 15 -> "Aku kangen… rawat aku sebentar dong."
            else -> idleLines[idleIdx % idleLines.size]
        }
    }

    private fun updateBubble(force: Boolean) {
        if (System.currentTimeMillis() < lineUntil) return
        val msg = bubbleMessage()
        if (!force && bubble.text.toString() == msg) return
        bubble.animate().cancel()
        bubble.animate().alpha(0f).translationY(6f).setStartDelay(0).setDuration(120).withEndAction {
            bubble.text = msg
            bubble.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
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
            val chip = layoutInflater.inflate(R.layout.chip_small, chips, false) as Chip
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
                switchList()
            }
        }
    }

    private fun buildCategoryChips() {
        val cats: List<String?> = listOf(null) + Categories.ALL
        cats.forEach { c ->
            val chip = layoutInflater.inflate(R.layout.chip_filter, catChips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = categoryLabel(c, 0)
            catChips.addView(chip)
            chipByCategory[c] = chip
        }
        chipByCategory[null]?.isChecked = true
        catChips.setOnCheckedStateChangeListener { group, ids ->
            val checked = ids.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            val newCat = chipByCategory.entries.firstOrNull { it.value === checked }?.key
            if (newCat != category) {
                category = newCat
                switchList()
            }
        }
    }

    private fun categoryLabel(c: String?, count: Int): String {
        val base = Categories.emoji(c) + " " + (if (c == null) "Semua" else Categories.label(c))
        return if (count > 0) "$base  $count" else base
    }

    private fun updateCategoryChips(byCat: Map<String, Int>, total: Int) {
        chipByCategory.forEach { (c, chip) ->
            chip.text = categoryLabel(c, if (c == null) total else byCat[c] ?: 0)
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
        val dp = resources.displayMetrics.density
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = getColor(R.color.accent) }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14 * dp
            isFakeBoldText = true
        }
        val box = RectF()
        val cb = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder): Float = 0.35f

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (vh.itemViewType == GroupAdapter.TYPE_HEADER && rv.adapter === groupAdapter) 0
                else super.getSwipeDirs(rv, vh)

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isActive: Boolean,
            ) {
                val v = vh.itemView
                if (dX != 0f) {
                    val prog = (kotlin.math.abs(dX) / v.width).coerceIn(0f, 1f)
                    box.set(v.left + 4 * dp, v.top + 5 * dp, v.right - 4 * dp, v.bottom - 5 * dp)
                    bgPaint.alpha = (255 * (0.35f + 0.65f * (prog / 0.35f).coerceAtMost(1f))).toInt()
                    c.drawRoundRect(box, 20 * dp, 20 * dp, bgPaint)
                    val label = "✓ Dibaca"
                    val tw = textPaint.measureText(label)
                    val ty = box.centerY() + textPaint.textSize / 3f
                    val tx = if (dX > 0) box.left + 20 * dp else box.right - 20 * dp - tw
                    textPaint.alpha = (255 * (prog / 0.25f).coerceAtMost(1f)).toInt()
                    c.drawText(label, tx, ty, textPaint)
                    v.alpha = 1f - 0.3f * prog
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isActive)
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (rv.adapter === groupAdapter) {
                    val row = groupAdapter.rowAt(vh.bindingAdapterPosition) as? Row.Thread
                    vh.itemView.alpha = if (row != null && row.unread == 0) 0.6f else 1f
                } else {
                    val item = adapter.itemAt(vh.bindingAdapterPosition)
                    vh.itemView.alpha = if (item?.read == true) 0.55f else 1f
                }
            }

            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (list.adapter === groupAdapter) {
                    val row = groupAdapter.rowAt(pos) as? Row.Thread
                    groupAdapter.notifyItemChanged(pos)
                    if (row != null) io.execute {
                        db.markThreadRead(row.pkg, row.thread)
                        runOnUiThread { refresh() }
                    }
                    return
                }
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
                R.id.action_view -> toggleView()
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
