package com.geto.notifhub

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var about: EditText
    private lateinit var vip: EditText
    private lateinit var swAll: SwitchCompat
    private lateinit var swAlert: SwitchCompat
    private lateinit var status: TextView
    private lateinit var nickname: EditText
    private lateinit var city: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }
        prefs = Prefs(this)

        apiKey = findViewById(R.id.api_key)
        model = findViewById(R.id.model)
        about = findViewById(R.id.about)
        vip = findViewById(R.id.vip)
        swAll = findViewById(R.id.sw_all)
        swAlert = findViewById(R.id.sw_alert)
        status = findViewById(R.id.status)
        nickname = findViewById(R.id.nickname)
        city = findViewById(R.id.city)
        nickname.setText(prefs.nickname)
        city.setText(prefs.city)
        val bdayBtn = findViewById<Button>(R.id.btn_birthday)
        fun showBday() {
            bdayBtn.text = prefs.birthday?.let { (y, m, d) ->
                val c = java.util.Calendar.getInstance().apply { set(y, m - 1, d) }
                "🎂 Tanggal lahir: " + java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("id", "ID")).format(c.time)
            } ?: "🎂 Atur tanggal lahir"
        }
        showBday()

        val swDigest = findViewById<SwitchCompat>(R.id.sw_digest)
        val timeBtn = findViewById<Button>(R.id.btn_digest_time)
        fun showTime() {
            timeBtn.text = String.format(java.util.Locale.US, "Jam %02d.%02d", prefs.digestHour, prefs.digestMinute)
        }
        showTime()
        swDigest.isChecked = prefs.digestEnabled
        swDigest.setOnCheckedChangeListener { _, on ->
            prefs.digestEnabled = on
            Digest.schedule(this)
        }
        timeBtn.setOnClickListener {
            android.app.TimePickerDialog(this, { _, h, m ->
                prefs.digestHour = h
                prefs.digestMinute = m
                showTime()
                Digest.schedule(this)
                Toast.makeText(this, "Ringkasan dikirim tiap hari jam ${timeBtn.text.removePrefix("Jam ")}", Toast.LENGTH_SHORT).show()
            }, prefs.digestHour, prefs.digestMinute, true).show()
        }
        findViewById<Button>(R.id.btn_digest_now).setOnClickListener {
            save()
            Toast.makeText(this, "Menyiapkan ringkasan…", Toast.LENGTH_SHORT).show()
            Thread {
                val err = try { Digest.sendNow(this); null } catch (e: Exception) { e.message }
                runOnUiThread {
                    if (err != null) Toast.makeText(this, "Gagal: $err", Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        bdayBtn.setOnClickListener {
            val (y, m, d) = prefs.birthday ?: Triple(2005, 1, 1)
            android.app.DatePickerDialog(this, { _, yy, mm, dd ->
                prefs.birthdayRaw = String.format(java.util.Locale.US, "%04d-%02d-%02d", yy, mm + 1, dd)
                showBday()

        val swDigest = findViewById<SwitchCompat>(R.id.sw_digest)
        val timeBtn = findViewById<Button>(R.id.btn_digest_time)
        fun showTime() {
            timeBtn.text = String.format(java.util.Locale.US, "Jam %02d.%02d", prefs.digestHour, prefs.digestMinute)
        }
        showTime()
        swDigest.isChecked = prefs.digestEnabled
        swDigest.setOnCheckedChangeListener { _, on ->
            prefs.digestEnabled = on
            Digest.schedule(this)
        }
        timeBtn.setOnClickListener {
            android.app.TimePickerDialog(this, { _, h, m ->
                prefs.digestHour = h
                prefs.digestMinute = m
                showTime()
                Digest.schedule(this)
                Toast.makeText(this, "Ringkasan dikirim tiap hari jam ${timeBtn.text.removePrefix("Jam ")}", Toast.LENGTH_SHORT).show()
            }, prefs.digestHour, prefs.digestMinute, true).show()
        }
        findViewById<Button>(R.id.btn_digest_now).setOnClickListener {
            save()
            Toast.makeText(this, "Menyiapkan ringkasan…", Toast.LENGTH_SHORT).show()
            Thread {
                val err = try { Digest.sendNow(this); null } catch (e: Exception) { e.message }
                runOnUiThread {
                    if (err != null) Toast.makeText(this, "Gagal: $err", Toast.LENGTH_LONG).show()
                }
            }.start()
        }
            }, y, m - 1, d).show()
        }

        apiKey.setText(prefs.apiKey)
        model.setText(prefs.model)
        about.setText(prefs.aboutMe)
        vip.setText(prefs.vipRaw)
        swAll.isChecked = prefs.allApps
        swAlert.isChecked = prefs.alertUrgent

        findViewById<Button>(R.id.btn_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btn_save).setOnClickListener {
            save()
            Toast.makeText(this, "Tersimpan", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_test).setOnClickListener {
            save()
            status.text = "Menghubungi Gemini…"
            val key = prefs.apiKey
            val m = prefs.model
            Thread {
                val msg = try {
                    "Berhasil. Jawaban model: " + GeminiClient.test(key, m).trim().take(80)
                } catch (e: Exception) {
                    "Gagal: ${e.message}"
                }
                runOnUiThread { status.text = msg }
            }.start()
        }
    }

    private fun save() {
        prefs.nickname = nickname.text.toString()
        prefs.city = city.text.toString()
        prefs.apiKey = apiKey.text.toString()
        prefs.model = model.text.toString()
        prefs.aboutMe = about.text.toString()
        prefs.vipRaw = vip.text.toString()
        prefs.allApps = swAll.isChecked
        prefs.alertUrgent = swAlert.isChecked
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
