package app.luma.voice

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.luma.LumaApp
import app.luma.net.CartesiaClient
import app.luma.net.OpenAiClient
import app.luma.net.friendly
import app.luma.ui.MainActivity
import kotlinx.coroutines.*
import java.text.Normalizer
import kotlin.coroutines.resume

/**
 * Máquina de estados:
 *  HOTWORD  -> escuta em loop até ouvir "Luma"
 *  SPEAKING -> Cartesia fala (greeting / respostas)
 *  LISTENING-> microfone aberto para a pergunta
 *  THINKING -> aguardando o ChatGPT
 *  (depois de SPEAKING volta para LISTENING sozinho, sem precisar dizer "Luma")
 */
class VoiceService : Service() {

    companion object {
        private const val ACTION_STOP = "app.luma.action.STOP"
        private const val ACTION_END_SESSION = "app.luma.action.END_SESSION"
        private const val CHANNEL_ID = "luma_voice"
        private const val NOTIFICATION_ID = 1

        /** Rodadas seguidas de silêncio até voltar ao modo "Luma". Aumente se quiser escuta mais persistente. */
        const val MAX_SILENT_ROUNDS = 3

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, VoiceService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, VoiceService::class.java))
        }
        /** Botão "Parar conversa": encerra a conversa contínua e volta a aguardar "Luma". */
        fun endSession(ctx: Context) {
            ctx.startService(Intent(ctx, VoiceService::class.java).setAction(ACTION_END_SESSION))
        }
    }

    private sealed interface ListenResult {
        data class Heard(val text: String) : ListenResult
        data object Silence : ListenResult
        data class Failure(val code: Int) : ListenResult {
            val fatal get() = code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mainJob: Job? = null
    private var sessionJob: Job? = null

    private val openAi = OpenAiClient()
    private val cartesia = CartesiaClient()
    private val app get() = application as LumaApp

    private val hotwordRegex = Regex("\\b(luma|lumma|lhuma)\\b")
    private val stopRegex = Regex("\\b(tchau|encerrar)\\b")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_END_SESSION -> {
                if (mainJob == null) stopSelf() else sessionJob?.cancel()
                return START_NOT_STICKY
            }
        }
        startAsForeground()
        if (mainJob?.isActive != true) mainJob = scope.launch { mainLoop() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        VoiceState.phase.value = VoicePhase.OFF
        super.onDestroy()
    }

    // ---------------------------------------------------------------- fluxo principal

    private suspend fun mainLoop() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Reconhecimento de voz indisponível neste aparelho.", Toast.LENGTH_LONG).show()
            stopSelf(); return
        }
        while (true) {
            VoiceState.phase.value = VoicePhase.HOTWORD
            if (!waitForHotword()) { stopSelf(); return }
            val job = scope.launch { runSession() }
            sessionJob = job
            job.join()
        }
    }

    private suspend fun waitForHotword(): Boolean {
        while (true) {
            when (val r = listen { hotwordRegex.containsMatchIn(normalize(it)) }) {
                is ListenResult.Heard -> return true
                ListenResult.Silence -> delay(250)
                is ListenResult.Failure -> {
                    if (r.fatal) {
                        Toast.makeText(this, "Sem permissão de microfone.", Toast.LENGTH_LONG).show()
                        return false
                    }
                    delay(1000)
                }
            }
        }
    }

    private suspend fun runSession() {
        val cid = app.repo.ensureConversationId()
        say(cid, "Olá, Esquitini", source = "voice")

        var silent = 0
        while (true) {
            delay(300)  // evita o microfone captar o final do próprio áudio da Luma
            VoiceState.phase.value = VoicePhase.LISTENING

            when (val r = listen()) {
                is ListenResult.Heard -> {
                    silent = 0
                    app.repo.add(cid, "user", r.text, "voice")

                    if (stopRegex.containsMatchIn(normalize(r.text))) {
                        say(cid, "Até logo!", source = "voice")
                        return
                    }

                    VoiceState.phase.value = VoicePhase.THINKING
                    val s = app.settings.get()
                    val reply = try {
                        openAi.chat(s.openAiKey, s.model, app.repo.history(cid), voiceMode = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        app.repo.add(cid, "error", "OpenAI: ${e.friendly()}", "voice")
                        continue
                    }
                    say(cid, reply, source = "voice")   // salva no histórico e fala; depois o loop reabre o mic
                }
                ListenResult.Silence -> if (++silent >= MAX_SILENT_ROUNDS) return
                is ListenResult.Failure -> {
                    if (r.fatal) { stopSelf(); return }
                    delay(500)
                    if (++silent >= MAX_SILENT_ROUNDS) return
                }
            }
        }
    }

    /** Salva a fala da Luma no histórico e a sintetiza via Cartesia (suspende até o áudio terminar). */
    private suspend fun say(cid: Long, text: String, source: String) {
        app.repo.add(cid, "assistant", text, source)
        VoiceState.phase.value = VoicePhase.SPEAKING
        val s = app.settings.get()
        try {
            cartesia.speak(s.cartesiaKey, s.voiceId, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            app.repo.add(cid, "error", "Cartesia: ${e.friendly()}", source)
        }
    }

    // ---------------------------------------------------------------- reconhecimento de voz

    /**
     * Abre o microfone uma vez.
     * - matcher != null (modo hotword): retorna Heard assim que um resultado parcial/final casar.
     * - matcher == null (modo conversa): retorna o resultado final.
     */
    private suspend fun listen(matcher: ((String) -> Boolean)? = null): ListenResult =
        suspendCancellableCoroutine { cont ->
            val rec = SpeechRecognizer.createSpeechRecognizer(this)
            var finished = false

            fun finish(r: ListenResult) {
                if (finished) return
                finished = true
                runCatching { rec.destroy() }
                if (cont.isActive) cont.resume(r)
            }

            fun Bundle?.texts(): List<String> =
                this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    finish(
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                            ListenResult.Silence
                        else ListenResult.Failure(error)
                    )
                }

                override fun onResults(results: Bundle?) {
                    val all = results.texts()
                    val first = all.firstOrNull()
                    finish(
                        when {
                            first == null -> ListenResult.Silence
                            matcher == null -> ListenResult.Heard(first)
                            matcher(all.joinToString(" ")) -> ListenResult.Heard(first)
                            else -> ListenResult.Silence
                        }
                    )
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (matcher != null) {
                        val all = partialResults.texts()
                        if (all.isNotEmpty() && matcher(all.joinToString(" "))) finish(ListenResult.Heard(all.first()))
                    }
                }
            })

            cont.invokeOnCancellation { mainHandler.post { runCatching { rec.destroy() } } }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
            rec.startListening(intent)
        }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    // ---------------------------------------------------------------- notificação

    private fun startAsForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Luma", NotificationManager.IMPORTANCE_LOW))

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Luma ativa")
            .setContentText("Diga “Luma” para conversar")
            .setContentIntent(open)
            .addAction(0, "Desativar", stop)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
}
