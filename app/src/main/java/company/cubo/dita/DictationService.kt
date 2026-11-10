package company.cubo.dita

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Bolha de ditado: aparece quando há um campo de texto em foco (ou teclado aberto),
 * grava, manda para o Whisper e insere o texto no campo.
 */
class DictationService : AccessibilityService(), BubbleView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager

    private var bubble: BubbleView? = null
    private val lp = WindowManager.LayoutParams()
    private var attached = false
    private var dragging = false

    private var state = BubbleView.IDLE
    private var generation = 0
    private var imeTop = -1
    private val recorder = Recorder { bubble?.pushLevel(it) }

    private val evaluate = Runnable { evaluateNow() }
    private val autoStop = Runnable { if (state == BubbleView.RECORDING) finish() }
    private val backToIdle = Runnable { setState(BubbleView.IDLE); evaluateNow() }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        Ui.apply(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = BubbleView(this).also { it.listener = this }
        lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        lp.format = PixelFormat.TRANSLUCENT
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        lp.gravity = Gravity.TOP or Gravity.START
        running = true
        evaluateNow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        main.removeCallbacks(evaluate)
        main.postDelayed(evaluate, 120)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        running = false
        main.removeCallbacksAndMessages(null)
        generation++
        recorder.cancel()
        hide()
    }

    // ---- visibilidade e posição ----

    private fun evaluateNow() {
        if (bubble == null) return
        var ime: Rect? = null
        runCatching {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val r = Rect()
                    w.getBoundsInScreen(r)
                    if (Build.VERSION.SDK_INT >= 33) {
                        // A janela do teclado costuma ser maior que o teclado visível;
                        // a região tocável é o que de fato aparece na tela.
                        val region = android.graphics.Region()
                        w.getRegionInScreen(region)
                        if (!region.isEmpty) r.set(region.bounds)
                    }
                    if (r.height() > 0) ime = r
                }
            }
        }
        val focus = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val editable = focus != null && editable(focus)
        val pkg = (focus?.packageName ?: runCatching { rootInActiveWindow?.packageName }.getOrNull())?.toString()
        // Discrição: nada de bolha em campo de senha nem nos apps que o usuário escolheu.
        val blocked = focus?.isPassword == true || (pkg != null && pkg in prefs.hiddenApps)
        imeTop = ime?.top ?: -1

        // Um áudio guardado (envio que falhou) vira o estado "reenviar" assim que a bolha aparece.
        val pending = Pending.exists(this)
        if (state == BubbleView.IDLE && pending) setState(BubbleView.RETRY)
        if (state == BubbleView.RETRY && !pending) setState(BubbleView.IDLE)

        val working = state == BubbleView.RECORDING || state == BubbleView.BUSY
        if (working || ((ime != null || editable) && !blocked)) show() else hide()
    }

    private fun screen(): Point {
        val p = Point()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            p.set(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
        }
        return p
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    /** Y de referência: topo do teclado, ou ~60% da tela quando não há teclado visível. */
    private fun anchorY(scr: Point): Int =
        if (imeTop > scr.y * 0.3f) imeTop else (scr.y * 0.6f).toInt()

    private fun place() {
        val b = bubble ?: return
        val scr = screen()
        lp.width = b.widthFor(state)
        lp.height = b.heightPx
        b.alignRight = prefs.bubbleRight
        if (dragging) return
        val margin = dp(10f)
        // Padrão: acima da barra de mensagem dos apps de conversa, sem cobrir o botão de enviar.
        val lift = if (prefs.bubbleLift >= 0) prefs.bubbleLift else dp(62f)
        lp.x = if (prefs.bubbleRight) scr.x - lp.width - margin else margin
        lp.y = (anchorY(scr) - lift - lp.height).coerceIn(dp(40f), scr.y - lp.height - dp(8f))
    }

    private fun show() {
        val b = bubble ?: return
        place()
        runCatching {
            if (attached) wm.updateViewLayout(b, lp) else {
                wm.addView(b, lp)
                attached = true
                b.appear()
            }
        }
    }

    private fun hide() {
        val b = bubble ?: return
        if (!attached) return
        runCatching { wm.removeViewImmediate(b) }
        attached = false
    }

    private fun setState(s: Int) {
        state = s
        bubble?.state = s
        if (attached) show()
    }

    // ---- gestos da bolha ----

    override fun onTap() {
        when (state) {
            BubbleView.IDLE, BubbleView.ERROR -> startRecording()
            BubbleView.RECORDING -> finish()
            BubbleView.BUSY -> cancel()
            BubbleView.RETRY -> {
                val wav = Pending.load(this)
                if (wav == null) { setState(BubbleView.IDLE); evaluateNow() } else send(wav)
            }
        }
    }

    override fun onCancelTap() {
        if (state == BubbleView.RETRY) {
            Pending.clear(this)
            setState(BubbleView.IDLE)
            toast("Áudio descartado")
            evaluateNow()
        } else cancel()
    }

    override fun onHoldStart() {
        if (state == BubbleView.IDLE || state == BubbleView.ERROR) startRecording()
    }

    override fun onHoldEnd() {
        if (state == BubbleView.RECORDING) finish()
    }

    override fun onDrag(dx: Float, dy: Float) {
        val b = bubble ?: return
        if (state != BubbleView.IDLE) return
        dragging = true
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { wm.updateViewLayout(b, lp) }
    }

    override fun onDragEnd() {
        if (!dragging) return
        dragging = false
        val scr = screen()
        prefs.bubbleRight = lp.x + lp.width / 2 > scr.x / 2
        prefs.bubbleLift = (anchorY(scr) - lp.y - lp.height).coerceAtLeast(0)
        show()
    }

    // ---- ditado ----

    private fun startRecording() {
        main.removeCallbacks(backToIdle)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Permita o microfone no Dita")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        try {
            recorder.start()
        } catch (e: Exception) {
            fail(e.message ?: "Erro no microfone")
            return
        }
        bubble?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        setState(BubbleView.RECORDING)
        main.postDelayed(autoStop, 5 * 60_000L)
    }

    private fun finish() {
        main.removeCallbacks(autoStop)
        bubble?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val res = recorder.stop()
        if (res == null) {
            setState(BubbleView.IDLE)
            toast("Não ouvi nada")
            evaluateNow()
            return
        }
        send(res.wav)
    }

    /** Envia; se falhar, o áudio fica guardado e a bolha oferece reenviar. */
    private fun send(wav: ByteArray) {
        setState(BubbleView.BUSY)
        val gen = ++generation
        io.execute {
            val out = runCatching { WhisperClient.process(prefs, wav) }
            if (out.isFailure) Pending.save(this, wav) else Pending.clear(this)
            main.post {
                if (gen != generation) return@post
                out.fold(
                    onSuccess = {
                        setState(BubbleView.IDLE)
                        if (it.viaFallback) toast("Transcrito pelo servidor reserva")
                        if (it.text.isEmpty()) toast("Não ouvi nada") else insert(it.text)
                        evaluateNow()
                    },
                    onFailure = {
                        toast(WhisperClient.explain(it) + " O áudio ficou guardado.")
                        setState(BubbleView.RETRY)
                    }
                )
            }
        }
    }

    private fun cancel() {
        main.removeCallbacks(autoStop)
        generation++
        recorder.cancel()
        setState(BubbleView.IDLE)
        evaluateNow()
    }

    private fun fail(msg: String) {
        toast(msg)
        setState(BubbleView.ERROR)
        main.removeCallbacks(backToIdle)
        main.postDelayed(backToIdle, 1800)
    }

    // ---- inserção no campo ----

    private fun insert(text: String) {
        prefs.addHistory(text)
        val node = target()
        if (node == null) {
            copy(text)
            toast("Copiado — cole onde quiser")
            return
        }
        node.refresh()
        val secret = node.isPassword
        val raw = node.text?.toString().orEmpty()
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        // Alguns apps (WhatsApp) devolvem o placeholder como se fosse o texto do campo,
        // sem marcar isShowingHintText. Texto igual ao hint e sem cursor no fim = campo vazio.
        val hint = node.hintText?.toString().orEmpty()
        val isHint = node.isShowingHintText ||
            (hint.isNotEmpty() && raw == hint && !(s == raw.length && e == raw.length))
        val current = if (secret || isHint) "" else raw
        // Sem posição de cursor confiável, colar é o único jeito de inserir no lugar certo.
        val blind = current.isNotEmpty() && (s < 0 || e < 0)
        if (s < 0 || e < 0 || s > current.length || e > current.length) {
            s = current.length
            e = current.length
        }
        if (s > e) s = e.also { e = s }
        val before = current.substring(0, s)
        val after = current.substring(e)
        val piece = (if (before.isNotEmpty() && !before.last().isWhitespace()) " " else "") + text

        var ok = false
        if (!prefs.pasteMode && !secret && !blind) {
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + piece + after
            )
            ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                val sel = Bundle()
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s + piece.length)
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, s + piece.length)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            }
        }
        if (!ok) {
            copy(piece)
            ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        if (!ok) {
            copy(text)
            toast("Copiado — cole onde quiser")
        }
    }

    /**
     * Apps feitos em Compose, Flutter ou React Native nem sempre marcam o campo como "editável".
     * Vale como campo de texto o que se declara EditText ou aceita definir/colar texto.
     */
    private fun editable(n: AccessibilityNodeInfo): Boolean =
        n.isEditable || n.className?.toString()?.endsWith("EditText") == true ||
            n.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SET_TEXT || it.id == AccessibilityNodeInfo.ACTION_PASTE
            }

    /** O campo em foco; se o sistema não o apontar, procura na janela ativa. */
    private fun target(): AccessibilityNodeInfo? {
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?.takeIf { editable(it) }?.let { return it }
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 4000) {
            val n = queue.removeFirst()
            if (n.isFocused && editable(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun copy(text: String) {
        runCatching {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Dita", text))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        @Volatile var running = false
            private set
    }
}
