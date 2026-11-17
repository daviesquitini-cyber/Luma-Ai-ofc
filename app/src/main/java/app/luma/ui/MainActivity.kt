package app.luma.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface {
                    val vm: ChatViewModel = viewModel()
                    var screen by rememberSaveable { mutableStateOf("chat") }
                    if (screen == "settings") {
                        BackHandler { screen = "chat" }
                        SettingsScreen(onBack = { screen = "chat" })
                    } else {
                        ChatScreen(vm = vm, onOpenSettings = { screen = "settings" })
                    }
                }
            }
        }
    }
}
