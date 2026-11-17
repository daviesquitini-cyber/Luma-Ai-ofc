package app.luma.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.luma.LumaApp
import app.luma.data.MessageEntity
import app.luma.net.OpenAiClient
import app.luma.net.friendly
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LumaApp
    private val repo = app.repo
    private val openAi = OpenAiClient()

    val messages: StateFlow<List<MessageEntity>> = repo.currentConversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    init {
        viewModelScope.launch { repo.ensureConversationId() }
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _sending.value) return
        viewModelScope.launch {
            val cid = repo.ensureConversationId()
            repo.add(cid, "user", clean, "text")
            _sending.value = true
            try {
                val s = app.settings.get()
                val reply = openAi.chat(s.openAiKey, s.model, repo.history(cid), voiceMode = false)
                repo.add(cid, "assistant", reply, "text")
            } catch (e: Exception) {
                repo.add(cid, "error", "OpenAI: ${e.friendly()}", "text")
            } finally {
                _sending.value = false
            }
        }
    }

    fun newConversation() { viewModelScope.launch { repo.newConversation() } }
    fun clearHistory() { viewModelScope.launch { repo.clearAll() } }
}
