package com.arin.selffix

// ARIN-PROTECTED-RULE: ai-helper
// PROTECTED (set by the owner): the AI helper works in every tab; helpers only assist and forget; the tab's model and
// Custom API are never changed. Only the owner can change this (see PROTECTED_RULES.md).

import android.content.Context
import com.arin.builder.ChatMessage
import com.arin.builder.Role
import com.arin.builder.THOUGHT
import com.arin.builder.streamModel
import com.arin.llm.ApiFormat
import com.arin.llm.CustomApi
import com.arin.llm.ModelOption
import com.arin.llm.Provider

/**
 * The AI helper: lets ANY model (DeepSeek, ChatGPT, a Custom API, free models…) ask another AI for help on a hard
 * problem, even when that AI isn't the model picked for the tab. The helper is chosen by the person: Claude, ChatGPT,
 * Gemini, or any model of a Custom API they already saved. The model writes <ask_helper>…</ask_helper> (or
 * <ask_claude>…</ask_claude>); Arin asks the helper and gives the answer back to the model, which then carries on.
 *
 * Switched on: models can take the helper's help. Switched off: every model works on its own.
 * The tab's own model and the Custom API in use are never changed (the helper gets its own fixed address).
 *
 * The helper only assists: every question is asked fresh, and it keeps no memory of what it helped with. Claude Code
 * as helper runs in its own throwaway folder and its session is deleted afterwards, so the Claude Code tab's own
 * conversation (the only place Claude Code remembers) is never touched.
 */
object ClaudeHelper {
    val tag = Regex("""<ask_(?:claude|helper)>\s*([\s\S]{3,12000}?)\s*</ask_(?:claude|helper)>""")
    private val openTag = Regex("""<ask_(?:claude|helper)>[\s\S]*$""")

    fun question(reply: String): String? = tag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    /** The reply without the tag, for showing to the person. */
    fun clean(reply: String): String = reply.replace(tag, "").replace(openTag, "")

    /** Most consultations per answer or build step, so a loop can't run up the bill. */
    const val MAX_PER_ANSWER = 3

    /** The helper's instructions for the model (empty when the helper is off). */
    fun instructions(context: Context, tab: String = "chat"): String {
        if (!enabled(context, tab)) return "AI helper status: the person has switched the AI helper OFF (⋮ menu → AI helper). " +
            "If asked, say it is off; you work on your own.\n\n"
        val who = helper(context).label
        val how = when (lastRoute) {
            "pro" -> " through the person's Claude Code login"
            "key" -> " through the Claude key in Settings"
            "chatgpt", "gemini", "custom" -> " through its saved key"
            else -> ""
        }
        val status = if (lastChecked > 0 && lastRoute == null)
            "AI helper status: switched ON ($who), but at the last check it couldn't be reached (no key and no Claude Code " +
                "login). If asked, say so and how to fix it.\n"
        else "AI helper status: switched ON — $who is your helper$how. Arin itself consults it for you at every step: its " +
            "advice appears in your messages marked [Guidance from the AI helper] or [Solution from the AI helper]. If the " +
            "person asks whether the AI helper is on, the answer is YES. Never test it yourself with commands or tell the " +
            "person it isn't connected unless a helper answer says it couldn't be reached.\n"
        return status + "AI helper: if a problem is complex, or the same error keeps coming back, you can ask $who for help even " +
            "though you are another model. Write <ask_helper>the problem, what you tried, the exact error text and the " +
            "relevant code</ask_helper> as the LAST thing in your reply and stop; Arin asks $who and sends you its " +
            "answer, then you continue. The helper remembers nothing between questions, so include all the details " +
            "every time. Up to $MAX_PER_ANSWER times per answer.\n\n"
    }

    private const val SYSTEM =
        "You are an expert helper, called in by another AI model working inside the Arin Android app. " +
            "Solve the problem it describes: find the real cause and give a concrete, working fix (exact code, commands " +
            "or settings). Be direct and complete, no small talk. Do not tell it to change its API, key or model."

    /** Claude Code as helper works here (own home and folder), never in the Claude Code tab's /root and /root/files. */
    private const val CC_DIR = "/root/.arin/helper-run"
    private const val CC_PROMPT = "/root/.arin/claude_helper_prompt.txt"

    // ---- Which AI helps, and the on/off switch (in every tab) ----

    /**
     * The chosen helper. [kind] = "claude", "chatgpt", "gemini" or "custom" (then [apiId] names the saved Custom API).
     * A blank [model] means the provider's default.
     */
    data class Helper(val kind: String, val model: String = "", val apiId: String = "", val apiName: String = "") {
        val name: String get() = when (kind) {
            "chatgpt" -> "ChatGPT"
            "gemini" -> "Gemini"
            "custom" -> apiName.ifBlank { "Custom API" }
            else -> "Claude"
        }
        val label: String get() = name + if (model.isNotBlank()) " ($model)" else ""
    }

    private const val PREFS = "claude_helper"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * True unless the person switched the AI helper off IN THIS TAB. Each tab has its own switch: turning it off in the
     * chat tab leaves it on in Arin Update, Build, Claude Code… ([tab] = "chat", "build", "arin-update", "claude-code", …).
     */
    fun enabled(context: Context, tab: String = "chat"): Boolean =
        prefs(context).getBoolean("on_" + tabOf(tab), prefs(context).getBoolean("on", true))

    fun setEnabled(context: Context, on: Boolean, tab: String = "chat") =
        prefs(context).edit().putBoolean("on_" + tabOf(tab), on).apply()

    /** Which tab's switch a helper request belongs to. */
    fun tabOf(source: String): String = when (source) {
        "", "all" -> "chat"
        else -> source
    }

    fun helper(context: Context): Helper = prefs(context).let {
        Helper(it.getString("kind", "claude") ?: "claude", it.getString("model", "") ?: "", it.getString("api", "") ?: "", it.getString("api_name", "") ?: "")
    }

    fun setHelper(context: Context, h: Helper) = prefs(context).edit()
        .putString("kind", h.kind).putString("model", h.model).putString("api", h.apiId).putString("api_name", h.apiName).apply()

    /** The person's saved Custom APIs (name, address, format), to pick a helper from. */
    fun savedApis(context: Context): List<com.arin.data.SavedApi> = com.arin.data.Settings(context).savedApis

    /** The models a helper can use: listed live from the service when it can, else the known ones. */
    suspend fun models(context: Context, kind: String, api: com.arin.data.SavedApi? = null): List<ModelOption> {
        val s = com.arin.data.Settings(context)
        return runCatching {
            when (kind) {
                "chatgpt" -> com.arin.llm.Llm.models(Provider.ChatGPT, s.key(Provider.ChatGPT))
                "gemini" -> com.arin.llm.Llm.models(Provider.Gemini, s.key(Provider.Gemini))
                "custom" -> if (api == null) emptyList() else customModels(api, s.apiKey(api))
                else -> {
                    val signedIn = runCatching { com.arin.terminal.Linux.installed(context) && com.arin.claudecode.ClaudeCode.connected(context) }.getOrDefault(false)
                    val pro = if (signedIn) com.arin.claudecode.ClaudeCode.PRO_MODELS.map { ModelOption(it, "$it (Claude Code login)") } else emptyList()
                    val key = s.key(Provider.Claude)
                    pro + if (key.isNotBlank()) com.arin.llm.Llm.models(Provider.Claude, key) else com.arin.llm.DefaultModels.of(Provider.Claude)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun endpoint(api: com.arin.data.SavedApi) =
        CustomApi.Endpoint(api.baseUrl, runCatching { ApiFormat.valueOf(api.format) }.getOrDefault(ApiFormat.OpenAI))

    /** Lists one saved Custom API's models, without switching the app to it. */
    private suspend fun customModels(api: com.arin.data.SavedApi, key: String): List<ModelOption> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ep = endpoint(api)
            val url = when (ep.format) {
                ApiFormat.OpenAI -> "${ep.baseUrl}/models"
                ApiFormat.Anthropic -> (if (ep.baseUrl.endsWith("/v1")) ep.baseUrl else "${ep.baseUrl}/v1") + "/models"
            }
            val req = okhttp3.Request.Builder().url(url).apply {
                if (key.isNotBlank()) {
                    header("Authorization", "Bearer $key")
                    if (ep.format == ApiFormat.Anthropic) { header("x-api-key", key); header("anthropic-version", "2023-06-01") }
                }
            }.get().build()
            val listed = runCatching {
                com.arin.builder.Http.client.newCall(req).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    val arr = org.json.JSONObject(body).optJSONArray("data") ?: org.json.JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("id").ifBlank { return@mapNotNull null }
                        ModelOption(id, o.optString("display_name").ifBlank { o.optString("name").ifBlank { id } })
                    }
                }
            }.getOrDefault(emptyList())
            val saved = api.model.takeIf { it.isNotBlank() && listed.none { m -> m.id == it } }?.let { listOf(ModelOption(it, "$it (saved)")) }.orEmpty()
            saved + listed
        }

    /** Which way the chosen helper can be reached now, or null (also null when the helper is switched off). */
    suspend fun route(context: Context, tab: String? = null): String? {
        if (tab != null && !enabled(context, tab)) return null
        val s = com.arin.data.Settings(context)
        val h = helper(context)
        return when (h.kind) {
            "chatgpt" -> "chatgpt".takeIf { s.key(Provider.ChatGPT).isNotBlank() }
            "gemini" -> "gemini".takeIf { s.key(Provider.Gemini).isNotBlank() }
            "custom" -> "custom".takeIf { s.savedApis.any { it.slotId == h.apiId } }
            else -> {
                // Any Claude model works through either way: the Claude key, or the Claude Code login (which accepts full
                // model names like claude-sonnet-5-5 too). Use whichever is there — never "not connected" while one works.
                val hasKey = s.key(Provider.Claude).isNotBlank()
                val pro = runCatching {
                    com.arin.terminal.Linux.installed(context) &&
                        com.arin.claudecode.ClaudeCode.installed(context) && com.arin.claudecode.ClaudeCode.connected(context)
                }.getOrDefault(false)
                when {
                    h.model.startsWith("claude-") && hasKey -> "key"
                    pro -> "pro"
                    hasKey -> "key"
                    else -> null
                }
            }
        }.also { lastRoute = it; lastChecked = System.currentTimeMillis() }
    }

    /** What the last check found, so every model can be told truthfully whether its helper is on and working. */
    @Volatile var lastRoute: String? = null
    @Volatile var lastChecked = 0L

    /** Asks the chosen helper. Returns its answer, or a plain note saying why it couldn't (for the model to read). */
    suspend fun ask(
        context: Context,
        question: String,
        source: String = "all",
        /** Files the person uploaded: the helper can read them (pictures too). */
        files: List<com.arin.data.Attachment> = emptyList(),
        onStatus: (String) -> Unit = {},
    ): String {
        if (!enabled(context, source)) {
            Activity.log(source, "🟣 Wanted the AI helper, but it's switched off in this tab — working on alone")
            return "The AI helper is switched off (⋮ menu → AI helper). Continue on your own."
        }
        val h = helper(context)
        val way = route(context, source)
        if (way == null) {
            Activity.log(source, "🟣 Wanted ${h.label}'s help, but it isn't connected (add its key or sign in) — working on alone")
            return "${h.name} isn't connected, so it couldn't help. (Add its key in Settings, or pick another helper in " +
                "the ⋮ menu.) Continue on your own: research the internet and keep fixing."
        }
        onStatus("Asking ${h.name} for help")
        val about = question.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("You are") }.orEmpty().take(90)
        Activity.log(source, "🟣 Asking ${h.label} for help: $about")
        suspend fun via(w: String) = when (w) {
            "pro" -> viaClaudeCode(context, question, h.model, files)
            else -> viaApi(context, h, w, question, files)
        }
        val answer = runCatching { via(way) }.recoverCatching { first ->
            // The Claude key failed: try the Claude Code login, and the other way round.
            val other = when {
                h.kind != "claude" && h.kind.isNotBlank() -> throw first
                way == "key" && runCatching { com.arin.claudecode.ClaudeCode.connected(context) }.getOrDefault(false) -> "pro"
                way == "pro" && com.arin.data.Settings(context).key(Provider.Claude).isNotBlank() -> "key"
                else -> throw first
            }
            onStatus("Trying ${h.name} another way")
            via(other)
        }.getOrElse { "${h.name} couldn't be reached (${it.message?.take(200)}). Continue on your own." }
            .ifBlank { "${h.name} sent an empty answer. Continue on your own." }
        val failed = answer.contains("couldn't be reached") || answer.contains("sent an empty answer")
        Activity.log(source, if (failed) "🟣 ${h.name} couldn't help: ${answer.take(120)}"
            else "🟣 ${h.name} answered (${answer.split(Regex("\\s+")).size} words) — using its help")
        return answer
    }

    /** One fresh question, no history: the helper keeps nothing between questions. */
    private suspend fun viaApi(context: Context, h: Helper, way: String, question: String, files: List<com.arin.data.Attachment> = emptyList()): String {
        val s = com.arin.data.Settings(context)
        val (provider, key, endpoint) = when (way) {
            "chatgpt" -> Triple(Provider.ChatGPT, s.key(Provider.ChatGPT), null)
            "gemini" -> Triple(Provider.Gemini, s.key(Provider.Gemini), null)
            "custom" -> {
                val api = s.savedApis.first { it.slotId == h.apiId }
                Triple(Provider.Custom, s.apiKey(api), endpoint(api))
            }
            else -> Triple(Provider.Claude, s.key(Provider.Claude), null)
        }
        val model = h.model.takeIf { it.isNotBlank() && !(provider == Provider.Claude && !it.startsWith("claude")) }
            ?: when (provider) {
                Provider.Claude -> "claude-opus-5-5"
                Provider.Custom -> s.savedApis.first { it.slotId == h.apiId }.model
                else -> com.arin.llm.DefaultModels.of(provider).firstOrNull()?.id.orEmpty()
            }
        val sb = StringBuilder()
        // The person's files go with the question (a helper that can't see gets pictures described for it).
        val (sendFiles, extra) = if (files.isEmpty()) files to "" else VisionBridge.prepareFiles(context, provider, model, files.takeLast(12))
        streamModel(provider, key, model, listOf(ChatMessage(Role.USER, question + extra, sendFiles)), SYSTEM, endpoint)
            .collect { if (!it.startsWith(THOUGHT)) sb.append(it) }
        return sb.toString().trim()
    }

    /**
     * Claude Code (Pro login) as helper: runs once in its own folder, never with --continue, and its saved session is
     * deleted afterwards, so it remembers nothing and the Claude Code tab's conversation stays exactly as it was.
     */
    private suspend fun viaClaudeCode(context: Context, question: String, chosen: String, files: List<com.arin.data.Attachment> = emptyList()): String {
        val s = com.arin.data.Settings(context)
        val model = chosen.ifBlank { s.ccProModel.ifBlank { "opus" } }.replace("'", "")
        // The person's files, copied where Claude Code can read them (pictures too).
        val root = com.arin.terminal.Linux.rootfs(context)
        val shared = files.takeLast(12).mapNotNull { f ->
            val src = java.io.File(f.path.ifBlank { f.textPath })
            if (!src.exists()) return@mapNotNull null
            val dst = java.io.File(root, "root/.arin/helper-files/" + f.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            dst.parentFile?.mkdirs()
            runCatching { src.copyTo(dst, overwrite = true) }.getOrNull()?.let { "/root/.arin/helper-files/" + dst.name }
        }
        val text = "$SYSTEM\n\n$question" + if (shared.isNotEmpty()) "\n\nThe person's files (read them with the Read tool): " + shared.joinToString(", ") else ""
        val b64 = android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        // A throwaway home folder: Claude Code writes its sessions and history there, and it is deleted afterwards.
        val home = "$CC_DIR/home"
        val r = com.arin.terminal.Shell.run(
            context,
            "rm -rf $CC_DIR; mkdir -p $home/.claude $CC_DIR/work /root/.arin/helper-files; echo '$b64' | base64 -d > $CC_PROMPT; " +
                "echo '{\"hasCompletedOnboarding\":true}' > $home/.claude.json; cd $CC_DIR/work; " +
                "unset ANTHROPIC_API_KEY ANTHROPIC_BASE_URL ANTHROPIC_AUTH_TOKEN ANTHROPIC_MODEL; " +
                "export CLAUDE_CODE_OAUTH_TOKEN=\$(cat /root/.cc_token 2>/dev/null) DISABLE_AUTOUPDATER=1 " +
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 DISABLE_TELEMETRY=1; " +
                "HOME=$home timeout 900 claude -p --model '$model' --output-format text " +
                "--add-dir /root/.arin/helper-files --allowedTools 'Read Grep Glob WebFetch WebSearch' < $CC_PROMPT 2>&1 | tail -c 20000; " +
                "cd /root; rm -rf $CC_DIR $CC_PROMPT /root/.arin/helper-files",
            16 * 60_000L,
        )
        return r.stdout.trim().ifBlank { r.stderr.trim() }
    }
}
