package company.cubo.dita

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A bolha flutuante. Desenhada à mão em Canvas: círculo com microfone (parada),
 * pílula com onda sonora ao vivo (gravando), anel girando (transcrevendo).
 */
class BubbleView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onTap()
        fun onCancelTap()
        fun onHoldStart()
        fun onHoldEnd()
        fun onDrag(dx: Float, dy: Float)
        fun onDragEnd()
    }

    var listener: Listener? = null
    var alignRight = true

    private val d = resources.displayMetrics.density
    private val pad = 7f * d            // respiro para a sombra
    private val size = 50f * d          // diâmetro da bolha
    private val pillW = 172f * d

    val heightPx: Int = (size + pad * 2).toInt()
    fun widthFor(state: Int): Int =
        ((if (state == RECORDING || state == RETRY) pillW else size) + pad * 2).toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bars = FloatArray(BARS)
    private var morph = 0f              // 0 = círculo, 1 = pílula
    private var morphAnim: ValueAnimator? = null

    var state: Int = IDLE
        set(v) {
            if (field == v) return
            field = v
            morphAnim?.cancel()
            if (v == RECORDING || v == RETRY) {
                synchronized(bars) { bars.fill(0f) }
                morph = 0f
                morphAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 220
                    interpolator = DecelerateInterpolator(1.6f)
                    addUpdateListener { morph = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else {
                morph = 0f
            }
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // sombra suave em formas
    }

    /** Chamado da thread de gravação (~25 Hz). */
    fun pushLevel(level: Float) {
        synchronized(bars) {
            System.arraycopy(bars, 1, bars, 0, BARS - 1)
            bars[BARS - 1] = level
        }
        postInvalidateOnAnimation()
    }

    fun appear() {
        scaleX = 0.5f; scaleY = 0.5f; alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
            .setInterpolator(OvershootInterpolator(2f)).start()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cy = height / 2f
        val r = size / 2f
        val bodyW = size + (w - pad * 2 - size) * morph
        val left = if (alignRight) w - pad - bodyW else pad
        rect.set(left, cy - r, left + bodyW, cy + r)

        fill.color = Ui.B_SURFACE
        fill.setShadowLayer(5f * d, 0f, 2f * d, 0x66000000)
        c.drawRoundRect(rect, r, r, fill)
        fill.clearShadowLayer()

        stroke.strokeWidth = 1.2f * d
        stroke.color = if (state == ERROR || state == RETRY) Ui.B_DANGER else Ui.B_OUTLINE
        c.drawRoundRect(rect, r, r, stroke)

        when (state) {
            IDLE -> drawMic(c, rect.centerX(), cy, Ui.B_ACCENT)
            ERROR -> drawMic(c, rect.centerX(), cy, Ui.B_DANGER)
            BUSY -> drawSpinner(c, rect.centerX(), cy)
            RECORDING, RETRY -> drawRecording(c, cy, r)
        }
    }

    private fun drawMic(c: Canvas, cx: Float, cy: Float, color: Int) {
        fill.color = color
        rect.set(cx - 3.6f * d, cy - 9.5f * d, cx + 3.6f * d, cy + 2.5f * d)
        c.drawRoundRect(rect, 3.6f * d, 3.6f * d, fill)
        stroke.color = color
        stroke.strokeWidth = 1.8f * d
        stroke.strokeCap = Paint.Cap.ROUND
        rect.set(cx - 6.8f * d, cy - 7.5f * d, cx + 6.8f * d, cy + 6f * d)
        c.drawArc(rect, 0f, 180f, false, stroke)
        c.drawLine(cx, cy + 6f * d, cx, cy + 9.5f * d, stroke)
    }

    private fun drawSpinner(c: Canvas, cx: Float, cy: Float) {
        val t = (SystemClock.uptimeMillis() % 900L) / 900f
        stroke.strokeWidth = 2.4f * d
        stroke.strokeCap = Paint.Cap.ROUND
        val rr = 10f * d
        rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
        stroke.color = Ui.B_OUTLINE
        c.drawCircle(cx, cy, rr, stroke)
        stroke.color = Ui.B_ACCENT
        c.drawArc(rect, t * 360f - 90f, 100f, false, stroke)
        postInvalidateOnAnimation()
    }

    private fun drawRecording(c: Canvas, cy: Float, r: Float) {
        val a = (morph * 255).toInt().coerceIn(0, 255)
        val bodyLeft = rect.left
        val bodyRight = rect.right
        val btn = r - 7f * d

        // cancelar (×) à esquerda
        val cxCancel = bodyLeft + r
        fill.color = Ui.withAlpha(Ui.B_RAISED, a)
        c.drawCircle(cxCancel, cy, btn, fill)
        stroke.color = Ui.withAlpha(Ui.B_TEXT_DIM, a)
        stroke.strokeWidth = 1.8f * d
        stroke.strokeCap = Paint.Cap.ROUND
        val k = 4.2f * d
        c.drawLine(cxCancel - k, cy - k, cxCancel + k, cy + k, stroke)
        c.drawLine(cxCancel - k, cy + k, cxCancel + k, cy - k, stroke)

        // concluir (✓) à direita
        val cxStop = bodyRight - r
        fill.color = Ui.withAlpha(Ui.B_ACCENT, a)
        c.drawCircle(cxStop, cy, btn, fill)
        stroke.color = Ui.withAlpha(Ui.B_ON_ACCENT, a)
        stroke.strokeWidth = 2.1f * d
        if (state == RETRY) {
            // ↻ reenviar
            val rr = 5.6f * d
            rect.set(cxStop - rr, cy - rr, cxStop + rr, cy + rr)
            c.drawArc(rect, -50f, 280f, false, stroke)
            val ex = cxStop - 0.643f * rr
            val ey = cy - 0.766f * rr
            c.drawLine(ex, ey, ex - 0.2f * d, ey - 4.4f * d, stroke)
            c.drawLine(ex, ey, ex - 4.4f * d, ey + 0.4f * d, stroke)
            text.color = Ui.withAlpha(Ui.B_TEXT, a)
            text.textSize = 14.5f * d
            text.textAlign = Paint.Align.CENTER
            c.drawText("Reenviar", (bodyLeft + bodyRight) / 2f, cy - (text.ascent() + text.descent()) / 2f, text)
            return
        }
        c.drawLine(cxStop - 5.2f * d, cy + 0.4f * d, cxStop - 1.4f * d, cy + 4.2f * d, stroke)
        c.drawLine(cxStop - 1.4f * d, cy + 4.2f * d, cxStop + 5.4f * d, cy - 3.8f * d, stroke)

        // onda sonora no meio
        val x0 = bodyLeft + size + 2f * d
        val x1 = bodyRight - size - 2f * d
        if (x1 > x0) {
            val step = (x1 - x0) / (BARS - 1)
            stroke.strokeWidth = 2.6f * d
            stroke.color = Ui.withAlpha(Ui.B_TEXT, a)
            val maxH = r - 9f * d
            synchronized(bars) {
                for (i in 0 until BARS) {
                    // barras centrais mais altas: forma de "fala", não de régua
                    val shape = 0.55f + 0.45f * (1f - abs(i - (BARS - 1) / 2f) / ((BARS - 1) / 2f))
                    val h = 1.3f * d + bars[i] * maxH * shape
                    val x = x0 + i * step
                    c.drawLine(x, cy - h, x, cy + h, stroke)
                }
            }
        }
    }

    // ---- toque: tocar, segurar (push-to-talk) ou arrastar ----

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var held = false
    private val holdRunnable = Runnable {
        held = true
        listener?.onHoldStart()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                lastX = downX; lastY = downY
                moved = false; held = false
                if (state == IDLE || state == ERROR) postDelayed(holdRunnable, HOLD_MS)
                animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && !held && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                    moved = true
                    removeCallbacks(holdRunnable)
                }
                if (moved && !held) listener?.onDrag(e.rawX - lastX, e.rawY - lastY)
                lastX = e.rawX; lastY = e.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                when {
                    held -> listener?.onHoldEnd()
                    moved -> listener?.onDragEnd()
                    e.actionMasked == MotionEvent.ACTION_UP -> {
                        if ((state == RECORDING || state == RETRY) && e.x < pad + size) listener?.onCancelTap()
                        else listener?.onTap()
                    }
                }
            }
        }
        return true
    }

    companion object {
        const val IDLE = 0
        const val RECORDING = 1
        const val BUSY = 2
        const val ERROR = 3
        const val RETRY = 4
        private const val BARS = 15
        private const val HOLD_MS = 320L
    }
}
