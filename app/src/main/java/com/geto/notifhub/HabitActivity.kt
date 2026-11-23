package com.geto.notifhub

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors
import kotlin.random.Random

class HabitActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var db: Db
    private lateinit var face: MascotFace
    private lateinit var motion: MascotController
    private lateinit var stage: FrameLayout
    private lateinit var bubble: TextView
    private lateinit var levelView: TextView
    private lateinit var moodLabel: TextView
    private lateinit var moodBar: LinearProgressIndicator
    private lateinit var xpLabel: TextView
    private lateinit var xpBar: LinearProgressIndicator
    private lateinit var habitTitle: TextView
    private lateinit var habitBar: LinearProgressIndicator
    private lateinit var habitEmpty: TextView
    private val adapter = HabitAdapter(::toggle, ::confirmDelete)
    private val achAdapter = AchievementAdapter(::claim, ::confirmDeleteAch)
    private lateinit var achEmpty: TextView

    private val achPresets = listOf(
        Triple("💼", "Selesai magang hari ini", 15),
        Triple("🎬", "Bikin konten Rispar", 20),
        Triple("📄", "Kirim lamaran kerja", 20),
        Triple("📚", "Selesaikan tugas kuliah", 15),
        Triple("✍️", "Progres paper / jurnal", 20),
        Triple("🏋️", "Latihan gym full", 10),
    )

    private val presets = listOf(
        "💧" to "Minum air 8 gelas", "🏃" to "Olahraga 20 menit", "📖" to "Baca 10 halaman",
        "🛏️" to "Tidur sebelum jam 23", "🧘" to "Stretching pagi", "🚶" to "Jalan kaki 5.000 langkah",
        "📝" to "Belajar 30 menit", "🧹" to "Rapikan kamar",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_habit)
        db = Db.get(this)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        stage = findViewById(R.id.stage)
        bubble = findViewById(R.id.bubble)
        levelView = findViewById(R.id.level)
        moodLabel = findViewById(R.id.mood_label)
        moodBar = findViewById(R.id.mood_bar)
        xpLabel = findViewById(R.id.xp_label)
        xpBar = findViewById(R.id.xp_bar)
        habitTitle = findViewById(R.id.habit_title)
        habitBar = findViewById(R.id.habit_bar)
        habitEmpty = findViewById(R.id.habit_empty)
        achEmpty = findViewById(R.id.ach_empty)
        val achList = findViewById<RecyclerView>(R.id.achievements)
        achList.layoutManager = LinearLayoutManager(this)
        achList.adapter = achAdapter
        achList.itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }
        findViewById<View>(R.id.btn_add_ach).setOnClickListener { showAddAchievement() }

        val mascot = findViewById<View>(R.id.mascot)
        face = MascotFace(findViewById(R.id.mascot_a), findViewById(R.id.mascot_b))
        motion = MascotController(mascot, findViewById(R.id.mascot_shadow))

        val list = findViewById<RecyclerView>(R.id.habits)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }

        mascot.setOnClickListener { pat() }
        findViewById<View>(R.id.btn_pat).setOnClickListener { pat() }
        findViewById<View>(R.id.btn_feed).setOnClickListener { feed() }
        findViewById<View>(R.id.btn_add).setOnClickListener { showAddDialog() }

        say(LineBank.next(this, "happy"))
        LineBank.refill(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        motion.start()
    }

    override fun onPause() {
        super.onPause()
        motion.stop()
    }

    override fun onDestroy() {
        face.release()
        super.onDestroy()
    }

    // ---------- Data ----------

    private fun refresh() {
        io.execute {
            val habits = db.habits()
            val achs = db.achievements()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                achAdapter.submit(achs)
                achEmpty.visibility = if (achs.isEmpty()) View.VISIBLE else View.GONE
                adapter.submit(habits)
                val done = habits.count { it.doneToday }
                habitTitle.text = if (habits.isEmpty()) "Rutinitas hari ini" else "Rutinitas hari ini  $done/${habits.size}"
                habitBar.setProgressCompat(if (habits.isEmpty()) 0 else done * 100 / habits.size, true)
                habitEmpty.visibility = if (habits.isEmpty()) View.VISIBLE else View.GONE
                updateStats()
            }
        }
    }

    private fun updateStats() {
        val mood = Pet.mood(this)
        levelView.text = "💗 Afeksi Lv. ${Pet.level(this)}"
        moodLabel.text = "Mood: ${Pet.moodLabel(mood)} ($mood/100)"
        moodBar.setProgressCompat(mood, true)
        xpLabel.text = "Afeksi ${Pet.levelProgress(this)}/100 menuju level berikutnya (naik dari pencapaian)"
        xpBar.setProgressCompat(Pet.levelProgress(this), true)
        face.setBase(
            when {
                mood < 15 -> R.drawable.mascot_cry
                mood >= 55 -> R.drawable.mascot_happy
                else -> R.drawable.mascot_normal
            }
        )
    }

    // ---------- Interaksi ----------

    private fun say(text: String) {
        bubble.animate().cancel()
        bubble.animate().alpha(0f).translationY(6f).setStartDelay(0).setDuration(110).withEndAction {
            bubble.text = text
            bubble.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private fun pat() {
        Pet.pat(this)
        motion.react()
        face.flash(R.drawable.mascot_happy, 1600)
        repeat(3) { i -> stage.postDelayed({ spawnFloat("💗") }, i * 120L) }
        say(LineBank.next(this, "pat"))
        updateStats()
    }

    private fun feed() {
        val wait = Pet.feed(this)
        if (wait > 0) {
            val mins = (wait / 60_000L).coerceAtLeast(1L)
            face.flash(R.drawable.mascot_angry, 1500)
            motion.react()
            say(if (mins >= 60) "Masih kenyang! Tunggu ${mins / 60} jam ${mins % 60} menit lagi ya." else "Masih kenyang! Tunggu $mins menit lagi ya.")
            return
        }
        motion.react(strong = true)
        face.flash(R.drawable.mascot_happy, 2200)
        repeat(4) { i -> stage.postDelayed({ spawnFloat(listOf("🍡", "✨", "💗").random()) }, i * 110L) }
        say(LineBank.next(this, "feed"))
        updateStats()
    }

    private fun toggle(h: Habit) {
        io.execute {
            val done = db.toggleHabitToday(h.id)
            Pet.habitToggled(this, done)
            val (d, total) = db.habitProgress()
            runOnUiThread {
                if (done) {
                    val all = d == total && total > 0
                    motion.react(strong = all)
                    face.flash(if (all) R.drawable.mascot_surprised else R.drawable.mascot_happy, if (all) 2600 else 1800)
                    repeat(if (all) 6 else 3) { i -> stage.postDelayed({ spawnFloat(if (all) "🎉" else "✨") }, i * 110L) }
                    say(LineBank.next(this, if (all) "allhabits" else "habit"))
                } else {
                    face.flash(R.drawable.mascot_cry, 1400)
                    say("Eh, dibatalin? Nanti dikerjain lagi ya…")
                }
                refresh()
            }
        }
    }

    // ---------- Pencapaian ----------

    private fun claim(a: Achievement) {
        io.execute {
            val ok = db.claimAchievement(a.id)
            val levelBefore = Pet.level(this)
            if (ok) Pet.addAffection(this, a.points)
            val levelAfter = Pet.level(this)
            runOnUiThread {
                if (!ok) {
                    say("Yang ini udah diklaim hari ini~ besok lagi ya!")
                    return@runOnUiThread
                }
                motion.react(strong = true)
                face.flash(R.drawable.mascot_surprised, 900)
                stage.postDelayed({ face.flash(R.drawable.mascot_happy, 2600) }, 900)
                repeat(8) { i -> stage.postDelayed({ spawnFloat(listOf("💗", "🏆", "✨", "🎉").random()) }, i * 90L) }
                if (levelAfter > levelBefore) {
                    say("AFEKSI NAIK KE LEVEL $levelAfter! ${LineBank.next(this, "achievement")}")
                } else {
                    say("+${a.points} afeksi! ${LineBank.next(this, "achievement")}")
                }
                refresh()
            }
        }
    }

    private fun confirmDeleteAch(a: Achievement) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus pencapaian?")
            .setMessage("${a.emoji} ${a.name} akan dihapus dari daftar. Afeksi yang sudah didapat tetap ada.")
            .setPositiveButton("Hapus") { _, _ ->
                io.execute {
                    db.archiveAchievement(a.id)
                    runOnUiThread { refresh() }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAddAchievement() {
        val view = layoutInflater.inflate(R.layout.dialog_achievement, null)
        val emoji = view.findViewById<EditText>(R.id.in_emoji)
        val name = view.findViewById<EditText>(R.id.in_name)
        val presetsGroup = view.findViewById<ChipGroup>(R.id.presets)
        val pointsGroup = view.findViewById<ChipGroup>(R.id.points)
        val pointChips = LinkedHashMap<Int, Chip>()
        listOf(5, 10, 15, 20, 30, 50).forEach { pts ->
            val chip = Chip(this)
            chip.id = View.generateViewId()
            chip.text = "+$pts"
            chip.isCheckable = true
            pointsGroup.addView(chip)
            pointChips[pts] = chip
        }
        pointChips[15]?.isChecked = true
        achPresets.forEach { (e, n, pts) ->
            val chip = Chip(this)
            chip.text = "$e $n"
            chip.setOnClickListener {
                emoji.setText(e)
                name.setText(n)
                name.setSelection(n.length)
                pointChips[pts]?.isChecked = true
            }
            presetsGroup.addView(chip)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Pencapaian baru")
            .setView(view)
            .setPositiveButton("Tambah") { _, _ ->
                val n = name.text.toString().trim()
                val e = emoji.text.toString().trim().ifEmpty { "🏆" }
                val pts = pointChips.entries.firstOrNull { it.value.isChecked }?.key ?: 15
                if (n.isEmpty()) {
                    Toast.makeText(this, "Nama pencapaian belum diisi", Toast.LENGTH_SHORT).show()
                } else {
                    io.execute {
                        db.addAchievement(n, e, pts)
                        runOnUiThread {
                            face.flash(R.drawable.mascot_happy, 1500)
                            motion.react()
                            say("Oke! Kalau \"$n\" kesampaian, kabarin aku ya!")
                            refresh()
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmDelete(h: Habit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus rutinitas?")
            .setMessage("${h.emoji} ${h.name} akan dihapus dari daftar.")
            .setPositiveButton("Hapus") { _, _ ->
                io.execute {
                    db.archiveHabit(h.id)
                    runOnUiThread { refresh() }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_habit, null)
        val emoji = view.findViewById<EditText>(R.id.in_emoji)
        val name = view.findViewById<EditText>(R.id.in_name)
        val chips = view.findViewById<ChipGroup>(R.id.presets)
        presets.forEach { (e, n) ->
            val chip = Chip(this)
            chip.text = "$e $n"
            chip.setOnClickListener {
                emoji.setText(e)
                name.setText(n)
                name.setSelection(n.length)
            }
            chips.addView(chip)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rutinitas baru")
            .setView(view)
            .setPositiveButton("Tambah") { _, _ ->
                val n = name.text.toString().trim()
                val e = emoji.text.toString().trim().ifEmpty { "⭐" }
                if (n.isEmpty()) {
                    Toast.makeText(this, "Nama rutinitas belum diisi", Toast.LENGTH_SHORT).show()
                } else {
                    io.execute {
                        db.addHabit(n, e)
                        runOnUiThread {
                            face.flash(R.drawable.mascot_happy, 1500)
                            motion.react()
                            say("Oke, \"$n\" kita kerjain bareng ya!")
                            refresh()
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Emoji kecil yang melayang naik dari maskot lalu menghilang. */
    private fun spawnFloat(symbol: String) {
        val dp = resources.displayMetrics.density
        val tv = TextView(this)
        tv.text = symbol
        tv.textSize = 22f
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = (stage.width * (0.3f + Random.nextFloat() * 0.4f)).toInt()
        lp.topMargin = (stage.height * 0.35f).toInt()
        stage.addView(tv, lp)
        tv.alpha = 0f
        tv.scaleX = 0.5f
        tv.scaleY = 0.5f
        tv.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .translationY(-(70 + Random.nextInt(50)) * dp)
            .translationX((Random.nextFloat() - 0.5f) * 50 * dp)
            .setDuration(900).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                tv.animate().alpha(0f).setDuration(250).withEndAction { stage.removeView(tv) }.start()
            }.start()
    }

    // ---------- Adapter ----------

    private class AchievementAdapter(
        val onClaim: (Achievement) -> Unit,
        val onLong: (Achievement) -> Unit,
    ) : RecyclerView.Adapter<AchievementAdapter.VH>() {
        private var items: List<Achievement> = emptyList()

        fun submit(list: List<Achievement>) {
            val old = items
            items = list
            if (old.size == list.size && old.map { it.id } == list.map { it.id }) {
                list.forEachIndexed { i, a -> if (a != old[i]) notifyItemChanged(i) }
            } else {
                notifyDataSetChanged()
            }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val emoji: TextView = v.findViewById(R.id.emoji)
            val name: TextView = v.findViewById(R.id.name)
            val info: TextView = v.findViewById(R.id.info)
            val claim: com.google.android.material.button.MaterialButton = v.findViewById(R.id.claim)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_achievement, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val a = items[position]
            h.emoji.text = a.emoji
            h.name.text = a.name
            h.info.text = "+${a.points} afeksi" + if (a.timesClaimed > 0) " · tercapai ${a.timesClaimed}×" else ""
            h.claim.isEnabled = !a.claimedToday
            h.claim.text = if (a.claimedToday) "✓ Hari ini" else "Klaim"
            h.claim.setOnClickListener { onClaim(a) }
            h.itemView.setOnLongClickListener {
                onLong(a)
                true
            }
        }
    }

    private class HabitAdapter(
        val onToggle: (Habit) -> Unit,
        val onLong: (Habit) -> Unit,
    ) : RecyclerView.Adapter<HabitAdapter.VH>() {
        private var items: List<Habit> = emptyList()

        fun submit(list: List<Habit>) {
            val old = items
            items = list
            if (old.size == list.size && old.map { it.id } == list.map { it.id }) {
                list.forEachIndexed { i, h -> if (h != old[i]) notifyItemChanged(i) }
            } else {
                notifyDataSetChanged()
            }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val emoji: TextView = v.findViewById(R.id.emoji)
            val name: TextView = v.findViewById(R.id.name)
            val streak: TextView = v.findViewById(R.id.streak)
            val check: TextView = v.findViewById(R.id.check)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_habit, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            val ctx = h.itemView.context
            h.emoji.text = item.emoji
            h.name.text = item.name
            h.streak.text = when {
                item.streak >= 2 -> "🔥 ${item.streak} hari berturut-turut"
                item.doneToday -> "Selesai hari ini"
                else -> "Belum dikerjakan"
            }
            h.check.text = if (item.doneToday) "✓" else ""
            h.check.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, if (item.doneToday) R.color.accent else R.color.chip_selected)
            )
            if (item.doneToday) {
                h.check.scaleX = 0.6f; h.check.scaleY = 0.6f
                h.check.animate().scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }
            h.name.alpha = if (item.doneToday) 0.6f else 1f
            h.itemView.setOnClickListener { onToggle(item) }
            h.itemView.setOnLongClickListener {
                onLong(item)
                true
            }
        }
    }
}
