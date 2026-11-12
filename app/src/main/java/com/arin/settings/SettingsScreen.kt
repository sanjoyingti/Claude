package com.arin.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel
import com.arin.data.ThemeMode
import com.arin.llm.Provider

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("Replies come from") {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Provider.entries.forEach { p ->
                        FilterChip(
                            selected = vm.provider == p,
                            onClick = { vm.chooseProvider(p) },
                            label = { Text(p.label) },
                        )
                    }
                }
                Text(
                    if (vm.provider == Provider.Free) "Free models work with no key or sign-up."
                    else "Uses your ${vm.provider.label} API key. You pay the provider for what you use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("API keys") {
                Text(
                    "Stored encrypted on this phone only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Provider.entries.filter { it.needsKey }.forEach { p -> KeyRow(vm, p) }
            }

            Section("Appearance") {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { t ->
                        FilterChip(selected = vm.theme == t, onClick = { vm.chooseTheme(t) }, label = { Text(t.label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show reasoning", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "See how the model worked through an answer, when it shares that.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = vm.showThinking, onCheckedChange = vm::toggleThinking)
                }
            }

            Section("Web search") {
                Toggle(
                    "Search the web",
                    "Arin looks things up when an answer needs current facts, and lists its sources.",
                    vm.webSearch,
                    vm::toggleWebSearch,
                )
            }

            Section("Memory") {
                Toggle(
                    "Remember things about me",
                    "Arin saves lasting facts you share, such as your name or how you like answers, and uses them in new chats. Kept only on this phone.",
                    vm.memoryOn,
                    vm::toggleMemory,
                )
                if (vm.memories.isEmpty()) {
                    Text("Nothing remembered yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vm.memories.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("• " + m.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.forget(m) }) { Text("Forget") }
                    }
                }
                var adding by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = adding,
                        onValueChange = { adding = it },
                        placeholder = { Text("Add something to remember") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = adding.isNotBlank(), onClick = { vm.addMemory(adding); adding = "" }) { Text("Add") }
                }
                if (vm.memories.isNotEmpty()) {
                    TextButton(onClick = { vm.forgetAll() }) { Text("Forget everything") }
                }
            }

            Section("Notifications and voice") {
                Toggle(
                    "Notify when a reply is ready",
                    "If you leave Arin while it's writing, it keeps going and lets you know when it's done.",
                    vm.notifyOnReply,
                    vm::toggleNotify,
                )
                var rate by remember { mutableStateOf(vm.settings.voiceRate) }
                Text("Voice speed: ${"%.1f".format(rate)}×", style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.Slider(
                    value = rate,
                    onValueChange = { rate = it },
                    onValueChangeFinished = { vm.settings.voiceRate = rate },
                    valueRange = 0.6f..1.8f,
                    steps = 5,
                )
            }

            Section("Your data") {
                OutlinedButton(onClick = { confirmClear = true }) { Text("Delete all chats and projects") }
            }

            Text(
                "Arin 0.3.1",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all chats and projects?") },
            text = { Text("Every chat and project on this phone will be removed. Memory is kept; clear it above. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteAll(); confirmClear = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun KeyRow(vm: ChatViewModel, p: Provider) {
    var editing by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(vm.hasKey(p)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (saved) "Key saved" else "Get one at ${p.keyHint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { editing = !editing; value = "" }) { Text(if (saved) "Change" else "Add") }
        }
        if (editing) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("${p.label} API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = { vm.saveKey(p, value); saved = value.isNotBlank(); editing = false }) { Text("Save") }
                if (saved) TextButton(onClick = { vm.saveKey(p, ""); saved = false; editing = false }) { Text("Remove key") }
            }
        }
    }
}
