package com.arin.claudecode

import android.content.Context
import com.arin.llm.Delta
import com.arin.llm.LlmException
import com.arin.llm.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ARIN-PROTECTED: claude-harness — the Claude Code harness for every tab, used the way the Claude app uses your limit.
 *
 * One Claude Code process stays running per conversation (stream-json in and out, through a pipe), so each new message
 * sends only what is new and the rest comes from the prompt cache — instead of starting Claude Code again for every
 * step and resending the whole chat. Claude Code's own tools (Bash, Read, Edit, Write, Glob, Grep, web) and Arin's
 * tools (through ArinMcp) are both available. Long tool output is cut short, and Claude Code compacts long chats itself.
 * Real plan usage (the same 5-hour and weekly numbers as the Claude app) comes from Claude Code's rate-limit events.
 */
object Harness {
    private const val BASE = "/root/.arin/h"
    private const val MAX_LIVE = 3
    private const val TOOLS = "Bash,Read,Edit,Write,Glob,Grep,WebFetch,WebSearch,TodoWrite"

    /** Arin's tools + the person's MCP servers (config JSON, server names) and GitHub token; set by the app. */
    @Volatile var extras: (() -> Triple<String, List<String>, String>)? = null

    private class Session(val dir: String, val key: String, val model: String) {
        var sessionId: String? = null
        val sentUser = mutableListOf<Int>()   // what this session already has: the person's / tool turns, in order
        var lastUse = System.currentTimeMillis()
        var readFrom = 0L
        val lock = Mutex()
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    private fun ctx(): Context = com.arin.ArinApp.instance
    private fun root(): File = com.arin.terminal.Linux.rootfs(ctx())
    private fun f(path: String) = File(root(), path.removePrefix("/"))

    private fun turnHash(t: Turn) = (t.text.hashCode() * 31 + t.images.size) * 31 + t.pdfs.size

    /**
     * A reply through the harness. [purpose] keeps different users apart ("chat", "helper:build"…); the conversation is
     * recognised by its first message, so the next step of the same conversation reuses the running session.
     */
    fun stream(model: String, system: String, turns: List<Turn>, purpose: String = "chat", tools: String = TOOLS): Flow<Delta> = channelFlow {
        val context = ctx()
        if (!com.arin.terminal.Linux.installed(context)) throw LlmException("The Claude Code login needs Arin's Linux: Settings → Linux → Install Linux.")
        if (!ClaudeCode.connected(context)) throw LlmException("Sign in to Claude in the Claude Code tab first, or pick another AI from the model menu.")
        val m = model.ifBlank { "sonnet" }
        val firstUser = turns.firstOrNull { it.fromUser } ?: throw LlmException("Nothing to answer.")
        val key = "$purpose|$m|${firstUser.text.take(4000).hashCode()}"
        val userTurns = turns.filter { it.fromUser }
        val hashes = userTurns.map(::turnHash)

        var s = sessions[key]
        // Only the turns this session hasn't seen yet are sent; if the conversation changed underneath, start fresh.
        val fresh = s == null || s.sentUser.size >= hashes.size || hashes.subList(0, s.sentUser.size) != s.sentUser
        if (fresh) {
            s?.let { stop(it) }
            s = Session("$BASE/${System.currentTimeMillis()}${(100..999).random()}", key, m)
            sessions[key] = s
        }
        val session = s!!
        session.lock.withLock {
            session.lastUse = System.currentTimeMillis()
            val newTurns = if (fresh) turns else userTurns.drop(session.sentUser.size)
            val content = JSONArray()
            val text = if (fresh && turns.size > 1) buildString {
                append("THE CONVERSATION SO FAR (you are the assistant; reply to the last message):\n\n")
                turns.dropLast(1).forEach { t -> append(if (t.fromUser) "USER:\n" else "ASSISTANT:\n").append(cap(t.text)).append("\n\n") }
                append("LATEST MESSAGE:\n").append(cap(turns.last().text))
            } else newTurns.joinToString("\n\n") { cap(it.text) }
            newTurns.forEach { t ->
                t.images.forEach { img ->
                    content.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64")
                        .put("media_type", img.mime.ifBlank { "image/png" }).put("data", img.base64)))
                }
                t.pdfs.forEach { p ->
                    content.put(JSONObject().put("type", "document").put("source", JSONObject().put("type", "base64")
                        .put("media_type", "application/pdf").put("data", p.base64)))
                }
            }
            content.put(JSONObject().put("type", "text").put("text", text.ifBlank { "Continue." }))
            val line = JSONObject().put("type", "user").put("message", JSONObject().put("role", "user").put("content", content)).toString() + "\n"

            if (!alive(session)) start(session, system, tools)
            val out = f("${session.dir}/out.jsonl")
            session.readFrom = withContext(Dispatchers.IO) { if (out.exists()) out.length() else 0L }
            send(session, line)
            session.sentUser.clear(); session.sentUser.addAll(hashes)

            // Read this turn's events until its result.
            var sentText = false
            var newBlock = false
            var finished = false
            var waited = 0L
            try {
                while (!finished) {
                    val lines = withContext(Dispatchers.IO) { readNew(session, out) }
                    for (l in lines) {
                        val o = runCatching { JSONObject(l) }.getOrNull() ?: continue
                        when (o.optString("type")) {
                            "system" -> o.optString("session_id").takeIf { it.isNotBlank() }?.let { session.sessionId = it }
                            "stream_event" -> {
                                val ev = o.optJSONObject("event") ?: continue
                                when (ev.optString("type")) {
                                    "message_start" -> if (sentText) newBlock = true
                                    "content_block_delta" -> {
                                        val d = ev.optJSONObject("delta") ?: continue
                                        when (d.optString("type")) {
                                            "text_delta" -> d.optString("text").takeIf { it.isNotEmpty() }?.let {
                                                if (newBlock) { send(Delta.Text("\n\n")); newBlock = false }
                                                sentText = true; send(Delta.Text(it))
                                            }
                                            "thinking_delta" -> d.optString("thinking").takeIf { it.isNotEmpty() }?.let { send(Delta.Thinking(it)) }
                                        }
                                    }
                                }
                            }
                            "assistant" -> {
                                // Tool steps show in the reasoning, so the person sees what Claude is doing.
                                val c = o.optJSONObject("message")?.optJSONArray("content") ?: continue
                                for (i in 0 until c.length()) {
                                    val b = c.optJSONObject(i) ?: continue
                                    if (b.optString("type") == "tool_use") send(Delta.Thinking("\n▸ " + describe(b) + "\n"))
                                }
                            }
                            "rate_limit_event" -> UsageMeter.fromRateLimit(o.optJSONObject("rate_limit_info"))
                            "result" -> {
                                finished = true
                                o.optString("session_id").takeIf { it.isNotBlank() }?.let { session.sessionId = it }
                                UsageMeter.fromResult(o)
                                if (o.optBoolean("is_error") && !sentText) {
                                    throw LlmException("Claude: " + o.optString("result").ifBlank { o.optString("subtype") }.take(600))
                                }
                                if (!sentText) o.optString("result").takeIf { it.isNotBlank() }?.let { sentText = true; send(Delta.Text(it)) }
                            }
                            "arin_end" -> if (!finished) {
                                val tail = withContext(Dispatchers.IO) { runCatching { out.readText().takeLast(800) }.getOrDefault("") }
                                throw LlmException("Claude Code stopped: " + tail.lineSequence().filter { !it.startsWith("{") }.joinToString(" ").take(500).ifBlank { "no answer" })
                            }
                        }
                    }
                    if (!finished) {
                        delay(150); waited += 150
                        if (waited > 40 * 60_000L) throw LlmException("Claude took too long to answer.")
                    }
                }
            } catch (e: Throwable) {
                // Stopped or failed mid-turn: end the process (the session resumes next time) so it doesn't keep working.
                if (!finished) withContext(NonCancellable) { stop(session, keepSession = true) }
                throw e
            }
            session.lastUse = System.currentTimeMillis()
        }
        trim()
    }

    private fun cap(t: String): String = if (t.length <= 24_000) t else t.take(16_000) + "\n…(cut: ${t.length - 22_000} characters)…\n" + t.takeLast(6_000)

    private fun describe(b: JSONObject): String {
        val name = b.optString("name").removePrefix("mcp__arin__").removePrefix("mcp__")
        val inp = b.optJSONObject("input") ?: JSONObject()
        val what = inp.optString("command").ifBlank { inp.optString("description") }.ifBlank { inp.optString("file_path") }
            .ifBlank { inp.optString("pattern") }.ifBlank { inp.optString("url") }.ifBlank { inp.optString("query") }
        return (name + if (what.isNotBlank()) ": " + what.take(140) else "")
    }

    private fun readNew(s: Session, out: File): List<String> {
        if (!out.exists() || out.length() <= s.readFrom) return emptyList()
        java.io.RandomAccessFile(out, "r").use { r ->
            r.seek(s.readFrom)
            val bytes = ByteArray((r.length() - s.readFrom).toInt()); r.readFully(bytes)
            val str = String(bytes, Charsets.UTF_8)
            val end = str.lastIndexOf('\n')
            if (end < 0) return emptyList()
            s.readFrom += str.substring(0, end + 1).toByteArray(Charsets.UTF_8).size
            return str.substring(0, end).lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private fun alive(s: Session): Boolean {
        val pid = runCatching { f("${s.dir}/pid").readText().trim().toInt() }.getOrNull() ?: return false
        if (!File("/proc/$pid").exists()) return false
        return runCatching {
            java.io.RandomAccessFile(f("${s.dir}/out.jsonl"), "r").use { r ->
                val n = minOf(300L, r.length()); r.seek(r.length() - n)
                val b = ByteArray(n.toInt()); r.readFully(b); !String(b).contains("\"arin_end\"")
            }
        }.getOrDefault(true)
    }

    private suspend fun send(s: Session, line: String) = withContext(Dispatchers.IO) {
        val msg = f("${s.dir}/msg.json")
        msg.writeText(line)
        // Straight into the pipe (the wrapper holds it open, so this doesn't block); the shell is the fallback.
        val ok = runCatching { java.io.FileOutputStream(f("${s.dir}/in")).use { it.write(line.toByteArray(Charsets.UTF_8)) } }.isSuccess
        if (!ok) com.arin.terminal.Shell.run(ctx(), "cat ${s.dir}/msg.json > ${s.dir}/in; echo ok", 30_000)
    }

    private suspend fun start(s: Session, system: String, tools: String) {
        val context = ctx()
        // Too many running at once uses a lot of memory: the least recently used one stops (it resumes later).
        sessions.values.filter { it !== s && alive(it) }.sortedBy { it.lastUse }.dropLast(MAX_LIVE - 1).forEach { stop(it, keepSession = true) }
        f(s.dir).mkdirs()
        f("$BASE/home/.claude").mkdirs()
        f("$BASE/home/.claude.json").takeIf { !it.exists() }?.writeText("{\"hasCompletedOnboarding\":true}")
        f("${s.dir}/system.txt").writeText(system.take(120_000))
        val (mcp, servers, gh) = runCatching { extras?.invoke() }.getOrNull() ?: Triple("", emptyList(), "")
        val mcpFlag = if (mcp.isNotBlank()) { f("$BASE/mcp.json").writeText(mcp); "--mcp-config $BASE/mcp.json " } else ""
        val allowed = (tools.split(',') + servers.map { "mcp__$it" }).filter { it.isNotBlank() }.joinToString(" ")
        val ghEnv = if (gh.isNotBlank()) "export GH_TOKEN='${gh.replace("'", "")}' GITHUB_TOKEN='${gh.replace("'", "")}'; " else ""
        val resume = s.sessionId?.let { "--resume '$it' " } ?: ""
        val token = ClaudeCode.tokenFile()
        val cmd = "mkdir -p ${s.dir} /root/files; cd /root/files; rm -f ${s.dir}/in; mkfifo ${s.dir}/in; " +
            "setsid bash -c \"exec 3<>${s.dir}/in; echo \\$\\$ > ${s.dir}/pid; " +
            "unset ANTHROPIC_API_KEY ANTHROPIC_BASE_URL ANTHROPIC_AUTH_TOKEN ANTHROPIC_MODEL; " +
            "export CLAUDE_CODE_OAUTH_TOKEN=\\$(cat $token 2>/dev/null) HOME=$BASE/home DISABLE_AUTOUPDATER=1 " +
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 DISABLE_TELEMETRY=1 DISABLE_ERROR_REPORTING=1 " +
            "BASH_MAX_OUTPUT_LENGTH=8000 MAX_MCP_OUTPUT_TOKENS=6000; $ghEnv" +
            "claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages " +
            "--model '${s.model.replace("'", "")}' $resume--tools '$tools' --allowedTools $allowed $mcpFlag" +
            "--add-dir ${s.dir} --append-system-prompt \\\"\\$(cat ${s.dir}/system.txt)\\\" " +
            "< ${s.dir}/in >> ${s.dir}/out.jsonl 2>&1; echo '{\\\"type\\\":\\\"arin_end\\\"}' >> ${s.dir}/out.jsonl\" >/dev/null 2>&1 & " +
            "for i in 1 2 3 4 5 6 7 8 9 10; do [ -s ${s.dir}/pid ] && break; sleep 0.3; done; echo started"
        val r = com.arin.terminal.Shell.run(context, cmd, 60_000)
        if (!r.stdout.contains("started")) throw LlmException("Couldn't start Claude Code: " + r.stderr.take(300))
    }

    /** Ends a session's process. With [keepSession] its conversation can be resumed later (same session id). */
    private suspend fun stop(s: Session, keepSession: Boolean = false) {
        val pid = runCatching { f("${s.dir}/pid").readText().trim() }.getOrNull()
        runCatching {
            com.arin.terminal.Shell.run(ctx(), "pkill -f 'add-dir ${s.dir}' 2>/dev/null; " + (if (pid != null) "kill $pid 2>/dev/null; " else "") +
                (if (keepSession) "" else "rm -rf ${s.dir}; ") + "echo ok", 20_000)
        }
        if (!keepSession) sessions.remove(s.key)
    }

    /** Sessions not used for 30 minutes stop (they resume if the conversation continues). */
    private suspend fun trim() {
        val now = System.currentTimeMillis()
        sessions.values.filter { now - it.lastUse > 30 * 60_000L && alive(it) }.forEach { stop(it, keepSession = true) }
        sessions.values.filter { now - it.lastUse > 24 * 3_600_000L }.forEach { stop(it) }
    }

    /** Ends everything (Free up space, sign-out). */
    suspend fun stopAll() { sessions.values.toList().forEach { stop(it) } }
}
