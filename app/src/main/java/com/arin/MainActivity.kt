package com.arin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.arin.app.AppShell
import com.arin.app.IncomingShare
import com.arin.chat.ChatViewModel
import com.arin.design.ArinTheme

class MainActivity : ComponentActivity() {
    private val chat: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Opened from another app's Share button (and not a rotation replaying that).
        if (savedInstanceState == null) IncomingShare.handle(intent, chat)
        setContent {
            ArinTheme(chat.theme) { AppShell(chat) }
        }
    }

    /** Arin was already open when something was shared to it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        IncomingShare.handle(intent, chat)
    }
}
