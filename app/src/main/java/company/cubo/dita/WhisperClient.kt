package company.cubo.dita

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Cliente mínimo de `POST /v1/audio/transcriptions` (formato OpenAI). Chamadas bloqueantes. */
object WhisperClient {

    /** Aceita a base (`http://host:8178`), a base com `/v1` ou a URL completa. */
    fun transcriptionUrl(endpoint: String): String {
        var e = endpoint.trim().trimEnd('/')
        if (!e.startsWith("http://") && !e.startsWith("https://")) e = "http://$e"
        return when {
            e.contains("/audio/transcriptions") -> e
            e.endsWith("/v1") -> "$e/audio/transcriptions"
            else -> "$e/v1/audio/transcriptions"
        }
    }

    fun transcribe(p: Prefs, wav: ByteArray): String {
        val boundary = "----dita" + System.nanoTime()
        val body = ByteArrayOutputStream(wav.size + 1024)
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", p.model.ifEmpty { "whisper-1" })
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

        val conn = URL(transcriptionUrl(p.endpoint)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6000
            conn.readTimeout = 120000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (p.apiKey.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
            conn.setFixedLengthStreamingMode(body.size())
            conn.outputStream.use { body.writeTo(it) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw RuntimeException("servidor respondeu $code ${text.take(120)}".trim())
            val out = runCatching { JSONObject(text).optString("text", "") }.getOrDefault(text)
            return clean(out)
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

    fun explain(e: Throwable): String = when (e) {
        is UnknownHostException -> "Não achei o servidor. O Tailscale está ligado?"
        is ConnectException -> "Servidor recusou a conexão. O Whisper está rodando?"
        is SocketTimeoutException -> "Servidor não respondeu a tempo."
        else -> e.message ?: e.javaClass.simpleName
    }
}
