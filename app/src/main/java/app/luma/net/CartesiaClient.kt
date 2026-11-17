package app.luma.net

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * TTS exclusivamente via Cartesia. O áudio chega em PCM 16-bit 24 kHz (container "raw")
 * e é escrito em um AudioTrack em modo streaming enquanto ainda está baixando (baixa latência).
 * A função só retorna quando o áudio TERMINOU de tocar — é o gatilho para reabrir o microfone.
 */
class CartesiaClient {

    companion object {
        private const val SAMPLE_RATE = 24000
        private const val MODEL_ID = "sonic-2"            // confira o modelo mais recente na doc da Cartesia
        private const val API_VERSION = "2025-04-16"      // idem para o header Cartesia-Version
        private const val MAX_CHARS = 280
    }

    suspend fun speak(apiKey: String, voiceId: String, text: String) {
        require(apiKey.isNotBlank()) { "Configure a chave da Cartesia em Configurações." }
        require(voiceId.isNotBlank()) { "Configure o Voice ID da Cartesia em Configurações." }

        val chunks = splitText(clean(text))
        if (chunks.isEmpty()) return

        val track = buildTrack()
        var totalBytes = 0L
        try {
            track.play()
            for (chunk in chunks) totalBytes += streamChunk(track, apiKey, voiceId, chunk)

            // Espera o buffer esvaziar de verdade (head position alcançar o total de frames escritos)
            val totalFrames = totalBytes / 2
            val deadline = SystemClock.elapsedRealtime() + totalFrames * 1000 / SAMPLE_RATE + 2000
            while (track.playbackHeadPosition < totalFrames && SystemClock.elapsedRealtime() < deadline) {
                delay(40)
            }
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }

    private suspend fun streamChunk(track: AudioTrack, apiKey: String, voiceId: String, text: String): Long {
        val json = JSONObject()
            .put("model_id", MODEL_ID)
            .put("transcript", text)
            .put("voice", JSONObject().put("mode", "id").put("id", voiceId))
            .put("language", "pt")
            .put(
                "output_format",
                JSONObject().put("container", "raw").put("encoding", "pcm_s16le").put("sample_rate", SAMPLE_RATE)
            )
            .toString()

        val request = Request.Builder()
            .url("https://api.cartesia.ai/tts/bytes")
            .header("X-API-Key", apiKey)
            .header("Cartesia-Version", API_VERSION)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val response = Http.client.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use { r ->
                if (!r.isSuccessful) throw IOException("Cartesia ${r.code}: ${r.body?.string()?.take(200)}")
                val src = r.body!!.byteStream()
                val buf = ByteArray(8192)
                var filled = 0
                var total = 0L
                while (true) {
                    val n = src.read(buf, filled, buf.size - filled)
                    if (n < 0) break
                    filled += n
                    val writable = filled - (filled % 2)       // PCM16: sempre pares de bytes
                    if (writable > 0) {
                        val w = track.write(buf, 0, writable)
                        if (w < 0) throw IOException("Falha no AudioTrack ($w)")
                        total += writable
                        val rem = filled - writable
                        if (rem > 0) buf[0] = buf[writable]
                        filled = rem
                    }
                    currentCoroutineContext().ensureActive()
                }
                total
            }
        }
    }

    private fun buildTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, SAMPLE_RATE))   // ~0,5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun clean(t: String): String = t
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_#`>~]"), "")
        .trim()

    private fun splitText(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[.!?…])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length + 1 > MAX_CHARS) { out += sb.toString(); sb.clear() }
            if (s.length > MAX_CHARS) s.chunked(MAX_CHARS).forEach { out += it }
            else { if (sb.isNotEmpty()) sb.append(' '); sb.append(s) }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
