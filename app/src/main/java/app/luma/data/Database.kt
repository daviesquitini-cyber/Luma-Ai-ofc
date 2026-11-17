package app.luma.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** role: "user" | "assistant" | "error" (error só aparece na tela, nunca vai para a OpenAI)
 *  source: "text" | "voice" */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val source: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ConversationDao {
    @Insert suspend fun insert(c: ConversationEntity): Long
    @Query("SELECT id FROM conversations ORDER BY id DESC LIMIT 1") suspend fun latestId(): Long?
    @Query("SELECT id FROM conversations ORDER BY id DESC LIMIT 1") fun observeLatestId(): Flow<Long?>
    @Query("DELETE FROM conversations") suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Insert suspend fun insert(m: MessageEntity): Long
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY id")
    fun observe(cid: Long): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY id")
    suspend fun list(cid: Long): List<MessageEntity>
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
}
