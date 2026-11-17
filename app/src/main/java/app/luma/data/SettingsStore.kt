package app.luma.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "luma_settings")

data class Settings(
    val openAiKey: String = "",
    val cartesiaKey: String = "",
    val voiceId: String = "",
    val model: String = "gpt-4o-mini"
)

class SettingsStore(private val context: Context) {
    private object K {
        val openAi = stringPreferencesKey("openai_key")
        val cartesia = stringPreferencesKey("cartesia_key")
        val voice = stringPreferencesKey("voice_id")
        val model = stringPreferencesKey("model")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            openAiKey = p[K.openAi] ?: "",
            cartesiaKey = p[K.cartesia] ?: "",
            voiceId = p[K.voice] ?: "",
            model = p[K.model] ?: "gpt-4o-mini"
        )
    }

    suspend fun get(): Settings = flow.first()

    suspend fun save(s: Settings) {
        context.dataStore.edit { p ->
            p[K.openAi] = s.openAiKey.trim()
            p[K.cartesia] = s.cartesiaKey.trim()
            p[K.voice] = s.voiceId.trim()
            p[K.model] = s.model.trim().ifEmpty { "gpt-4o-mini" }
        }
    }
}
