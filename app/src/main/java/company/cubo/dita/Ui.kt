package company.cubo.dita

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** Paleta (clara e escura, segue o sistema) e peças desenhadas à mão. */
object Ui {
    var dark = false; private set
    var BG = 0; private set
    var SURFACE = 0; private set
    var RAISED = 0; private set
    var OUTLINE = 0; private set
    var TEXT = 0; private set
    var TEXT_DIM = 0; private set
    var ACCENT = 0; private set
    var ACCENT_SOFT = 0; private set
    var INK = 0; private set       // botões principais
    var ON_INK = 0; private set
    var DANGER = 0; private set
    var WARN = 0; private set

    // A bolha flutua sobre qualquer app: sempre escura, para ter contraste em qualquer fundo.
    const val B_SURFACE = 0xFF1B1A18.toInt()
    const val B_RAISED = 0xFF2E2D29.toInt()
    const val B_OUTLINE = 0xFF45433E.toInt()
    const val B_TEXT = 0xFFF3F1EC.toInt()
    const val B_TEXT_DIM = 0xFFB3B0A7.toInt()
    const val B_ACCENT = 0xFF8FD9B0.toInt()
    const val B_ON_ACCENT = 0xFF0E2A1C.toInt()
    const val B_DANGER = 0xFFFF7A70.toInt()

    fun apply(ctx: Context) {
        dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (dark) {
            BG = 0xFF121210.toInt(); SURFACE = 0xFF1C1C19.toInt(); RAISED = 0xFF282824.toInt()
            OUTLINE = 0xFF34342F.toInt(); TEXT = 0xFFF3F1EC.toInt(); TEXT_DIM = 0xFF9E9B92.toInt()
            ACCENT = 0xFF8FD9B0.toInt(); ACCENT_SOFT = 0xFF1F3329.toInt()
            INK = 0xFFF3F1EC.toInt(); ON_INK = 0xFF161613.toInt()
            DANGER = 0xFFFF7A70.toInt(); WARN = 0xFFF2B766.toInt()
        } else {
            BG = 0xFFF3F1EC.toInt(); SURFACE = 0xFFFFFFFF.toInt(); RAISED = 0xFFE9E6DF.toInt()
            OUTLINE = 0xFFE2DFD7.toInt(); TEXT = 0xFF1B1A18.toInt(); TEXT_DIM = 0xFF78756D.toInt()
            ACCENT = 0xFF2A6B4C.toInt(); ACCENT_SOFT = 0xFFDCEBE2.toInt()
            INK = 0xFF1B1A18.toInt(); ON_INK = 0xFFFFFFFF.toInt()
            DANGER = 0xFFC8402F.toInt(); WARN = 0xFFC57F1B.toInt()
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    fun box(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    fun topBox(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    }
}

/** Ícones de traço, desenhados numa grade de 24 unidades. */
class Glyph(ctx: Context, private val kind: Int, color: Int) : View(ctx) {
    var color: Int = color
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val u = min(width, height) / 24f
        c.translate((width - 24 * u) / 2f, (height - 24 * u) / 2f)
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.9f * u
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) = c.drawLine(x0 * u, y0 * u, x1 * u, y1 * u, p)
        fun poly(vararg pts: Float) {
            path.reset()
            path.moveTo(pts[0] * u, pts[1] * u)
            var i = 2
            while (i < pts.size) { path.lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
            c.drawPath(path, p)
        }
        when (kind) {
            CLOSE -> { line(7f, 7f, 17f, 17f); line(7f, 17f, 17f, 7f) }
            CHECK -> poly(5.5f, 12.5f, 10f, 17f, 18.5f, 7.5f)
            BACK -> { poly(11f, 6f, 5f, 12f, 11f, 18f); line(5f, 12f, 19f, 12f) }
            SLIDERS -> {
                line(4f, 8f, 20f, 8f); line(4f, 16f, 20f, 16f)
                p.style = Paint.Style.FILL
                c.drawCircle(15f * u, 8f * u, 2.9f * u, p)
                c.drawCircle(9f * u, 16f * u, 2.9f * u, p)
            }
            WAVE -> {
                // a marca: barras de onda sonora que desenham um D
                p.strokeWidth = 2.3f * u
                line(4.6f, 3.8f, 4.6f, 20.2f); line(8.3f, 3.9f, 8.3f, 20.1f); line(12.0f, 4.3f, 12.0f, 19.7f); line(15.7f, 5.3f, 15.7f, 18.7f); line(19.4f, 8.3f, 19.4f, 15.7f)
            }
            MIC -> {
                rect.set(9.5f * u, 4f * u, 14.5f * u, 14f * u)
                c.drawArc(RectF(6.5f * u, 5f * u, 17.5f * u, 17f * u), 0f, 180f, false, p)
                line(12f, 17f, 12f, 20.5f)
                p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(9f * u, 3f * u, 15f * u, 14f * u), 3f * u, 3f * u, p)
            }
        }
    }

    companion object {
        const val CLOSE = 0
        const val CHECK = 1
        const val BACK = 2
        const val SLIDERS = 3
        const val WAVE = 4
        const val MIC = 5
    }
}

/** Onda sonora do painel de escuta; vira pontilhado com brilho passando ao transcrever. */
class WaveView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val bars = FloatArray(N)

    var processing = false
        set(v) { field = v; invalidate() }

    fun reset() { bars.fill(0f); invalidate() }

    fun push(level: Float) {
        System.arraycopy(bars, 1, bars, 0, N - 1)
        bars[N - 1] = level
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val step = 9f * d
        val x0 = width / 2f - step * (N - 1) / 2f
        p.strokeWidth = 4.2f * d
        val maxH = height / 2f - 4f * d
        val t = (SystemClock.uptimeMillis() % 1100L) / 1100f * (N + 6) - 3
        for (i in 0 until N) {
            val x = x0 + i * step
            if (processing) {
                val glow = (1f - abs(i - t) / 3f).coerceIn(0f, 1f)
                p.color = Ui.withAlpha(Ui.TEXT, (70 + 185 * glow).toInt())
                c.drawLine(x, cy - glow * 3f * d, x, cy + glow * 3f * d, p)
            } else {
                val shape = 0.45f + 0.55f * (1f - abs(i - (N - 1) / 2f) / ((N - 1) / 2f))
                val h = bars[i] * maxH * shape
                p.color = Ui.TEXT
                c.drawLine(x, cy - h, x, cy + h, p)
            }
        }
        if (processing) postInvalidateOnAnimation()
    }

    companion object { private const val N = 19 }
}

/** Anel girando. */
class Spinner(ctx: Context, private val color: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val r = min(width, height) / 2f - 3f * d
        rect.set(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
        p.strokeWidth = 2.6f * d
        p.color = color
        val t = (SystemClock.uptimeMillis() % 800L) / 800f
        c.drawArc(rect, t * 360f - 90f, 110f, false, p)
        if (isShown) postInvalidateOnAnimation()
    }
}
