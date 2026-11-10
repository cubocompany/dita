package company.cubo.dita

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Do áudio ao texto final: transcrição (formato OpenAI), servidor reserva,
 * limpeza opcional por LLM e substituições. Chamadas bloqueantes.
 */
object WhisperClient {

    class Outcome(val text: String, val viaFallback: Boolean)

    class HttpError(val code: Int, body: String) : IOException("servidor respondeu $code ${body.take(120)}".trim())

    /** Aceita a base (`http://host:8178`), a base com `/v1` ou a URL completa. */
    fun transcriptionUrl(endpoint: String): String = apiUrl(endpoint, "/audio/transcriptions")

    private fun apiUrl(endpoint: String, path: String): String {
        var e = endpoint.trim().trimEnd('/')
        if (!e.startsWith("http://") && !e.startsWith("https://")) e = "http://$e"
        return when {
            e.contains(path) -> e
            e.endsWith("/v1") -> "$e$path"
            else -> "$e/v1$path"
        }
    }

    /** Lança exceção só se nenhum servidor de transcrição respondeu; o resto nunca perde o texto. */
    fun process(p: Prefs, wav: ByteArray): Outcome {
        var viaFallback = false
        var text = try {
            transcribe(p.endpoint, p.apiKey, p.model, p, wav)
        } catch (e: IOException) {
            if (p.fallbackEndpoint.isEmpty()) throw e
            viaFallback = true
            transcribe(p.fallbackEndpoint, p.fallbackKey, p.fallbackModel, p, wav)
        }
        if (text.isNotEmpty() && p.llmEnabled && p.llmEndpoint.isNotEmpty()) {
            text = runCatching { polish(p, text) }.getOrDefault(text)
        }
        return Outcome(replace(text, p.replacements), viaFallback)
    }

    private fun transcribe(endpoint: String, key: String, model: String, p: Prefs, wav: ByteArray): String {
        val boundary = "----dita" + System.nanoTime()
        val body = ByteArrayOutputStream(wav.size + 1024)
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", model.ifEmpty { "whisper-1" })
        field("response_format", "json")
        field("temperature", "0")
        if (p.language.isNotEmpty()) field("language", p.language)
        if (p.prompt.isNotEmpty()) field("prompt", p.prompt)
        body.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n").toByteArray()
        )
        body.write(wav)
        body.write("\r\n--$boundary--\r\n".toByteArray())

        val text = post(
            transcriptionUrl(endpoint), "multipart/form-data; boundary=$boundary",
            body.toByteArray(), key, readTimeout = 120000
        )
        return clean(runCatching { JSONObject(text).optString("text", "") }.getOrDefault(text))
    }

    /** Limpeza por LLM (`/v1/chat/completions`): tira muletas e aplica autocorreções faladas. */
    private fun polish(p: Prefs, text: String): String {
        val body = JSONObject()
            .put("model", p.llmModel)
            .put("temperature", 0)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", POLISH_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", text))
            )
        val res = post(
            apiUrl(p.llmEndpoint, "/chat/completions"), "application/json",
            body.toString().toByteArray(), p.llmKey, readTimeout = 30000
        )
        val out = JSONObject(res).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content", "").trim()
        // Se o modelo respondeu ao conteúdo em vez de limpar, o tamanho denuncia: fica o original.
        return if (out.isEmpty() || out.length > text.length * 2 + 40) text else out
    }

    private fun post(url: String, contentType: String, body: ByteArray, key: String, readTimeout: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6000
            conn.readTimeout = readTimeout
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Tempo de resposta em ms. Qualquer resposta HTTP conta como servidor alcançável. */
    fun ping(endpoint: String): Long {
        val u = URL(transcriptionUrl(endpoint))
        val root = URL(u.protocol, u.host, u.port, "/")
        val t0 = System.nanoTime()
        val conn = root.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.responseCode
            return (System.nanoTime() - t0) / 1_000_000
        } finally {
            conn.disconnect()
        }
    }

    /** Whisper devolve espaço inicial e, às vezes, quebras de linha entre segmentos. */
    private fun clean(s: String): String {
        val t = s.replace(Regex("\\s*\\n\\s*"), " ").replace(Regex(" {2,}"), " ").trim()
        // Em quase-silêncio o Whisper devolve só pontuação ("." ou "..."): tratar como vazio.
        return if (t.any { it.isLetterOrDigit() }) t else ""
    }

    /**
     * Substituições, uma por linha: `key hall, keyhole = keyhall`.
     * Ignora maiúsculas e só troca palavras inteiras.
     */
    fun replace(text: String, rules: String): String {
        var out = text
        for (line in rules.lines()) {
            val m = Regex("\\s*(=|->|→)\\s*").find(line) ?: continue
            val to = line.substring(m.range.last + 1).trim()
            for (from in line.substring(0, m.range.first).split(',')) {
                val f = from.trim()
                if (f.isEmpty()) continue
                val re = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(f) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                out = re.replace(out, Regex.escapeReplacement(to))
            }
        }
        return out
    }

    fun explain(e: Throwable): String = when (e) {
        is UnknownHostException -> "Não achei o servidor. O Tailscale está ligado?"
        is ConnectException -> "Servidor recusou a conexão. O Whisper está rodando?"
        is SocketTimeoutException -> "Servidor não respondeu a tempo."
        else -> e.message ?: e.javaClass.simpleName
    }

    private const val POLISH_PROMPT =
        "Você limpa transcrições de ditado. Remova hesitações e muletas (é, ãh, hum, tipo, né). " +
            "Quando a pessoa se corrige no meio da fala, mantenha só a versão final. " +
            "Corrija pontuação e maiúsculas. Não mude o sentido, não resuma, não traduza, " +
            "não responda ao conteúdo e não acrescente nada. Devolva somente o texto limpo."
}

/** Áudio que não pôde ser enviado: fica em arquivo até ser reenviado ou descartado. */
object Pending {
    private fun file(ctx: Context) = File(ctx.filesDir, "pending.wav")
    fun exists(ctx: Context) = file(ctx).length() > 44
    fun save(ctx: Context, wav: ByteArray) = runCatching { file(ctx).writeBytes(wav) }
    fun load(ctx: Context): ByteArray? = runCatching { file(ctx).takeIf { it.length() > 44 }?.readBytes() }.getOrNull()
    fun clear(ctx: Context) { file(ctx).delete() }
}
