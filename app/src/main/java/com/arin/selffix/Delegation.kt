package com.arin.selffix

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ARIN-PROTECTED: delegation-firewall — every request to a supporting model (the worker/middle model such as Claude,
 * and the helper) passes through here. The main model owns the conversation; the others get only a task brief:
 *  - API keys, tokens, passwords and private keys are redacted (all saved secrets + common secret formats),
 *  - lines that look like a conversation transcript (User:/Assistant: …) are removed,
 *  - only the project requirements relevant to this task are added (never the whole store),
 *  - an audit record says what kind of information was shared — without storing the content.
 * The pure parts ([Brief]) have unit tests.
 */
object Delegation {
    /** Pure, testable rules. */
    object Brief {
        private val patterns = listOf(
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
            Regex("""\bsk-ant-[A-Za-z0-9_\-]{16,}"""),
            Regex("""\bsk-(?:proj-)?[A-Za-z0-9_\-]{20,}"""),
            Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"""),
            Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}"""),
            Regex("""\bAIza[0-9A-Za-z_\-]{30,}"""),
            Regex("""\bxox[abposr]-[A-Za-z0-9\-]{10,}"""),
            Regex("""\bhf_[A-Za-z0-9]{20,}"""),
            Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-]{16,}"""),
            Regex("""(?m)^([A-Z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD|PASS)[A-Z0-9_]*)(\s*=\s*)()[^\s$]{8,}$"""),
            // Last: a quoted value given to a secret-named setting (code such as prefs.getString(...) isn't touched).
            Regex("""(?i)\b(api[_-]?key|apikey|secret|token|password|passwd|access[_-]?key|client[_-]?secret)(\s*[:=]\s*)(["'])[^"'\s$(){}]{8,}\3"""),
        )
        const val MASK = "[REDACTED]"

        /** Replaces every known secret value and every secret-looking string. Returns the text and how many were hidden. */
        fun redact(text: String, known: Collection<String> = emptyList()): Pair<String, Int> {
            var t = text; var n = 0
            known.filter { it.length >= 8 }.sortedByDescending { it.length }.forEach { s ->
                if (t.contains(s)) { n += t.split(s).size - 1; t = t.replace(s, MASK) }
            }
            for ((i, p) in patterns.withIndex()) {
                t = p.replace(t) { m ->
                    n++
                    if (i >= patterns.lastIndex - 1) m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + MASK + m.groupValues[3] else MASK
                }
            }
            return t to n
        }

        // A transcript turn starts at the beginning of the line with a capitalised speaker ("User: …", "**Assistant:** …").
        // Indented code such as `user: User,` is never matched.
        private val turn = Regex("""^(?:\*\*)?(?:User|Human|Owner|Person|The person|Me|You|Assistant|AI|Arin|Claude|DeepSeek)(?:\*\*)?\s*(?:\(\w+\))?\s*:(?:\*\*)?\s""")

        /**
         * Removes conversation-transcript lines from a task brief. Code and build logs
         * are kept: a transcript line is only one that starts with a speaker name and a colon.
         */
        fun stripConversation(text: String): Pair<String, Int> {
            val out = StringBuilder(); var removed = 0; var inCode = false
            for (line in text.lines()) {
                if (line.trimStart().startsWith("```")) { inCode = !inCode; out.appendLine(line); continue }
                if (!inCode && turn.containsMatchIn(line)) { removed++; continue }
                out.appendLine(line)
            }
            return out.toString().trimEnd() to removed
        }

        private val fileRef = Regex("""(?<![\w.-])((?:[\w.-]+/)*[\w.-]+\.(?:kt|kts|java|xml|gradle|toml|json|pro|properties|yml|yaml|md))\b""")

        fun files(text: String): List<String> = fileRef.findAll(text).map { it.groupValues[1] }.distinct().take(40).toList()

        fun categories(text: String): List<String> = buildList {
            if (Regex("""(?m)^\s*(fun |class |val |var |import |package |@Composable|<\w+[ >])""").containsMatchIn(text)) add("source code")
            if (Regex("""(?i)\b(e: |error:|exception|unresolved reference|build failed)""").containsMatchIn(text)) add("build errors")
            if (files(text).isNotEmpty()) add("file references")
            if (text.contains("PROJECT REQUIREMENTS")) add("project requirements")
            if (Regex("""(?i)(stackoverflow|github\.com/|search results|from the internet)""").containsMatchIn(text)) add("web research")
            if (isEmpty()) add("task description")
        }

        /** Requirements that share words with the task (the minimum needed), never the whole store. */
        fun relevant(requirements: List<String>, task: String, max: Int = 6): List<String> {
            val words = Regex("[a-zA-Z]{4,}").findAll(task.lowercase()).map { it.value }.toSet()
            return requirements.map { r -> r to Regex("[a-zA-Z]{4,}").findAll(r.lowercase()).count { it.value in words } }
                .filter { it.second >= 2 }.sortedByDescending { it.second }.take(max).map { it.first }
        }

        /** The brief actually sent: transcript lines out, secrets out, relevant requirements in. */
        fun build(task: String, known: Collection<String>, requirements: List<String> = emptyList()): Built {
            val (noChat, turns) = stripConversation(task)
            val req = relevant(requirements, noChat)
            val withReq = if (req.isEmpty()) noChat else noChat + "\n\nPROJECT REQUIREMENTS RELEVANT TO THIS TASK (keep them):\n" + req.joinToString("\n") { "- $it" }
            val (clean, secrets) = redact(withReq, known)
            return Built(clean, secrets, turns, req.size, files(clean), categories(clean))
        }
    }

    data class Built(val text: String, val secretsHidden: Int, val transcriptLinesRemoved: Int, val requirementsShared: Int,
                     val files: List<String>, val categories: List<String>)

    private const val MAX_RECORDS = 500
    private fun log(c: Context) = java.io.File(c.filesDir, "delegation-audit.jsonl")

    /** Saved secrets (API keys, GitHub token, Claude tokens on file) — so they are never sent to another model. */
    private fun knownSecrets(c: Context): List<String> = runCatching { com.arin.data.Settings(c).secretValues() }.getOrDefault(emptyList()) +
        runCatching {
            java.io.File(c.filesDir, "claude").walkTopDown().maxDepth(3).filter { it.isFile && it.name.contains("token") && it.length() in 20..4096 }
                .map { it.readText().trim() }.filter { it.length >= 16 }.toList()
        }.getOrDefault(emptyList())

    /**
     * Gates one delegation. [dest]: who gets it ("worker", "helper:Claude"…). [tab]: the conversation tab it belongs to.
     * [project]: the project key whose relevant requirements may be added. Returns the brief to send and the record id.
     */
    fun gate(c: Context, dest: String, task: String, tab: String = "", project: String? = null, requirements: List<String> = emptyList()): Pair<String, String> {
        val b = Brief.build(task, knownSecrets(c), requirements)
        val id = "d" + System.currentTimeMillis().toString(36) + (0..999).random().toString(36)
        record(c, JSONObject()
            .put("id", id).put("time", System.currentTimeMillis()).put("to", dest).put("tab", tab).put("project", project ?: "")
            .put("task", "t" + (task.take(400).hashCode().toUInt().toString(36))) // an identifier, not the content
            .put("files", JSONArray(b.files)).put("shared", JSONArray(b.categories)).put("chars", b.text.length)
            .put("secretsHidden", b.secretsHidden).put("transcriptLinesRemoved", b.transcriptLinesRemoved)
            .put("requirementsShared", b.requirementsShared).put("status", "sent"))
        return b.text to id
    }

    /** Marks how a delegation ended ("done", "failed", "refused"). */
    fun finish(c: Context, id: String, status: String) = record(c, JSONObject().put("id", id).put("time", System.currentTimeMillis()).put("status", status))

    @Synchronized private fun record(c: Context, o: JSONObject) = runCatching {
        val f = log(c)
        f.appendText(o.toString() + "\n")
        if (f.length() > 400_000) f.writeText(f.readLines().takeLast(MAX_RECORDS).joinToString("\n", postfix = "\n"))
    }

    /** The latest records, newest first, for the audit screen / report. */
    fun recent(c: Context, max: Int = 50): List<JSONObject> = runCatching {
        log(c).readLines().takeLast(max * 2).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.reversed()
    }.getOrDefault(emptyList())
}
