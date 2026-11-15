package com.arin.builder

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arin.data.Settings
import com.arin.llm.Llm
import com.arin.llm.ModelOption
import com.arin.llm.Provider

/**
 * The API (provider and model) each Build project uses. A project keeps the one it was first built with, so two
 * projects can build at the same time on different APIs; it can be changed per project.
 */
object ProjectApi {
    private fun prefs(c: Context) = c.getSharedPreferences("project_api", Context.MODE_PRIVATE)

    fun get(context: Context, id: String): Pair<Provider, String>? {
        val raw = prefs(context).getString(id, null) ?: return null
        val provider = runCatching { Provider.valueOf(raw.substringBefore('\u0000')) }.getOrNull() ?: return null
        val model = raw.substringAfter('\u0000', "")
        return if (model.isBlank()) null else provider to model
    }

    fun set(context: Context, id: String, provider: Provider, model: String) {
        prefs(context).edit().putString(id, provider.name + "\u0000" + model.trim()).apply()
    }

    /**
     * [request] with the project's own API. A project that has none yet keeps the request's and remembers it.
     * If the saved provider has no key any more, the request's API is used for this build.
     */
    fun apply(context: Context, id: String, request: BuildRequest): BuildRequest {
        if (request.selfUpdate) return request
        val saved = get(context, id)
        if (saved == null) {
            set(context, id, request.provider, request.model)
            return request
        }
        val (provider, model) = saved
        val key = Settings(context).key(provider)
        if (provider.needsKey && key.isBlank()) return request
        return request.copy(provider = provider, model = model, apiKey = key)
    }

    fun label(context: Context, id: String): String? = get(context, id)?.let { (p, m) -> "${p.label} · $m" }
}

/** Picks the API (provider + model) for one project. */
@Composable
fun ProjectApiDialog(projectId: String, projectName: String, fallback: Pair<Provider, String>, onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val start = remember { ProjectApi.get(context, projectId) ?: fallback }
    var provider by remember { mutableStateOf(start.first) }
    var model by remember { mutableStateOf(start.second) }
    var options by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    val usable = Provider.entries.filter { !it.needsKey || settings.key(it).isNotBlank() }

    LaunchedEffect(provider) {
        options = runCatching { Llm.models(provider, settings.key(provider)) }.getOrDefault(emptyList())
        if (provider != start.first && options.none { it.id == model }) model = options.firstOrNull()?.id ?: ""
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("API for $projectName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "This project builds with this API, whatever model the chat uses. Other projects can use a different one, " +
                        "even while they build at the same time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                usable.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { provider = p },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = provider == p, onClick = { provider = p })
                        Text(p.label)
                    }
                }
                if (usable.size < Provider.entries.size) {
                    Text(
                        "Other providers show here once their key is added in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    options.take(40).forEach { o ->
                        Text(
                            o.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (o.id == model) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable { model = o.id }.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = model.isNotBlank(),
                onClick = { ProjectApi.set(context, projectId, provider, model); onClose() },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
