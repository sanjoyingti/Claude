package com.arin.security

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Security workstation: APK analysis, Web/API check and Network check. All checks are Arin's own code on the phone (no AI
 * credit). Scanning someone else's site or device needs the owner's confirmation that they're allowed to; every target
 * checked is written to a scope log.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var report by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var allowed by rememberSaveable { mutableStateOf(false) }

    fun run(what: String, scopeTarget: String?, work: suspend ((String) -> Unit) -> String) {
        if (busy) return
        busy = true; report = ""; step = "Starting…"
        scopeTarget?.let { ScopeLog.add(ctx, what, it) }
        scope.launch {
            report = runCatching { withContext(Dispatchers.IO) { work { s -> step = s } } }.getOrElse { "⚠️ ${it.message}" }
            ScopeLog.saveReport(ctx, what, report)
            busy = false; step = ""
        }
    }

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        run("APK analysis", null) { onStep ->
            onStep("Copying the APK")
            val f = java.io.File(ctx.cacheDir, "audit.apk")
            ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            ApkAudit.analyse(ctx, f, onStep)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Security", style = MaterialTheme.typography.titleLarge)
        }
        TabRow(selectedTabIndex = tab) {
            listOf("APK", "Web / API", "Network").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i; report = "" }, text = { Text(t) }) }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val small = MaterialTheme.typography.bodySmall
            when (tab) {
                0 -> {
                    Text("Check an APK for security problems: permissions, components open to other apps, signing, debug flags, " +
                        "plain-HTTP, WebView risks, storage, libraries and secrets left inside (shown redacted). Runs on the phone; nothing is uploaded.", style = small)
                    Button(onClick = { pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) }, enabled = !busy) { Text("Choose an APK") }
                }
                1 -> {
                    Text("Passive check of a website or API: security headers, cookies, CORS, HTTPS redirect, TLS certificate and " +
                        "API descriptions. Only normal requests — no attacks, no password guessing.", style = small)
                    OutlinedTextField(target, { target = it.trim() }, label = { Text("Website or API address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Permission(allowed) { allowed = it }
                    Button(onClick = { run("Web check", target) { WebAudit.analyse(target, it) } }, enabled = !busy && allowed && target.isNotBlank()) { Text("Check it") }
                }
                else -> {
                    Text("Check this Wi-Fi network, the devices on it, your router, or the services open on a host you choose. " +
                        "Ordinary connections only (no attacks). A phone has no root, so there is no Wi-Fi monitor mode or packet capture.", style = small)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { run("Network info", null) { NetAudit.networkInfo(ctx) } }, enabled = !busy) { Text("This network") }
                        OutlinedButton(onClick = { run("Router check", NetAudit.gateway(ctx) ?: "router") { s -> NetAudit.routerCheck(ctx, s) } }, enabled = !busy) { Text("Check my router") }
                        OutlinedButton(onClick = {
                            run("Device discovery", (NetAudit.localRange(ctx) ?: "?") + ".0/24") { s ->
                                val hosts = NetAudit.discover(ctx, s)
                                "# Devices on this network (${hosts.size})\n_Found by ordinary connections to common ports._\n\n" +
                                    hosts.joinToString("\n") { h -> "- ${h.ip}" + if (h.open.isNotEmpty()) " — open: " + h.open.joinToString { p -> "$p ${NetAudit.PORTS[p]?.first ?: ""}".trim() } else " — answers, no common ports open" } +
                                    "\n\n---\n_Next step: type an address below and tap \"Scan host\" to look closer at one device._"
                            }
                        }, enabled = !busy) { Text("Find devices") }
                    }
                    OutlinedTextField(target, { target = it.trim() }, label = { Text("Host to scan (IP or name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Permission(allowed) { allowed = it }
                    Button(onClick = { run("Host scan", target) { s -> NetAudit.scanHost(target, onStep = s) } }, enabled = !busy && allowed && target.isNotBlank()) { Text("Scan host") }
                }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(step, style = small) }
            if (report.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Security report", report))
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, report), "Share report").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Share") }
                    TextButton(onClick = {
                        // The owner taps this: a new chat gets the report and is asked to explain and fix (it asks before building).
                        vm.newChat()
                        vm.send("Here is a security report. Explain it in plain English, then tell me how to fix each problem. " +
                            "If it's about my own app or site, find the affected files (and every file that uses them), propose the fixes, " +
                            "and ask me before building.\n\n" + report.take(14_000))
                        vm.openChat = true
                    }) { Text("Ask Arin to fix") }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                    SelectionContainer { Text(report, style = small, modifier = Modifier.padding(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Permission(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text("I own this, or I have the owner's permission to test it.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Every target checked and every report, kept on the phone (evidence and scope record). */
object ScopeLog {
    private fun dir(c: android.content.Context) = java.io.File(c.filesDir, "security").apply { mkdirs() }
    fun add(c: android.content.Context, what: String, target: String) = runCatching {
        java.io.File(dir(c), "scope.log").appendText("${java.util.Date()}\t$what\t$target\tconfirmed: owner or permission\n")
    }
    fun saveReport(c: android.content.Context, what: String, report: String) = runCatching {
        java.io.File(dir(c), "report-${System.currentTimeMillis()}-${what.replace(' ', '-')}.md").writeText(report)
    }
}
