package com.geto.notifhub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * "Nofi" — maskot 3D original NotifHub: rubah-roh kecil berwarna krem dengan ujung telinga & ekor lilac
 * dan lonceng notifikasi di kalungnya.
 *
 * Dirender sendiri dengan Canvas: setiap bagian tubuh adalah bola/elipsoid di ruang 3D yang diputar
 * (yaw/pitch), diproyeksikan dengan perspektif, diurutkan menurut kedalaman, dan diberi shading.
 * Ekspresi adalah kumpulan parameter wajah yang dihaluskan (lerp) tiap frame, jadi pergantiannya mulus.
 */
class MascotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), SensorEventListener {

    enum class Mood { IDLE, HAPPY, EXCITED, SURPRISED, FOCUSED, WORRIED, SLEEPY }

    /** Parameter wajah & pose; semuanya dihaluskan menuju target. */
    private class Pose(
        var eyeOpen: Float, var eyeSmile: Float, var eyeScale: Float, var sparkle: Float,
        var mouthOpen: Float, var mouthCurve: Float, var mouthWidth: Float,
        var brow: Float, var browAlpha: Float, var blush: Float,
        var sweat: Float, var zzz: Float, var exclaim: Float,
        var armR: Float, var armL: Float, var headTilt: Float, var bounce: Float,
    ) {
        fun lerpTo(o: Pose, k: Float) {
            eyeOpen += (o.eyeOpen - eyeOpen) * k
            eyeSmile += (o.eyeSmile - eyeSmile) * k
            eyeScale += (o.eyeScale - eyeScale) * k
            sparkle += (o.sparkle - sparkle) * k
            mouthOpen += (o.mouthOpen - mouthOpen) * k
            mouthCurve += (o.mouthCurve - mouthCurve) * k
            mouthWidth += (o.mouthWidth - mouthWidth) * k
            brow += (o.brow - brow) * k
            browAlpha += (o.browAlpha - browAlpha) * k
            blush += (o.blush - blush) * k
            sweat += (o.sweat - sweat) * k
            zzz += (o.zzz - zzz) * k
            exclaim += (o.exclaim - exclaim) * k
            armR += (o.armR - armR) * k
            armL += (o.armL - armL) * k
            headTilt += (o.headTilt - headTilt) * k
            bounce += (o.bounce - bounce) * k
        }
    }

    private fun poseFor(m: Mood): Pose = when (m) {
        //                     open smile scale spark mOpen mCurve mW   brow bAl  blush sweat zzz  excl armR armL tilt bounce
        Mood.IDLE -> Pose(1f, 0f, 1f, 0f, 0f, 0.55f, 0.9f, 0f, 0f, 0.35f, 0f, 0f, 0f, 0f, 0f, 0f, 0.4f)
        Mood.HAPPY -> Pose(1f, 1f, 1f, 0f, 0.35f, 1f, 1.15f, 0f, 0f, 0.75f, 0f, 0f, 0f, 0.15f, 0.15f, 7f, 1f)
        Mood.EXCITED -> Pose(1f, 0f, 1.12f, 1f, 0.6f, 1f, 1.2f, 0f, 0f, 0.85f, 0f, 0f, 0f, 1f, 0f, -5f, 1.4f)
        Mood.SURPRISED -> Pose(1f, 0f, 1.3f, 0f, 0.8f, 0f, 0.55f, -0.4f, 1f, 0.15f, 0f, 0f, 1f, 0.8f, 0.8f, 0f, 0.6f)
        Mood.FOCUSED -> Pose(0.82f, 0f, 1f, 0f, 0f, 0.1f, 0.65f, 1f, 1f, 0.2f, 0f, 0f, 0f, 0f, 0f, -3f, 0.5f)
        Mood.WORRIED -> Pose(0.92f, 0f, 1.02f, 0f, 0.18f, -0.7f, 0.75f, -1f, 1f, 0.1f, 1f, 0f, 0f, 0f, 0.25f, 5f, 0.3f)
        Mood.SLEEPY -> Pose(0.06f, 0f, 1f, 0f, 0.22f, 0.2f, 0.55f, 0f, 0f, 0.45f, 0f, 1f, 0f, 0f, 0f, 11f, 0.15f)
    }

    // ---------- Keadaan ----------
    private var baseMood = Mood.IDLE
    private var mood = Mood.IDLE
    private var tempUntil = 0L
    private val cur = poseFor(Mood.IDLE)
    private var target = poseFor(Mood.IDLE)

    private var running = false
    private var lastFrame = 0L
    private var t = 0f

    private var yaw = 0f; private var pitch = 0f
    private var yawT = 0f; private var pitchT = 0f
    private var hasSensor = false

    private var hopY = 0f; private var hopV = 0f
    private var squash = 0f; private var stretch = 0f
    private var bellSwing = 0f; private var bellV = 0f

    private var nextBlink = 1.5f; private var blink = 0f   // blink: 0..1 progres kedip
    private var lookX = 0f; private var lookY = 0f
    private var lookTX = 0f; private var lookTY = 0f; private var nextGlance = 2f
    private var earTwitch = 0f; private var nextTwitch = 3f

    private val rnd = Random(SystemClock.uptimeMillis())

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // ---------- API ----------
    fun setMood(m: Mood) {
        baseMood = m
        if (SystemClock.uptimeMillis() >= tempUntil) applyMood(m)
    }

    /** Ekspresi sementara, lalu kembali ke mood dasar. */
    fun flash(m: Mood, ms: Long) {
        tempUntil = SystemClock.uptimeMillis() + ms
        applyMood(m)
    }

    fun react(strong: Boolean = false) {
        if (hopY > -4f) {
            hopV = -(if (strong) 640f else 460f) * dp
            stretch = 1f
            bellV += if (strong) 14f else 9f
        }
        flash(if (strong) Mood.SURPRISED else Mood.EXCITED, if (strong) 2600 else 1900)
    }

    fun start() {
        if (running) return
        running = true
        lastFrame = 0L
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        sensorManager.unregisterListener(this)
    }

    private fun applyMood(m: Mood) {
        mood = m
        target = poseFor(m)
    }

    // ---------- Gambar ----------
    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()

    private val furLight = Color.parseColor("#FFFFFF")
    private val fur = Color.parseColor("#FFF3E6")
    private val furDark = Color.parseColor("#E3C3AE")
    private val lilac = Color.parseColor("#B9A2FF")
    private val lilacDark = Color.parseColor("#8A6FE8")
    private val earInner = Color.parseColor("#FFB8CB")
    private val outline = Color.argb(70, 90, 60, 50)
    private val eyeTop = Color.parseColor("#1D1830")
    private val eyeBottom = Color.parseColor("#5B49B8")
    private val mouthCol = Color.parseColor("#5A2230")
    private val tongue = Color.parseColor("#FF7F98")
    private val blushCol = Color.parseColor("#FF8FA3")
    private val gold = Color.parseColor("#FFD15A")
    private val goldDark = Color.parseColor("#D9961A")
    private val collar = Color.parseColor("#E8557F")

    // Model (satuan model, y ke bawah)
    private val headX = 0f; private val headY = -0.42f; private val headZ = 0f
    private val headR = 0.44f

    private var cx = 0f; private var cy = 0f; private var scale = 1f; private var groundY = 0f

    private class P(val x: Float, val y: Float, val z: Float, val k: Float)

    private var cYaw = 1f; private var sYaw = 0f; private var cPitch = 1f; private var sPitch = 0f

    private fun proj(x: Float, y: Float, z: Float): P {
        val yy = y - 0.1f
        val x1 = x * cYaw + z * sYaw
        val z1 = -x * sYaw + z * cYaw
        val y1 = yy * cPitch - z1 * sPitch
        val z2 = yy * sPitch + z1 * cPitch
        val k = 3.4f / (3.4f - z2)
        return P(cx + x1 * scale * k, cy + (y1 + 0.1f) * scale * k, z2, k)
    }

    /** Titik di permukaan kepala; theta = kiri/kanan, phi = atas/bawah (radian). */
    private fun headPt(theta: Float, phi: Float, r: Float = headR): P =
        proj(
            headX + r * sin(theta) * cos(phi),
            headY - r * sin(phi),
            headZ + r * cos(theta) * cos(phi),
        )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame).coerceAtMost(50L) / 1000f)
        lastFrame = now
        update(dt, now)

        val w = width.toFloat(); val h = height.toFloat()
        scale = min(w / 1.45f, h / 1.75f)
        cx = w / 2f
        groundY = h - 0.1f * scale
        cy = groundY - 0.58f * scale

        cYaw = cos(yaw); sYaw = sin(yaw); cPitch = cos(pitch); sPitch = sin(pitch)

        drawShadow(canvas)

        val breathe = sin(t * 2f * PI.toFloat() / 2.6f)
        val floatAmp = 4f * dp * cur.bounce
        val floatY = sin(t * 2f * PI.toFloat() / 3.2f) * floatAmp
        val sy = (1f + 0.02f * breathe) * (1f - 0.16f * squash) * (1f + 0.12f * stretch)
        val sx = (1f - 0.01f * breathe) * (1f + 0.14f * squash) * (1f - 0.07f * stretch)

        canvas.save()
        canvas.translate(0f, hopY + floatY)
        canvas.scale(sx, sy, cx, groundY)
        drawCharacter(canvas)
        canvas.restore()

        drawOverlays(canvas, hopY + floatY)

        if (running) postInvalidateOnAnimation()
    }

    private fun update(dt: Float, now: Long) {
        t += dt
        if (now >= tempUntil && mood != baseMood) applyMood(baseMood)
        cur.lerpTo(target, 1f - exp(-dt * 7f))

        // rotasi: sensor atau goyang pelan
        if (!hasSensor) {
            yawT = sin(t * 2f * PI.toFloat() / 7f) * 0.28f + lookX * 0.15f
            pitchT = sin(t * 2f * PI.toFloat() / 9f) * 0.06f
        }
        val k = 1f - exp(-dt * 5f)
        yaw += (yawT + lookX * 0.12f - yaw) * k
        pitch += (pitchT - pitch) * k

        // lompat
        if (hopV != 0f || hopY != 0f) {
            hopV += 2700f * dp * dt
            hopY += hopV * dt
            if (hopY > 0f) {
                hopY = 0f
                if (abs(hopV) > 120f * dp) { squash = 1f; bellV += 6f }
                hopV = -hopV * 0.3f
                if (abs(hopV) < 70f * dp) hopV = 0f
            }
        }
        squash *= exp(-dt * 9f)
        stretch *= exp(-dt * 7f)

        // lonceng berayun (pegas teredam)
        bellV += (-bellSwing * 60f - bellV * 4f) * dt
        bellSwing += bellV * dt

        // kedip
        if (blink > 0f) {
            blink += dt / 0.16f
            if (blink >= 1f) blink = 0f
        } else if (t >= nextBlink && mood != Mood.SLEEPY) {
            blink = 0.001f
            nextBlink = t + 2.2f + rnd.nextFloat() * 3.5f
            if (rnd.nextFloat() < 0.2f) nextBlink = t + 0.25f   // kadang kedip dua kali
        }

        // melirik
        if (t >= nextGlance) {
            nextGlance = t + 2.5f + rnd.nextFloat() * 3.5f
            if (rnd.nextFloat() < 0.6f) {
                lookTX = (rnd.nextFloat() * 2f - 1f) * 0.7f
                lookTY = (rnd.nextFloat() * 2f - 1f) * 0.35f
            } else {
                lookTX = 0f; lookTY = 0f
            }
        }
        val lk = 1f - exp(-dt * 10f)
        lookX += (lookTX - lookX) * lk
        lookY += (lookTY - lookY) * lk

        // telinga berkedut
        if (t >= nextTwitch) {
            nextTwitch = t + 3f + rnd.nextFloat() * 5f
            earTwitch = 1f
        }
        earTwitch *= exp(-dt * 6f)
    }

    private fun shaded(px: Float, py: Float, r: Float, light: Int, base: Int, dark: Int): Shader =
        RadialGradient(
            px - r * 0.38f, py - r * 0.42f, max(1f, r * 1.5f),
            intArrayOf(light, base, dark), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )

    private fun ball(c: Canvas, x: Float, y: Float, z: Float, r: Float, light: Int, base: Int, dark: Int) {
        val p = proj(x, y, z)
        val rs = r * scale * p.k
        fill.color = Color.WHITE
        fill.shader = shaded(p.x, p.y, rs, light, base, dark)
        c.drawCircle(p.x, p.y, rs, fill)
        fill.shader = null
        stroke.color = outline
        stroke.strokeWidth = 1.2f * dp
        c.drawCircle(p.x, p.y, rs, stroke)
    }

    private fun drawShadow(c: Canvas) {
        val lift = (-hopY / (70f * dp)).coerceIn(0f, 1f)
        val rx = 0.42f * scale * (1f - 0.4f * lift) * (1f + 0.15f * squash)
        val ry = 0.07f * scale * (1f - 0.4f * lift)
        val sx = cx + yaw * 0.25f * scale
        fill.color = Color.WHITE
        fill.shader = RadialGradient(
            sx, groundY, max(1f, rx),
            intArrayOf(Color.argb((90 * (1f - 0.6f * lift)).toInt(), 0, 0, 0), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        c.save()
        c.scale(1f, ry / rx, sx, groundY)
        c.drawCircle(sx, groundY, rx, fill)
        c.restore()
        fill.shader = null
    }

    private fun drawCharacter(c: Canvas) {
        // Ekor (di belakang)
        drawTail(c)

        // Kaki
        ball(c, -0.15f, 0.47f, 0.06f, 0.11f, furLight, fur, furDark)
        ball(c, 0.15f, 0.47f, 0.06f, 0.11f, furLight, fur, furDark)

        // Lengan yang ada di belakang badan (saat diputar)
        val armPos = armPositions()
        for ((x, y, z) in armPos) if (proj(x, y, z).z < proj(0f, 0.2f, 0f).z) ball(c, x, y, z, 0.085f, furLight, fur, furDark)

        // Badan (elipsoid)
        val b = proj(0f, 0.2f, 0f)
        val rx = 0.3f * scale * b.k
        val ry = 0.3f * scale * b.k
        fill.color = Color.WHITE
        fill.shader = shaded(b.x, b.y, max(rx, ry), furLight, fur, furDark)
        rect.set(b.x - rx, b.y - ry, b.x + rx, b.y + ry)
        c.drawOval(rect, fill)
        fill.shader = null
        stroke.color = outline; stroke.strokeWidth = 1.2f * dp
        c.drawOval(rect, stroke)

        // Kalung & lonceng
        drawCollar(c)

        // Kepala (dengan sedikit miring/roll)
        val neck = proj(0f, -0.06f, 0f)
        val roll = cur.headTilt + sin(t * 0.9f) * 2.5f + yaw * 8f
        c.save()
        c.rotate(roll, neck.x, neck.y)
        drawEars(c)
        ball(c, headX, headY, headZ, headR, furLight, fur, furDark)
        drawCheekFluff(c)
        drawFace(c)
        c.restore()

        // Lengan di depan
        for ((x, y, z) in armPos) if (proj(x, y, z).z >= proj(0f, 0.2f, 0f).z) ball(c, x, y, z, 0.085f, furLight, fur, furDark)
    }

    private fun armPositions(): List<Triple<Float, Float, Float>> {
        val wave = sin(t * 13f) * 0.35f
        fun arm(side: Float, raise: Float): Triple<Float, Float, Float> {
            val a = 0.45f + raise * 2.0f + (if (raise > 0.5f) wave * raise else 0f)   // 0 = ke bawah, PI = ke atas
            val shX = 0.22f * side; val shY = 0.08f; val len = 0.16f
            return Triple(shX + side * len * sin(a), shY + len * cos(a), 0.08f)
        }
        return listOf(arm(-1f, cur.armL), arm(1f, cur.armR))
    }

    private fun drawTail(c: Canvas) {
        val sway = sin(t * 2.2f) * 0.18f + yaw * 0.3f
        val p0 = proj(0.05f, 0.38f, -0.24f)
        val p1 = proj(0.42f + sway * 0.6f, 0.25f, -0.36f)
        val p2 = proj(0.36f + sway, -0.08f, -0.42f)
        path.reset()
        path.moveTo(p0.x, p0.y)
        path.quadTo(p1.x, p1.y, p2.x, p2.y)
        stroke.color = Color.WHITE
        stroke.shader = LinearGradient(p0.x, p0.y, p2.x, p2.y, intArrayOf(fur, fur, lilac), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        stroke.strokeWidth = 0.15f * scale * p1.k
        c.drawPath(path, stroke)
        stroke.shader = null
        // ujung ekor membulat
        fill.color = Color.WHITE
        fill.shader = shaded(p2.x, p2.y, 0.09f * scale * p2.k, Color.parseColor("#DCCFFF"), lilac, lilacDark)
        c.drawCircle(p2.x, p2.y, 0.09f * scale * p2.k, fill)
        fill.shader = null
    }

    private fun drawCollar(c: Canvas) {
        val n = proj(0f, 0.03f, 0f)
        val rx = 0.25f * scale * n.k
        val ry = 0.06f * scale * n.k
        rect.set(n.x - rx, n.y - ry, n.x + rx, n.y + ry)
        stroke.color = collar
        stroke.strokeWidth = 0.05f * scale
        c.drawArc(rect, 10f, 160f, false, stroke)

        // lonceng (hanya jika menghadap depan)
        val bellBase = proj(0f, 0.1f, 0.27f)
        if (bellBase.z > 0.05f) {
            val len = 0.07f * scale * bellBase.k
            val bx = bellBase.x + sin(bellSwing) * len
            val by = bellBase.y + cos(bellSwing) * len * 0.6f
            val r = 0.065f * scale * bellBase.k
            fill.color = Color.WHITE
            fill.shader = shaded(bx, by, r, Color.parseColor("#FFF3C4"), gold, goldDark)
            c.drawCircle(bx, by, r, fill)
            fill.shader = null
            stroke.color = goldDark; stroke.strokeWidth = 1.2f * dp
            c.drawLine(bx - r * 0.7f, by + r * 0.15f, bx + r * 0.7f, by + r * 0.15f, stroke)
            fill.color = goldDark
            c.drawCircle(bx, by + r * 0.5f, r * 0.18f, fill)
        }
    }

    private fun drawEars(c: Canvas) {
        for (side in floatArrayOf(-1f, 1f)) {
            val tw = if (side > 0) earTwitch * 0.18f else 0f
            var dx = side * (0.6f + tw); var dy = -0.8f + tw * 0.2f
            val len = sqrt(dx * dx + dy * dy); dx /= len; dy /= len
            val tx = -dy; val ty = dx                          // tangen untuk lebar telinga
            fun at(along: Float, across: Float, dz: Float) =
                proj(headX + dx * headR * along + tx * across, headY + dy * headR * along + ty * across, headZ + dz)

            val b1 = at(0.72f, 0.2f, 0f); val b2 = at(0.72f, -0.2f, 0f); val tip = at(1.65f, 0f, -0.02f)
            path.reset()
            path.moveTo(b1.x, b1.y)
            path.quadTo((b1.x + tip.x) / 2f + (b1.x - b2.x) * 0.15f, (b1.y + tip.y) / 2f, tip.x, tip.y)
            path.quadTo((b2.x + tip.x) / 2f - (b1.x - b2.x) * 0.15f, (b2.y + tip.y) / 2f, b2.x, b2.y)
            path.close()
            val mid = at(1.2f, 0f, 0f)
            fill.color = Color.WHITE
            fill.shader = LinearGradient(mid.x, b1.y, tip.x, tip.y, intArrayOf(fur, fur, lilac), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
            fill.shader = null
            stroke.color = outline; stroke.strokeWidth = 1.2f * dp
            c.drawPath(path, stroke)

            // bagian dalam telinga, hanya terlihat dari depan
            val facing = proj(headX + dx * headR, headY + dy * headR, 0.2f).z - proj(headX + dx * headR, headY + dy * headR, 0f).z
            if (facing > 0.05f) {
                val i1 = at(0.8f, 0.1f, 0.03f); val i2 = at(0.8f, -0.1f, 0.03f); val it = at(1.45f, 0f, 0.02f)
                path.reset()
                path.moveTo(i1.x, i1.y)
                path.quadTo((i1.x + it.x) / 2f, (i1.y + it.y) / 2f, it.x, it.y)
                path.quadTo((i2.x + it.x) / 2f, (i2.y + it.y) / 2f, i2.x, i2.y)
                path.close()
                fill.color = earInner
                fill.alpha = (255 * min(1f, facing * 5f)).toInt()
                c.drawPath(path, fill)
                fill.alpha = 255
            }
        }
    }

    private fun drawCheekFluff(c: Canvas) {
        for (side in floatArrayOf(-1f, 1f)) {
            val p = headPt(side * 1.15f, -0.32f, headR * 0.98f)
            val r = 0.1f * scale * p.k
            fill.color = Color.WHITE
            fill.shader = shaded(p.x, p.y, r, furLight, fur, furDark)
            c.drawCircle(p.x, p.y, r, fill)
            fill.shader = null
        }
    }

    private fun visibility(p: P, center: P): Float =
        ((p.z - center.z) / headR).let { ((it - 0.05f) / 0.3f).coerceIn(0f, 1f) }

    private fun drawFace(c: Canvas) {
        val hc = proj(headX, headY, headZ)
        val rs = headR * scale * hc.k
        val blinkF = if (blink > 0f) 1f - sin(blink * PI.toFloat()) else 1f
        val open = cur.eyeOpen * blinkF

        // pipi merah
        for (side in floatArrayOf(-1f, 1f)) {
            val p = headPt(side * 0.62f, -0.17f)
            val v = visibility(p, hc)
            if (v <= 0f) continue
            fill.color = blushCol
            fill.alpha = (255 * 0.55f * cur.blush * v).toInt()
            rect.set(p.x - rs * 0.11f * v, p.y - rs * 0.055f, p.x + rs * 0.11f * v, p.y + rs * 0.055f)
            c.drawOval(rect, fill)
            fill.alpha = 255
        }

        // mata & alis
        for (side in floatArrayOf(-1f, 1f)) {
            val p = headPt(side * 0.36f, 0.0f)
            val v = visibility(p, hc)
            if (v <= 0f) continue
            val fore = (0.45f + 0.55f * v)
            val ew = rs * 0.13f * cur.eyeScale * fore
            val eh = rs * 0.19f * cur.eyeScale
            val ex = p.x + lookX * ew * 0.2f
            val ey = p.y + lookY * eh * 0.15f
            val normalA = (1f - cur.eyeSmile) * v
            val smileA = cur.eyeSmile * v

            if (normalA > 0.01f) {
                if (open < 0.14f) {
                    stroke.color = eyeTop
                    stroke.alpha = (255 * normalA).toInt()
                    stroke.strokeWidth = rs * 0.035f
                    path.reset()
                    path.moveTo(ex - ew, ey)
                    path.quadTo(ex, ey + eh * 0.35f, ex + ew, ey)
                    c.drawPath(path, stroke)
                    stroke.alpha = 255
                } else {
                    rect.set(ex - ew, ey - eh * open, ex + ew, ey + eh * open)
                    fill.color = Color.WHITE
                    fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, eyeTop, eyeBottom, Shader.TileMode.CLAMP)
                    fill.alpha = (255 * normalA).toInt()
                    c.drawOval(rect, fill)
                    fill.shader = null
                    // kilau
                    fill.color = Color.WHITE
                    fill.alpha = (255 * normalA).toInt()
                    c.drawCircle(ex - ew * 0.3f + lookX * ew * 0.2f, ey - eh * open * 0.38f, ew * 0.38f * min(1f, open * 1.5f), fill)
                    c.drawCircle(ex + ew * 0.35f, ey + eh * open * 0.42f, ew * 0.15f, fill)
                    if (cur.sparkle > 0.02f) drawSparkle(c, ex + ew * 0.1f, ey + eh * open * 0.05f, ew * 0.55f, normalA * cur.sparkle)
                    fill.alpha = 255
                }
            }
            if (smileA > 0.01f) {
                stroke.color = eyeTop
                stroke.alpha = (255 * smileA).toInt()
                stroke.strokeWidth = rs * 0.04f
                path.reset()
                path.moveTo(ex - ew, ey + eh * 0.25f)
                path.quadTo(ex, ey - eh * 0.85f, ex + ew, ey + eh * 0.25f)
                c.drawPath(path, stroke)
                stroke.alpha = 255
            }

            // alis
            if (cur.browAlpha > 0.02f) {
                val b = headPt(side * 0.36f, 0.3f)
                val bl = ew * 0.95f
                val innerY = b.y + cur.brow * bl * 0.45f
                val outerY = b.y - cur.brow * bl * 0.2f
                stroke.color = eyeTop
                stroke.alpha = (255 * cur.browAlpha * v).toInt()
                stroke.strokeWidth = rs * 0.03f
                c.drawLine(b.x + side * bl, outerY, b.x - side * bl * 0.6f, innerY, stroke)
                stroke.alpha = 255
            }
        }

        // hidung kecil
        run {
            val p = headPt(0f, -0.1f)
            val v = visibility(p, hc)
            if (v > 0f) {
                fill.color = Color.parseColor("#7A4A52")
                fill.alpha = (255 * v).toInt()
                rect.set(p.x - rs * 0.035f, p.y - rs * 0.022f, p.x + rs * 0.035f, p.y + rs * 0.022f)
                c.drawOval(rect, fill)
                fill.alpha = 255
            }
        }

        // mulut
        run {
            val p = headPt(0f, -0.3f)
            val v = visibility(p, hc)
            if (v <= 0f) return@run
            val mw = rs * 0.12f * cur.mouthWidth * (0.5f + 0.5f * v)
            val mx = p.x; val my = p.y
            if (cur.mouthOpen > 0.06f) {
                val oh = rs * 0.15f * cur.mouthOpen
                path.reset()
                path.moveTo(mx - mw, my)
                path.quadTo(mx, my - oh * 0.25f + cur.mouthCurve * oh * 0.25f, mx + mw, my)
                path.quadTo(mx, my + oh * (1f + 0.35f * max(cur.mouthCurve, 0f)), mx - mw, my)
                path.close()
                fill.color = mouthCol
                fill.alpha = (255 * v).toInt()
                c.drawPath(path, fill)
                c.save()
                c.clipPath(path)
                fill.color = tongue
                fill.alpha = (255 * v).toInt()
                c.drawCircle(mx, my + oh * 1.0f, mw * 0.65f, fill)
                c.restore()
                fill.alpha = 255
            } else {
                stroke.color = mouthCol
                stroke.alpha = (255 * v).toInt()
                stroke.strokeWidth = rs * 0.03f
                path.reset()
                // mulut kucing "ω" saat santai, kurva biasa saat cemberut
                if (cur.mouthCurve > 0.3f) {
                    path.moveTo(mx - mw, my - mw * 0.1f)
                    path.quadTo(mx - mw * 0.5f, my + mw * 0.55f * cur.mouthCurve, mx, my)
                    path.quadTo(mx + mw * 0.5f, my + mw * 0.55f * cur.mouthCurve, mx + mw, my - mw * 0.1f)
                } else {
                    path.moveTo(mx - mw, my - cur.mouthCurve * mw * 0.15f)
                    path.quadTo(mx, my + cur.mouthCurve * mw * 0.7f, mx + mw, my - cur.mouthCurve * mw * 0.15f)
                }
                c.drawPath(path, stroke)
                stroke.alpha = 255
            }
        }
    }

    private fun drawSparkle(c: Canvas, x: Float, y: Float, r: Float, a: Float) {
        val rot = t * 2f
        path.reset()
        for (i in 0 until 8) {
            val ang = rot + i * PI.toFloat() / 4f
            val rr = if (i % 2 == 0) r else r * 0.28f
            val px = x + cos(ang) * rr; val py = y + sin(ang) * rr
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        fill.color = Color.WHITE
        fill.alpha = (255 * a).toInt()
        c.drawPath(path, fill)
    }

    /** Efek di luar badan: keringat, zzz, tanda seru. */
    private fun drawOverlays(c: Canvas, offY: Float) {
        val hc = proj(headX, headY, headZ)
        val rs = headR * scale * hc.k

        if (cur.sweat > 0.02f) {
            val slide = (t * 0.35f) % 1f
            val x = hc.x + rs * 0.85f
            val y = hc.y - rs * 0.35f + slide * rs * 0.35f + offY
            val r = rs * 0.1f
            path.reset()
            path.moveTo(x, y - r * 1.8f)
            path.quadTo(x + r * 1.1f, y - r * 0.2f, x, y + r)
            path.quadTo(x - r * 1.1f, y - r * 0.2f, x, y - r * 1.8f)
            fill.color = Color.parseColor("#8FD3FF")
            fill.alpha = (255 * cur.sweat * (1f - slide * 0.6f)).toInt()
            c.drawPath(path, fill)
            fill.alpha = 255
        }

        if (cur.zzz > 0.02f) {
            text.color = Color.WHITE
            for (i in 0 until 3) {
                val ph = ((t * 0.45f) + i / 3f) % 1f
                text.textSize = rs * (0.22f + 0.18f * ph)
                text.alpha = (255 * cur.zzz * sin(ph * PI.toFloat())).toInt()
                c.drawText("z", hc.x + rs * (0.7f + 0.45f * ph), hc.y - rs * (0.6f + 0.9f * ph) + offY, text)
            }
            text.alpha = 255
        }

        if (cur.exclaim > 0.02f) {
            val pop = 1f + 0.15f * sin(t * 10f)
            text.textSize = rs * 0.7f * pop * cur.exclaim
            text.color = Color.parseColor("#FFD23F")
            text.alpha = (255 * cur.exclaim).toInt()
            c.drawText("!", hc.x + rs * 1.05f, hc.y - rs * 0.75f + offY, text)
            text.alpha = 255
        }
    }

    // ---------- Sensor ----------
    override fun onSensorChanged(e: SensorEvent) {
        hasSensor = true
        val gx = e.values[0]; val gy = e.values[1]; val gz = e.values[2]
        yawT = (-gx * 0.045f).coerceIn(-0.5f, 0.5f)
        val pitchDeg = Math.toDegrees(atan2(gz.toDouble(), gy.toDouble())).toFloat()
        pitchT = ((pitchDeg - 40f) * 0.006f).coerceIn(-0.22f, 0.22f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
