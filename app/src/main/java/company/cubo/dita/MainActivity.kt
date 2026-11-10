package company.cubo.dita

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Uma tela só: contador, histórico e o botão Ditar. Os ajustes ficam atrás do ícone do topo.
 * O que falta configurar aparece como pendência; quando está tudo certo, some.
 */
class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private lateinit var prefs: Prefs
    private var d = 1f

    // início
    private lateinit var chipDot: View
    private lateinit var chipText: TextView
    private lateinit var statBig: TextView
    private lateinit var statSub: TextView
    private lateinit var pendingBox: LinearLayout
    private lateinit var historyBox: LinearLayout

    // painel de escuta
    private lateinit var dictateBtn: View
    private lateinit var panel: LinearLayout
    private lateinit var wave: WaveView
    private lateinit var panelHint: TextView
    private lateinit var panelCaption: TextView
    private lateinit var checkBtn: View
    private lateinit var spinner: View

    // ajustes
    private lateinit var settings: View
    private lateinit var statusBox: LinearLayout

    private val recorder = Recorder { lvl -> main.post { wave.push(lvl) } }
    private var dictGen = 0
    private var sending = false
    private var pingGen = 0
    private var serverState = TESTING
    private var serverMsg = "Testando…"
    private val pingLater = Runnable { ping() }

    private class Status(
        val title: String, val sub: String, val color: Int,
        val ok: Boolean, val action: String?, val run: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Ui.apply(this)
        d = resources.displayMetrics.density
        window.setBackgroundDrawable(ColorDrawable(Ui.BG))
        if (Build.VERSION.SDK_INT >= 30) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(if (Ui.dark) 0 else light, light)
        }

        val root = FrameLayout(this)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.addView(buildHome(), MATCH_PARENT, MATCH_PARENT)
        root.addView(buildDock(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        settings = buildSettings()
        root.addView(settings, MATCH_PARENT, MATCH_PARENT)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        prefs.store.registerOnSharedPreferenceChangeListener(this)
        renderHistory()
        refresh()
        ping()
    }

    override fun onPause() {
        super.onPause()
        prefs.store.unregisterOnSharedPreferenceChangeListener(this)
        if (recorder.isRecording) cancelDictation()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == "history") renderHistory()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        refresh()
        if (code == REQ_DICTATE && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) startDictation()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            panel.visibility == View.VISIBLE -> cancelDictation()
            settings.visibility == View.VISIBLE -> showSettings(false)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- início

    private fun buildHome(): View {
        val col = column(pad = 20, bottom = 132)

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Glyph(this, Glyph.WAVE, Ui.ACCENT), LinearLayout.LayoutParams(dp(28), dp(28)))
        bar.addView(serif("Dita", 27f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        val chip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.box(Ui.SURFACE, 99f * d)
            setPadding(dp(12), dp(8), dp(14), dp(8))
            setOnClickListener { ping() }
        }
        chipDot = View(this)
        chip.addView(chipDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
        chipText = text("", 13f, Ui.TEXT)
        chip.addView(chipText)
        bar.addView(chip)
        bar.addView(
            iconButton(Glyph.SLIDERS, Ui.SURFACE, Ui.TEXT, 40) { showSettings(true) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8) }
        )
        col.addView(bar, lp(top = 10))

        val stat = card(radius = 28, padV = 34)
        stat.gravity = Gravity.CENTER_HORIZONTAL
        statBig = serif("", 44f).apply { gravity = Gravity.CENTER; setTextColor(Ui.ACCENT) }
        statSub = text("", 15f, Ui.TEXT_DIM).apply { gravity = Gravity.CENTER }
        stat.addView(statBig)
        stat.addView(statSub, lp(top = 6))
        col.addView(stat, lp(top = 22))

        pendingBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(pendingBox)

        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(historyBox)

        return scroll(col)
    }

    private fun renderHistory() {
        val list = prefs.history()
        val words = prefs.words
        if (words == 0) {
            statBig.textSize = 32f
            statBig.text = "Fale.\nO seu Whisper escreve."
            statSub.text = "Nada sai do seu Tailscale."
        } else {
            statBig.textSize = 46f
            val n = NumberFormat.getIntegerInstance(Locale("pt", "BR")).format(words)
            statBig.text = if (words == 1) "1 palavra" else "$n palavras"
            statSub.text = if (words == 1) "ditada até agora" else "ditadas até agora"
        }

        historyBox.removeAllViews()
        historyBox.addView(section("Recentes"), lp(top = 28))
        val box = card(radius = 24, padV = 6)
        if (list.isEmpty()) {
            box.setPadding(dp(20), dp(26), dp(20), dp(26))
            box.addView(serif("Nada ditado ainda.", 21f, italic = true))
            box.addView(
                text("Toque em Ditar aqui embaixo, ou use a bolha que aparece junto do teclado em qualquer app.", 15f, Ui.TEXT_DIM)
                    .apply { setLineSpacing(0f, 1.2f) },
                lp(top = 8)
            )
        }
        list.take(60).forEachIndexed { i, e ->
            if (i > 0) box.addView(divider(0))
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(16), 0, dp(14))
                setOnClickListener { copy(e.text); toast("Copiado") }
                setOnLongClickListener { prefs.removeHistory(e.time); toast("Apagado"); true }
            }
            item.addView(text(e.text, 16.5f, Ui.TEXT).apply { setLineSpacing(0f, 1.25f) })
            val flags = DateUtils.FORMAT_SHOW_TIME or
                if (DateUtils.isToday(e.time)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
            item.addView(text(DateUtils.formatDateTime(this, e.time, flags), 13f, Ui.TEXT_DIM), lp(top = 8))
            box.addView(item)
        }
        historyBox.addView(box, lp(top = 10))
        if (list.isNotEmpty()) {
            historyBox.addView(
                text("Toque para copiar · segure para apagar", 12.5f, Ui.TEXT_DIM).apply { gravity = Gravity.CENTER },
                lp(top = 12)
            )
        }
    }

    // ---------------------------------------------------------------- estado

    private fun statuses(): List<Status> {
        val vpn = vpnActive()
        val hasTs = packageManager.getLaunchIntentForPackage(TAILSCALE) != null
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val bubble = bubbleEnabled()
        return listOf(
            Status(
                "Tailscale",
                when {
                    vpn -> "VPN ativa"
                    hasTs -> "Desligado. Abra e conecte."
                    else -> "O app do Tailscale não está instalado."
                },
                if (vpn) Ui.ACCENT else Ui.WARN, vpn, if (hasTs) "Abrir" else "Instalar"
            ) { openTailscale() },
            Status(
                "Servidor Whisper", serverMsg,
                when (serverState) { OK -> Ui.ACCENT; FAIL -> Ui.DANGER; else -> Ui.TEXT_DIM },
                serverState != FAIL, "Testar"
            ) { ping() },
            Status(
                "Microfone", if (micOk) "Permitido" else "Necessário para gravar.",
                if (micOk) Ui.ACCENT else Ui.WARN, micOk, "Permitir"
            ) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) },
            Status(
                "Bolha em outros apps",
                if (bubble) "Ativa. Aparece junto do teclado." else "Para ditar em qualquer app.",
                if (bubble) Ui.ACCENT else Ui.WARN, bubble, "Ativar"
            ) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Ative “Dita — bolha de ditado”")
            }
        )
    }

    private fun refresh() {
        val all = statuses()

        chipDot.background = Ui.box(all[1].color, 99f * d)
        chipText.text = when (serverState) {
            OK -> host()
            FAIL -> "sem conexão"
            else -> "conectando"
        }

        val pending = all.filter { !it.ok }
        pendingBox.removeAllViews()
        if (Pending.exists(this)) {
            pendingBox.addView(section("Ditado guardado"), lp(top = 28))
            val box = card(radius = 24, padV = 18)
            box.addView(
                text(
                    if (sending) "Enviando…" else "Um ditado não chegou ao servidor. O áudio está guardado neste aparelho.",
                    15.5f, Ui.TEXT
                ).apply { setLineSpacing(0f, 1.25f) }
            )
            if (!sending) {
                val line = LinearLayout(this)
                line.addView(button("Tentar de novo", primary = true) { Pending.load(this)?.let { submit(it) } })
                line.addView(
                    button("Descartar", primary = false) { Pending.clear(this); refresh() },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) }
                )
                box.addView(line, lp(top = 14))
            }
            pendingBox.addView(box, lp(top = 10))
        }
        if (pending.isNotEmpty()) {
            pendingBox.addView(section(if (pending.size == 1) "Falta 1 passo" else "Faltam ${pending.size} passos"), lp(top = 28))
            val box = card(radius = 24, padV = 18)
            pending.forEachIndexed { i, s ->
                if (i > 0) box.addView(divider(14))
                box.addView(statusRow(s, showAction = true))
            }
            pendingBox.addView(box, lp(top = 10))
        }

        statusBox.removeAllViews()
        all.forEachIndexed { i, s ->
            if (i > 0) statusBox.addView(divider(14))
            statusBox.addView(statusRow(s, showAction = !s.ok || s.title.startsWith("Servidor")))
        }
    }

    private fun statusRow(s: Status, showAction: Boolean): View {
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val dot = View(this).apply { background = Ui.box(s.color, 99f * d) }
        line.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(s.title, 16f, Ui.TEXT))
        texts.addView(text(s.sub, 13.5f, Ui.TEXT_DIM), lp(top = 1))
        line.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (showAction && s.action != null) {
            line.addView(
                button(s.action, primary = !s.ok) { s.run() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) }
            )
        }
        return line
    }

    private fun ping() {
        val gen = ++pingGen
        if (serverState != OK) { serverState = TESTING; serverMsg = "Testando…" }
        refresh()
        val endpoint = prefs.endpoint
        io.execute {
            val r = runCatching { WhisperClient.ping(endpoint) }
            main.post {
                if (gen != pingGen) return@post
                r.fold(
                    onSuccess = { serverState = OK; serverMsg = "Respondeu em $it ms" },
                    onFailure = { serverState = FAIL; serverMsg = WhisperClient.explain(it) }
                )
                refresh()
            }
        }
    }

    private fun host(): String =
        runCatching { Uri.parse(WhisperClient.transcriptionUrl(prefs.endpoint)).host }.getOrNull() ?: "servidor"

    private fun vpnActive(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private fun bubbleEnabled(): Boolean {
        if (DictationService.running) return true
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.contains("$packageName/")
    }

    private fun openTailscale() {
        val i = packageManager.getLaunchIntentForPackage(TAILSCALE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE"))
        runCatching { startActivity(i) }.onFailure { toast("Instale o app Tailscale") }
    }

    // ---------------------------------------------------------------- ditado

    private fun buildDock(): View {
        val dock = FrameLayout(this)

        val btn = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = Ui.box(Ui.INK, 99f * d)
            setPadding(dp(26), 0, dp(32), 0)
            elevation = 8f * d
            setOnClickListener { startDictation() }
        }
        btn.addView(Glyph(this, Glyph.MIC, Ui.ON_INK), LinearLayout.LayoutParams(dp(26), dp(26)))
        btn.addView(
            text("Ditar", 18f, Ui.ON_INK).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) }
        )
        dictateBtn = btn
        dock.addView(btn, FrameLayout.LayoutParams(WRAP_CONTENT, dp(62), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(28)
            topMargin = dp(16)
        })

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.topBox(Ui.RAISED, 32f * d)
            setPadding(dp(18), dp(18), dp(18), dp(30))
            elevation = 16f * d
            visibility = View.GONE
            isClickable = true
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(
            iconButton(Glyph.CLOSE, Ui.SURFACE, Ui.TEXT, 52) { cancelDictation() },
            LinearLayout.LayoutParams(dp(52), dp(52))
        )
        panelHint = text("", 15.5f, Ui.TEXT).apply { gravity = Gravity.CENTER }
        top.addView(panelHint, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val right = FrameLayout(this)
        checkBtn = iconButton(Glyph.CHECK, Ui.INK, Ui.ON_INK, 52) { confirmDictation() }
        spinner = Spinner(this, Ui.TEXT).apply { visibility = View.GONE }
        right.addView(checkBtn, MATCH_PARENT, MATCH_PARENT)
        right.addView(spinner, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        top.addView(right, LinearLayout.LayoutParams(dp(52), dp(52)))
        panel.addView(top)

        wave = WaveView(this)
        panel.addView(wave, LinearLayout.LayoutParams(MATCH_PARENT, dp(64)).apply { topMargin = dp(30) })
        panelCaption = text("", 14.5f, Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }
        panel.addView(panelCaption, lp(top = 18))
        dock.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        return dock
    }

    private fun showPanel(show: Boolean) {
        val ease = DecelerateInterpolator(1.8f)
        if (show) {
            panel.visibility = View.VISIBLE
            panel.translationY = 320f * d
            panel.animate().translationY(0f).setDuration(260).setInterpolator(ease).withEndAction(null).start()
            dictateBtn.animate().scaleX(0.7f).scaleY(0.7f).alpha(0f).setDuration(140).start()
        } else {
            panel.animate().translationY(panel.height.toFloat() + 40f * d).setDuration(220).setInterpolator(ease)
                .withEndAction { panel.visibility = View.GONE }.start()
            dictateBtn.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start()
        }
    }

    private fun startDictation() {
        if (recorder.isRecording || panel.visibility == View.VISIBLE) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_DICTATE)
            return
        }
        try {
            recorder.start()
        } catch (e: Exception) {
            toast(e.message ?: "Erro no microfone")
            return
        }
        dictateBtn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        wave.reset()
        wave.processing = false
        checkBtn.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        panelHint.text = "Toque no ✓ para concluir  →"
        panelCaption.text = "Ouvindo\nMicrofone do celular"
        showPanel(true)
    }

    private fun confirmDictation() {
        if (!recorder.isRecording) return
        panel.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val res = recorder.stop()
        if (res == null) {
            showPanel(false)
            toast("Não ouvi nada")
            return
        }
        wave.processing = true
        checkBtn.visibility = View.INVISIBLE
        spinner.visibility = View.VISIBLE
        panelHint.text = ""
        panelCaption.text = "Transcrevendo\nem ${host()}"
        submit(res.wav)
    }

    /** Envia para transcrever; se falhar, o áudio fica guardado e vira um cartão na tela inicial. */
    private fun submit(wav: ByteArray) {
        val gen = ++dictGen
        sending = true
        refresh()
        io.execute {
            val r = runCatching { WhisperClient.process(prefs, wav) }
            if (r.isFailure) Pending.save(this, wav) else Pending.clear(this)
            main.post {
                sending = false
                if (panel.visibility == View.VISIBLE) showPanel(false)
                if (gen == dictGen) r.fold(
                    onSuccess = {
                        if (it.text.isEmpty()) toast("Não ouvi nada") else {
                            prefs.addHistory(it.text)
                            copy(it.text)
                            toast(if (it.viaFallback) "Copiado · pelo servidor reserva" else "Copiado")
                        }
                    },
                    onFailure = { toast(WhisperClient.explain(it) + " O áudio ficou guardado."); ping() }
                )
                refresh()
            }
        }
    }

    private fun cancelDictation() {
        dictGen++
        recorder.cancel()
        showPanel(false)
    }

    // ---------------------------------------------------------------- ajustes

    private fun buildSettings(): View {
        val col = column(pad = 20, bottom = 40)

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(
            iconButton(Glyph.BACK, Ui.SURFACE, Ui.TEXT, 40) { showSettings(false) },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        bar.addView(serif("Ajustes", 27f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        col.addView(bar, lp(top = 10))

        col.addView(section("Conexão"), lp(top = 26))
        statusBox = card(radius = 24, padV = 18)
        col.addView(statusBox, lp(top = 10))

        col.addView(section("Servidor"), lp(top = 28))
        val server = card(radius = 24, padV = 18)
        server.addView(field("Endereço", prefs.endpoint, "http://workstation:8178", uri = true) {
            prefs.endpoint = it
            main.removeCallbacks(pingLater)
            main.postDelayed(pingLater, 700)
        })
        server.addView(
            text("Nome da máquina no Tailscale e a porta. O caminho /v1/audio/transcriptions é completado sozinho.", 13f, Ui.TEXT_DIM)
                .apply { setLineSpacing(0f, 1.2f) },
            lp(top = 8)
        )
        server.addView(field("Idioma", prefs.language, "pt") { prefs.language = it }, lp(top = 18))
        server.addView(field("Vocabulário", prefs.prompt, "Nomes e siglas que ele costuma errar", multi = true) { prefs.prompt = it }, lp(top = 18))
        server.addView(field("Modelo", prefs.model, "whisper-1") { prefs.model = it }, lp(top = 18))
        server.addView(field("Chave de API (opcional)", prefs.apiKey, "Só se o servidor exigir", secret = true) { prefs.apiKey = it }, lp(top = 18))
        col.addView(server, lp(top = 10))

        col.addView(section("Servidor reserva"), lp(top = 28))
        val fb = card(radius = 24, padV = 18)
        fb.addView(note("Usado só quando o principal não responde. Se for um serviço na internet, o áudio sai do seu Tailscale. Deixe o endereço vazio para não usar."))
        fb.addView(field("Endereço", prefs.fallbackEndpoint, "https://api.groq.com/openai/v1", uri = true) { prefs.fallbackEndpoint = it }, lp(top = 16))
        fb.addView(field("Modelo", prefs.fallbackModel, "whisper-large-v3") { prefs.fallbackModel = it }, lp(top = 18))
        fb.addView(field("Chave de API", prefs.fallbackKey, "Chave do serviço reserva", secret = true) { prefs.fallbackKey = it }, lp(top = 18))
        col.addView(fb, lp(top = 10))

        col.addView(section("Substituições"), lp(top = 28))
        val rep = card(radius = 24, padV = 18)
        rep.addView(field("Trocar palavras", prefs.replacements, "key hall, keyhole = keyhall", multi = true) { prefs.replacements = it })
        rep.addView(note("Uma regra por linha: o que o Whisper escreve, um sinal de igual e o certo. Separe variações com vírgula."), lp(top = 8))
        col.addView(rep, lp(top = 10))

        col.addView(section("Limpeza por LLM"), lp(top = 28))
        val llm = card(radius = 24, padV = 18)
        llm.addView(Switch(this).apply {
            text = "Limpar o texto depois de transcrever"
            setTextColor(Ui.TEXT)
            textSize = 16f
            isChecked = prefs.llmEnabled
            setOnCheckedChangeListener { _, on -> prefs.llmEnabled = on }
        }, MATCH_PARENT, WRAP_CONTENT)
        llm.addView(note("Opcional e desligada. Um modelo de chat compatível com a OpenAI tira “é…”, “tipo” e aplica as correções que você faz falando. Deixa o ditado um pouco mais lento."), lp(top = 8))
        llm.addView(field("Endereço", prefs.llmEndpoint, "http://workstation:11434", uri = true) { prefs.llmEndpoint = it }, lp(top = 16))
        llm.addView(field("Modelo", prefs.llmModel, "Nome do modelo de chat") { prefs.llmModel = it }, lp(top = 18))
        llm.addView(field("Chave de API (opcional)", prefs.llmKey, "Só se o servidor exigir", secret = true) { prefs.llmKey = it }, lp(top = 18))
        col.addView(llm, lp(top = 10))

        col.addView(section("Bolha"), lp(top = 28))
        val ins = card(radius = 24, padV = 18)
        ins.addView(Switch(this).apply {
            text = "Inserir colando"
            setTextColor(Ui.TEXT)
            textSize = 16f
            isChecked = prefs.pasteMode
            setOnCheckedChangeListener { _, on -> prefs.pasteMode = on }
        }, MATCH_PARENT, WRAP_CONTENT)
        ins.addView(
            text("Ligue se algum app não receber o texto. Usa a área de transferência, então substitui o que você tinha copiado.", 13f, Ui.TEXT_DIM)
                .apply { setLineSpacing(0f, 1.2f) },
            lp(top = 8)
        )
        ins.addView(divider(16))
        ins.addView(
            text("Toque na bolha para ditar e toque de novo para concluir. Ou segure, fale e solte. Arraste para mudar de lugar.", 14.5f, Ui.TEXT)
                .apply { setLineSpacing(0f, 1.25f) }
        )
        ins.addView(divider(16))
        ins.addView(note("A bolha nunca aparece em campos de senha. Você também pode escondê-la em apps específicos."))
        ins.addView(
            button("Ocultar em apps…", primary = false) { pickHiddenApps() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) }
        )
        ins.addView(
            button("Restaurar posição", primary = false) {
                prefs.bubbleLift = -1
                prefs.bubbleRight = true
                toast("Bolha de volta à direita, acima do teclado")
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) }
        )
        col.addView(ins, lp(top = 10))

        col.addView(section("Histórico"), lp(top = 28))
        val hist = card(radius = 24, padV = 18)
        hist.addView(text("Fica só neste aparelho, com no máximo 100 ditados.", 14.5f, Ui.TEXT).apply { setLineSpacing(0f, 1.25f) })
        hist.addView(
            button("Limpar histórico", primary = false) { prefs.clearHistory(); toast("Histórico apagado") },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) }
        )
        col.addView(hist, lp(top = 10))

        return scroll(col).apply {
            setBackgroundColor(Ui.BG)
            visibility = View.GONE
            isClickable = true
        }
    }

    private fun pickHiddenApps() {
        io.execute {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filter { it.first != packageName }
                .sortedBy { it.second.lowercase() }
            main.post {
                if (isFinishing) return@post
                val chosen = HashSet(prefs.hiddenApps)
                android.app.AlertDialog.Builder(this)
                    .setTitle("Ocultar a bolha em")
                    .setMultiChoiceItems(
                        apps.map { it.second }.toTypedArray(),
                        BooleanArray(apps.size) { apps[it].first in chosen }
                    ) { _, i, on -> if (on) chosen.add(apps[i].first) else chosen.remove(apps[i].first) }
                    .setPositiveButton("Pronto") { _, _ -> prefs.hiddenApps = chosen }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    private fun note(s: String) = text(s, 13f, Ui.TEXT_DIM).apply { setLineSpacing(0f, 1.2f) }

    private fun showSettings(show: Boolean) {
        val ease = DecelerateInterpolator(1.8f)
        if (show) {
            settings.visibility = View.VISIBLE
            settings.scrollTo(0, 0)
            settings.translationX = settings.width.toFloat().coerceAtLeast(300f * d)
            settings.animate().translationX(0f).setDuration(260).setInterpolator(ease).withEndAction(null).start()
        } else {
            currentFocus?.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(settings.windowToken, 0)
            settings.animate().translationX(settings.width.toFloat()).setDuration(220).setInterpolator(ease)
                .withEndAction { settings.visibility = View.GONE }.start()
        }
    }

    // ---------------------------------------------------------------- peças

    private fun dp(v: Int) = (v * d).toInt()

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun column(pad: Int, bottom: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(8), dp(pad), dp(bottom))
    }

    private fun scroll(content: View) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(content, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun text(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
    }

    private fun serif(s: String, size: Float, italic: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Ui.TEXT)
        typeface = Typeface.create(Typeface.SERIF, if (italic) Typeface.ITALIC else Typeface.NORMAL)
        letterSpacing = -0.01f
        setLineSpacing(0f, 1.08f)
    }

    private fun section(s: String) = text(s, 14.5f, Ui.TEXT_DIM).apply { setPadding(dp(4), 0, 0, 0) }

    private fun card(radius: Int, padV: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.box(Ui.SURFACE, radius * d)
        setPadding(dp(20), dp(padV), dp(20), dp(padV))
    }

    private fun divider(margin: Int) = View(this).apply {
        setBackgroundColor(Ui.OUTLINE)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1))).apply {
            topMargin = dp(margin); bottomMargin = dp(margin)
        }
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Ui.ON_INK else Ui.TEXT)
        background = Ui.box(if (primary) Ui.INK else Ui.RAISED, 13f * d)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }

    private fun iconButton(kind: Int, bg: Int, fg: Int, size: Int, onClick: () -> Unit) = FrameLayout(this).apply {
        background = Ui.box(bg, 99f * d)
        val g = dp(size) * 46 / 100
        addView(Glyph(context, kind, fg), FrameLayout.LayoutParams(g, g, Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    private fun field(
        label: String, value: String, placeholder: String,
        uri: Boolean = false, secret: Boolean = false, multi: Boolean = false,
        onChange: (String) -> Unit
    ): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(label, 13.5f, Ui.TEXT_DIM))
        val e = EditText(this).apply {
            setText(value)
            hint = placeholder
            setHintTextColor(Ui.withAlpha(Ui.TEXT_DIM, 0x99))
            setTextColor(Ui.TEXT)
            textSize = 16f
            background = Ui.box(Ui.BG, 14f * d, Ui.OUTLINE, maxOf(1, dp(1)))
            setPadding(dp(14), dp(13), dp(14), dp(13))
            inputType = when {
                uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
            })
        }
        box.addView(e, lp(top = 7))
        return box
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Dita", s))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAILSCALE = "com.tailscale.ipn"
        private const val REQ_DICTATE = 2
        private const val TESTING = 0
        private const val OK = 1
        private const val FAIL = 2
    }
}
