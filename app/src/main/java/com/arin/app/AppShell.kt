package com.arin.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.arin.chat.ChatScreen
import com.arin.chat.ChatViewModel
import com.arin.settings.SettingsScreen

/** Every top-level screen. Chat is home: Back from anywhere else returns to it. */
enum class Screen { Chat, Settings }

/** Owns which screen is showing. Screens never navigate themselves; they call back here. */
@Composable
fun AppShell(vm: ChatViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    BackHandler(enabled = screen != Screen.Chat) { screen = Screen.Chat }
    // Something was shared into Arin: show Chat, where it lands.
    LaunchedEffect(IncomingShare.count) { if (IncomingShare.count > 0) screen = Screen.Chat }

    when (screen) {
        Screen.Chat -> ChatScreen(vm, onOpenSettings = { screen = Screen.Settings })
        Screen.Settings -> SettingsScreen(vm, onBack = { screen = Screen.Chat })
    }
}
