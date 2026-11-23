package com.geto.notifhub

import android.os.Handler
import android.os.Looper
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import kotlin.random.Random

/** Mengganti ekspresi maskot dengan crossfade + efek "pop" di antara dua ImageView bertumpuk. */
class MascotFace(private val a: ImageView, private val b: ImageView) {

    companion object {
        val ALL = listOf(
            R.drawable.mascot_normal, R.drawable.mascot_happy, R.drawable.mascot_surprised,
            R.drawable.mascot_angry, R.drawable.mascot_cry, R.drawable.mascot_sleepy,
        )
        /** Ekspresi acak saat diketuk (lebih sering senang/kaget daripada nangis). */
        private val TAP_POOL = listOf(
            R.drawable.mascot_happy, R.drawable.mascot_happy, R.drawable.mascot_happy,
            R.drawable.mascot_surprised, R.drawable.mascot_surprised,
            R.drawable.mascot_angry, R.drawable.mascot_angry,
            R.drawable.mascot_sleepy, R.drawable.mascot_cry, R.drawable.mascot_normal,
        )

        fun category(res: Int): String = when (res) {
            R.drawable.mascot_happy -> "happy"
            R.drawable.mascot_surprised -> "surprised"
            R.drawable.mascot_angry -> "angry"
            R.drawable.mascot_cry -> "cry"
            R.drawable.mascot_sleepy -> "sleepy"
            else -> "normal"
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var frontIsA = true
    var current = R.drawable.mascot_normal
        private set
    private var base = R.drawable.mascot_normal
    private var flashing = false
    private val restore = Runnable {
        flashing = false
        show(base)
    }

    fun setBase(res: Int) {
        base = res
        if (!flashing) show(res)
    }

    fun flash(res: Int, ms: Long) {
        ui.removeCallbacks(restore)
        flashing = true
        show(res)
        ui.postDelayed(restore, ms)
    }

    /** Ekspresi acak yang berbeda dari sekarang; mengembalikan drawable yang dipilih. */
    fun flashRandom(ms: Long): Int {
        var pick: Int
        do { pick = TAP_POOL[Random.nextInt(TAP_POOL.size)] } while (pick == current)
        flash(pick, ms)
        return pick
    }

    fun show(res: Int) {
        if (res == current) return
        current = res
        val front = if (frontIsA) a else b
        val back = if (frontIsA) b else a
        front.animate().cancel()
        back.animate().cancel()
        back.setImageResource(res)
        back.alpha = 0f
        back.scaleX = 0.9f
        back.scaleY = 0.9f
        back.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(OvershootInterpolator(2f)).start()
        front.animate().alpha(0f).scaleX(1.05f).scaleY(1.05f).setDuration(220).start()
        frontIsA = !frontIsA
    }

    fun release() {
        ui.removeCallbacksAndMessages(null)
    }
}
