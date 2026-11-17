package app.luma.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.luma.data.MessageEntity
import app.luma.voice.VoicePhase
import app.luma.voice.VoiceService
import app.luma.voice.VoiceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val messages by vm.messages.collectAsState()
    val sending by vm.sending.collectAsState()
    val phase by VoiceState.phase.collectAsState()

    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) VoiceService.start(ctx)
        else Toast.makeText(ctx, "A permissão de microfone é necessária.", Toast.LENGTH_LONG).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Luma") },
                actions = {
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Nova conversa") },
                                onClick = { menuOpen = false; vm.newConversation() })
                            DropdownMenuItem(text = { Text("Limpar todo o histórico") },
                                onClick = { menuOpen = false; confirmClear = true })
                            DropdownMenuItem(text = { Text("Configurações") },
                                onClick = { menuOpen = false; onOpenSettings() })
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // ---- barra de voz
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(statusText(phase), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (phase.inSession) {
                        OutlinedButton(onClick = { VoiceService.endSession(ctx) }) { Text("⏹ Parar conversa") }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (phase == VoicePhase.OFF) {
                        Button(onClick = {
                            val perms = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                            }.toTypedArray()
                            permissionLauncher.launch(perms)
                        }) { Text("🎙 Ativar Luma") }
                    } else {
                        TextButton(onClick = { VoiceService.stop(ctx) }) { Text("Desativar") }
                    }
                }
            }

            // ---- mensagens
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { Bubble(it) }
                if (sending) item { Text("Luma está digitando…", style = MaterialTheme.typography.labelMedium) }
            }

            // ---- entrada de texto
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Digite sua mensagem…") },
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.send(input); input = "" },
                    enabled = input.isNotBlank() && !sending
                ) { Text("Enviar") }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Limpar histórico?") },
            text = { Text("Todas as conversas (texto e voz) serão apagadas e a Luma esquecerá o contexto.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } }
        )
    }
}

private fun statusText(p: VoicePhase) = when (p) {
    VoicePhase.OFF -> "Luma desativada"
    VoicePhase.HOTWORD -> "Aguardando você dizer “Luma”…"
    VoicePhase.SPEAKING -> "Luma falando…"
    VoicePhase.LISTENING -> "Ouvindo você…"
    VoicePhase.THINKING -> "Pensando…"
}

@Composable
private fun Bubble(m: MessageEntity) {
    val isUser = m.role == "user"
    val color = when {
        m.role == "error" -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(color = color, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(12.dp)) {
                if (m.source == "voice") Text("🎙 voz", style = MaterialTheme.typography.labelSmall)
                Text(m.content)
            }
        }
    }
}
