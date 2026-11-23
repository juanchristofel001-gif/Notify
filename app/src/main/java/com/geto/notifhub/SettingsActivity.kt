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
