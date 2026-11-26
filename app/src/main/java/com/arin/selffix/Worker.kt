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

    fun on(c: Context?): Boolean = c != null && CreditSaver.on(c) && CreditSaver.workerMode(c) && CreditSaver.workers(c).isNotEmpty()

    private fun names(c: Context): String {
        val apis = com.arin.data.Settings(c).savedApis
        return CreditSaver.workers(c).joinToString(", then ") { w -> if (w.apiId == "offline") "the offline model" else w.model.ifBlank { apis.firstOrNull { it.slotId == w.apiId }?.model.orEmpty() } }
    }

    /** What the boss is told in chat when worker mode is on. */
    fun instructions(c: Context): String {
        if (!on(c)) return ""
        return "WORKER MODE IS ON. The team: you (the boss, the only one who talks to the person), the worker (${names(c)}, a " +
            "different model that does the heavy work) and the AI helper (helps the worker). Save your own tokens: for any code, " +
            "long text, long analysis or multi-step work, do NOT do it yourself — write <ask_worker>TASK: everything the worker " +
            "needs (it cannot see the person or this chat)</ask_worker> as the LAST thing in your reply and stop. Its result comes " +
            "back to you: inspect it; if it's wrong, send directions in another <ask_worker>. To show the worker's output to the " +
            "person, write $RESULT_TAG instead of copying it. Short questions and small talk you answer yourself, briefly.\n\n"
    }

    private val SYSTEM = "You are the WORKER. A supervisor model gives you tasks and checks your work. Do the task completely " +
        "and well; reply with only the result (code in full where asked), no chit-chat. When stuck, ask the helper code-only: " +
        "<ask_helper>FILE: …\nBODY:\n…\nERROR: …\nNEED: <one line></ask_helper> as the last thing; its answer comes back to you."

    private data class Target(val key: String, val model: String, val endpoint: com.arin.llm.CustomApi.Endpoint, val w: CreditSaver.Worker)

    private suspend fun targets(c: Context): List<Target> {
        val settings = com.arin.data.Settings(c)
        return CreditSaver.workers(c).mapNotNull { w ->
            if (w.apiId == "offline") {
                val f = com.arin.llm.LocalModel.lastModel(c) ?: return@mapNotNull null
                if (!com.arin.llm.LocalModel.isUp() && com.arin.llm.LocalModel.start(c, f) != null) return@mapNotNull null
                Target(com.arin.llm.LocalModel.DUMMY_KEY, com.arin.llm.LocalModel.MODEL_ID,
                    com.arin.llm.CustomApi.Endpoint(com.arin.llm.LocalModel.BASE_URL, com.arin.llm.ApiFormat.OpenAI), w)
            } else {
                val api = settings.savedApis.firstOrNull { it.slotId == w.apiId } ?: return@mapNotNull null
                val fmt = runCatching { com.arin.llm.ApiFormat.valueOf(api.format) }.getOrDefault(com.arin.llm.ApiFormat.OpenAI)
                Target(settings.apiKey(api), w.model.ifBlank { api.model }, com.arin.llm.CustomApi.Endpoint(api.baseUrl, fmt), w)
            }
        }.sortedBy { if (CreditSaver.refused(c, it.w, "chat")) 1 else 0 }
    }

    /** Runs [task] on the first worker that accepts it (asking the helper when the worker wants). "" if none could. */
    suspend fun run(c: Context, task: String, onStatus: (String) -> Unit = {}): Pair<String, String> {
        for (t in targets(c)) {
            onStatus("Worker ${t.model} is working on it")
            val turns = mutableListOf(Turn(true, task))
            var asks = 0
            while (true) {
                val sb = StringBuilder()
                val ok = runCatching {
                    com.arin.llm.Llm.stream(com.arin.llm.Provider.Custom, t.key, t.model, SYSTEM, turns.toList(), endpoint = t.endpoint)
                        .collect { d -> if (d is Delta.Text) sb.append(d.text) }
                }.isSuccess
                val text = sb.toString()
                if (!ok || CreditSaver.isRefusal(text)) {
                    if (ok) CreditSaver.noteRefusal(c, t.w, "chat")
                    onStatus("Worker ${t.model} " + (if (ok) "refused" else "failed") + " — trying the next one")
                    break
                }
                val q = ClaudeHelper.question(text)
                if (q != null && asks < 3) {
                    asks++
                    onStatus("Worker ${t.model} is asking the helper")
                    val ans = ClaudeHelper.ask(c, q, source = "chat") { onStatus(it) }
                    turns += Turn(false, text)
                    turns += Turn(true, "The helper's answer:\n$ans\n\nNow finish the task.")
                    continue
                }
                return t.model to ClaudeHelper.clean(text).trim()
            }
        }
        return "" to ""
    }
}
