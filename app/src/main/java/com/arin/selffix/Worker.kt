package com.arin.selffix

import android.content.Context
import com.arin.llm.Delta
import com.arin.llm.Turn

/**
 * ARIN-PROTECTED: worker-mode in chat — the model the person picked is the boss: it talks to the person and decides.
 * Heavy work (code, long text, long analysis) goes to the worker as a task the boss writes; the worker may ask the
 * helper; the boss inspects the result and answers. The worker sees only the boss's task, never the person's chat.
 */
object Worker {
    private val tag = Regex("(?s)<ask_worker>(.*?)</ask_worker>")
    private val openTag = Regex("(?s)<ask_worker>.*$")
    const val RESULT_TAG = "<worker_result/>"

    fun task(reply: String): String? = tag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    fun clean(reply: String): String = reply.replace(tag, "").replace(openTag, "")

    /** A plain name for a model id: "DeepSeek", "GPT-6 Luna", "Claude"… so the steps say who is working. */
    fun nice(model: String): String {
        val m = model.lowercase()
        return when {
            "luna" in m -> "GPT-6 Luna"
            "terra" in m -> "GPT Terra"
            "gpt" in m && "sol" in m -> "GPT Sol"
            "deepseek" in m -> "DeepSeek"
            "claude" in m || "sonnet" in m || "opus" in m || "haiku" in m -> "Claude"
            "gemini" in m -> "Gemini"
            "glm" in m -> "GLM"
            "qwen" in m -> "Qwen"
            "gemma" in m -> "Gemma"
            m == "local" || m == "offline" -> "Offline model"
            m.startsWith("gpt") -> "GPT"
            else -> model.substringAfterLast('/').substringBefore('@')
        }
    }

    fun on(c: Context?): Boolean = c != null && CreditSaver.on(c) && CreditSaver.workerMode(c) && CreditSaver.workers(c).isNotEmpty()

    private fun names(c: Context): String {
        val apis = com.arin.data.Settings(c).savedApis
        return CreditSaver.workers(c).joinToString(", then ") { w -> if (w.apiId == "offline") "the offline model" else w.model.ifBlank { apis.firstOrNull { it.slotId == w.apiId }?.model ?: BUILT_IN.firstOrNull { it.first == w.apiId }?.second.orEmpty() } }
    }

    /** What the boss is told in chat when worker mode is on. */
    fun instructions(c: Context): String {
        if (!on(c)) return ""
        return "WORKER MODE IS ON. The team: you (the boss, the only one who talks to the person), the worker (${names(c)}, a " +
            "different model that does the heavy work) and the AI helper (helps the worker). OWNER'S STRICT RULE: you only direct and " +
            "inspect; you NEVER write code (not even a few lines), long text, research summaries or long analysis yourself. Keep your " +
            "own replies short: think as deeply as the problem needs, but write little. For any code, " +
            "long text, long analysis or multi-step work, do NOT do it yourself — write <ask_worker>TASK: everything the worker " +
            "needs (it cannot see the person or this chat)</ask_worker> as the LAST thing in your reply and stop. Its result comes " +
            "back to you: inspect it; if it's wrong, send directions in another <ask_worker>. To show the worker's output to the " +
            "person, write $RESULT_TAG instead of copying it. Short questions and small talk you answer yourself, briefly.\n\n"
    }

    private val SYSTEM = "You are the WORKER. A supervisor model gives you tasks and checks your work. Do the task completely " +
        "and well; reply with only the result (code in full where asked), no chit-chat. When stuck, ask the helper code-only: " +
        "<ask_helper>FILE: …\nBODY:\n…\nERROR: …\nNEED: <one line></ask_helper> as the last thing; its answer comes back to you."

    private data class Target(val key: String, val model: String, val endpoint: com.arin.llm.CustomApi.Endpoint?, val w: CreditSaver.Worker,
                              val provider: com.arin.llm.Provider = com.arin.llm.Provider.Custom)

    /** The built-in services a worker (or helper) can be, besides saved Custom APIs and the offline model. */
    val BUILT_IN = listOf("claude" to "Claude (key or Claude Code login)", "chatgpt" to "ChatGPT", "gemini" to "Gemini", "free" to "Free models")

    /** How to reach one worker now (null when it isn't set up: no key, no login, API removed…). */
    data class Resolved(val provider: com.arin.llm.Provider, val key: String, val model: String, val endpoint: com.arin.llm.CustomApi.Endpoint?)

    suspend fun resolve(c: Context, w: CreditSaver.Worker): Resolved? = targetFor(c, w)?.let { Resolved(it.provider, it.key, it.model, it.endpoint) }

    private suspend fun targetFor(c: Context, w: CreditSaver.Worker): Target? {
        val settings = com.arin.data.Settings(c)
        return when (w.apiId) {
                "offline" -> {
                    val f = com.arin.llm.LocalModel.lastModel(c) ?: return null
                    if (!com.arin.llm.LocalModel.isUp() && com.arin.llm.LocalModel.start(c, f) != null) return null
                    Target(com.arin.llm.LocalModel.DUMMY_KEY, com.arin.llm.LocalModel.MODEL_ID,
                        com.arin.llm.CustomApi.Endpoint(com.arin.llm.LocalModel.BASE_URL, com.arin.llm.ApiFormat.OpenAI), w)
                }
                "claude" -> {
                    val key = settings.key(com.arin.llm.Provider.Claude)
                    val login = runCatching { com.arin.terminal.Linux.installed(c) && com.arin.claudecode.ClaudeCode.connected(c) }.getOrDefault(false) &&
                        !com.arin.claudecode.ClaudeLimit.allLimited(c)
                    val m = w.model.ifBlank { if (key.isNotBlank()) "claude-sonnet-5-5" else "sonnet" }
                    when {
                        // Claude Code login models ("sonnet", "opus", "haiku"), or no key: use the login.
                        login && (key.isBlank() || !m.startsWith("claude-")) -> Target("", m, null, w, com.arin.llm.Provider.ClaudeCode)
                        key.isNotBlank() -> Target(key, if (m.startsWith("claude-")) m else "claude-sonnet-5-5", null, w, com.arin.llm.Provider.Claude)
                        else -> null
                    }
                }
                "chatgpt", "gemini" -> {
                    val p = if (w.apiId == "chatgpt") com.arin.llm.Provider.ChatGPT else com.arin.llm.Provider.Gemini
                    val key = settings.key(p).takeIf { it.isNotBlank() } ?: return null
                    Target(key, w.model.ifBlank { com.arin.llm.DefaultModels.of(p).firstOrNull()?.id.orEmpty() }, null, w, p)
                }
                "free" -> Target("", w.model.ifBlank { com.arin.llm.DefaultModels.of(com.arin.llm.Provider.Free).firstOrNull()?.id.orEmpty() },
                    null, w, com.arin.llm.Provider.Free)
                else -> {
                    val api = settings.savedApis.firstOrNull { it.slotId == w.apiId } ?: return null
                    val fmt = runCatching { com.arin.llm.ApiFormat.valueOf(api.format) }.getOrDefault(com.arin.llm.ApiFormat.OpenAI)
                    Target(settings.apiKey(api), w.model.ifBlank { api.model }, com.arin.llm.CustomApi.Endpoint(api.baseUrl, fmt), w)
                }
            }
    }

    private suspend fun targets(c: Context): List<Target> {
        val helper = runCatching { ClaudeHelper.asWriter(c) }.getOrNull()?.let { r ->
            Target(r.key, r.model, r.endpoint, CreditSaver.Worker("helper", r.model), r.provider)
        }
        // The helper is the last one to try, so the boss never has to do the heavy work itself.
        return CreditSaver.workers(c).mapNotNull { w -> targetFor(c, w) }
            .sortedBy { if (CreditSaver.refused(c, it.w, "chat")) 1 else 0 } + listOfNotNull(helper)
    }

    /** Runs [task] on the first worker that accepts it (asking the helper when the worker wants). "" if none could. */
    suspend fun run(c: Context, task: String, boss: String = "your model", system: String = SYSTEM, onStatus: (who: String, text: String) -> Unit = { _, _ -> }): Pair<String, String> {
        val helper = ClaudeHelper.helper(c).name
        for (t in targets(c)) {
            val me = nice(t.model)
            onStatus(me, "$me is following $boss's instructions")
            val turns = mutableListOf(Turn(true, task))
            var asks = 0
            while (true) {
                val sb = StringBuilder()
                val ok = runCatching {
                    com.arin.llm.Llm.stream(t.provider, t.key, t.model, system, turns.toList(), endpoint = t.endpoint, conv = "worker")
                        .collect { d -> if (d is Delta.Text) sb.append(d.text) }
                }.isSuccess
                val text = sb.toString()
                if (!ok || CreditSaver.isRefusal(text)) {
                    if (ok) CreditSaver.noteRefusal(c, t.w, "chat")
                    onStatus(me, "$me " + (if (ok) "refused" else "couldn't do it") + " — passing $boss's task to the next worker")
                    break
                }
                val q = ClaudeHelper.question(text)
                if (q != null && asks < 3) {
                    asks++
                    onStatus(helper, "$me asked $helper for help — $helper is helping $me")
                    val ans = ClaudeHelper.ask(c, q, source = "chat") { }
                    onStatus(me, "$helper helped; $me is continuing $boss's task")
                    turns += Turn(false, text)
                    turns += Turn(true, "The helper's answer:\n$ans\n\nNow finish the task.")
                    continue
                }
                onStatus(boss, "$me finished; $boss is inspecting $me's work")
                return t.model to ClaudeHelper.clean(text).trim()
            }
        }
        return "" to ""
    }
}
