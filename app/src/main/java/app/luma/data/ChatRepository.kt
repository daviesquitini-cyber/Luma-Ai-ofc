package app.luma.data

import app.luma.net.ChatTurn
import kotlinx.coroutines.flow.Flow

/** Fonte única de verdade: o chat de texto e o serviço de voz gravam e leem daqui. */
class ChatRepository(private val db: AppDatabase) {

    val currentConversationId: Flow<Long?> = db.conversations().observeLatestId()

    fun messages(conversationId: Long): Flow<List<MessageEntity>> = db.messages().observe(conversationId)

    suspend fun newConversation(): Long =
        db.conversations().insert(ConversationEntity(title = "Conversa"))

    suspend fun ensureConversationId(): Long =
        db.conversations().latestId() ?: newConversation()

    suspend fun add(conversationId: Long, role: String, content: String, source: String) {
        db.messages().insert(MessageEntity(conversationId = conversationId, role = role, content = content, source = source))
    }

    /** Contexto enviado à OpenAI: texto + voz juntos, só user/assistant, últimas [limit] mensagens. */
    suspend fun history(conversationId: Long, limit: Int = 30): List<ChatTurn> =
        db.messages().list(conversationId)
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(limit)
            .map { ChatTurn(it.role, it.content) }

    suspend fun clearAll() {
        db.conversations().deleteAll()   // CASCADE apaga as mensagens
        newConversation()
    }
}
