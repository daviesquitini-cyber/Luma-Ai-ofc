package app.luma.voice

import kotlinx.coroutines.flow.MutableStateFlow

enum class VoicePhase {
    OFF, HOTWORD, SPEAKING, LISTENING, THINKING;
    val inSession: Boolean get() = this == SPEAKING || this == LISTENING || this == THINKING
}

/** Estado compartilhado entre o serviço e a UI. */
object VoiceState {
    val phase = MutableStateFlow(VoicePhase.OFF)
}
