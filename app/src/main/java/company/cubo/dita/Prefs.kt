package company.cubo.dita

import android.content.Context
import android.content.SharedPreferences

/** Configuração do app. Tudo fica só no aparelho. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("dita", Context.MODE_PRIVATE)

    var endpoint: String
        get() = sp.getString("endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        set(v) = sp.edit().putString("endpoint", v.trim()).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    var model: String
        get() = sp.getString("model", "whisper-1") ?: "whisper-1"
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var language: String
        get() = sp.getString("language", "pt") ?: "pt"
        set(v) = sp.edit().putString("language", v.trim()).apply()

    /** Vocabulário enviado como `prompt` (nomes próprios, siglas). */
    var prompt: String
        get() = sp.getString("prompt", "") ?: ""
        set(v) = sp.edit().putString("prompt", v.trim()).apply()

    /** Inserir colando da área de transferência em vez de definir o texto do campo. */
    var pasteMode: Boolean
        get() = sp.getBoolean("pasteMode", false)
        set(v) = sp.edit().putBoolean("pasteMode", v).apply()

    /** Posição da bolha: lado (true = direita) e distância acima do teclado, em px. */
    var bubbleRight: Boolean
        get() = sp.getBoolean("bubbleRight", true)
        set(v) = sp.edit().putBoolean("bubbleRight", v).apply()

    var bubbleLift: Int
        get() = sp.getInt("bubbleLift", -1)
        set(v) = sp.edit().putInt("bubbleLift", v).apply()

    /** Substituições de texto, uma por linha: `errado, outro errado = certo`. */
    var replacements: String
        get() = sp.getString("replacements", "") ?: ""
        set(v) = sp.edit().putString("replacements", v.trim()).apply()

    /** Servidor reserva: usado só quando o principal não responde. */
    var fallbackEndpoint: String
        get() = sp.getString("fbEndpoint", "") ?: ""
        set(v) = sp.edit().putString("fbEndpoint", v.trim()).apply()
    var fallbackKey: String
        get() = sp.getString("fbKey", "") ?: ""
        set(v) = sp.edit().putString("fbKey", v.trim()).apply()
    var fallbackModel: String
        get() = sp.getString("fbModel", "whisper-1") ?: "whisper-1"
        set(v) = sp.edit().putString("fbModel", v.trim()).apply()

    /** Limpeza por LLM (chat completions). Desligada por padrão. */
    var llmEnabled: Boolean
        get() = sp.getBoolean("llmOn", false)
        set(v) = sp.edit().putBoolean("llmOn", v).apply()
    var llmEndpoint: String
        get() = sp.getString("llmEndpoint", "") ?: ""
        set(v) = sp.edit().putString("llmEndpoint", v.trim()).apply()
    var llmKey: String
        get() = sp.getString("llmKey", "") ?: ""
        set(v) = sp.edit().putString("llmKey", v.trim()).apply()
    var llmModel: String
        get() = sp.getString("llmModel", "") ?: ""
        set(v) = sp.edit().putString("llmModel", v.trim()).apply()

    /** Pacotes de apps onde a bolha não aparece. */
    var hiddenApps: Set<String>
        get() = sp.getStringSet("hiddenApps", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("hiddenApps", HashSet(v)).apply()

    /** Total de palavras já ditadas. */
    val words: Int get() = sp.getInt("words", 0)

    val store: SharedPreferences get() = sp

    class Entry(val time: Long, val text: String)

    fun history(): List<Entry> = runCatching {
        val a = org.json.JSONArray(sp.getString("history", "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let { Entry(it.getLong("t"), it.getString("s")) } }
    }.getOrDefault(emptyList())

    private fun save(list: List<Entry>, addWords: Int = 0) {
        val a = org.json.JSONArray()
        for (e in list) a.put(org.json.JSONObject().put("t", e.time).put("s", e.text))
        sp.edit().putString("history", a.toString()).putInt("words", words + addWords).apply()
    }

    /** Guarda no topo do histórico (só no aparelho, no máximo 100 itens). */
    fun addHistory(text: String) {
        val count = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        save((listOf(Entry(System.currentTimeMillis(), text)) + history()).take(100), count)
    }

    fun removeHistory(time: Long) = save(history().filter { it.time != time })

    fun clearHistory() = save(emptyList())

    companion object {
        const val DEFAULT_ENDPOINT = "http://workstation:8178"
    }
}
