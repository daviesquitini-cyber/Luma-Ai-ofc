#!/usr/bin/env bash
# gerar_luma.sh — cria o projeto Luma completo e empacota em Luma.zip
set -euo pipefail

ROOT="Luma"
rm -rf "$ROOT" Luma.zip
mkdir -p "$ROOT"

# ---------- helpers ----------
write() { # write <caminho> ; conteúdo via stdin
  mkdir -p "$(dirname "$1")"
  cat > "$1"
}

# =====================================================================
# 1. Configuração Gradle raiz
# =====================================================================
write "$ROOT/settings.gradle.kts" <<'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Luma"
include(":app")
EOF

write "$ROOT/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
}
EOF

write "$ROOT/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
EOF

# =====================================================================
# 2. app/build.gradle.kts
# =====================================================================
write "$ROOT/app/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.luma.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.luma.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Chaves via local.properties (NÃO versionar). Em produção: proxy no servidor.
        buildConfigField("String", "OPENAI_API_KEY",
            "\"${project.findProperty("OPENAI_API_KEY") ?: ""}\"")
        buildConfigField("String", "CARTESIA_API_KEY",
            "\"${project.findProperty("CARTESIA_API_KEY") ?: ""}\"")
        buildConfigField("String", "CARTESIA_VOICE_ID",
            "\"${project.findProperty("CARTESIA_VOICE_ID") ?: ""}\"")
    }

    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
EOF

# =====================================================================
# 3. AndroidManifest
# =====================================================================
write "$ROOT/app/src/main/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <application
        android:name=".LumaApp"
        android:allowBackup="true"
        android:label="Luma"
        android:supportsRtl="true"
        android:theme="@style/Theme.Luma">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

write "$ROOT/app/src/main/res/values/themes.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Luma" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
EOF

# =====================================================================
# 4. Application + DI simples
# =====================================================================
write "$ROOT/app/src/main/java/com/luma/app/LumaApp.kt" <<'EOF'
package com.luma.app

import android.app.Application
import androidx.room.Room
import com.luma.app.data.local.LumaDatabase
import com.luma.app.data.remote.CartesiaClient
import com.luma.app.data.remote.OpenAiClient
import com.luma.app.data.repository.ChatRepository

class LumaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    private val db: LumaDatabase = Room.databaseBuilder(
        app, LumaDatabase::class.java, "luma.db"
    ).build()

    private val openAi = OpenAiClient(BuildConfig.OPENAI_API_KEY)
    private val cartesia = CartesiaClient(
        BuildConfig.CARTESIA_API_KEY,
        BuildConfig.CARTESIA_VOICE_ID
    )

    val chatRepository = ChatRepository(db.messageDao(), openAi, cartesia)
}
EOF

# =====================================================================
# 5. Camada local (Room)
# =====================================================================
write "$ROOT/app/src/main/java/com/luma/app/data/local/MessageEntity.kt" <<'EOF'
package com.luma.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,          // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long,
    val isVoiceInput: Boolean = false
)
EOF

write "$ROOT/app/src/main/java/com/luma/app/data/local/MessageDao.kt" <<'EOF'
package com.luma.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllFlow(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAll(): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}
EOF

write "$ROOT/app/src/main/java/com/luma/app/data/local/LumaDatabase.kt" <<'EOF'
package com.luma.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class LumaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
}
EOF

# =====================================================================
# 6. Camada remota — OpenAI (streaming SSE)
# =====================================================================
write "$ROOT/app/src/main/java/com/luma/app/data/remote/OpenAiClient.kt" <<'EOF'
package com.luma.app.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

@Serializable data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable private data class ChatRequest(
    val model: String = "gpt-4o-mini",
    val messages: List<ChatMessage>,
    val stream: Boolean = true
)

@Serializable private data class StreamChoice(val delta: Delta)
@Serializable private data class Delta(
    val content: String? = null
)
@Serializable private data class StreamChunk(
    @SerialName("choices") val choices: List<StreamChoice> = emptyList()
)

class OpenAiClient(private val apiKey: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming
        .build()

    /** Emite fragmentos de texto à medida que chegam do SSE. */
    fun streamCompletion(history: List<ChatMessage>): Flow<String> = callbackFlow {
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(messages = history)
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") { close(); return }
                runCatching {
                    json.decodeFromString(StreamChunk.serializer(), data)
                }.getOrNull()?.choices?.firstOrNull()?.delta?.content?.let { trySend(it) }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: IllegalStateException("SSE falhou: ${response?.code}"))
            }
            override fun onClosed(eventSource: EventSource) { close() }
        }

        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }
}
EOF

# =====================================================================
# 7. Camada remota — Cartesia (stub estruturado p/ WebSocket)
# =====================================================================
write "$ROOT/app/src/main/java/com/luma/app/data/remote/CartesiaClient.kt" <<'EOF'
package com.luma.app.data.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Ponto de integração do TTS Cartesia.
 * Implementação real: WebSocket wss://api.cartesia.ai (model_id="sonic-2"),
 * enviar texto em chunks e receber áudio PCM para reproduzir com AudioTrack.
 * Mantido como stub para o projeto compilar sem credenciais.
 */
class CartesiaClient(
    private val apiKey: String,
    private val voiceId: String
) {
    fun synthesizeStream(text: String): Flow<ByteArray> = flow {
        // TODO: abrir WebSocket, enviar {model_id, transcript:text, voice:{...}, ...}
        // e emitir os buffers PCM recebidos.
        if (apiKey.isBlank() || voiceId.isBlank()) return@flow
    }
}
EOF

# =====================================================================
# 8. Repositório
# =====================================================================
write "$ROOT/app/src/main/java/com/luma/app/data/repository/ChatRepository.kt" <<'EOF'
package com.luma.app.data.repository

import com.luma.app.data.local.MessageDao
import com.luma.app.data.local.MessageEntity
import com.luma.app.data.remote.CartesiaClient
import com.luma.app.data.remote.ChatMessage
import com.luma.app.data.remote.OpenAiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(
    private val dao: MessageDao,
    private val openAi: OpenAiClient,
    private val cartesia: CartesiaClient
) {
    val messagesFlow: Flow<List<MessageEntity>> = dao.getAllFlow()

    suspend fun addMessage(role: String, content: String, isVoice: Boolean = false) {
        dao.insert(MessageEntity(
            role = role,
            content = content,
            timestamp = System.currentTimeMillis(),
            isVoiceInput = isVoice
        ))
    }

    /** Envia histórico atual + pergunta e retorna stream de tokens do assistente. */
    fun streamAnswer(): Flow<String> {
        return messagesFlow.map { list ->
            list.map { ChatMessage(it.role, it.content) }
        }.let { historyFlow ->
            kotlinx.coroutines.flow.flow {
                val history = kotlinx.coroutines.flow.first(historyFlow)
                openAi.streamCompletion(history).collect { emit(it) }
            }
        }
    }

    fun synthesize(text: String): Flow<ByteArray> = cartesia.synthesizeStream(text)

    suspend fun clear() = dao.clearAll()
}
EOF

# =====================================================================
# 9. UI — MainActivity + Tela de Chat + ViewModel
# =====================================================================
write "$ROOT/app/src/main/java/com/luma/app/MainActivity.kt" <<'EOF'
package com.luma.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luma.app.ui.chat.ChatScreen
import com.luma.app.ui.chat.ChatViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = (application as LumaApp).container.chatRepository
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: ChatViewModel = viewModel(
                        factory = ChatViewModel.factory(repo)
                    )
                    ChatScreen(vm)
                }
            }
        }
    }
}
EOF

write "$ROOT/app/src/main/java/com/luma/app/ui/chat/ChatViewModel.kt" <<'EOF'
package com.luma.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.luma.app.data.local.MessageEntity
import com.luma.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(private val repo: ChatRepository) : ViewModel() {

    val messages: StateFlow<List<MessageEntity>> = repo.messagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _streamingText.value = ""
            repo.addMessage("user", trimmed)
            val sb = StringBuilder()
            runCatching {
                repo.streamAnswer().collect { token ->
                    sb.append(token)
                    _streamingText.value = sb.toString()
                }
            }
            if (sb.isNotEmpty()) repo.addMessage("assistant", sb.toString())
            _streamingText.value = ""
            _busy.value = false
        }
    }

    fun clear() = viewModelScope.launch { repo.clear() }

    companion object {
        fun factory(repo: ChatRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(repo) as T
        }
    }
}
EOF

write "$ROOT/app/src/main/java/com/luma/app/ui/chat/ChatScreen.kt" <<'EOF'
package com.luma.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luma.app.data.local.MessageEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val streaming by vm.streamingText.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, streaming) {
        val target = messages.size + if (streaming.isNotEmpty()) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Luma") },
                actions = {
                    IconButton(onClick = { vm.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Limpar")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Mensagem...") },
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = !busy && input.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Enviar")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages) { msg -> MessageBubble(msg) }
            if (streaming.isNotEmpty()) {
                item {
                    MessageBubble(
                        MessageEntity(
                            role = "assistant",
                            content = streaming,
                            timestamp = 0L
                        )
                    )
                }
            }
            if (busy && streaming.isEmpty()) {
                item {
                    Text(
                        "…",
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(msg: MessageEntity) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(10.dp)
        ) {
            Text(
                text = msg.content,
                color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
EOF

# =====================================================================
# 10. Placeholder de áudio (voz) — estrutura pronta
# =====================================================================
write "$ROOT/app/src/main/java/com/luma/app/audio/AudioRecorder.kt" <<'EOF'
package com.luma.app.audio

/**
 * Estrutura para captura contínua com VAD.
 * Próximo passo: AudioRecord (16kHz, mono, PCM16) + Silero VAD (ONNX)
 * para detectar fim de fala e acionar transcrição.
 */
class AudioRecorder {
    @Volatile private var running = false
    fun start(onChunk: (ShortArray) -> Unit) { running = true /* TODO */ }
    fun stop() { running = false }
    val isRunning: Boolean get() = running
}
EOF

write "$ROOT/app/src/main/java/com/luma/app/audio/AudioPlayer.kt" <<'EOF'
package com.luma.app.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** Reprodução streaming de PCM16 16kHz (usada pelo TTS Cartesia). */
class AudioPlayer(sampleRate: Int = 16000) {
    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2)
        .build()

    fun start() { track.play() }
    fun write(pcm: ByteArray) { track.write(pcm, 0, pcm.size) }
    fun stop() { runCatching { track.stop() }; track.release() }
}
EOF

# =====================================================================
# 11. Workflow GitHub Actions
# =====================================================================
write "$ROOT/.github/workflows/build-apk.yml" <<'EOF'
name: Build Luma APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew || true

      - name: Build Debug APK
        run: ./gradlew assembleDebug --stacktrace

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: luma-debug-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error
EOF

# =====================================================================
# 12. README + .gitignore + local.properties de exemplo
# =====================================================================
write "$ROOT/README.md" <<'EOF'
# Luma

App Android (Kotlin + Compose) com chat de texto, histórico em Room e
estrutura pronta para voz contínua (OpenAI + Cartesia).

## Como compilar localmente

1. Abra a pasta no Android Studio (Ladybug+).
2. Crie `local.properties` na raiz com:
