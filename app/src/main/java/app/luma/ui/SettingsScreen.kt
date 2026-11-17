package app.luma.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.luma.LumaApp
import app.luma.data.Settings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = (ctx.applicationContext as LumaApp).settings
    val scope = rememberCoroutineScope()

    var openAi by remember { mutableStateOf("") }
    var cartesia by remember { mutableStateOf("") }
    var voiceId by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("gpt-4o-mini") }

    LaunchedEffect(Unit) {
        val s = store.get()
        openAi = s.openAiKey; cartesia = s.cartesiaKey; voiceId = s.voiceId; model = s.model
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Configurações") }, navigationIcon = { TextButton(onClick = onBack) { Text("←") } })
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(openAi, { openAi = it }, label = { Text("Chave da API OpenAI") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Modelo (gpt-4o-mini ou gpt-4o)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cartesia, { cartesia = it }, label = { Text("Chave da API Cartesia") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(voiceId, { voiceId = it }, label = { Text("Voice ID da Cartesia") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            Button(
                onClick = {
                    scope.launch {
                        store.save(Settings(openAi, cartesia, voiceId, model))
                        Toast.makeText(ctx, "Configurações salvas", Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Salvar") }
        }
    }
}
