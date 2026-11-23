package com.geto.notifhub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Pantauan berita ekonomi & keuangan (judul + sumber + tautan) dengan ringkasan AI dari judul. */
class NewsActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private var topic = News.TOPICS.first()
    private var items: List<News.Item> = emptyList()
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var status: TextView
    private val adapter = NewsAdapter { openLink(it.link) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        refresh = findViewById(R.id.refresh)
        status = findViewById(R.id.status)
        refresh.setColorSchemeColors(getColor(R.color.accent))
        refresh.setOnRefreshListener { load(force = true) }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val group = findViewById<ChipGroup>(R.id.topics)
        val byId = HashMap<Int, News.Topic>()
        News.TOPICS.forEach { t ->
            val chip = layoutInflater.inflate(R.layout.chip_filter, group, false) as Chip
            chip.id = View.generateViewId()
            chip.text = t.label
            group.addView(chip)
            byId[chip.id] = t
            if (t == topic) chip.isChecked = true
        }
        group.setOnCheckedStateChangeListener { _, ids ->
            val t = ids.firstOrNull()?.let { byId[it] } ?: return@setOnCheckedStateChangeListener
            if (t != topic) {
                topic = t
                findViewById<View>(R.id.brief_card).visibility = View.GONE
                findViewById<MaterialButton>(R.id.btn_brief).text = "✨ Ringkasan AI topik ini"
                load(force = false)
            }
        }
        findViewById<View>(R.id.btn_brief).setOnClickListener { brief() }
        load(force = false)
    }

    private fun load(force: Boolean) {
        val t = topic
        val cached = News.cached(this, t)
        if (cached.isNotEmpty()) show(cached)
        refresh.isRefreshing = true
        status.text = "Memuat berita ${t.label}…"
        io.execute {
            val r = try { Result.success(News.load(this, t, force)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing || isDestroyed || t != topic) return@runOnUiThread
                refresh.isRefreshing = false
                r.onSuccess { show(it) }.onFailure {
                    status.text = "Gagal memuat: ${it.message}. Tarik ke bawah untuk coba lagi."
                }
            }
        }
    }

    private fun show(list: List<News.Item>) {
        items = list
        adapter.submit(list)
        status.text = if (list.isEmpty()) "Belum ada berita baru untuk topik ini."
        else "${list.size} berita · 2 hari terakhir · ketuk untuk membaca di sumbernya"
    }

    private fun brief() {
        val prefs = Prefs(this)
        if (prefs.apiKey.isEmpty()) {
            Toast.makeText(this, "Isi Gemini API key dulu di Pengaturan", Toast.LENGTH_LONG).show()
            return
        }
        if (items.isEmpty()) return
        val card = findViewById<MaterialCardView>(R.id.brief_card)
        val text = findViewById<TextView>(R.id.brief)
        val btn = findViewById<MaterialButton>(R.id.btn_brief)
        btn.isEnabled = false
        btn.text = "Meringkas…"
        card.visibility = View.VISIBLE
        text.text = "Sebentar, lagi aku baca judul-judulnya…"
        val snapshot = items.take(30)
        val t = topic
        Thread {
            val out = try {
                GeminiClient.newsBrief(prefs.apiKey, prefs.model, t.label, snapshot)
            } catch (e: Exception) {
                "Gagal meringkas: ${e.message}"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                text.text = out
                btn.isEnabled = true
                btn.text = "✨ Ringkas ulang"
            }
        }.start()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak ada browser untuk membuka tautan", Toast.LENGTH_SHORT).show()
        }
    }

    private class NewsAdapter(val onClick: (News.Item) -> Unit) : RecyclerView.Adapter<NewsAdapter.VH>() {
        private var items: List<News.Item> = emptyList()
        private val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("id", "ID"))

        fun submit(l: List<News.Item>) {
            items = l
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.icon)
            val title: TextView = v.findViewById(R.id.title)
            val sub: TextView = v.findViewById(R.id.subtitle)
            val trailing: TextView = v.findViewById(R.id.trailing)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val n = items[position]
            h.icon.text = n.source.firstOrNull()?.uppercaseChar()?.toString() ?: "📰"
            h.title.text = n.title
            h.title.maxLines = 3
            h.sub.text = n.source + if (n.time > 0) " · " + fmt.format(Date(n.time)) else ""
            h.trailing.text = ""
            h.itemView.setOnClickListener { onClick(n) }
        }
    }
}
