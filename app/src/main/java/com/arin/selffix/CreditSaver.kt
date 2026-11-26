package com.arin.selffix

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * ARIN-PROTECTED: credit-saver — the person's two modes (only they switch them):
 *  1 = their model + Claude: Claude researches and fixes errors (code-only), their model applies the fix.
 *  2 = their model + an offline model + Claude: as 1, and the offline model (any they installed) filters web pages and
 *      writes titles on the phone, for free.
 * Their model always stays the boss, at full power: only waste is cut (no whole-project resends, no repeated research,
 * no repeated questions, errors cut to their cause, small edits instead of whole files).
 */
object CreditSaver {
    private fun prefs(c: Context) = c.getSharedPreferences("credit_saver", Context.MODE_PRIVATE)

    /** 0 = off, 1 = model + Claude, 2 = model + offline model + Claude. */
    fun mode(c: Context): Int = prefs(c).getInt("mode", 0)
    fun setMode(c: Context, m: Int) = prefs(c).edit().putInt("mode", m).apply()
    fun on(c: Context?): Boolean = c != null && mode(c) > 0

    /** The offline model the person chose for mode 2 (a .gguf file in Arin's Linux), or "" for none. */
    fun offlineModel(c: Context): String = prefs(c).getString("offline", "").orEmpty()
    fun setOfflineModel(c: Context, path: String) = prefs(c).edit().putString("offline", path).apply()

    /** Helper tries Claude Haiku first for an error, then the chosen Claude model (off by default: full power). */
    fun haikuFirst(c: Context): Boolean = prefs(c).getBoolean("haiku_first", false)
    fun setHaikuFirst(c: Context, on: Boolean) = prefs(c).edit().putBoolean("haiku_first", on).apply()

    /** Daily warning, in thousands of tokens, for the person's own model (0 = off). Only warns, never stops. */
    fun dailyBudgetK(c: Context): Int = prefs(c).getInt("daily_k", 0)
    fun setDailyBudgetK(c: Context, k: Int) = prefs(c).edit().putInt("daily_k", k).apply()

    // ---- tokens used (rough: characters / 4), per day, for the meter and the daily warning ----

    fun addUsage(c: Context, chars: Int): Long {
        val day = java.time.LocalDate.now().toString()
        val p = prefs(c)
        val used = (if (p.getString("day", "") == day) p.getLong("used", 0) else 0L) + chars / 4
        p.edit().putString("day", day).putLong("used", used).apply()
        return used
    }
    fun usedToday(c: Context): Long = prefs(c).let { if (it.getString("day", "") == java.time.LocalDate.now().toString()) it.getLong("used", 0) else 0 }

    // ---- errors cut to their cause ----

    private val errLine = Regex("(?i)(^e: |error:|^\\s*\\* what went wrong|unresolved reference|cannot find symbol|exception|caused by|failed|could not|type mismatch|expected|no value passed)")

    /** The lines that matter in a build/compile log (first real errors and a little context), not the noise. */
    fun trimErrors(log: String, maxLines: Int = 40): String {
        val lines = log.lines()
        val keep = sortedSetOf<Int>()
        lines.forEachIndexed { i, l -> if (errLine.containsMatchIn(l)) { (maxOf(0, i - 1)..minOf(lines.lastIndex, i + 2)).forEach { keep += it } } }
        if (keep.isEmpty()) return log.takeLast(3_000)
        return keep.take(maxLines * 2).map { lines[it] }.distinct().take(maxLines).joinToString("\n").take(6_000)
    }

    /** One error's "fingerprint": the first real error line without paths, line numbers or ids. */
    fun signature(errors: String): String = trimErrors(errors, 6).lines().firstOrNull { errLine.containsMatchIn(it) }.orEmpty()
        .replace(Regex("file:///\\S+"), "").replace(Regex("\\(\\d+:\\d+\\)|:\\d+(:\\d+)?"), "").replace(Regex("\\s+"), " ").trim().take(300)

    // ---- remembered fixes: the same error again → the fix that worked, with no AI ----

    private fun memFile(c: Context) = File(c.filesDir, "fix-memory.json")

    fun rememberedFix(c: Context, sig: String): String? = runCatching {
        if (sig.isBlank()) null else JSONObject(memFile(c).readText()).optString(sig).ifBlank { null }
    }.getOrNull()

    fun rememberFix(c: Context, sig: String, fix: String) {
        if (sig.isBlank() || fix.isBlank()) return
        runCatching {
            val o = runCatching { JSONObject(memFile(c).readText()) }.getOrElse { JSONObject() }
            o.put(sig, fix.take(8_000))
            // Keep the newest 300.
            if (o.length() > 300) o.remove(o.keys().next())
            memFile(c).writeText(o.toString())
        }
    }

    fun forgetFix(c: Context, sig: String) = runCatching {
        val o = JSONObject(memFile(c).readText()); o.remove(sig); memFile(c).writeText(o.toString())
    }

    // ---- research once: the same search isn't done twice ----

    private val researched = ConcurrentHashMap<String, String>()
    fun cachedResearch(q: String): String? = researched[q.trim().lowercase()]
    fun cacheResearch(q: String, r: String) { if (r.isNotBlank()) researched[q.trim().lowercase()] = r }

    // ---- the offline model (mode 2): tiny, strict jobs, checked; plain rules when it's slow or wrong ----

    /** Asks the chosen offline model one short thing. Null when it isn't set up, is slow (20 s) or fails. */
    suspend fun offline(c: Context, prompt: String, maxTokens: Int = 60): String? = withContext(Dispatchers.IO) {
        if (mode(c) != 2) return@withContext null
        val path = offlineModel(c).ifBlank { return@withContext null }
        val file = File(com.arin.terminal.Linux.rootfs(c), path.removePrefix("/"))
        if (!file.exists()) return@withContext null
        // Loading a model takes about its file size in memory: only when the phone has that much free (plus 1.5 GB),
        // otherwise the plain rules do the job — Android would close Arin for using too much memory.
        val loaded = com.arin.llm.LocalModel.isUp() && com.arin.llm.LocalModel.lastModel(c)?.name == file.name
        if (!loaded) {
            val mi = android.app.ActivityManager.MemoryInfo()
            (c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
            if (mi.availMem < file.length() + 1_500_000_000L) return@withContext null
        }
        withTimeoutOrNull(25_000) {
            if (!com.arin.llm.LocalModel.isUp() || com.arin.llm.LocalModel.lastModel(c)?.name != file.name) {
                if (com.arin.llm.LocalModel.start(c, file, 4096) != null) return@withTimeoutOrNull null
            }
            runCatching {
                val body = JSONObject().put("model", "local").put("max_tokens", maxTokens).put("temperature", 0)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                val con = java.net.URL("http://127.0.0.1:${com.arin.llm.LocalModel.PORT}/v1/chat/completions").openConnection() as java.net.HttpURLConnection
                con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 5_000; con.readTimeout = 20_000
                con.setRequestProperty("Content-Type", "application/json")
                con.outputStream.use { it.write(body.toString().toByteArray()) }
                JSONObject(con.inputStream.bufferedReader().readText()).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content").trim()
            }.getOrNull()
        }
    }

    /**
     * Shortens web text to the paragraphs that matter for [topic]: plain rules first (free, exact), then in mode 2 the
     * offline model only picks paragraph NUMBERS — the chosen paragraphs are copied word for word, never rewritten.
     */
    suspend fun shorten(c: Context, topic: String, text: String, keep: Int = 4): String {
        val paras = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.length > 60 }.distinct()
        if (paras.size <= keep) return text
        val words = topic.lowercase().split(Regex("[^a-z0-9_.]+")).filter { it.length > 3 }.toSet()
        val scored = paras.mapIndexed { i, p -> i to words.count { p.lowercase().contains(it) } }.sortedByDescending { it.second }
        val candidates = scored.take(10).map { it.first }.sorted()
        val picked = offline(c, "Topic: ${topic.take(300)}\n\n" + candidates.joinToString("\n\n") { "[$it] " + paras[it].take(400) } +
            "\n\nReply ONLY with the numbers of the $keep paragraphs most useful for the topic, like: 3, 7, 12")
            ?.let { r -> Regex("\\d+").findAll(r).map { it.value.toInt() }.filter { it in candidates }.distinct().take(keep).toList() }
            ?.takeIf { it.isNotEmpty() }
            ?: scored.take(keep).map { it.first }.sorted()
        return picked.joinToString("\n\n") { paras[it] }
    }

    /** A short title (mode 2: by the offline model; otherwise from the first words). Never uses the person's paid model. */
    suspend fun title(c: Context, firstMessage: String): String {
        val plain = firstMessage.lines().firstOrNull { it.isNotBlank() }.orEmpty().split(Regex("\\s+")).take(6).joinToString(" ").take(48)
        val t = offline(c, "Give a 3 to 6 word title for this message. Reply with the title only.\n\n${firstMessage.take(600)}", 16)
            ?.lines()?.firstOrNull()?.trim('"', '*', ' ', '#')?.take(60)
        return t?.takeIf { it.length in 3..60 && !it.contains('\n') } ?: plain.ifBlank { "New chat" }
    }
}

/** Settings → Credit saver: the two modes (only you switch), any offline model for mode 2, and the options. */
@androidx.compose.runtime.Composable
fun CreditSaverSection(vm: com.arin.chat.ChatViewModel) {
    val c = androidx.compose.ui.platform.LocalContext.current
    var mode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(CreditSaver.mode(c)) }
    var offline by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CreditSaver.offlineModel(c)) }
    var haiku by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CreditSaver.haikuFirst(c)) }
    var budget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CreditSaver.dailyBudgetK(c).takeIf { it > 0 }?.toString().orEmpty()) }
    val models = androidx.compose.runtime.remember { com.arin.llm.LocalModel.downloaded(c) }
    val small = androidx.compose.material3.MaterialTheme.typography.bodySmall
    val dim = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    val model = vm.modelLabel()
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Text("Your model ($model) stays the boss at full power. Only waste is cut. The helper " +
            "never sees your chat or files — only your model's code-only asks.", style = small, color = dim)
        listOf(
            0 to "Off",
            1 to "1 · $model + Claude",
            2 to "2 · $model + offline model + Claude",
        ).forEach { (m, label) ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = mode == m, onClick = { mode = m; CreditSaver.setMode(c, m) })
                androidx.compose.material3.Text(label)
            }
        }
        if (mode == 2) {
            androidx.compose.material3.Text("Offline model (filters web pages and writes titles on your phone, free):", style = small)
            if (models.isEmpty()) androidx.compose.material3.Text("No offline models yet — download one in Settings → Offline models.", style = small, color = dim)
            models.forEach { f ->
                val path = com.arin.llm.LocalModel.linuxPath(c, f)
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = offline == path, onClick = { offline = path; CreditSaver.setOfflineModel(c, path) })
                    androidx.compose.material3.Text(f.nameWithoutExtension, style = small)
                }
            }
        }
        if (mode > 0) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Text("Helper tries Claude Haiku first (saves Claude's limit; off = full power)", style = small, modifier = androidx.compose.ui.Modifier.weight(1f))
                androidx.compose.material3.Switch(haiku, { haiku = it; CreditSaver.setHaikuFirst(c, it) })
            }
            androidx.compose.material3.OutlinedTextField(budget, { v -> budget = v.filter(Char::isDigit).take(6); CreditSaver.setDailyBudgetK(c, budget.toIntOrNull() ?: 0) },
                label = { androidx.compose.material3.Text("Daily warning, thousand tokens (blank = off)") }, singleLine = true)
            androidx.compose.material3.Text("Used today by your model: about ${"%,d".format(CreditSaver.usedToday(c))} tokens. Unlimited tries " +
                "pause only when the same error fails 5 times in a row.", style = small, color = dim)
        }
    }
}
