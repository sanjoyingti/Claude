package com.arin.app

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.arin.chat.ChatViewModel

/** "Share to Arin": text goes in the message box, text files become attachments. */
object IncomingShare {
    /** Goes up once per share, so the shell knows to switch to Chat. */
    var count by mutableIntStateOf(0)
        private set

    fun handle(intent: Intent?, chat: ChatViewModel) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> return
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (uris.isEmpty() && text.isEmpty()) return
        if (uris.isNotEmpty()) chat.attach(uris)
        if (text.isNotEmpty()) chat.sharedText = text
        count++
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
}
