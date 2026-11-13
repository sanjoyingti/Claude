package com.arin.builder

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arin.chat.ChatViewModel
import com.arin.llm.LlmException
import com.arin.llm.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * "View app": a working preview of a Build-mode app, made from its source code before (or after) it is compiled.
 *
 * Android code can't run without being compiled, so the model ports the app's screens and logic to one web page
 * that behaves like the app: every button, field and screen works, and saved data stays saved. The page is shown
 * full screen in a WebView and kept on the phone until the code changes.
 */
object AppPreview {
    /** The project the state below belongs to. */
    var projectId by mutableStateOf<String?>(null)
        private set

    /** The page to show, or null when there is none yet. */
    var html by mutableStateOf<String?>(null)
        private set

    /** A preview is being written right now. */
    var generating by mutableStateOf(false)
        private set

    /** What's happening while [generating] ("Writing the preview… 14 KB"). */
    var status by mutableStateOf("")
        private set

    /** Why the last try failed, in words that can be shown as they are. */
    var error by mutableStateOf<String?>(null)
        private set

    /** The page on screen was made from older code than the project has now. */
    var outdated by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + com.arin.CrashLog.handler { runCatching { com.arin.ArinApp.instance }.getOrNull() },
    )
    private var job: Job? = null

    private fun dir(context: Context) = File(context.filesDir, "app_previews").apply { mkdirs() }
    private fun pageFile(context: Context, id: String) = File(dir(context), "$id.html")
    private fun markFile(context: Context, id: String) = File(dir(context), "$id.mark")

    /** Files that decide how the app looks and behaves (no build setup, no binaries). */
    private fun relevant(project: Project): List<Map.Entry<String, String>> = project.files.entries.filter { (path, _) ->
        val name = path.substringAfterLast('/').lowercase()
        val inBuildOutput = path.contains("/build/") || path.startsWith("build/") || path.startsWith(".")
        !inBuildOutput && (name.endsWith(".kt") || name.endsWith(".java") || name == "androidmanifest.xml" ||
            (name.endsWith(".xml") && path.contains("/res/")) || (name.endsWith(".json") && path.contains("/assets/")))
    }

    /** Changes whenever the code that the preview is made from changes. */
    private fun mark(project: Project): String {
        val md = MessageDigest.getInstance("SHA-1")
        relevant(project).sortedBy { it.key }.forEach { (p, c) ->
            md.update(p.toByteArray()); md.update(0.toByte()); md.update(c.toByteArray()); md.update(0.toByte())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Gets the preview of [project] ready to show: the saved one when the code hasn't changed since, otherwise a
     * new one is written (the old page stays on screen, marked outdated, until the new one is done).
     */
    fun open(context: Context, id: String, project: Project, provider: Provider, apiKey: String, model: String) {
        val app = context.applicationContext
        if (projectId != id) {
            if (generating) cancel()
            projectId = id
            html = null
            error = null
            outdated = false
        }
        if (generating) return
        scope.launch {
            val (saved, fresh) = withContext(Dispatchers.IO) {
                val page = runCatching { pageFile(app, id).takeIf { it.isFile }?.readText() }.getOrNull()
                val made = runCatching { markFile(app, id).readText() }.getOrNull()
                page to (page != null && made == mark(project))
            }
            if (projectId != id || generating) return@launch
            html = saved
            outdated = saved != null && !fresh
            if (!fresh) generate(app, id, project, provider, apiKey, model)
        }
    }

    /** Writes the preview again from the current code. */
    fun generate(context: Context, id: String, project: Project, provider: Provider, apiKey: String, model: String) {
        if (generating) return
        val app = context.applicationContext
        projectId = id
        error = null
        if (relevant(project).isEmpty()) {
            error = "This project has no app code to preview yet."
            return
        }
        if (provider.needsKey && apiKey.isBlank()) {
            error = "Add your ${provider.label} key in Settings first: the preview is written by the model."
            return
        }
        if (model.isBlank()) {
            error = "Pick a model on the chat screen first: the preview is written by the model."
            return
        }
        generating = true
        status = "Reading the app's code…"
        job = scope.launch {
            try {
                val made = mark(project)
                val page = withContext(Dispatchers.Default) { write(project, provider, apiKey, model) }
                withContext(Dispatchers.IO) {
                    runCatching {
                        pageFile(app, id).writeText(page)
                        markFile(app, id).writeText(made)
                    }
                }
                if (projectId == id) {
                    html = page
                    outdated = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (projectId == id) error = friendly(e)
            } finally {
                generating = false
                status = ""
            }
        }
    }

    fun cancel() {
        job?.cancel()
        generating = false
        status = ""
    }

    /** Removes the saved preview of a deleted project. */
    fun forget(context: Context, id: String) {
        runCatching { pageFile(context, id).delete(); markFile(context, id).delete() }
        if (projectId == id) { html = null; outdated = false; error = null }
    }

    private fun friendly(t: Throwable): String = when {
        t is ProviderException && t.code == 401 -> "The model's key was rejected. Check it in Settings. (${t.message})"
        t is ProviderException && t.code == 429 -> "The model is rate limited right now. Wait a moment and try again. (${t.message})"
        t is ProviderException -> t.message ?: "The model returned an error."
        t is LlmException -> t.message ?: "The model returned an error."
        t is IOException -> "Network error. Check your connection and try again."
        else -> t.message ?: "Something went wrong while writing the preview."
    }

    private fun kb(chars: Int) = if (chars < 1024) "$chars characters" else "${chars / 1024} KB"

    /** Asks the model for the page, and for the rest of it while the answer keeps getting cut off. */
    /** The preview is being written by a build (it can't be stopped from the preview screen). */
    var fromBuild by mutableStateOf(false)
        private set

    /**
     * Writes and saves the preview while a build runs, right after the code is written, so View app opens instantly
     * afterwards. Returns null when it's ready (or already was), else why it couldn't be made.
     */
    suspend fun prepare(
        context: Context,
        id: String,
        project: Project,
        provider: Provider,
        apiKey: String,
        model: String,
        onStatus: (String) -> Unit = {},
    ): String? {
        val app = context.applicationContext
        if (relevant(project).isEmpty()) return "no app code yet"
        val made = mark(project)
        val upToDate = withContext(Dispatchers.IO) {
            runCatching { pageFile(app, id).isFile && markFile(app, id).readText() == made }.getOrDefault(false)
        }
        if (upToDate) return null
        val started = withContext(Dispatchers.Main) {
            if (generating) return@withContext false
            if (projectId != id) { projectId = id; html = null; outdated = false }
            error = null
            generating = true
            fromBuild = true
            status = "Writing the preview…"
            true
        }
        if (!started) return "a preview is already being written"
        return try {
            val page = withContext(Dispatchers.Default) { write(project, provider, apiKey, model, onStatus) }
            withContext(Dispatchers.IO) {
                pageFile(app, id).writeText(page)
                markFile(app, id).writeText(made)
            }
            withContext(Dispatchers.Main) { if (projectId == id) { html = page; outdated = false } }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            friendly(e)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                generating = false
                fromBuild = false
                status = ""
            }
        }
    }

    private suspend fun write(
        project: Project,
        provider: Provider,
        apiKey: String,
        model: String,
        onStatus: (String) -> Unit = {},
    ): String {
        fun note(text: String) {
            scope.launch { if (generating) status = text }
            onStatus(text)
        }
        val messages = mutableListOf(ChatMessage(Role.USER, prompt(project)))
        val page = StringBuilder()
        var lastNote = 0L
        for (round in 0..MAX_CONTINUES) {
            val part = StringBuilder()
            resilient(
                attempt = { m -> streamModel(provider, apiKey, m, messages.toList(), SYSTEM) },
                model = model,
                fallback = { alternativeModel(provider, apiKey, model) },
                onRetry = { text -> note(text) },
            ).collect { raw ->
                val thinking = raw.startsWith(THOUGHT)
                if (!thinking) part.append(raw)
                val now = System.currentTimeMillis()
                if (now - lastNote > 300) {
                    lastNote = now
                    val text = when {
                        thinking && part.isEmpty() && page.isEmpty() -> "Working out how the app behaves…"
                        else -> "Writing the preview… " + kb(page.length + part.length)
                    }
                    note(text)
                }
            }
            val piece = if (round == 0) part.toString() else continuation(page, part.toString())
            page.append(piece)
            if (END.containsMatchIn(page) || piece.isBlank()) break
            if (round == MAX_CONTINUES) break
            // Cut off (the answer was longer than the model may write at once): ask for the rest.
            note("It's a big app: writing the next part of the preview… " + kb(page.length))
            messages.clear()
            messages += ChatMessage(Role.USER, prompt(project))
            messages += ChatMessage(Role.ASSISTANT, page.toString())
            messages += ChatMessage(
                Role.USER,
                "Your answer was cut off before </html>. Continue EXACTLY from the character where it stopped: the text so far ends with\n" +
                    page.takeLast(160) + "\nDon't repeat anything that is already written, don't start over, don't explain, and don't use " +
                    "code fences. Finish every function, close every tag, and end with </html>.",
            )
        }
        return extract(page.toString())
            ?: throw LlmException(
                "The model didn't send back a preview page" +
                    (if (page.isBlank()) " (its answer was empty)" else "") + ". Try again, or pick a stronger model on the chat screen.",
            )
    }

    /** The next part of the page, without the code fence or the repeated words some models start it with. */
    private fun continuation(soFar: CharSequence, next: String): String {
        var n = next.trimStart('\n', '\r')
        if (n.startsWith("```")) n = n.substringAfter('\n', "")
        n = n.substringBeforeLast("\n```", n)
        // The model started over instead of continuing: take the new, complete page.
        if (START.find(n.take(400)) != null && END.containsMatchIn(n)) return "\u0000RESTART" + n
        // Drop a long overlap with what was already written (a model that repeats its last lines before going on).
        // Short matches are left alone: closing tags and brackets repeat naturally in a page.
        val tail = soFar.takeLast(400).toString()
        for (len in minOf(tail.length, n.length) downTo 40) {
            if (n.startsWith(tail.takeLast(len))) return n.substring(len)
        }
        return n
    }

    /** The HTML document inside the model's answer, with Arin's small helper script added; null when there's none. */
    private fun extract(answer: String): String? {
        val text = answer.substringAfterLast("\u0000RESTART")
        val start = START.find(text)?.range?.first ?: return null
        val end = END.findAll(text).lastOrNull()?.range?.last
        var page = if (end != null && end > start) text.substring(start, end + 1) else text.substring(start).substringBeforeLast("\n```")
        if (page.length < 200) return null
        if (end == null) page += "\n</script></body></html>" // cut off for good: show what there is
        val at = page.lastIndexOf("</body>", ignoreCase = true).takeIf { it >= 0 } ?: page.lastIndexOf("</html>", ignoreCase = true)
        return if (at >= 0) page.substring(0, at) + HELPER + page.substring(at) else page + HELPER
    }

    private fun prompt(project: Project) = buildString {
        appendLine("App name: ${project.appName}")
        appendLine("Package: ${project.packageName}")
        val what = project.requirements.ifBlank { project.description }.trim()
        if (what.isNotBlank()) {
            appendLine()
            appendLine("What the app is for (the code below is what counts; this only helps you read it):")
            appendLine(what.take(4_000))
        }
        appendLine()
        appendLine("The app's source code:")
        appendLine()
        var budget = SOURCE_BUDGET
        var left = 0
        relevant(project).sortedBy { (path, text) ->
            val name = path.substringAfterLast('/').lowercase()
            when {
                name == "mainactivity.kt" -> 0
                text.contains("@Composable") || text.contains("NavHost") -> 1
                name.endsWith(".kt") || name.endsWith(".java") -> 2
                path.contains("/res/values") || path.contains("/res/layout") || path.contains("/res/menu") -> 3
                name == "androidmanifest.xml" -> 4
                else -> 5
            }
        }.forEach { (path, text) ->
            if (budget <= 0 || text.length > budget && budget < 4_000) { left++; return@forEach }
            val part = text.take(budget)
            budget -= part.length
            appendLine("=== FILE: $path ===")
            appendLine(part)
            appendLine("=== END FILE ===")
        }
        if (left > 0) appendLine("($left smaller files were left out to save room.)")
        appendLine()
        append("Now write the preview page for this app: one complete HTML file, starting with <!DOCTYPE html> and ending with </html>.")
    }

    private const val MAX_CONTINUES = 5
    private const val SOURCE_BUDGET = 120_000
    private val START = Regex("<!DOCTYPE html|<html[\\s>]", RegexOption.IGNORE_CASE)
    private val END = Regex("</html\\s*>", RegexOption.IGNORE_CASE)

    private val SYSTEM = """
        You turn the source code of an Android app (Kotlin, Jetpack Compose or XML layouts) into ONE self-contained HTML file that
        looks and behaves like that app. Its owner opens the page on their phone to try the app BEFORE it is compiled into an APK,
        so the page must work the way the compiled app will: this is not a mock-up or a picture of the app.

        Follow the code exactly:
        - Rebuild every screen the code has, with the same layout, order, texts, colours (take them from the app's theme), sizes,
          icons (inline SVG or emoji), lists, cards, dialogs, bottom sheets, menus, tabs, bottom bars, drawers and snackbars.
        - Port the app's logic to JavaScript, function by function: state, ViewModels, validation, calculations, sorting and
          filtering, timers, navigation between screens and what the back button does. Every button, switch, slider, text field,
          checkbox, list item, tab and menu item must do exactly what its Kotlin code does. Start on the screen the app starts on.
        - Don't add features, screens, sample data or texts that the code doesn't have, and don't leave out any that it has.
          Where the code shows an empty, loading or error state, show the same one.
        - Data the app saves (DataStore, SharedPreferences, Room, files) is saved in localStorage in the same shape, so it is
          still there after the page is reloaded, like the app's data after it is reopened.
        - Internet requests the app makes: make the same requests with fetch(). If a request fails, show the app's own error state.
        - Things only a phone can do (camera, gallery and file pickers, notifications, sensors, location, vibration, alarms,
          widgets, opening other apps, sharing, background work): imitate the result as closely as a web page can (use
          <input type="file"> for pickers, navigator.geolocation for location, the Web Audio / <audio> elements for sound) and, when
          it can only be imitated, also show a short toast that starts with "Preview:" and says what the real app does at that moment.
          Show Android-style permission dialogs (Allow / Don't allow) where the app asks for a permission, and honour the answer.

        How the page must be built:
        - A phone screen: <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">, the app fills
          the whole window (100% wide, 100dvh high), no phone frame around it, no page margins, nothing else on the page.
        - Material 3 look like Compose draws it: Roboto / system sans-serif, rounded shapes, tonal surfaces, a pressed state on
          everything that can be tapped, touch targets of at least 48px, screens that scroll inside the window with top bars and
          bottom bars staying in place. If the app has light and dark colours, follow prefers-color-scheme.
        - Define window.arinBack = function () { ... } that does what Android's back button does on the current screen (close the
          dialog, sheet, menu or drawer, or go to the previous screen) and returns true; it returns false on the first screen
          when there is nothing to go back to.
        - Everything inline in the one file: no CDN scripts, no web fonts, no external stylesheets or images (only pictures the
          app itself loads from the internet). No frameworks: plain HTML, CSS and JavaScript that runs in an Android WebView.
        - Write compact code with no comments so a big app fits, but never shorten the app: all screens and all logic.

        Reply with ONLY the HTML file: start with <!DOCTYPE html> and end with </html>. No explanations before or after, no
        Markdown code fences.
    """.trimIndent()

    /** Added to every page: shows script errors instead of a silently dead screen. */
    private val HELPER = """
        <script>(function(){var n=0;function show(m){if(n++>2)return;var d=document.createElement('div');d.textContent='Preview problem: '+m;
        d.style.cssText='position:fixed;left:8px;right:8px;bottom:8px;z-index:2147483647;background:#b3261e;color:#fff;font:13px/1.35 sans-serif;padding:10px 12px;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.3)';
        d.onclick=function(){d.remove()};(document.body||document.documentElement).appendChild(d);setTimeout(function(){d.remove()},9000)}
        window.addEventListener('error',function(e){show((e&&e.message)||'a script error')});
        window.addEventListener('unhandledrejection',function(e){show(String((e&&e.reason&&e.reason.message)||(e&&e.reason)||'a failed request'))});})();</script>
    """.trimIndent()
}

/**
 * Full-screen "View app": the working preview of the open Build-mode project. [onBuildApk] is offered while the code
 * is still waiting to be compiled.
 */
@Composable
fun AppPreviewDialog(chat: ChatViewModel, onClose: () -> Unit, onBuildApk: (() -> Unit)? = null) {
    val context = LocalContext.current
    val project = BuildRunner.project
    val id = BuildRunner.projectId
    var restarts by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }

    fun remake() {
        val p = BuildRunner.project ?: return
        AppPreview.generate(context, id, p, chat.provider, chat.key(chat.provider), chat.model)
    }

    LaunchedEffect(id) {
        if (project != null) AppPreview.open(context, id, project, chat.provider, chat.key(chat.provider), chat.model)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val page = AppPreview.html.takeIf { AppPreview.projectId == id }
        // Back goes back inside the previewed app first, like on the real one; from its first screen it closes the preview.
        BackHandler {
            val w = web
            if (w == null || page == null) onClose()
            else w.evaluateJavascript("(function(){try{return !!(window.arinBack&&window.arinBack()===true)}catch(e){return false}})()") { handled ->
                if (handled != "true") onClose()
            }
        }
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close the preview") }
                    Column(Modifier.weight(1f)) {
                        Text(
                            project?.appName ?: "App preview",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when {
                                AppPreview.generating && page != null -> "Updating the preview…"
                                project?.awaitingPreview == true -> "Preview · not compiled yet"
                                else -> "Preview"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Preview options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Restart the app") },
                                enabled = page != null,
                                onClick = { menu = false; restarts++ },
                            )
                            DropdownMenuItem(
                                text = { Text("Make the preview again") },
                                enabled = project != null && !AppPreview.generating,
                                onClick = { menu = false; remake() },
                            )
                            if (AppPreview.generating && !AppPreview.fromBuild) {
                                DropdownMenuItem(text = { Text("Stop writing the preview") }, onClick = { menu = false; AppPreview.cancel() })
                            }
                        }
                    }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        project == null -> Notice(
                            "No app to view yet",
                            "Build an app, or upload a project, in Build mode first. Then View shows how it works before it's compiled.",
                        )
                        page != null -> key(page, restarts) {
                            PreviewWeb(page, id, onReady = { web = it })
                        }
                        AppPreview.generating -> Column(
                            Modifier.fillMaxSize().padding(32.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Text(AppPreview.status.ifBlank { "Working…" }, textAlign = TextAlign.Center)
                            Text(
                                if (AppPreview.fromBuild) "The build is writing the preview together with the code. It opens here as soon as it's done."
                                else "Arin is turning the app's code into a working preview. It takes a minute or two, longer for a big " +
                                    "app. You can close this screen; it keeps going.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            if (!AppPreview.fromBuild) OutlinedButton(onClick = { AppPreview.cancel() }) { Text("Stop") }
                        }
                        else -> Notice(
                            "Couldn't make the preview",
                            AppPreview.error ?: "Tap Try again to write the preview.",
                            action = "Try again" to { remake() },
                        )
                    }
                }
                // Problems and notes that shouldn't hide a page that is already showing.
                val note = when {
                    page == null -> null
                    AppPreview.error != null -> "Couldn't update the preview: ${AppPreview.error}"
                    AppPreview.outdated && !AppPreview.generating -> "The code has changed since this preview was made."
                    else -> null
                }
                if (note != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.TextButton(onClick = { remake() }) { Text("Update") }
                    }
                }
                if (project?.awaitingPreview == true && onBuildApk != null && !BuildRunner.running) {
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Works the way you want? Compile it. Otherwise close this and ask for changes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                            )
                            Button(onClick = { onClose(); onBuildApk() }) { Text("Build APK") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(title: String, text: String, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) Button(onClick = action.second) { Text(action.first) }
    }
}

/**
 * Runs the preview page: scripts and saved data on, no access to the phone's files, and each project in its own
 * web address so one app's saved data never shows up in another's. Links to other sites open in the browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewWeb(html: String, projectId: String, onReady: (WebView) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                        val url = request.url
                        if (request.isForMainFrame && (url.scheme == "http" || url.scheme == "https")) {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                            return true
                        }
                        return false
                    }
                }
                val host = "p" + projectId.filter { it.isLetterOrDigit() }.take(40).lowercase()
                loadDataWithBaseURL("https://$host.preview.arin.local/", html, "text/html", "utf-8", null)
                onReady(this)
            }
        },
        update = { },
        onRelease = { it.destroy() },
    )
}
