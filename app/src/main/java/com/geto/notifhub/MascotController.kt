package com.geto.notifhub

import android.animation.TimeAnimator
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sin

/**
 * Menggerakkan maskot dalam satu loop per frame:
 * - melayang naik-turun dan "bernapas" (sedikit memanjang/memendek dari kaki)
 * - miring 3D mengikuti kemiringan HP (sensor gravitasi), atau bergoyang pelan jika tidak ada sensor
 * - melompat dengan fisika sederhana + squash & stretch saat [react] dipanggil
 * - bayangan di bawahnya mengecil/memudar saat maskot naik
 */
class MascotController(
    private val mascot: View,
    private val shadow: View,
) : SensorEventListener {

    private val dp = mascot.resources.displayMetrics.density
    private val sensorManager = mascot.context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var t = 0f                // detik sejak mulai
    private var hasTilt = false
    private var targetRotX = 0f
    private var targetRotY = 0f
    private var rotX = 0f
    private var rotY = 0f

    private var hopY = 0f             // px, negatif = ke atas
    private var hopV = 0f             // px/detik
    private var squash = 0f           // 0..1, efek mendarat
    private var stretch = 0f          // 0..1, efek melompat

    private val ticker = TimeAnimator().apply {
        setTimeListener { _, _, deltaMs -> step(deltaMs.coerceAtMost(48L) / 1000f) }
    }

    init {
        mascot.cameraDistance = 9000 * dp
        mascot.post {
            mascot.pivotX = mascot.width / 2f
            mascot.pivotY = mascot.height.toFloat()
        }
    }

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (!ticker.isStarted) ticker.start()
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        ticker.cancel()
    }

    /** Lompatan; strong = true untuk notifikasi urgent. */
    fun react(strong: Boolean = false) {
        if (hopY < -2 * dp) return    // masih di udara
        hopV = -(if (strong) 620f else 420f) * dp
        stretch = 1f
    }

    private fun step(dt: Float) {
        t += dt

        // 1) Melayang & bernapas (gelombang sinus, periode berbeda supaya terasa hidup)
        val floatY = sin(t * 2f * PI.toFloat() / 3.6f) * 5f * dp
        val breathe = sin(t * 2f * PI.toFloat() / 2.4f)

        // 2) Lompatan: gravitasi + pantulan teredam
        if (hopV != 0f || hopY != 0f) {
            hopV += 2600f * dp * dt
            hopY += hopV * dt
            if (hopY > 0f) {
                hopY = 0f
                if (abs(hopV) > 120f * dp) squash = 1f
                hopV = -hopV * 0.32f
                if (abs(hopV) < 60f * dp) hopV = 0f
            }
        }
        squash *= exp(-dt * 9f)
        stretch *= exp(-dt * 7f)

        // 3) Kemiringan 3D: ikuti sensor, atau goyang pelan
        if (!hasTilt) {
            targetRotY = sin(t * 2f * PI.toFloat() / 7f) * 8f
            targetRotX = sin(t * 2f * PI.toFloat() / 9f) * 3f
        }
        val k = 1f - exp(-dt * 6f)    // smoothing tidak tergantung frame rate
        rotX += (targetRotX - rotX) * k
        rotY += (targetRotY - rotY) * k

        // Terapkan
        val y = floatY + hopY
        mascot.translationY = y
        mascot.rotationX = rotX
        mascot.rotationY = rotY
        mascot.scaleY = (1f + 0.018f * breathe) * (1f - 0.14f * squash) * (1f + 0.10f * stretch)
        mascot.scaleX = (1f - 0.008f * breathe) * (1f + 0.12f * squash) * (1f - 0.06f * stretch)

        val lift = (-y / (60f * dp)).coerceIn(0f, 1f)
        val s = 1f - 0.45f * lift
        shadow.scaleX = s * (1f + 0.15f * squash)
        shadow.scaleY = s
        shadow.alpha = 1f - 0.6f * lift
        shadow.translationX = rotY * 0.6f * dp
    }

    override fun onSensorChanged(e: SensorEvent) {
        val gx = e.values[0]
        val gy = e.values[1]
        val gz = e.values[2]
        hasTilt = true
        // Miring kiri/kanan → putar sumbu Y; condong depan/belakang → putar sumbu X
        targetRotY = (-gx * 2.2f).coerceIn(-18f, 18f)
        val pitch = Math.toDegrees(atan2(gz.toDouble(), gy.toDouble())).toFloat()  // 0 = tegak, 90 = rebah
        targetRotX = ((pitch - 40f) * 0.25f).coerceIn(-10f, 10f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
