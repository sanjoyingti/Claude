package com.arin.chat

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arin.data.Attachment
import com.arin.data.Attachments
import com.arin.data.Chat
import com.arin.data.ChatStore
import com.arin.data.ChatSummary
import com.arin.data.Message
import com.arin.data.Settings
import com.arin.data.ThemeMode
import com.arin.llm.Delta
import com.arin.llm.Llm
import com.arin.llm.ModelOption
import com.arin.llm.Provider
import com.arin.llm.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the chat, the chats list and Settings share. One instance for the whole app. */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app)
    val settings = Settings(app)

    // ---- Current chat ----
    var chatId by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("New chat")
        private set
    val messages = mutableStateListOf<Message>()

    /** Files waiting to go with the next message. */
    val pending = mutableStateListOf<Attachment>()

    /** Text from another app's Share button, waiting to be placed in the message box. */
    var sharedText by mutableStateOf<String?>(null)

    var streaming by mutableStateOf(false)
        private set
    var liveText by mutableStateOf("")
        private set
    var liveThinking by mutableStateOf("")
        private set
    var notice by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var thinkStart = 0L

    // ---- Chats list ----
    val chats = mutableStateListOf<ChatSummary>()
    var query by mutableStateOf("")
        private set

    // ---- Settings ----
    var provider by mutableStateOf(settings.provider)
        private set
    var models by mutableStateOf(com.arin.llm.DefaultModels.byProvider.getValue(settings.provider))
        private set
    var model by mutableStateOf(settings.model(settings.provider) ?: models.first().id)
        private set
    var theme by mutableStateOf(settings.theme)
        private set
    var showThinking by mutableStateOf(settings.showThinking)
        private set

    init {
        refreshChats()
        loadModels()
    }

    // ---------------- Sending ----------------

    fun send(text: String) {
        if (streaming) return
        val files = pending.toList()
        if (text.isBlank() && files.isEmpty()) return
        pending.clear()
        messages += Message(fromUser = true, text = text.trim(), attachments = files)
        if (messages.size == 1) title = makeTitle(text, files)
        persist()
        reply()
    }

    /** Ask again for the last reply. */
    fun retry() {
        if (streaming) return
        while (messages.isNotEmpty() && !messages.last().fromUser) messages.removeAt(messages.lastIndex)
        if (messages.isEmpty()) return
        persist()
        reply()
    }

    fun stop() {
        job?.cancel()
    }

    private fun reply() {
        val turns = messages.filterNot { it.failed }.map {
            Turn(it.fromUser, if (it.fromUser) Attachments.promptWithFiles(it.text, it.attachments) else it.text)
        }
        val p = provider
        val m = model
        val key = settings.key(p)
        streaming = true
        liveText = ""
        liveThinking = ""
        thinkStart = 0L
        job = viewModelScope.launch {
            val text = StringBuilder()
            val thinking = StringBuilder()
            var failure: String? = null
            try {
                Llm.stream(p, key, m, turns).collect { d ->
                    when (d) {
                        is Delta.Text -> { text.append(d.text); liveText = text.toString() }
                        is Delta.Thinking -> {
                            if (thinkStart == 0L) thinkStart = System.currentTimeMillis()
                            thinking.append(d.text)
                            liveThinking = thinking.toString()
                        }
                    }
                }
                if (text.isBlank()) failure = "The model sent an empty reply. Tap Retry, or pick another model."
            } catch (e: CancellationException) {
                // Stop pressed: keep whatever arrived.
            } catch (e: Throwable) {
                failure = e.message ?: "Something went wrong."
            } finally {
                val seconds = if (thinkStart > 0) ((System.currentTimeMillis() - thinkStart) / 1000).toInt() else 0
                when {
                    failure != null && text.isBlank() -> messages += Message(fromUser = false, text = failure, failed = true)
                    else -> {
                        messages += Message(
                            fromUser = false,
                            text = text.toString().ifBlank { "(Stopped)" },
                            thinking = thinking.toString(),
                            thoughtSeconds = seconds,
                        )
                        if (failure != null) notice = failure
                    }
                }
                streaming = false
                liveText = ""
                liveThinking = ""
                job = null
                persist()
            }
        }
    }

    private fun makeTitle(text: String, files: List<Attachment>): String {
        val base = text.lines().firstOrNull { it.isNotBlank() }?.trim() ?: files.firstOrNull()?.name ?: "New chat"
        return if (base.length > 48) base.take(47).trimEnd() + "…" else base
    }

    private fun persist() {
        if (messages.isEmpty()) return
        val id = chatId ?: java.util.UUID.randomUUID().toString().also { chatId = it }
        val chat = Chat(id = id, title = title, updated = System.currentTimeMillis(), messages = messages.toList())
        viewModelScope.launch(Dispatchers.IO) {
            store.save(chat)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    // ---------------- Attachments ----------------

    fun attach(uris: List<Uri>) {
        viewModelScope.launch {
            uris.forEach { uri ->
                try {
                    pending += Attachments.read(getApplication(), uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    notice = e.message ?: "Couldn't read that file."
                }
            }
        }
    }

    fun removePending(a: Attachment) {
        pending.remove(a)
    }

    // ---------------- Chats list ----------------

    fun newChat() {
        stop()
        chatId = null
        title = "New chat"
        messages.clear()
        pending.clear()
    }

    fun open(id: String) {
        if (id == chatId) return
        stop()
        viewModelScope.launch {
            val chat = withContext(Dispatchers.IO) { store.load(id) } ?: run {
                notice = "That chat couldn't be opened."
                return@launch
            }
            chatId = chat.id
            title = chat.title
            messages.clear()
            messages.addAll(chat.messages)
            pending.clear()
        }
    }

    fun rename(id: String, newTitle: String) {
        val t = newTitle.trim().ifEmpty { return }
        if (id == chatId) title = t
        viewModelScope.launch(Dispatchers.IO) {
            store.rename(id, t)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    fun delete(id: String) {
        if (id == chatId) newChat()
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(id)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    fun deleteAll() {
        newChat()
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteAll()
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    fun search(q: String) {
        query = q
        refreshChats()
    }

    private fun refreshChats() {
        val q = query
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.search(q) }
            if (q == query) {
                chats.clear()
                chats.addAll(list)
            }
        }
    }

    // ---------------- Settings ----------------

    fun chooseProvider(p: Provider) {
        provider = p
        settings.provider = p
        models = com.arin.llm.DefaultModels.byProvider.getValue(p)
        model = settings.model(p) ?: models.first().id
        loadModels()
    }

    fun chooseModel(option: ModelOption) {
        model = option.id
        settings.setModel(provider, option.id)
    }

    fun saveKey(p: Provider, key: String) {
        settings.setKey(p, key)
        if (p == provider) loadModels()
    }

    fun hasKey(p: Provider) = settings.key(p).isNotBlank()

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        settings.theme = mode
    }

    fun toggleThinking(show: Boolean) {
        showThinking = show
        settings.showThinking = show
    }

    fun modelLabel(): String = models.firstOrNull { it.id == model }?.label ?: model

    private fun loadModels() {
        val p = provider
        viewModelScope.launch {
            val list = Llm.models(p, settings.key(p))
            if (p != provider) return@launch
            models = list
            // Keep the saved choice if it still exists; otherwise take the first.
            val saved = settings.model(p)
            model = list.firstOrNull { it.id == saved }?.id ?: list.first().id
        }
    }
}
