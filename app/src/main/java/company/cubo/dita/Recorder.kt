package company.cubo.dita

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Grava PCM 16 kHz mono 16-bit em memória e devolve um WAV pronto para o Whisper.
 * WAV sem compressão porque o whisper.cpp server só aceita WAV sem ffmpeg; 1 min ≈ 1,9 MB.
 */
class Recorder(private val onLevel: (Float) -> Unit) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile private var running = false
    @Volatile private var peak = 0

    val isRecording: Boolean get() = running

    /** Lança exceção se o microfone não puder ser aberto. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "microfone indisponível" }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 2)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("não foi possível abrir o microfone")
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            error("o microfone está em uso por outro app")
        }
        pcm.reset()
        peak = 0
        record = rec
        running = true
        thread = Thread({ loop(rec) }, "dita-rec").also { it.start() }
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(RATE / 25) // 40 ms
        val bytes = ByteArray(buf.size * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            var sum = 0.0
            var localPeak = 0
            for (i in 0 until n) {
                val s = buf[i].toInt()
                sum += (s * s).toDouble()
                val a = if (s < 0) -s else s
                if (a > localPeak) localPeak = a
            }
            if (localPeak > peak) peak = localPeak
            bb.clear()
            bb.asShortBuffer().put(buf, 0, n)
            synchronized(pcm) {
                if (pcm.size() < MAX_BYTES) pcm.write(bytes, 0, n * 2)
            }
            val rms = sqrt(sum / n) / 32768.0
            // Curva perceptual: fala normal fica em ~0,3–0,9.
            onLevel((sqrt(rms) * 2.2).toFloat().coerceIn(0f, 1f))
        }
    }

    /** Para e devolve o resultado; `null` se foi curto demais ou silencioso. */
    fun stop(): Result? {
        if (!running) return null
        running = false
        thread?.join(500)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        pcm.reset()
        val seconds = data.size / (RATE * 2f)
        if (seconds < 0.35f || peak < SILENCE_PEAK) return null
        return Result(wav(data), seconds)
    }

    fun cancel() {
        stop()
    }

    class Result(val wav: ByteArray, val seconds: Float)

    private fun wav(data: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        out.putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(data.size).put(data)
        return out.array()
    }

    companion object {
        const val RATE = 16000
        private const val MAX_BYTES = RATE * 2 * 600 // 10 min
        private const val SILENCE_PEAK = 500 // ~-36 dBFS: abaixo disso o Whisper só alucina
    }
}
