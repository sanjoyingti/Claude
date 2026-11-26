package com.arin.builder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BuildRequest(
    /** The new app's description, or the change to make to [BuildRunner.project]. */
    val description: String,
    val appName: String,
    val provider: com.arin.llm.Provider,
    val model: String,
    val apiKey: String,
    val effort: Effort,
    val githubToken: String,
    /** Let the agent download files from sites outside the built-in safe list. */
    val allowOtherSites: Boolean,
    /** Continue this project instead of starting a new one. */
    val project: Project? = null,
    /** An uploaded Android Studio project (.zip) to build instead of writing a new app. */
    val zipPath: String? = null,
    val zipName: String? = null,
    /** Files attached to this request: shown to the model, and images/fonts/sounds added to the app. */
    val attachments: List<com.arin.data.Attachment> = emptyList(),
    /** Pick up where the last build of [project] stopped instead of starting over. */
    val resume: Boolean = false,
    /** Change Arin itself: edit [selfRepo] on GitHub and build it with Arin's own workflow. */
    val selfUpdate: Boolean = false,
    /** Arin's source: owner/repo and branch. */
    val selfRepo: String = "",
    val selfBranch: String = "",
    /** Force the build to run on the phone (built-in Linux), never GitHub. */
    val localOnly: Boolean = false,
    /** Build in the cloud and also open a GitHub Codespace on the project; the APK is saved straight to the phone. */
    val codespace: Boolean = false,
    /**
     * Everything that was asked for, in full (e.g. the whole chat about the app). Blank: [description] is all there is.
     */
    val requirements: String = "",
    /** The custom API this build uses (its own, even if another chat switches APIs). Blank = the app's current one. */
    val customBaseUrl: String = "",
    val customFormat: String = "",
    /** After the APK builds, open it on GitHub's Android emulator and check it doesn't crash (fixed like build errors). */
    val testApp: Boolean = true,
    /** [requirements] already covers earlier requests too (a chat), so it replaces what the project has saved. */
    val requirementsComplete: Boolean = false,
    /** The owner unlocked the protected rules for this self-update (only from the Arin Update tab, by the owner). */
    val unlockProtected: Boolean = false,
    /** What earlier attempts ran into and the researched fix: advice for another method, never a change to the request. */
    val retryHint: String = "",
    /** Stop once the code is written and ask the owner where to build it (every tab). */
    val stopAfterWriting: Boolean = false,
    /** Where the owner chose to build after the code was written: "github", "phone" or "codespace" (blank = as before). */
    val target: String = "",
    /** Talking with the helper: once the model has written the code, the helper reviews it and the model fixes what it finds. */
    val consultHelper: Boolean = false,
    /** Which tab's AI helper switch this build follows ("chat", "build", "arin-update"); blank = Build or Arin Update. */
    val helperTab: String = "",
)

data class BuildResult(
    val success: Boolean,
    val message: String,
    val repoUrl: String? = null,
    val apkUrl: String? = null,
    /** Why it failed: the build's error lines, or what the model sent back. */
    val log: String = "",
    /** The GitHub Actions run with the full log. */
    val runUrl: String? = null,
    /** Where the APK compiled on the phone (built-in Linux) was saved, if it was. */
    val phoneApk: String? = null,
    /** The source code as a zip on the phone (for builds made only on the phone). */
    val phoneSourceZip: String? = null,
    /** The phone-built APK in Arin's storage, ready for Android's installer. */
    val phoneInstallPath: String? = null,
    /** The Codespace opened for this project, if any. */
    val codespaceUrl: String? = null,
    /** Save the APK to the phone automatically (used for codespace builds). */
    val autoDownloadApk: Boolean = false,
    /** What the emulator test found, in plain words (blank when the app wasn't tested). */
    val testNote: String = "",
    /** Screenshot of the app's first screen on the emulator, if the test took one. */
    val testScreenshot: String? = null,
    /** ARIN-PROTECTED: explain-after-coding — what the code does, in plain English, then suggestions (blank: no code written). */
    val explanation: String = "",
)

/**
 * Build Mode agent: the model writes or edits the app, GitHub Actions builds it, and build errors
 * go back to the model to fix, until the APK builds or the fix rounds for the Brain level run out.
 */
/** The fixed API address for [BuildRequest] (custom APIs only). */
fun BuildRequest.endpoint(): com.arin.llm.CustomApi.Endpoint? =
    if (provider == com.arin.llm.Provider.Custom && customBaseUrl.isNotBlank())
        com.arin.llm.CustomApi.Endpoint(customBaseUrl, runCatching { com.arin.llm.ApiFormat.valueOf(customFormat) }.getOrDefault(com.arin.llm.ApiFormat.OpenAI))
    else null

class BuildAgent(
    private val req: BuildRequest,
    private val emit: (BuildEvent) -> Unit,
    /** Called whenever the project changes, so it can be saved. */
    private val onProject: (Project) -> Unit = {},
    /** Live progress for long steps (null = done), with a short description. */
    private val onProgress: (Float?, String) -> Unit = { _, _ -> },
    /** For building on the phone in the built-in Linux, when its Android build tools are installed. */
    private val context: android.content.Context? = null,
    /** Directions the person sent while this build runs (taken at each step). */
    private val takeSteering: () -> List<String> = { emptyList() },
    /** True when a direction is waiting (checked while GitHub builds, so it's applied right away). */
    private val hasSteering: () -> Boolean = { false },
) {
    /** Set when waiting for GitHub was cut short by a direction from the person. */
    private var steerNow = false
    /** Every direction the person sent while this build ran: kept in every later prompt, in the order sent. */
    private val steered = mutableListOf<String>()

    private class SteerNow : RuntimeException()

    private fun steeringNote(): String {
        val fresh = takeSteering()
        fresh.forEach { step(BuildEvent.Kind.THINKING, "Following your direction: ${it.take(140)}") }
        steered += fresh
        if (steered.isEmpty()) return ""
        return "\n\nTHE OWNER'S DIRECTIONS, sent while you were working (follow them exactly; they win where they conflict " +
            "with the above, and nothing else that was asked may be dropped):\n" + steered.joinToString("\n") { "- $it" }
    }
    private val gh = GitHubClient(req.githubToken)
    private val slug = (req.project?.appName ?: req.appName).lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "app" }.take(30)
    /** The package the person asked for ("package com.example.calendar"), else one made from the app name. */
    private val packageName = req.project?.packageName
        ?: Regex("""(?i)\bpackage(?:\s+name)?\s*[:=]?\s*`?([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,5})`?""")
            .find(req.description)?.groupValues?.get(1)?.lowercase()
        ?: ("com.aibuilt." + (if (slug.first().isLetter()) slug else "a$slug"))
    private val appName get() = req.project?.appName ?: importedName ?: req.appName

    /** Updating Arin itself (its own repo, workflow and signing key). */
    private val self = req.selfUpdate || req.project?.selfUpdate == true
    private val imported = req.project?.imported == true || req.zipPath != null || self
    /** Test the built app on GitHub's emulator (not for Arin itself, nor builds made only on the phone). */
    private val testing get() = false   // app-test temporarily disabled (restoring reliable builds)
    private val workflowFile = when {
        self -> SELF_WORKFLOW
        imported -> ProjectTemplate.IMPORT_WORKFLOW_FILE
        else -> "build.yml"
    }
    private var importedName: String? = null
    private var uploadedOnce = false
    private var currentSha: String? = req.project?.lastSha
    private var currentRound = 0

    private val files = linkedMapOf<String, String>().apply { req.project?.files?.let { putAll(it) } }
    private val deps = linkedSetOf<String>().apply { req.project?.deps?.let { addAll(it) } }

    /** Files changed since the last commit (null value = delete). */
    private val pendingFiles = linkedMapOf<String, ByteArray?>()
    /** Big uploaded files waiting to be committed straight from storage. */
    private val pendingDisk = linkedMapOf<String, java.io.File>()
    private var depsChanged = false

    /** Everything the app must do: the first specification plus every change asked for since, so nothing gets lost. */
    private val requirements: String = kotlin.run {
        val now = req.requirements.ifBlank { req.description }.trim()
        val before = (req.project?.requirements?.ifBlank { null } ?: req.project?.description).orEmpty().trim()
        val all = when {
            req.selfUpdate || req.project?.selfUpdate == true -> now
            req.requirementsComplete || before.isEmpty() -> now.ifBlank { before }
            req.resume || now.isEmpty() || before.contains(now) -> before
            else -> before + "\n\nCHANGE ASKED FOR LATER (do this too; it wins where it conflicts with the above):\n" + now
        }
        if (all.length > 36_000) all.take(12_000) + "\n[…]\n" + all.takeLast(24_000) else all
    }

    /** How many times the finished code is checked against the requirements before building. */
    private val reviewRounds: Int = when (req.effort) {
        Effort.LOW -> 0
        Effort.MAX -> 2
        else -> 1
    }

    /** The fix rounds the Brain level allows (used for the phone build before trying GitHub). */
    private val baseFixRounds: Int = when (req.effort) {
        Effort.LOW -> 1
        Effort.MEDIUM, Effort.AUTO -> 3
        Effort.HIGH -> 6
        Effort.MAX -> 10
    } + if (false) 2 else 0

    /** ARIN-PROTECTED: research-first auto-fix keeps going until the error is fixed (only Stop ends it). */
    private val maxFixRounds: Int = if (com.arin.selffix.AutoFix.UNTIL_FIXED) com.arin.selffix.AutoFix.NO_LIMIT else baseFixRounds
    /** Errors that keep coming back get Claude's help. */
    private val repeats = com.arin.selffix.AutoFix.Repeats()
    /** Arin's own source as read from GitHub (self-updates), to keep the strict and protected rules. */
    private var selfOriginal: Map<String, String> = emptyMap()
    private var claudeAsks = 0
    private var approvedSig = 0
    /** The helper's plan is asked for once per build, before the model writes its first code. */
    private var helperPlanned = false

    private fun step(kind: BuildEvent.Kind, text: String) = emit(BuildEvent(kind, text))

    /** The model's last answer, kept so a failure report can show what it sent. */
    private var lastReply = ""
    /** Roughly how many tokens of the person's model this task has used (characters / 4). */
    private var taskTokens = 0L

    private var lastThinkingNote = 0L

    /** The Codespace opened for this build, if any. */
    private var codespaceUrl: String? = null

    /** Parts written so far, numbered for the plain-English progress. */
    private var part = 0

    private fun modelReplyLog(): String =
        if (lastReply.isBlank()) "(The model sent back an empty answer: it may have spent its whole answer thinking, or the service cut it off.)"
        else "Last answer from the model (end):\n" + lastReply.takeLast(1_500)

    /** The phone can compile apps itself (Linux with the Android build tools). */
    private val phoneCan = !self && context != null && LocalBuild.available(context)

    /** No GitHub for this build: compile, fix and hand over the APK on the phone. */
    private var localOnly = (req.project?.owner == LOCAL_OWNER && req.target !in setOf("github", "codespace")) ||
        (req.localOnly && (!self || req.target == "phone"))

    private fun localRepo(name: String) = GitHubClient.Repo(LOCAL_OWNER, name, "main", "")

    /** Creates the repo on GitHub, or switches to a phone-only build if GitHub refuses and the phone can build. */
    private suspend fun createRepoOrLocal(name: String, description: String): GitHubClient.Repo {
        if (localOnly) return localRepo(name)
        step(BuildEvent.Kind.STEP, "Creating GitHub repo $name")
        return try {
            gh.createRepo(name, description)
        } catch (e: ProviderException) {
            if (!phoneCan) throw e
            step(BuildEvent.Kind.ERROR, e.message ?: "GitHub refused to create the repository")
            step(BuildEvent.Kind.STEP, "Building on this phone instead (no GitHub needed)")
            localOnly = true
            localRepo(name)
        }
    }

    /** Set whenever the model wrote or changed code in this run (so the owner gets it explained). */
    private var codedThisRun = false

    // ARIN-PROTECTED: explain-after-coding — the owner's strict rule for every tab (chat, Build, Arin Update and all others):
    // after every piece of coding, the owner gets a detailed plain-English explanation of what was made or changed, then
    // suggestions to change, improve or update it, and the offer to build. Only the owner can change this rule.
    suspend fun run(): BuildResult {
        val result = runBuild()
        if (!codedThisRun || result.message == "Stopped.") return result
        val text = runCatching { explainForOwner(result) }.getOrDefault("")
        return if (text.isBlank()) result else result.copy(explanation = text)
    }

    /**
     * Your own model (the boss, never the worker or helper: they may not see the code) explains the code in plain
     * English from a short list of what changed, then suggests changes, improvements and updates, and offers to build.
     */
    private suspend fun explainForOwner(result: BuildResult): String {
        val changed = (lastChanged.ifEmpty { pendingFiles.keys.toList() }).distinct()
        if (changed.isEmpty()) return ""
        step(BuildEvent.Kind.THINKING, "${bossName()} is explaining the code in plain English")
        val brief = buildString {
            appendLine("WHAT THE OWNER ASKED: " + req.description.take(2_500))
            appendLine("APP: " + (if (self) "Arin itself (an update)" else appName))
            appendLine("FILES WRITTEN OR CHANGED:")
            changed.take(30).forEach { p ->
                val c = files[p].orEmpty()
                val sig = Regex("(?m)^\\s*(?:(?:private|internal|override|suspend|data|sealed|abstract|open)\\s+)*(fun|class|object|interface)\\s+[\\w<>.,? ]+")
                    .findAll(c).map { it.value.trim() }.take(12).joinToString("; ")
                appendLine("- $p" + if (sig.isNotBlank()) ": $sig" else "")
            }
            if (lastNotes.isNotEmpty()) appendLine("NOTES FROM WRITING IT: " + lastNotes.joinToString("; ").take(1_500))
            appendLine("STATUS: " + when {
                result.message.startsWith(CODE_READY) -> "the code is written; NOT built yet — the owner will choose where to build"
                result.success -> "the code is written and the app is built"
                else -> "the code is written but the build did not finish: " + result.message.take(200)
            })
        }
        val prompt = brief + "\nWrite to the OWNER, who is not a programmer. Use plain, everyday English: no jargon (if a technical " +
            "word is unavoidable, explain it in a few words). Write exactly these sections:\n" +
            "**What I made — in plain English:** explain in detail what the code does for the person using the app: each " +
            "screen, button and feature, what happens when they tap things, and how it works behind the scenes in simple terms. " +
            "Mention anything that was left out or that they should know.\n" +
            "**Suggestions:** 3 to 6 numbered, concrete ideas to change, improve or update the app (each one short).\n" +
            "**What next:** one line offering to make any of these changes, or to " +
            (if (result.message.startsWith(CODE_READY)) "build it (they pick where below)." else if (result.success) "update it again." else "carry on fixing and building it.")
        val out = StringBuilder()
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(240_000) {
                streamModel(req.provider, req.apiKey, req.model, listOf(ChatMessage(Role.USER, prompt)),
                    "You explain software to people who are not programmers, clearly and kindly, in plain English.", req.endpoint())
                    .collect { if (!it.startsWith(THOUGHT)) out.append(it) }
            }
        }
        context?.let { com.arin.selffix.CreditSaver.addUsage(it, prompt.length + out.length) }
        val text = out.toString().trim()
        if (text.isNotBlank()) step(BuildEvent.Kind.DONE, "Explained the code in plain English, with suggestions")
        return text.ifBlank {
            // The model couldn't be reached: Arin's own short explanation (no AI), so the rule still holds.
            "**What I made — in plain English:** I wrote or changed ${changed.size} part${if (changed.size == 1) "" else "s"} of " +
                (if (self) "Arin" else appName) + " to do what you asked: " + req.description.take(300) + "\n\n" +
                "**Suggestions:**\n1. Try it and tell me anything that doesn't look or work the way you want.\n" +
                "2. Ask me to add a feature or change the design.\n\n**What next:** " +
                (if (result.message.startsWith(CODE_READY)) "pick where to build it below, or ask for a change first." else "ask for any change.")
        }
    }

    private var lastChanged = mutableListOf<String>()
    private var lastNotes = listOf<String>()

    private suspend fun runBuild(): BuildResult {
        if (!localOnly) {
            step(BuildEvent.Kind.STEP, "Checking your GitHub token")
            try {
                if (req.githubToken.isBlank()) throw ProviderException(401, "Not signed in to GitHub")
                gh.login()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!phoneCan || req.project != null && req.project.owner != LOCAL_OWNER) throw e
                step(BuildEvent.Kind.STEP, "GitHub isn't available (${e.message}); building on this phone only")
                localOnly = true
            }
        }

        val existing = req.project
        val repo: GitHubClient.Repo
        var notes: List<String>
        val neverUploaded = existing != null && existing.imported && !existing.uploaded && existing.lastApkUrl == null
        val resumeZip = existing?.takeIf { neverUploaded }?.zipPath?.takeIf { java.io.File(it).exists() }
        val zipToRead = req.zipPath ?: resumeZip
        var checkSha: String? = null
        var startRound = 0
        if (req.resume && existing != null && resumeZip == null) {
            // Retry: continue from the saved checkpoint instead of starting over.
            var resumeRepo = GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            var switched = false
            if (existing.owner == LOCAL_OWNER && !localOnly) {
                // Written first, built on GitHub now: the repository is created only now that the owner chose GitHub.
                resumeRepo = createRepoOrLocal(existing.repo, "Built by Arin: ${existing.description}")
                existing.pendingPaths.forEach { p ->
                    val bytes = when (p) {
                        "app/signing.keystore" -> android.util.Base64.decode(ProjectTemplate.SHARED_KEYSTORE_B64, android.util.Base64.DEFAULT)
                        "app/src/main/AndroidManifest.xml" -> (files[p] ?: ProjectTemplate.defaultManifest(appName)).toByteArray()
                        else -> (files[p] ?: (if (!imported) ProjectTemplate.files(appName, packageName, deps.toList())[p] else null))?.toByteArray()
                    }
                    if (bytes != null) pendingFiles[p] = bytes
                }
                files.forEach { (p, t) -> if (p !in pendingFiles) pendingFiles[p] = t.toByteArray() }
                if (req.target == "codespace") pendingFiles[".devcontainer/devcontainer.json"] = ProjectTemplate.devcontainer.toByteArray()
                step(BuildEvent.Kind.STEP, "Uploading the written code to the new repository ${resumeRepo.owner}/${resumeRepo.name}")
                switched = true
            }
            if (self) {
                // The saved repository may be one this account can't push to: switch to the right one and carry the
                // changes already made over to it (nothing is redone).
                val found = com.arin.selffix.SelfRepo.resolve(context, req.githubToken, "${existing.owner}/${existing.repo}", existing.branch) {
                    step(BuildEvent.Kind.STEP, it)
                }.getOrElse { return BuildResult(false, it.message ?: "Couldn't find Arin's repository on your GitHub.") }
                if (found.repo != "${existing.owner}/${existing.repo}" || found.branch != existing.branch) {
                    val (o2, n2) = found.repo.split('/').let { it[0] to it[1] }
                    resumeRepo = GitHubClient.Repo(o2, n2, found.branch, "https://github.com/${found.repo}/tree/${found.branch}")
                    step(BuildEvent.Kind.STEP, "Resuming in ${found.repo}: carrying over the changes already made")
                    val there = gh.readTextFiles(resumeRepo)
                    selfOriginal = there
                    files.forEach { (path, text) -> if (there[path] != text) pendingFiles[path] = text.toByteArray() }
                    step(BuildEvent.Kind.STEP, "Resuming: uploading ${pendingFiles.size} changed file(s) — nothing is rewritten")
                    switched = true
                } else selfOriginal = files.toMap() // ARIN-PROTECTED: strict self-update rules also apply on Retry
            }
            repo = resumeRepo
            notes = emptyList()
            startRound = existing.round
            if (!switched) {
                val restored = existing.pendingPaths.mapNotNull { p ->
                    if (p == "app/signing.keystore") return@mapNotNull p to android.util.Base64.decode(ProjectTemplate.SHARED_KEYSTORE_B64, android.util.Base64.DEFAULT)
                    if (p == ".devcontainer/devcontainer.json") return@mapNotNull p to ProjectTemplate.devcontainer.toByteArray()
                    if (p == "app/src/main/AndroidManifest.xml" && files[p] == null) return@mapNotNull p to ProjectTemplate.defaultManifest(appName).toByteArray()
                    val text = files[p] ?: (if (!imported) ProjectTemplate.files(appName, packageName, deps.toList())[p] else null)
                    text?.let { p to it.toByteArray() }
                }
                when {
                    restored.isNotEmpty() -> {
                        restored.forEach { (p, b) -> pendingFiles[p] = b }
                        step(BuildEvent.Kind.STEP, "Resuming: uploading ${restored.size} file(s) that didn't reach GitHub last time")
                    }
                    existing.lastSha != null -> {
                        checkSha = existing.lastSha
                        step(BuildEvent.Kind.STEP, "Resuming: checking the last build on GitHub (no code is rewritten)")
                    }
                    else -> step(BuildEvent.Kind.STEP, "Resuming: starting a fresh build of the current code")
                }
            }
        } else if ((existing == null && req.zipPath != null) || resumeZip != null) {
            val zipLabel = req.zipName ?: existing?.zipName ?: "the project"
            if (resumeZip != null) step(BuildEvent.Kind.STEP, "The first upload didn't finish — uploading the whole project again")
            step(BuildEvent.Kind.STEP, "Unpacking $zipLabel")
            onProgress(-1f, "Reading ${mb(java.io.File(zipToRead!!).length())}…")
            val zip = withContext(Dispatchers.IO) { ZipImport.read(java.io.File(zipToRead), zipLabel) }
            onProgress(null, "")
            importedName = zip.name
            zip.files.forEach { (path, bytes) ->
                pendingFiles[path] = bytes
                if (ZipImport.isText(path) && bytes.size < 200_000) files[path] = bytes.toString(Charsets.UTF_8)
            }
            pendingDisk.putAll(zip.bigFiles)
            if (zip.tooBig.isNotEmpty()) {
                step(BuildEvent.Kind.ERROR, "Skipped ${zip.tooBig.size} file(s) over 95 MB (GitHub's limit): ${zip.tooBig.take(3).joinToString { it.substringAfterLast('/') }}")
            }
            pendingFiles[".github/workflows/${ProjectTemplate.IMPORT_WORKFLOW_FILE}"] = ProjectTemplate.importWorkflow.toByteArray()
            step(BuildEvent.Kind.STEP, "Found ${zip.count} files in \"${zip.name}\"" + if (zip.skipped > 0) " (skipped ${zip.skipped} build/cache files)" else "")
            val repoName = zip.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "project" }.take(40) +
                "-" + SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
            repo = if (existing != null) {
                GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            } else {
                createRepoOrLocal(repoName, "Uploaded to Arin: ${zip.name}")
            }
            notes = emptyList()
        } else if (self) {
            // Arin updating itself: read its source from GitHub, change it, build it with its own workflow.
            // Find the repository with Arin's source that this GitHub account can push to (else GitHub says "Not Found").
            val found = com.arin.selffix.SelfRepo.resolve(context, req.githubToken, req.selfRepo, req.selfBranch) { step(BuildEvent.Kind.STEP, it) }
                .getOrElse { return BuildResult(false, it.message ?: "Couldn't find Arin's repository on your GitHub.") }
            val (owner, name) = found.repo.split('/').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            if (owner.isBlank() || name.isBlank()) return BuildResult(false, "Set Arin's source repository in Settings → GitHub first.")
            repo = GitHubClient.Repo(owner, name, found.branch.ifBlank { "main" }, "https://github.com/$owner/$name/tree/${found.branch}")
            importedName = "Arin"
            step(BuildEvent.Kind.STEP, "Reading Arin's source from ${found.repo} (${repo.branch})")
            onProgress(-1f, "Downloading source files…")
            files.clear()
            files.putAll(gh.readTextFiles(repo))
            onProgress(null, "")
            if (files.isEmpty()) return BuildResult(false, "Couldn't read Arin's source from ${found.repo}@${repo.branch}. Check the repository in Settings → GitHub.")
            selfOriginal = files.toMap()
            step(BuildEvent.Kind.DONE, "Read ${files.size} source files")
            step(BuildEvent.Kind.THINKING, "Working out the changes to Arin with ${req.provider.label}")
            val assets = addAttachedAssets()
            notes = apply(ask(changePrompt() + assets, req.attachments))
            notes = inspect(notes, "the changes, before building")
            if (pendingFiles.isEmpty() && !depsChanged) {
                return BuildResult(false, "The model didn't change any of Arin's files. Try describing the change differently.", repo.url, log = modelReplyLog())
            }
            uploadedOnce = true
            saveProject(repo, null) // checkpoint: the changes are saved, so Resume carries on from here
            if (req.stopAfterWriting) {
                // The code is written: the owner chooses where to build it (the tab asks, then Resume builds it).
                step(BuildEvent.Kind.DONE, "The code is written (${pendingFiles.size} file(s)). Waiting for you to choose where to build")
                return BuildResult(true, CODE_READY + pendingFiles.keys.joinToString(","), repo.url)
            }
        } else if (existing == null) {
            step(BuildEvent.Kind.THINKING, "Planning and writing the app with ${req.provider.label}")
            val assets = addAttachedAssets()
            notes = apply(ask(firstPrompt() + assets, req.attachments))
            // Some models plan in prose and forget to write the files: ask again, strictly, up to twice.
            var retries = 0
            while (files.isEmpty() && retries < 2) {
                retries++
                step(BuildEvent.Kind.STEP, "No files came back. Asking ${req.provider.label} again for the code (try $retries of 2)")
                notes = apply(ask(firstPrompt() + assets + formatReminder(), req.attachments))
            }
            if (files.isNotEmpty()) {
                notes = finishWriting(notes)
                notes = reviewAgainstRequirements(notes)
            }
            if (files.isEmpty()) {
                return BuildResult(
                    false,
                    "The model didn't return any files, even after asking again, so there was nothing to build. " +
                        "Below is the end of what it sent back. Try again, or pick another model.",
                    log = modelReplyLog(),
                )
            }
            val repoName = "$slug-" + SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
            repo = if (req.stopAfterWriting) localRepo(repoName) else createRepoOrLocal(repoName, "Built by Arin: ${req.description}")
            ProjectTemplate.files(appName, packageName, deps.toList()).forEach { (p, c) -> pendingFiles[p] = c.toByteArray() }
            // Sign every built app with the one shared key, so a rebuild updates over the installed app cleanly.
            pendingFiles["app/signing.keystore"] = android.util.Base64.decode(ProjectTemplate.SHARED_KEYSTORE_B64, android.util.Base64.DEFAULT)
            if (req.codespace && !localOnly) {
                // Make an opened codespace ready to build Android, and open one now (if the sign-in allows it).
                pendingFiles[".devcontainer/devcontainer.json"] = ProjectTemplate.devcontainer.toByteArray()
                step(BuildEvent.Kind.STEP, "Opening a GitHub Codespace on the project")
                codespaceUrl = gh.createCodespace(repo) ?: gh.codespaceLink(repo)
            }
            if ("app/src/main/AndroidManifest.xml" !in files) {
                pendingFiles["app/src/main/AndroidManifest.xml"] = ProjectTemplate.defaultManifest(appName).toByteArray()
            }
        } else {
            repo = GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            step(BuildEvent.Kind.THINKING, "Working out the changes with ${req.provider.label}")
            val assets = addAttachedAssets()
            notes = apply(ask(changePrompt() + assets, req.attachments))
            if (pendingFiles.isEmpty() && !depsChanged) {
                return BuildResult(false, "The model didn't change any files. Try describing the change differently.", repo.url, existing.lastApkUrl, log = modelReplyLog())
            }
            if (!imported) {
                notes = finishWriting(notes)
                notes = reviewAgainstRequirements(notes)
            }
        }
        // In worker mode your model does ALL the checking (the helper never reads the code).
        if (!workerOn() && (helperOn() || com.arin.selffix.CreditSaver.on(context)) && !req.resume && pendingFiles.isNotEmpty()) helperReview()
        if (!req.resume) notes = inspect(notes, "the finished code, before building")
        saveProject(repo, null)
        if (req.stopAfterWriting && !self && !(existing == null && req.zipPath != null) && !req.resume) {
            step(BuildEvent.Kind.DONE, "The code is written (${pendingFiles.size} file(s)). Waiting for you to choose where to build")
            return BuildResult(true, CODE_READY + pendingFiles.keys.joinToString(","), if (repo.owner == LOCAL_OWNER) null else repo.url)
        }
        // Continuing after the owner chose where to build: a codespace on request.
        if (req.target == "codespace" && !localOnly && codespaceUrl == null && repo.owner != LOCAL_OWNER) {
            if (!self) pendingFiles[".devcontainer/devcontainer.json"] = ProjectTemplate.devcontainer.toByteArray()
            step(BuildEvent.Kind.STEP, "Opening a GitHub Codespace on the project")
            codespaceUrl = gh.createCodespace(repo) ?: gh.codespaceLink(repo)
        }

        // Build on the phone first when Linux has the Android build tools: errors are found and fixed here,
        // and GitHub then only builds code that already compiles (and gives the download links).
        val localSlug = repo.name
        // The owner said where: "github"/"codespace" means GitHub only — no phone build first.
        val githubOnly = req.target == "github" || req.target == "codespace"
        var useLocal = localOnly || !githubOnly && phoneCan && (existing == null || LocalBuild.hasCopy(context!!, localSlug))
        if (useLocal && existing == null) {
            LocalBuild.write(context!!, localSlug, files.mapValues { it.value.toByteArray() })
        }
        if (useLocal && existing != null && context != null && !LocalBuild.hasCopy(context, localSlug)) ensureLocalCopy(repo, localSlug)
        var localRounds = 0
        var phoneApk: String? = null

        var round = startRound
        while (true) {
          currentRound = round
          val sha: String
          val resumeSha = checkSha
          if (resumeSha != null) {
            // Already on GitHub: just look at (or wait for) that build.
            checkSha = null
            sha = resumeSha
          } else {
            if (depsChanged) {
                val gradle = ProjectTemplate.files(appName, packageName, deps.toList())
                pendingFiles["app/build.gradle.kts"] = gradle.getValue("app/build.gradle.kts").toByteArray()
                // The top-level file changes too when a code generator (KSP, for Room) is added.
                pendingFiles["build.gradle.kts"] = gradle.getValue("build.gradle.kts").toByteArray()
                depsChanged = false
            }
            // Make sure GitHub always has the build instructions (harmless if unchanged).
            if (self) {
                // Arin's repo already has its workflow and signing key: leave them alone.
            } else if (imported) {
                pendingFiles[".github/workflows/${ProjectTemplate.IMPORT_WORKFLOW_FILE}"] = ProjectTemplate.importWorkflow.toByteArray()
            } else {
                ProjectTemplate.files(appName, packageName, deps.toList())[".github/workflows/build.yml"]?.let {
                    pendingFiles[".github/workflows/build.yml"] = it.toByteArray()
                }
            }
            if (useLocal) {
                val ctx = context!!
                LocalBuild.write(ctx, localSlug, pendingFiles.toMap())
                LocalBuild.writeFiles(ctx, localSlug, pendingDisk.toMap())
                step(BuildEvent.Kind.BUILD, "Compiling on this phone in Linux (the first build downloads libraries and can take 10+ minutes)")
                when (val out = LocalBuild.build(ctx, localSlug, appName) { }) {
                    is LocalBuild.Outcome.Ok -> {
                        phoneApk = out.apkPath
                        if (localOnly) {
                            if (self && repo.owner != LOCAL_OWNER && pendingFiles.isNotEmpty()) {
                                step(BuildEvent.Kind.STEP, "Saving the changes to Arin's source on GitHub too")
                                runCatching { currentSha = gh.commit(repo, pendingFiles.toMap(), "Arin: ${req.description.take(60)} (built on the phone)", emptyMap()) { _, _, _, _ -> } }
                            }
                            val zip = LocalBuild.saveSourceZip(ctx, localSlug, appName)
                            step(BuildEvent.Kind.DONE, "APK ready (${mb(out.size)}): ${out.apkPath}")
                            saveProject(repo, null)
                            step(BuildEvent.Kind.DONE, "Done")
                            return BuildResult(
                                true,
                                if (existing != null) "Your changes are built on this phone." else "Your app is ready (built on this phone).",
                                phoneApk = out.apkPath,
                                phoneSourceZip = zip,
                                phoneInstallPath = out.installFile?.absolutePath,
                            )
                        }
                        step(BuildEvent.Kind.DONE, "Compiled on this phone (${mb(out.size)}), saved to ${out.apkPath}. Now building on GitHub for the download links")
                    }
                    is LocalBuild.Outcome.Errors -> {
                        if (localRounds < (if (localOnly) maxFixRounds else baseFixRounds)) {
                            localRounds++
                            val query = mainError(out.log)
                            val web = researchFirst(query, out.log) // ARIN-PROTECTED: research the internet before every fix
                            val count = out.log.lines().count { it.startsWith("e: ") || it.contains("error:", true) }.coerceAtLeast(1)
                            step(BuildEvent.Kind.ERROR, "Phone build failed with $count error${if (count == 1) "" else "s"}: ${plainError(out.log)}. Fixing on the phone (${com.arin.selffix.AutoFix.roundText(localRounds, if (localOnly) maxFixRounds else baseFixRounds)})")
                            notes = if (workerOn()) privateFix(out.log, notes, web) else apply(ask(fixPrompt(out.log, notes, web)))
                            continue
                        }
                        if (localOnly) {
                            saveProject(repo, null)
                            return BuildResult(
                                false,
                                "The build still fails on the phone after $localRounds fix rounds. The errors are below; say \"try again\" to keep fixing.",
                                log = out.log,
                            )
                        }
                        step(BuildEvent.Kind.ERROR, "Still failing on the phone after $localRounds fixes; trying GitHub's build")
                        useLocal = false
                    }
                    is LocalBuild.Outcome.Skipped -> {
                        if (localOnly) {
                            saveProject(repo, null)
                            return BuildResult(false, "The phone couldn't run the build: ${out.reason.take(200)}", log = out.reason)
                        }
                        step(BuildEvent.Kind.STEP, "Couldn't build on this phone (${out.reason.take(120)}); GitHub will build it")
                        useLocal = false
                    }
                }
            }
            val toUpload = pendingFiles.size + pendingDisk.size
            step(BuildEvent.Kind.STEP, "Uploading $toUpload changed file${if (toUpload == 1) "" else "s"} to GitHub")
            val message = when {
                round > 0 -> "Fix build errors (round $round)"
                self -> "Arin: ${req.description.take(60)}"
                existing != null -> "Change: ${req.description.take(60)}"
                req.zipPath != null -> "Uploaded project from Arin"
                else -> "Initial app from Arin"
            }
            val total = pendingFiles.size + pendingDisk.size
            var lastPct = -1
            sha = gh.commit(repo, pendingFiles.toMap(), message, pendingDisk.toMap()) { n, of, sent, size ->
                val pct = if (size > 0) (sent * 100 / size).toInt() else n * 100 / of.coerceAtLeast(1)
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct / 100f, "$n of $of files · ${mb(sent)} of ${mb(size)}")
                }
            }
            onProgress(null, "")
            step(BuildEvent.Kind.DONE, "Upload finished: $total file${if (total == 1) "" else "s"} (${mb(pendingFiles.values.sumOf { (it?.size ?: 0).toLong() } + pendingDisk.values.sumOf { it.length() })})")
            pendingFiles.clear()
            pendingDisk.values.forEach { it.delete() }
            pendingDisk.clear()
            uploadedOnce = true
            currentSha = sha
            saveProject(repo, null)
          }

            step(BuildEvent.Kind.BUILD, if (testing) "Building the APK on GitHub, then testing it on an Android emulator (about 6–12 minutes)"
                else "Building the APK on GitHub (usually 3–5 minutes)")
            val waited = waitForRun(repo, sha)
            if (steerNow) {
                // Steered during the build: change the code now with the direction, then upload and build again.
                steerNow = false
                step(BuildEvent.Kind.THINKING, "You sent a direction during the build — applying it now, then building again")
                notes = apply(ask(steerPrompt()))
                saveProject(repo, null)
                continue
            }
            val run = waited
                ?: return BuildResult(
                    false,
                    if (neverUploaded) {
                        "GitHub didn't start a build because this project's first upload never finished. Tap New, then upload the zip again."
                    } else {
                        "GitHub didn't start a build. Open the repo on GitHub → Actions: if Actions is turned off for the repo, " +
                            "turn it on and try again; otherwise sign out and sign in with GitHub again in Settings."
                    },
                    repo.url,
                )

            var crashLog: String? = null
            if (run.conclusion == "success") {
                step(BuildEvent.Kind.STEP, "Build succeeded. Getting the download link")
                // Only hand over a link once the APK file is really there.
                var apk: String? = null
                var size: Long? = null
                for (i in 0 until 20) {
                    apk = if (self) gh.apkForTag(repo, "$SELF_TAG_PREFIX${run.number}") else gh.latestApkUrl(repo)
                    size = apk?.let { ApkInstaller.check(it) }
                    if (apk != null && size != null) break
                    delay(3000)
                }
                if (apk == null || size == null) {
                    return BuildResult(false, "The build worked, but GitHub hasn't published the APK yet. Open the repo's Releases on GitHub in a minute.", repo.url)
                }
                step(BuildEvent.Kind.DONE, "APK ready: ${mb(size)}")
                saveProject(repo, apk)
                // The emulator test (it ran in the same GitHub job, after the APK was published).
                var testNote = ""
                var shot: String? = null
                if (testing) {
                    val tag = "build-${run.number}"
                    val report = gh.releaseText(repo, tag, "arin-test-report.txt")
                    shot = gh.releaseAssetLink(repo, tag, "arin-test-screen.png")
                    val result = report?.lineSequence()?.firstOrNull { it.startsWith("RESULT:") }?.removePrefix("RESULT:")?.trim()
                    val why = report?.lineSequence()?.firstOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim().orEmpty()
                    when (result) {
                        "PASS" -> {
                            testNote = "Tested on an Android emulator: it opens, and tapping around didn't crash it."
                            step(BuildEvent.Kind.DONE, testNote)
                        }
                        "CRASH", "NOT_RUNNING", "INSTALL_FAILED" -> {
                            val plain = when (result) {
                                "INSTALL_FAILED" -> "The APK wouldn't install on the emulator"
                                "NOT_RUNNING" -> "The app closed by itself right after it opened"
                                else -> "The app crashed on the emulator"
                            } + (if (why.isNotBlank()) ": $why" else "")
                            if (round < maxFixRounds) {
                                crashLog = "RUNTIME PROBLEM (the app builds, but fails when it runs on an Android emulator).\n" +
                                    "ERROR: $plain\n\nTest report and crash log:\n" + report.orEmpty().take(8_000)
                                step(BuildEvent.Kind.ERROR, "$plain. Fixing it")
                            } else {
                                testNote = "⚠️ $plain. The APK is ready, but say \"fix the crash\" to keep fixing it."
                                step(BuildEvent.Kind.ERROR, testNote)
                            }
                        }
                        else -> {
                            testNote = "The emulator test couldn't run this time, so the app wasn't checked."
                            step(BuildEvent.Kind.STEP, testNote)
                        }
                    }
                }
              if (crashLog == null) {
                step(BuildEvent.Kind.DONE, "Done")
                return BuildResult(
                    true,
                    when {
                        self -> "The new version of Arin is ready. Tap Install to update."
                        existing != null -> "Your changes are built."
                        req.zipPath != null -> "Your project compiled to an APK."
                        else -> "Your app is ready."
                    },
                    repo.url,
                    apk,
                    phoneApk = phoneApk,
                    codespaceUrl = codespaceUrl,
                    autoDownloadApk = req.codespace,
                    testNote = testNote,
                    testScreenshot = shot,
                )
              }
            }

            val errors = crashLog ?: kotlin.run {
                step(BuildEvent.Kind.STEP, "Reading the build errors")
                gh.failureSummary(repo, run.id)
            }
            if (crashLog == null && round >= maxFixRounds) {
                return BuildResult(
                    false,
                    "The build still fails after $round fix round${if (round == 1) "" else "s"}. The errors are below; " +
                        "say \"try again\" to keep fixing, or try a stronger model.",
                    repo.url,
                    log = errors,
                    runUrl = run.url,
                )
            }
            round++
            currentRound = round
            // Look the main error up online every time, and give the model what others did to fix it.
            val web = researchFirst(mainError(errors), errors) // ARIN-PROTECTED: research the internet before every fix
            val count = errors.lines().count { it.startsWith("e: ") || it.contains("error:", true) }.coerceAtLeast(1)
            if (crashLog != null) step(BuildEvent.Kind.ERROR, "Fixing the crash (${com.arin.selffix.AutoFix.roundText(round, maxFixRounds)}), then building and testing again")
            else step(BuildEvent.Kind.ERROR, "Build failed with $count error${if (count == 1) "" else "s"}: ${plainError(errors)}. Fixing (${com.arin.selffix.AutoFix.roundText(round, maxFixRounds)})")
            notes = if (workerOn()) privateFix(errors, notes, web) else apply(ask(fixPrompt(errors, notes, web)))
            saveProject(repo, null)
        }
    }

    private fun mb(bytes: Long) = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)

    private fun saveProject(repo: GitHubClient.Repo, apk: String?) {
        onProject(
            Project(
                appName = appName,
                packageName = packageName,
                owner = repo.owner,
                repo = repo.name,
                branch = repo.branch,
                repoUrl = repo.url,
                description = req.project?.description ?: req.description.ifBlank { "Uploaded project" },
                requirements = requirements,
                files = files.toMap(),
                deps = deps.toList(),
                lastApkUrl = apk ?: req.project?.lastApkUrl,
                imported = imported,
                selfUpdate = self,
                zipPath = req.zipPath ?: req.project?.zipPath,
                zipName = req.zipName ?: req.project?.zipName,
                uploaded = uploadedOnce || req.project?.uploaded == true,
                lastSha = currentSha,
                pendingPaths = pendingFiles.keys.toList(),
                round = currentRound,
            ),
        )
    }

    /**
     * Asks the model, letting it search the web or read pages before it writes files, and reports
     * each file as it starts streaming in.
     */
    /** Adds attached images, fonts, sounds and other files to the project; returns notes for the prompt. */
    private fun addAttachedAssets(): String {
        val notes = mutableListOf<String>()
        req.attachments.filter { it.isBinary && !it.isZip }.forEach { a ->
            val ext = a.name.substringAfterLast('.', "").lowercase()
            val base = a.name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
                .let { if (it.isEmpty() || !it.first().isLetter()) "file_$it" else it }.take(40)
            val path = when {
                ext in listOf("png", "jpg", "jpeg", "webp") || (a.kind == com.arin.data.Attachment.IMAGE && a.mime == "image/jpeg") ->
                    "app/src/main/res/drawable/$base.${if (ext == "jpeg" || (a.mime == "image/jpeg" && ext !in listOf("png", "webp"))) "jpg" else ext}"
                ext in listOf("ttf", "otf") -> "app/src/main/res/font/$base.$ext"
                ext in listOf("mp3", "ogg", "wav", "m4a", "aac", "mp4", "webm") -> "app/src/main/res/raw/$base.$ext"
                else -> "app/src/main/assets/${a.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
            }
            val bytes = runCatching { java.io.File(a.path).readBytes() }.getOrNull() ?: return@forEach
            if (bytes.size > MAX_DOWNLOAD) {
                notes += "${a.name} is over 15 MB, so it wasn't added to the app."
                return@forEach
            }
            pendingFiles[path] = bytes
            val ref = when {
                path.contains("/res/drawable/") -> "R.drawable.${path.substringAfterLast('/').substringBeforeLast('.')}"
                path.contains("/res/font/") -> "R.font.${path.substringAfterLast('/').substringBeforeLast('.')}"
                path.contains("/res/raw/") -> "R.raw.${path.substringAfterLast('/').substringBeforeLast('.')}"
                else -> "assets/${path.substringAfterLast('/')}"
            }
            step(BuildEvent.Kind.DOWNLOAD, "Added your file ${a.name} as $ref")
            notes += "The user's file ${a.name} is in the project as $ref; use it where it fits."
        }
        return if (notes.isEmpty()) "" else "\n\nFiles from the user:\n" + notes.joinToString("\n") { "- $it" }
    }

    private suspend fun ask(prompt: String, attachments: List<com.arin.data.Attachment> = emptyList()): String {
        val effort = if (req.effort == Effort.AUTO) Effort.HIGH else req.effort
        val system = (if (imported) ProjectTemplate.importedSystemPrompt else ProjectTemplate.systemPrompt(packageName)) +
            ProjectTemplate.webInstructions +
            (if (context != null) "\n\n" + com.arin.selffix.ClaudeHelper.instructions(context, helperTab) else "")
        val cap = if (false) 3_000 else 8_000
        // Models that can't see get pictures and videos described for them.
        val (seenFiles, seenText) = if (context != null && attachments.isNotEmpty())
            com.arin.selffix.VisionBridge.prepareFiles(context, req.provider, req.model, attachments) { step(BuildEvent.Kind.STEP, it) }
        else attachments to ""
        // ARIN-PROTECTED: ai-helper during builds — with the helper on, it gives the model a plan before it writes (it can read
        // the person's files), checks the written code, and gives the solution for every error. The model does the work.
        var helperPlan = ""
        // ARIN-PROTECTED: helper-sees-only-the-boss — no plan from the person's request (the helper may not see it).
        if (false && !helperPlanned && helperOn() && context != null && com.arin.selffix.ClaudeHelper.route(context) != null) {
            helperPlanned = true
            step(BuildEvent.Kind.THINKING, "Asking ${com.arin.selffix.ClaudeHelper.helper(context).name} for a plan before writing")
            val plan = com.arin.selffix.ClaudeHelper.ask(
                context = context,
                question = "An AI model is about to write/change the code of an Android app (Kotlin, Jetpack Compose, Gradle) " +
                    (if (self) "— the Arin app itself, updated in place" else "") + ". The model asks you for a short, concrete plan: " +
                    "which files to create or change, libraries and versions that build, and pitfalls that make Android builds fail.\n\n" +
                    "What the person wants:\n" + req.description.take(4_000) +
                    (if (req.requirements.isNotBlank()) "\n\nEverything asked so far:\n" + req.requirements.take(4_000) else "") +
                    "\n\nWrite to the model, not to the person. Be brief.",
                source = helperTab,
            ) { step(BuildEvent.Kind.THINKING, it.take(120)) }
            if (plan.isNotBlank() && !plan.contains("couldn't be reached") && !plan.contains("isn't connected") && !plan.contains("switched off")) {
                helperPlan = "\n\n[Plan from the AI helper — use it where right; you write the code:]\n" + plan.take(6_000)
                step(BuildEvent.Kind.THINKING, "🟣 Got the helper's plan; the model writes the code")
            }
        }
        val working = mutableListOf(ChatMessage(Role.USER, prompt + seenText + helperPlan + steeringNote(), seenFiles))
        var steps = 0
        claudeAsks = 0
        // ARIN-PROTECTED: worker-mode — the worker writes; the boss (req.provider/model) only inspects (see [inspect]).
        val pool = if (workerOn()) workerTargets() else emptyList()
        var wi = 0
        var target: Target = pool.firstOrNull() ?: bossTarget()
        val workSystem = if (target.worker) workerSystem(system) else system
        if (target.worker) step(BuildEvent.Kind.THINKING, "${nice(target.model)} is following ${bossName()}'s instructions: writing the code (${bossName()} inspects it after)")
        while (true) {
            val sb = StringBuilder()
            val thinking = StringBuilder()
            var scanned = 0
            var about: String? = null
            // ARIN-PROTECTED: steer-immediately — a direction sent while the model writes stops this answer at once, and the
            // model starts again WITH the direction (not queued for later).
            val steeredNow = try { resilient(
                attempt = { model -> streamModel(target.provider, target.key, model, working.toList(), if (target.worker) workSystem else system, target.endpoint) },
                model = target.model,
                fallback = { if (req.provider != com.arin.llm.Provider.Free) null else alternativeModel(req.provider, req.apiKey, req.model) }, // ARIN-PROTECTED: never change the Custom API — nor Claude, GPT or the Claude Code login: only the person switches them
                onRetry = { step(BuildEvent.Kind.STEP, it) },
            ).collect { raw ->
                if (hasSteering()) throw SteerNow()
                if (raw.startsWith(THOUGHT)) {
                    // Show what the model is thinking, live, under the current step.
                    thinking.append(raw.substring(1))
                    // Only the newest part is shown, so only look at the end (the whole text grows with every word).
                    val now = System.currentTimeMillis()
                    if (now - lastThinkingNote > 400) {
                        lastThinkingNote = now
                        onProgress(-1f, "${nice(target.model)} is thinking: " + proseOnly(thinking.takeLast(1_200).toString()).takeLast(160))
                    }
                    return@collect
                }
                if (thinking.isNotEmpty()) {
                    step(BuildEvent.Kind.THINKING, "${nice(target.model)} planned it: " + proseOnly(thinking.take(3_000).toString()).trim().take(160) + "…")
                    thinking.clear()
                    onProgress(null, "")
                }
                val chunk = raw
                sb.append(chunk)
                // Announce files as their headers arrive, like an IDE agent.
                val nl = sb.lastIndexOf("\n")
                if (nl > scanned) {
                    sb.substring(scanned, nl).lines().forEach { line ->
                        aboutLine.find(line.trim())?.let { about = it.groupValues[1].trim() }
                        fileStart.find(line.trim())?.let {
                            // Say in plain English what this part of the app does, like Claude does, not the code.
                            val path = it.groupValues[1].trim('`', ' ')
                            part++
                            val what = about?.takeIf { a -> a.length > 3 } ?: plainEnglish(path)
                            step(BuildEvent.Kind.FILE, "Part $part: $what (${path.substringAfterLast('/')})")
                            about = null
                        }
                    }
                    scanned = nl
                }
            }; false } catch (e: SteerNow) { true }
            if (steeredNow) {
                step(BuildEvent.Kind.THINKING, "Changing course now with your direction")
                onProgress(null, "")
                val base = working[0].text.substringBefore("\n\nTHE OWNER'S DIRECTIONS")
                working.clear()
                working += ChatMessage(Role.USER, base + steeringNote(), seenFiles)
                continue
            }
            val reply = sb.toString()
            // A worker that refuses: the same task goes to the next worker; after the last one, to your model.
            if (target.worker && com.arin.selffix.CreditSaver.isRefusal(reply)) {
                com.arin.selffix.CreditSaver.noteRefusal(context!!, target.w!!, helperTab)
                wi++
                target = pool.getOrNull(wi) ?: bossTarget()
                step(BuildEvent.Kind.THINKING, "${nice(pool[wi - 1].model)} refused — ${bossName()} is passing the task to " + (if (target.worker) nice(target.model) else "itself (${bossName()} writes it)"))
                continue
            }
            lastReply = reply
            if (target.worker) lastWorker = nice(target.model)
            // Credit meter: roughly how much of your model this task has used so far (and today's warning, if set).
            if (context != null && !target.worker) {
                val chars = system.length + working.sumOf { it.text.length } + reply.length
                taskTokens += chars / 4
                val today = com.arin.selffix.CreditSaver.addUsage(context, chars)
                val budget = com.arin.selffix.CreditSaver.dailyBudgetK(context) * 1000L
                step(BuildEvent.Kind.THINKING, "≈ ${"%,d".format(taskTokens)} tokens of ${req.model.substringAfterLast('/')} used in this task" +
                    if (budget > 0 && today > budget) " · ⚠️ over today's budget (${"%,d".format(today)})" else "")
            }
            if (steps >= MAX_WEB_STEPS || reply.contains("=== FILE:")) return reply
            val search = WebTools.searchTag.find(reply)?.groupValues?.get(1)?.trim()
            val fetch = WebTools.fetchTag.find(reply)?.groupValues?.get(1)?.trim()
            val claudeQ = if (context != null && (target.worker || com.arin.selffix.ClaudeHelper.enabled(context, helperTab)) && claudeAsks < com.arin.selffix.ClaudeHelper.maxAsks(context)) com.arin.selffix.ClaudeHelper.question(reply) else null
            val result = when {
                claudeQ != null -> {
                    claudeAsks++
                    val hn = com.arin.selffix.ClaudeHelper.helper(context!!).name
                    step(BuildEvent.Kind.THINKING, "${nice(target.model)} asked $hn for help — $hn is helping ${nice(target.model)}")
                    "The helper's answer:\n\n" + com.arin.selffix.ClaudeHelper.ask(context!!, projectNote() + claudeQ, source = helperTab).take(12_000)
                }
                search != null -> {
                    step(BuildEvent.Kind.WEB, "Searching: $search")
                    "Web search results for \"$search\":\n\n" + WebTools.search(search).take(cap)
                }
                fetch != null -> {
                    step(BuildEvent.Kind.WEB, "Reading ${fetch.removePrefix("https://").take(60)}")
                    "Content of $fetch:\n\n" + WebTools.fetch(fetch).take(cap)
                }
                else -> return reply
            }
            steps++
            working += ChatMessage(Role.ASSISTANT, reply.trim())
            working += ChatMessage(Role.USER, "[Tool result]\n$result\n\nNow continue: write the files in the required format.")
        }
    }

    /** Who writes this answer: a worker (worker mode) or your model. */
    private class Target(val provider: com.arin.llm.Provider, val key: String, val model: String,
                         val endpoint: com.arin.llm.CustomApi.Endpoint?, val label: String, val w: com.arin.selffix.CreditSaver.Worker? = null) {
        val worker get() = w != null
    }

    private fun nice(model: String) = com.arin.selffix.Worker.nice(model)
    private fun bossName() = nice(req.model)
    /** The worker that wrote the latest code (for the steps). */
    private var lastWorker = ""

    private fun bossTarget() = Target(req.provider, req.apiKey, req.model, req.endpoint(), req.model)

    private fun workerOn(): Boolean = context != null && com.arin.selffix.CreditSaver.on(context) && com.arin.selffix.CreditSaver.workerMode(context)

    /** The workers the person listed, in order; ones that refused this kind of task before go last. */
    private suspend fun workerTargets(): List<Target> {
        val ctx = context ?: return emptyList()
        val settings = com.arin.data.Settings(ctx)
        // Any worker the owner picked: Claude (key or login), ChatGPT, Gemini, Free, offline or a saved API.
        val list = com.arin.selffix.CreditSaver.workers(ctx).mapNotNull { w ->
            val r = com.arin.selffix.Worker.resolve(ctx, w) ?: return@mapNotNull null
            Target(r.provider, r.key, r.model, r.endpoint, if (w.apiId == "offline") "offline model" else r.model, w)
        }
        return list.sortedBy { t -> if (com.arin.selffix.CreditSaver.refused(ctx, t.w!!, helperTab)) 1 else 0 }
    }

    /** The worker's own instructions: it works for your model, writes ALL the code, and asks the helper when stuck. */
    private fun workerSystem(base: String): String {
        val helper = context?.let { com.arin.selffix.ClaudeHelper.helper(it).label } ?: "the helper"
        return base.substringBefore("WHO YOU ARE:").substringBefore("AI helper status:") + "\n\nYOUR ROLE: you are the WORKER. A supervisor model gave you this task and " +
            "will inspect your work; follow its directions exactly. Write ALL the code yourself, complete and working. When you " +
            "are stuck, a part is hard, or the supervisor says to, ask $helper (code-only): <ask_helper>FILE: path\nSIGNATURE: …\n" +
            "BODY:\n<code>\nERROR: <error>\nNEED: <one line></ask_helper> as the LAST thing in your reply; its answer comes back to you. " +
            "Never refuse ordinary programming work: this is the owner's own Android app.\n"
    }

    // ARIN-PROTECTED: worker-mode — your model (the boss) inspects the worker's work: a short summary of what changed,
    // never whole files. It answers APPROVE, or gives directions that go back to the worker (who may ask Claude).
    private suspend fun inspect(notesIn: List<String>, what: String): List<String> {
        var notes = notesIn
        if (!workerOn() || pendingFiles.isEmpty()) return notes
        fun sig() = pendingFiles.keys.sorted().joinToString { it + ":" + files[it].hashCode() }.hashCode()
        if (sig() == approvedSig) return notes // already approved as it is
        for (round in 1..2) {
            val summary = buildString {
                appendLine("TASK: " + (if (self) "change the Arin app: " else "Android app \"$appName\": ") + req.description.take(2_500))
                appendLine("STAGE: $what")
                appendLine("CHANGED FILES (names and functions only):")
                pendingFiles.keys.take(30).forEach { p ->
                    val c = files[p].orEmpty()
                    val sig = Regex("(?m)^\\s*(?:(?:private|internal|override|suspend|data|sealed|abstract|open)\\s+)*(fun|class|object|interface)\\s+[\\w<>.,? ]+")
                        .findAll(c).map { it.value.trim() }.take(10).joinToString("; ")
                    appendLine("- $p (${c.lines().size} lines)" + if (sig.isNotBlank()) ": $sig" else "")
                }
                if (notes.isNotEmpty()) appendLine("WORKER NOTES: " + notes.joinToString("; ").take(800))
            }
            val wn = lastWorker.ifBlank { "the worker" }
            step(BuildEvent.Kind.THINKING, "${bossName()} is inspecting $wn's work ($what)")
            val verdict = bossSay(summary + "\nYou are the supervisor. Inspect: does this cover the task, and is anything clearly " +
                "wrong or missing? Answer in at most 6 lines: either just APPROVE, or FIX: <exact directions for the worker>. " +
                "You may write \"ask Claude\" in a direction.")
            if (verdict.isBlank() || verdict.trim().uppercase().startsWith("APPROVE") || !verdict.contains("FIX", true)) {
                step(BuildEvent.Kind.THINKING, "✅ ${bossName()} approved $wn's work")
                approvedSig = sig()
                return notes
            }
            step(BuildEvent.Kind.THINKING, "${bossName()} is instructing $wn: " + verdict.trim().removePrefix("FIX:").trim().take(160))
            notes = apply(ask(buildString {
                appendLine("The supervisor inspected your work and gives these directions. Follow them exactly (ask the helper if it says so " +
                    "or you're stuck):")
                appendLine(verdict.trim().take(2_000))
                appendLine()
                appendFilesLean(verdict)
                appendLine()
                appendLine("Reply with every file you change, in full, or EDIT blocks.")
            }))
        }
        return notes
    }

    // ARIN-PROTECTED: worker-mode strict privacy for build errors — once your model approved the code, the worker and the
    // helper never see the project or your chat. Your model reads the errors and names the broken pieces; Arin itself
    // (no AI) cuts out ONLY those pieces; the worker (who may ask the helper) returns each piece fixed; your model inspects
    // the before/after; only then is it built again.
    private class Piece(val file: String, val start: Int, val end: Int, val text: String, val need: String) { var origin: Piece? = null }

    private suspend fun privateFix(errors: String, notesIn: List<String>, web: String): List<String> {
        val ctx = context ?: return notesIn
        val boss = bossName()
        val trimmed = com.arin.selffix.CreditSaver.trimErrors(errors)
        step(BuildEvent.Kind.THINKING, "$boss is analysing the build errors")
        val plan = bossSay(buildString {
            appendLine("The build failed. ERRORS:")
            appendLine(trimmed)
            appendLine()
            appendLine("CODE AROUND THE ERRORS (numbered lines):")
            appendLine(errorRegions(trimmed))
            if (web.isNotBlank()) { appendLine(); appendLine("WEB RESEARCH (short):"); appendLine(web.take(2_500)) }
            appendLine()
            appendLine("Analyse the cause. Do NOT write the fix. Name each broken piece for the worker, one block each (max 4):")
            appendLine("<piece>FILE: path\nFUNCTION: functionName   (or LINES: from-to)\nNEED: one line saying exactly what to fix</piece>")
        })
        val pieces = Regex("(?s)<piece>(.*?)</piece>").findAll(plan).mapNotNull { cut(it.groupValues[1]) }.take(4).toList()
        if (pieces.isEmpty()) {
            step(BuildEvent.Kind.THINKING, "$boss couldn't point to a piece — $boss fixes it itself this time")
            return apply(ask(fixPrompt(errors, notesIn, web)))
        }
        var current = pieces
        for (round in 1..2) {
            val fixed = mutableListOf<Pair<Piece, String>>()
            for (pc in current) {
                step(BuildEvent.Kind.THINKING, "$boss is instructing the worker: ${pc.need.take(100)} (${pc.file.substringAfterLast('/')})")
                val task = "FILE: ${pc.file}\nCODE:\n${pc.text}\nERROR:\n" + trimmed.lines().filter { it.contains(pc.file.substringAfterLast('/')) }.take(8).joinToString("\n").ifBlank { trimmed.take(1_500) } +
                    "\nNEED: ${pc.need}\nReturn ONLY the fixed CODE above (the same piece, complete), nothing else."
                val (who, out) = com.arin.selffix.Worker.run(ctx, task, boss) { _, t -> step(BuildEvent.Kind.THINKING, t) }
                val code = out.replace(Regex("(?s)^.*?```[\\w-]*\\n"), "").substringBeforeLast("```").trim()
                if (who.isBlank() || code.isBlank()) { step(BuildEvent.Kind.THINKING, "No worker could fix ${pc.file.substringAfterLast('/')}"); continue }
                fixed += pc to code
            }
            if (fixed.isEmpty()) {
                step(BuildEvent.Kind.THINKING, "No worker could fix it — $boss fixes it itself this time")
                return apply(ask(fixPrompt(errors, notesIn, web)))
            }
            step(BuildEvent.Kind.THINKING, "$boss is inspecting the worker's fix")
            val verdict = bossSay(buildString {
                appendLine("ERRORS:"); appendLine(trimmed.take(2_000)); appendLine()
                fixed.forEachIndexed { i, (pc, code) ->
                    appendLine("PIECE ${i + 1} — ${pc.file} — NEED: ${pc.need}"); appendLine("BEFORE:"); appendLine(pc.text); appendLine("AFTER:"); appendLine(code); appendLine()
                }
                appendLine("Inspect. Answer just APPROVE, or FIX n: <exact direction> for each piece that is still wrong (max 6 lines).")
            })
            val redo = Regex("(?i)FIX\\s*(\\d+)\\s*:\\s*(.+)").findAll(verdict).mapNotNull { m ->
                fixed.getOrNull((m.groupValues[1].toIntOrNull() ?: 0) - 1)?.let { (pc, code) -> m.groupValues[2] to (pc to code) }
            }.toList()
            // Put the approved pieces in place (Arin does it, no AI).
            val redoFiles = redo.map { it.second.first }.toSet()
            fixed.filter { it.first !in redoFiles || round == 2 }.forEach { (pc, code) -> replacePiece(pc, code) }
            if (redo.isEmpty() || round == 2) {
                step(BuildEvent.Kind.THINKING, "✅ $boss approved the fix — building again")
                return notesIn
            }
            step(BuildEvent.Kind.THINKING, "$boss wants changes: " + redo.joinToString("; ") { it.first }.take(160))
            current = redo.map { (dir, p) -> Piece(p.first.file, -1, -1, p.second, dir).also { it.origin = p.first } }
        }
        return notesIn
    }

    /** Lines around each error location (numbered), so your model can name the broken pieces. */
    private fun errorRegions(errors: String): String {
        val out = StringBuilder()
        Regex("([\\w./-]+\\.(?:kt|java|xml|kts|gradle))[:(](\\d+)").findAll(errors).map { it.groupValues[1] to it.groupValues[2].toInt() }
            .distinct().take(6).forEach { (f, line) ->
                val path = files.keys.firstOrNull { it.endsWith(f.substringAfter("/src/").ifBlank { f }) || it.endsWith("/" + f.substringAfterLast('/')) } ?: return@forEach
                val lines = files[path].orEmpty().lines()
                val from = maxOf(0, line - 25); val to = minOf(lines.size, line + 15)
                out.appendLine("=== $path (lines ${from + 1}-$to) ===")
                for (i in from until to) out.appendLine("${i + 1}: ${lines[i]}")
            }
        return out.toString().take(20_000).ifBlank { "(no file locations in the errors)" }
    }

    /** Arin (not an AI) cuts out the piece your model named: a whole function, or the lines it gave. */
    private fun cut(spec: String): Piece? {
        val file = Regex("FILE:\\s*(\\S+)").find(spec)?.groupValues?.get(1)?.trim('`') ?: return null
        val need = Regex("NEED:\\s*(.+)").find(spec)?.groupValues?.get(1)?.trim().orEmpty()
        val path = files.keys.firstOrNull { it == file || it.endsWith("/" + file) || it.endsWith(file) } ?: return null
        val text = files[path] ?: return null
        Regex("LINES:\\s*(\\d+)\\s*-\\s*(\\d+)").find(spec)?.let { m ->
            val lines = text.lines(); val a = (m.groupValues[1].toInt() - 1).coerceIn(0, lines.size); val b = m.groupValues[2].toInt().coerceIn(a, lines.size)
            val start = lines.take(a).sumOf { it.length + 1 }; val piece = lines.subList(a, b).joinToString("\n")
            return Piece(path, start, start + piece.length, piece, need)
        }
        val fn = Regex("FUNCTION:\\s*([\\w.]+)").find(spec)?.groupValues?.get(1)?.substringAfterLast('.') ?: return null
        val m = Regex("(?m)^[ \\t]*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*(?:(?:private|internal|public|protected|override|suspend|inline|operator|open|abstract)\\s+)*fun\\s+(?:<[^>]+>\\s*)?(?:[\\w.<>?]+\\.)?$fn\\s*\\(").find(text) ?: return null
        // The parameter list: match its parentheses, then see whether the body is { … } or "= expression".
        var k = m.range.last; var paren = 0
        while (k < text.length) { if (text[k] == '(') paren++ else if (text[k] == ')') { paren--; if (paren == 0) break }; k++ }
        val brace = text.indexOf('{', k).let { if (it < 0) Int.MAX_VALUE else it }
        val eq = text.indexOf('=', k).let { if (it < 0) Int.MAX_VALUE else it }
        val end = if (brace < eq) {
            var depth = 0; var i = brace
            while (i < text.length) { if (text[i] == '{') depth++ else if (text[i] == '}') { depth--; if (depth == 0) break }; i++ }
            minOf(i + 1, text.length)
        } else if (eq != Int.MAX_VALUE) {
            text.indexOf("\n\n", eq).let { if (it < 0) text.length else it }
        } else return null
        return Piece(path, m.range.first, end, text.substring(m.range.first, end), need)
    }

    /** Puts a fixed piece back exactly where it was cut out. */
    private fun replacePiece(pc: Piece, code: String) {
        val src = pc.origin ?: pc
        val text = files[src.file] ?: return
        val now = if (src.start >= 0 && src.end <= text.length && text.substring(src.start, src.end) == src.text)
            text.substring(0, src.start) + code + text.substring(src.end)
        else if (text.contains(src.text)) text.replaceFirst(src.text, code) else return
        files[src.file] = now
        pendingFiles[src.file] = now.toByteArray()
        step(BuildEvent.Kind.FILE, "Put the fixed piece into ${src.file.substringAfterLast('/')}")
    }

    /** A short answer from your model (the boss), with little thinking, for inspecting. */
    private suspend fun bossSay(prompt: String): String {
        val out = StringBuilder()
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(180_000) {
                streamModel(req.provider, req.apiKey, req.model, listOf(ChatMessage(Role.USER, prompt)),
                    "You are the supervisor of an AI coding worker. Be brief.", req.endpoint())
                    .collect { if (!it.startsWith(THOUGHT)) out.append(it) }
            }
        }
        context?.let { com.arin.selffix.CreditSaver.addUsage(it, prompt.length + out.length) }
        return out.toString().take(3_000)
    }

    /** Sent again when a reply had no files: just the format, as plainly as possible. */
    private fun formatReminder() = "\n\n" + """
        IMPORTANT: your last reply contained no files, so nothing could be built. Don't explain or plan this time.
        Reply with ONLY the complete files, starting right away with the lines below, every file in this exact format:
        === ABOUT: what this part of the app does, in plain English ===
        === FILE: app/src/main/java/${packageName.replace('.', '/')}/MainActivity.kt ===
        (the whole file)
        === END FILE ===
        Keep it to a few files (one MainActivity.kt with all the screens is fine) so the whole answer fits.
    """.trimIndent()

    private fun firstPrompt() = buildString {
        appendLine("Build this Android app. Implement EVERY point below in full: every feature, screen, option and detail that was")
        appendLine("asked for or agreed. Nothing may be left out, simplified away, faked with sample data, or left as a TODO or placeholder.")
        appendLine()
        appendLine("=== REQUIREMENTS ===")
        appendLine(requirements)
        appendLine("=== END REQUIREMENTS ===")
        appendLine()
        appendLine("App name: $appName")
        appendLine(attemptNotes())
        append(sizeRule)
    }

    /** ARIN-PROTECTED: notes from earlier attempts — a different method, the exact same request. */
    private fun attemptNotes(): String = buildString {
        appendLine()
        appendLine(com.arin.selffix.AutoFix.RULE_EXACT)
        if (req.retryHint.isNotBlank()) {
            appendLine("EARLIER ATTEMPTS FAILED. What was found (researched on the internet) — use a different method this time, " +
                "but build exactly what was asked:")
            appendLine(req.retryHint.take(6_000))
        }
    }

    private val sizeRule = "If the whole app doesn't fit in one reply, write as many COMPLETE files as fit (the most important first) and end " +
        "with the line === MORE === ; you will be asked to continue. When every file is written, end with the line === DONE === ."

    /** The file that was being written when a reply got cut off, if any. */
    private fun cutOffFile(reply: String): String? {
        if (!parse(reply).truncated) return null
        return reply.lines().lastOrNull { fileStart.containsMatchIn(it.trim()) }?.let { fileStart.find(it.trim())?.groupValues?.get(1)?.trim('`', ' ') }
    }

    /** Big apps don't fit in one reply: keep asking for the rest until the model says it's done. */
    private suspend fun finishWriting(notesIn: List<String>): List<String> {
        var notes = notesIn
        var rounds = 0
        val maxRounds = if (req.effort == Effort.LOW) 2 else 8
        while (rounds < maxRounds && (moreLine.containsMatchIn(lastReply) || parse(lastReply).truncated)) {
            rounds++
            val cut = cutOffFile(lastReply)
            step(BuildEvent.Kind.STEP, "It's a big app: writing the next part (${files.size} files so far)")
            val before = files.size to pendingFiles.size
            notes = apply(ask(buildString {
                appendLine("You are part-way through writing this Android app.")
                appendLine()
                appendLine("=== REQUIREMENTS ===")
                appendLine(requirements)
                appendLine("=== END REQUIREMENTS ===")
                appendLine()
                appendLine("Files written so far (complete; don't send them again unless you change them):")
                appendFiles()
                if (cut != null) {
                    appendLine()
                    appendLine("Your last reply was cut off in the middle of $cut, so that file was NOT saved. Write it again in full (split it into smaller files if it's long).")
                }
                appendLine()
                appendLine("Now write the remaining files, so that every requirement is implemented and everything that is used exists (classes, functions, screens, resources).")
                append(sizeRule)
            }))
            // Nothing new came back: stop asking.
            if (before == (files.size to pendingFiles.size) && cutOffFile(lastReply) == null) break
        }
        return notes
    }

    /** Checks the written app against the requirements, one by one, and has the model add whatever is missing. */
    private suspend fun reviewAgainstRequirements(notesIn: List<String>): List<String> {
        var notes = notesIn
        repeat(reviewRounds) { round ->
            step(BuildEvent.Kind.STEP, if (round == 0) "Checking that everything you asked for is in the app" else "Checking once more that nothing is missing")
            val reply = ask(buildString {
                appendLine("Review this Android app against its requirements before it is built.")
                appendLine()
                appendLine("=== REQUIREMENTS ===")
                appendLine(requirements)
                appendLine("=== END REQUIREMENTS ===")
                appendLine()
                appendLine("Current files:")
                appendFiles()
                appendLine()
                appendLine("Go through the requirements one at a time and look for:")
                appendLine("- anything asked for that is missing or only partly done;")
                appendLine("- placeholders, TODOs, empty click handlers, buttons that do nothing, sample data where real behaviour was asked for;")
                appendLine("- screens that can't be reached, settings that aren't saved, data that is lost when the app restarts (if it should be kept);")
                appendLine("- code that won't compile: missing imports, classes, functions or resources, wrong API names, missing manifest permissions.")
                appendLine()
                appendLine("If you find anything, reply with every file to add or change, in full, in the usual format; start each with an")
                appendLine("=== ABOUT: line saying in plain English which requirement it completes or what it fixes. $sizeRule")
                append("If every requirement is fully implemented and the code is correct, reply with exactly: === COMPLETE ===")
            })
            val changedBefore = pendingFiles.size to files.hashCode()
            if (!reply.contains("=== FILE:") && parse(reply).files.isEmpty()) {
                step(BuildEvent.Kind.DONE, "Checked: everything you asked for is in the app")
                return notes
            }
            notes = apply(reply)
            notes = finishWriting(notes)
            if (changedBefore == (pendingFiles.size to files.hashCode())) return notes
        }
        return notes
    }

    /** Files most likely to matter first: ones named in [hint] (errors or the request), then build files, then sources. */
    private fun prioritized(hint: String): List<Map.Entry<String, String>> {
        val h = hint.lowercase()
        return files.entries.sortedBy { (path, _) ->
            val name = path.substringAfterLast('/').lowercase()
            when {
                h.contains(path.lowercase()) || h.contains(name) -> 0
                name.startsWith("build.gradle") || name.startsWith("settings.gradle") || name == "libs.versions.toml" -> 1
                name == "androidmanifest.xml" || name.endsWith(".kt") || name.endsWith(".java") -> 2
                else -> 3
            }
        }
    }

    private fun StringBuilder.appendFiles(hint: String = "") {
        // Offline models have a much smaller memory for text.
        var budget = if (self) 160_000 else 130_000
        prioritized(hint).forEach { (path, content) ->
            if (budget <= 0) return@forEach
            val c = content.take(budget)
            budget -= c.length
            appendLine("=== FILE: $path ===")
            appendLine(c)
            appendLine("=== END FILE ===")
        }
        if (deps.isNotEmpty()) appendLine("Extra dependencies: ${deps.joinToString()}")
    }

    /** Credit saver: the files the errors name, in full; every other file only as a one-line map entry. */
    private fun StringBuilder.appendFilesLean(hint: String) {
        val all = prioritized(hint)
        val named = all.filter { (p, _) -> hint.contains(p.substringAfterLast('/')) || lastReply.contains(p.substringAfterLast('/')) }.take(4)
        val send = named.ifEmpty { all.take(2) }
        var budget = 40_000
        send.forEach { (path, content) ->
            if (budget <= 0) return@forEach
            val c = content.take(budget); budget -= c.length
            appendLine("=== FILE: $path ==="); appendLine(c); appendLine("=== END FILE ===")
        }
        appendLine("Other files (map — names and functions only):")
        all.filter { it !in send }.forEach { (p, c) ->
            val sig = Regex("(?m)^\\s*(?:@\\w+\\s+)*(?:(?:private|internal|public|override|suspend|data|sealed|abstract|open)\\s+)*(fun|class|object|interface)\\s+[\\w<>.,? ]+").findAll(c)
                .map { it.value.trim() }.take(12).joinToString("; ")
            appendLine("- $p" + if (sig.isNotBlank()) ": $sig" else "")
        }
        if (deps.isNotEmpty()) appendLine("Extra dependencies: ${deps.joinToString()}")
    }

    private fun changePrompt() = buildString {
        if (self) {
            appendLine("Here is the source of Arin itself: the Android AI assistant app you are running in (package com.arin, Kotlin + Jetpack Compose).")
            appendLine("Its build setup, GitHub workflow and signing key already work: don't change .github/, the keystore, applicationId or targetSdk.")
            appendLine("Increase versionCode by 1 and the last number of versionName in app/build.gradle.kts, and update the version shown in Settings to match.")
            appendLine("Keep everything that works; change only what the request needs. Add new files where they fit the existing package layout.")
        } else {
            appendLine("Here is an existing Android app, \"$appName\" (${if (imported) "uploaded by the user" else "originally: ${req.project?.description}"}).")
        }
        appendLine()
        appendLine("Current files:")
        appendFiles(req.description)
        appendLine()
        if (!self && !imported && requirements.isNotBlank() && requirements != req.description) {
            appendLine("Everything this app must do (keep all of it working, and add anything from this list that is still missing):")
            appendLine("=== REQUIREMENTS ===")
            appendLine(requirements)
            appendLine("=== END REQUIREMENTS ===")
            appendLine()
        }
        appendLine("Make this change: ${req.description}")
        if (self) appendLine(com.arin.selffix.SelfUpdateRules.RULE_FOR_MODEL)
        if (self && req.unlockProtected) appendLine("The owner APPROVED changing the protected rules this change needs (only those, exactly as described).")
        appendLine(attemptNotes())
        appendLine()
        appendLine("Reply with every file you change or add, in full, in the same format. Files you don't send stay as they are.")
        if (!self) appendLine(sizeRule)
        appendLine("To delete a file, send it with the single line: DELETE")
    }

    /**
     * ARIN-PROTECTED: before every fix, research the error on the internet (searches + the best pages). When the same
     * error comes back, Claude is also asked for help (when connected), whatever model this build uses.
     */
    private suspend fun researchFirst(query: String, errors: String): String {
        val seen = repeats.count(query.ifBlank { errors.take(200) })
        // ARIN-PROTECTED: credit-saver — 1) a fix that worked before for this exact error (no AI at all); 2) Claude
        // researches and returns the patch (code-only), so your model reads a short answer instead of web pages.
        // Your model stays the boss: it makes the change. If Claude can't help, your model researches as before.
        if (com.arin.selffix.CreditSaver.on(context) && !workerOn()) {
            val ctx = context!!
            val sig = com.arin.selffix.CreditSaver.signature(errors)
            if (seen < 2) com.arin.selffix.CreditSaver.rememberedFix(ctx, sig)?.let {
                step(BuildEvent.Kind.THINKING, "Seen this error before — using the fix that worked (no AI needed)")
                return "A fix that worked before for this exact error (apply it):\n$it"
            } else com.arin.selffix.CreditSaver.forgetFix(ctx, sig)
            if (com.arin.selffix.ClaudeHelper.route(ctx, helperTab) != null || com.arin.selffix.ClaudeHelper.route(ctx) != null) {
                val trimmed = com.arin.selffix.CreditSaver.trimErrors(errors)
                val code = prioritized(trimmed).filter { (p, _) -> trimmed.contains(p.substringAfterLast('/')) }.take(2)
                    .joinToString("\n") { (p, c) -> "FILE: $p\nBODY:\n" + c.take(6_000) }
                step(BuildEvent.Kind.THINKING, "Claude is researching this error and writing the fix")
                val answer = com.arin.selffix.ClaudeHelper.ask(
                    context = ctx,
                    question = projectNote() + code + "\nERROR:\n" + trimmed + (if (seen >= 2) "\nATTEMPT: $seen (the last fix didn't work — take a different approach)" else "") +
                        "\nNEED: research if needed, then return only the patched code (whole changed functions or files).",
                    source = helperTab, hard = seen >= 2,
                ) { step(BuildEvent.Kind.THINKING, it.take(120)) }
                if (answer.isNotBlank() && !answer.contains("couldn't be reached") && !answer.contains("isn't connected") && !answer.contains("switched off")) {
                    com.arin.selffix.CreditSaver.rememberFix(ctx, sig, answer)
                    return "Claude (your helper) researched this error. Its patch — apply it; you make the change:\n" + answer.take(8_000)
                }
            }
        }
        var web = com.arin.selffix.AutoFix.research(query, errors) { step(BuildEvent.Kind.WEB, it) }
        if (seen >= 2) web += "\n\nThis same error has come back $seen times: use a clearly different approach this time."
        // ARIN-PROTECTED: with the AI helper on, every build error is solved one by one with its help (the model asks,
        // the helper gives the solution, the model makes the fix). With it off, the model works alone.
        if (context != null && !workerOn() && helperOn() && com.arin.selffix.ClaudeHelper.route(context, helperTab) != null) {
            step(BuildEvent.Kind.THINKING, "Asking ${com.arin.selffix.ClaudeHelper.helper(context).name} for the solution to this error")
            val answer = com.arin.selffix.ClaudeHelper.ask(
                context = context,
                question = "An Android (Kotlin/Gradle) build failed" + (if (seen >= 2) " with the same error after $seen fix attempts" else "") +
"; I am the building model (the boss) and ask you for the solution.\n\nErrors:\n" + errors.take(6_000) + "\n\nWhat I found on the internet:\n" + web.take(4_000) +
                    "\n\nWhat is the real cause, and exactly what should be changed (files and code) to fix it? Write to the model.",
                source = helperTab,
            ) { step(BuildEvent.Kind.THINKING, it.take(120)) }
            if (answer.isNotBlank() && !answer.contains("couldn't be reached") && !answer.contains("isn't connected") && !answer.contains("switched off"))
                web += "\n\nThe AI helper's solution (use it; you make the fix):\n" + answer.take(8_000)
        }
        return web
    }

    // ARIN-PROTECTED: credit-saver + helper-sees-only-the-boss — Claude (Claude Code login) may search a read-only COPY
    // of the code being built, so the boss doesn't spend its tokens pasting files. Never the person's request or files.
    private fun projectNote(): String {
        val ctx = context ?: return ""
        if (workerOn() || !com.arin.selffix.CreditSaver.on(ctx) || com.arin.selffix.ClaudeHelper.lastRoute != "pro" || files.isEmpty()) return ""
        return runCatching {
            val dir = java.io.File(com.arin.terminal.Linux.rootfs(ctx), "root/.arin/helper-work/project")
            dir.deleteRecursively(); dir.mkdirs()
            files.forEach { (p, c) ->
                if (p.contains("..")) return@forEach
                java.io.File(dir, p).apply { parentFile?.mkdirs() }.writeText(c)
            }
            "PROJECT: /root/.arin/helper-work/project (read-only copy of the code)\n"
        }.getOrDefault("")
    }

    /** The AI helper helps from start to end when the person switched it on, or asked for it for this build. */
    private fun helperOn(): Boolean = context != null && com.arin.selffix.ClaudeHelper.enabled(context, helperTab)

    /** Each tab has its own helper switch: this build follows the tab it was started from. */
    private val helperTab: String get() = req.helperTab.ifBlank { if (self) "arin-update" else "build" }

    /**
     * A phone build needs the whole project on the phone: the repository from GitHub (with its binary files, e.g. Arin's
     * signing key) when there is one, else the generated project; then the code written so far on top.
     */
    private suspend fun ensureLocalCopy(repo: GitHubClient.Repo, slug: String) {
        val ctx = context ?: return
        if (repo.owner != LOCAL_OWNER && req.githubToken.isNotBlank()) {
            step(BuildEvent.Kind.DOWNLOAD, "Copying ${repo.owner}/${repo.name} to the phone for the phone build")
            runCatching {
                withContext(Dispatchers.IO) {
                    val reqZip = okhttp3.Request.Builder().url("https://api.github.com/repos/${repo.owner}/${repo.name}/zipball/${repo.branch}")
                        .header("Authorization", "Bearer ${req.githubToken}").build()
                    Http.client.newCall(reqZip).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("GitHub: HTTP ${r.code}")
                        val all = linkedMapOf<String, ByteArray?>()
                        java.util.zip.ZipInputStream(r.body!!.byteStream()).use { z ->
                            while (true) {
                                val e = z.nextEntry ?: break
                                if (!e.isDirectory) {
                                    val path = e.name.substringAfter('/')
                                    if (path.isNotBlank()) all[path] = z.readBytes()
                                }
                            }
                        }
                        LocalBuild.write(ctx, slug, all)
                    }
                }
            }.onFailure { step(BuildEvent.Kind.ERROR, "Couldn't copy the repository to the phone: ${it.message}") }
        } else if (!imported) {
            LocalBuild.write(ctx, slug, ProjectTemplate.files(appName, packageName, deps.toList()).mapValues { it.value.toByteArray() } +
                mapOf("app/signing.keystore" to android.util.Base64.decode(ProjectTemplate.SHARED_KEYSTORE_B64, android.util.Base64.DEFAULT)))
        }
        LocalBuild.write(ctx, slug, files.mapValues { it.value.toByteArray() })
    }

    /** The model wrote the code; the AI helper reviews it, and the model fixes what the helper found. */
    private suspend fun helperReview() {
        val ctx = context ?: return
        val changed = pendingFiles.keys.filter { files[it] != null }.take(20)
        val code = changed.joinToString("\n\n") { p -> "=== $p ===\n" + files[p].orEmpty().take(6_000) }.take(40_000)
        step(BuildEvent.Kind.THINKING, "Asking the AI helper to review the code")
        if (com.arin.selffix.ClaudeHelper.route(ctx, helperTab) == null && com.arin.selffix.ClaudeHelper.route(ctx) == null) return
        val help = com.arin.selffix.ClaudeHelper.ask(ctx,
            // Only the boss's own code — never the person's request (helper-sees-only-the-boss).
            projectNote() + "NEED: review this code before the build: list only real bugs/build errors, each as FILE + the " +
                "patched code. If it's all right, answer just: OK\n\nCODE:\n$code",
            source = helperTab)
        if (help.contains("couldn't be reached") || help.contains("isn't connected") || help.contains("switched off")) return
        if (help.trim().let { it == "OK" || it.startsWith("OK.") || it.contains("NO_HELP_NEEDED") }) return
        step(BuildEvent.Kind.THINKING, "Fixing what the AI helper found")
        apply(ask(buildString {
            appendLine("The AI helper reviewed the code you wrote. Its review:")
            appendLine(help.take(10_000))
            appendLine()
            appendLine("Current files:")
            if (com.arin.selffix.CreditSaver.on(context)) appendFilesLean(help.take(2_000)) else appendFiles(help.take(300))
            appendLine()
            appendLine("Fix what is right in the review (keep everything that was asked). Reply with every file you change, in full, " +
                "in the same format" + (if (com.arin.selffix.CreditSaver.on(context)) " (or EDIT blocks)" else "") +
                "; if nothing needs changing, reply with just: NO CHANGES")
        }))
    }

    /** Directions sent during a build: change the code that's already written (the directions are added by [ask]). */
    private fun steerPrompt() = buildString {
        appendLine("Here is the current code of \"$appName\". It was being built when the owner sent new directions (listed at the end).")
        if (self) appendLine(com.arin.selffix.SelfUpdateRules.RULE_FOR_MODEL)
        appendLine()
        appendLine("Current files:")
        appendFiles(steered.joinToString(" "))
        appendLine()
        appendLine("Apply the owner's new directions exactly, and keep everything else as it is (nothing that was asked before may be dropped).")
        appendLine("Reply with every file you change or add, in full, in the same format. Files you don't send stay as they are.")
        appendLine("To delete a file, send it with the single line: DELETE")
    }

    /** The first compiler or Gradle error, cleaned up to search the web for it. */
    private fun mainError(errors: String): String {
        val line = errors.lines().map { it.trim() }.firstOrNull { it.startsWith("e: ") || it.contains("error:", true) || it.contains("What went wrong", true) || it.contains("Could not resolve", true) }
            ?: errors.lines().firstOrNull { it.isNotBlank() } ?: return ""
        return "android gradle " + line.removePrefix("e: ")
            .replace(Regex("""file:///\S+?:\d+:\d+\s*"""), "")
            .replace(Regex("""(/[\w.@+-]+){2,}"""), " ")
            .replace(Regex("""\s+"""), " ").trim().take(150)
    }

    private fun fixPrompt(errors: String, notes: List<String>, web: String = "") = buildString {
        appendLine(if (errors.startsWith("RUNTIME PROBLEM")) "The app below builds, but it CRASHES or won't run when opened on an Android emulator. " +
            "Find the cause in the crash log (the first 'Caused by' and the app's own lines in the stack trace) and fix it. " +
            "Typical causes: a missing permission or activity in the manifest, a null value, network on the main thread, a missing resource." else "The app below failed to build. Fix it.")
        appendLine("What the app must do (keep every feature; never fix an error by removing what was asked for):")
        appendLine(if (self) req.description else requirements.take(14_000))
        appendLine()
        appendLine("Build errors:")
        appendLine(if (com.arin.selffix.CreditSaver.on(context)) com.arin.selffix.CreditSaver.trimErrors(errors) else errors)
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("Notes from the build tool:")
            notes.forEach { appendLine("- $it") }
        }
        if (web.isNotBlank()) {
            appendLine()
            appendLine(com.arin.selffix.AutoFix.RULE_FOR_MODEL)
            if (self) appendLine(com.arin.selffix.SelfUpdateRules.RULE_FOR_MODEL)
            appendLine("I searched the internet for the main error. Results:")
            appendLine(web)
            appendLine("Use what fits (you may also <search> or <fetch> for more), and try a different approach than last time.")
        }
        appendLine()
        val saver = com.arin.selffix.CreditSaver.on(context)
        appendLine(if (saver) "Files involved (if you need another file in full, say which and it will be sent):" else "Current files:")
        if (saver) appendFilesLean(errors) else appendFiles(errors)
        appendLine()
        if (saver) {
            appendLine("For a small change, reply with EDIT blocks instead of whole files:")
            appendLine("=== EDIT: path ===\n<<<<<<< SEARCH\n(exact lines now in the file)\n=======\n(new lines)\n>>>>>>> REPLACE\n=== END EDIT ===")
            appendLine("For bigger changes send the whole file as before.")
        }
        appendLine("Reply with every file you change, in full, in the same format (or EDIT blocks). Files you don't send stay as they are.")
        appendLine("To delete a file, send it with the single line: DELETE")
    }

    /** Applies the model's output; returns notes to pass back to the model (skipped downloads etc.). */
    private suspend fun apply(output0: String): List<String> {
        val before = files.map { it.key + "#" + it.value.hashCode() }.toSet()
        val notes = applyFiles(output0)
        val now = files.map { it.key + "#" + it.value.hashCode() }
        val touched = now.filter { it !in before }.map { it.substringBeforeLast('#') }
        if (touched.isNotEmpty()) {
            codedThisRun = true
            touched.forEach { if (it !in lastChanged) lastChanged += it }
            if (notes.isNotEmpty()) lastNotes = notes
        }
        return notes
    }

    private suspend fun applyFiles(output0: String): List<String> {
        val notes = mutableListOf<String>()
        // Small edits ("replace these lines with these"): applied to the current file; if one doesn't match, the model is
        // asked for the whole file instead — nothing breaks to save tokens.
        var output = output0
        Regex("(?s)=== EDIT: (.+?) ===\\s*\\n(.*?)=== END EDIT ===").findAll(output0).forEach { m ->
            val path = normalizePath(m.groupValues[1].trim('`', ' '))
            var content = files[path]
            if (content == null) { notes += "Edit for $path: no such file — send it in full."; return@forEach }
            Regex("(?s)<<<<<<< SEARCH\\n(.*?)\\n=======\\n(.*?)\\n>>>>>>> REPLACE").findAll(m.groupValues[2]).forEach { b ->
                val from = b.groupValues[1]
                if (content!!.contains(from)) content = content!!.replaceFirst(from, b.groupValues[2])
                else notes += "An edit for $path didn't match its current text — send the whole file instead."
            }
            if (content != files[path]) { files[path] = content!!; pendingFiles[path] = content!!.toByteArray(); step(BuildEvent.Kind.FILE, "Edited ${path.substringAfterLast('/')}") }
            output = output.replace(m.value, "")
        }
        val parsed = parse(output)
        if (parsed.truncated) notes += "Your last reply was cut off. Send fewer or shorter files."
        parsed.files.map { (p, c) -> normalizePath(p) to c }.forEach { (path, content) ->
            val allowed = if (imported) !path.startsWith(".github/") && !path.endsWith(".jar") else path.startsWith("app/src/main/")
            if (!allowed || path.contains("..") || path.startsWith("/")) {
                notes += if (imported) "Ignored $path: .github/ and the wrapper jar can't be changed." else "Ignored $path: only files under app/src/main/ are allowed."
            } else if (content.trim() == "DELETE") {
                if (files.remove(path) != null) pendingFiles[path] = null
            } else if (files[path] != content) {
                files[path] = content
                pendingFiles[path] = content.toByteArray()
            }
        }
        if (imported && parsed.deps.isNotEmpty()) notes += "Add libraries by editing the module's build.gradle(.kts) directly."
        if (!imported) parsed.deps.forEach { raw ->
            val d = raw.trim().removePrefix("ksp:").removePrefix("ksp ").removePrefix("implementation ").trim().trim('"', '(', ')')
            // Code generators (Room's compiler) are added through KSP, with its Gradle plugin.
            val generator = raw.trim().startsWith("ksp") || ProjectTemplate.kspArtifacts.any { d.contains(":$it") }
            val resolved = resolveDependency(d)
            if (resolved == null) {
                notes += "Dependency $d was not found on Maven; use a different library."
                return@forEach
            }
            val entry = if (generator) "ksp:$resolved" else resolved
            if (entry !in deps) {
                deps.removeAll { it.removePrefix("ksp:").substringBeforeLast(':') == resolved.substringBeforeLast(':') }
                deps += entry
                depsChanged = true
                step(BuildEvent.Kind.LIBRARY, "Added library $resolved")
            }
            if (generator && deps.none { it.startsWith("plugin:com.google.devtools.ksp:") }) {
                val ksp = resolveDependency("com.google.devtools.ksp:symbol-processing-gradle-plugin:latest")?.substringAfterLast(':')
                    ?.takeIf { v -> v.none { it.isLetter() } } // a finished release, not a beta or RC
                deps += "plugin:com.google.devtools.ksp:" + (ksp ?: ProjectTemplate.KSP)
                depsChanged = true
            }
            if (resolved.startsWith("androidx.room:room-compiler:")) {
                val runtime = "androidx.room:room-runtime:" + resolved.substringAfterLast(':')
                if (deps.none { it.substringBeforeLast(':') == "androidx.room:room-runtime" }) {
                    deps += runtime
                    depsChanged = true
                }
            }
        }
        parsed.downloads.forEach { (url, path) -> download(url, path)?.let { notes += it } }
        // ARIN-PROTECTED: self-updates change only Arin, keep its id, key and protected rules, and raise the version.
        if (self && selfOriginal.isNotEmpty()) {
            com.arin.selffix.SelfUpdateRules.enforce(selfOriginal, files, pendingFiles, req.unlockProtected).forEach {
                notes += it
                step(BuildEvent.Kind.STEP, it)
            }
        }
        return notes
    }

    private suspend fun waitForRun(repo: GitHubClient.Repo, sha: String): GitHubClient.Run? {
        var run: GitHubClient.Run? = null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 35 * 60_000) {
            run = runCatching { gh.runFor(repo, sha, workflowFile) }.getOrNull() ?: run
            if (run == null && System.currentTimeMillis() - start > 3 * 60_000) return null
            if (run?.status == "completed") return run
            // A direction from the person: stop waiting and apply it now (then build again).
            for (i in 0 until 10) {
                if (hasSteering()) { steerNow = true; return run }
                delay(1_000)
            }
        }
        return run
    }

    // ---- Internet access: libraries and files ----

    private val repos = listOf(
        "https://dl.google.com/android/maven2",
        "https://repo1.maven.org/maven2",
        "https://jitpack.io",
    )

    private val safeHosts = listOf(
        "github.com", "raw.githubusercontent.com", "githubusercontent.com", "fonts.google.com",
        "fonts.gstatic.com", "upload.wikimedia.org", "commons.wikimedia.org", "repo1.maven.org",
        "dl.google.com", "jitpack.io", "unpkg.com", "cdn.jsdelivr.net",
    )

    private suspend fun httpText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }

    /** Checks the library exists; replaces "latest", "+" or a missing version with the newest release. */
    private suspend fun resolveDependency(coord: String): String? {
        val parts = coord.trim().split(":")
        if (parts.size < 2) return null
        val (group, artifact) = parts
        val version = parts.getOrNull(2)?.trim().orEmpty()
        val path = group.replace('.', '/') + "/" + artifact
        for (base in repos) {
            if (version.isNotEmpty() && version != "latest" && !version.contains('+')) {
                if (httpText("$base/$path/$version/$artifact-$version.pom") != null) return "$group:$artifact:$version"
            }
            val meta = httpText("$base/$path/maven-metadata.xml") ?: continue
            val latest = Regex("<release>([^<]+)</release>").find(meta)?.groupValues?.get(1)
                ?: Regex("<latest>([^<]+)</latest>").find(meta)?.groupValues?.get(1)
                ?: Regex("<version>([^<]+)</version>").findAll(meta).lastOrNull()?.groupValues?.get(1)
            if (latest != null) return "$group:$artifact:$latest"
        }
        return null
    }

    private val resourceName = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9]+)?$")

    /** Downloads a file into the project; returns a note for the model if it was skipped. */
    private suspend fun download(url: String, path: String): String? {
        val okPath = path.startsWith("app/src/main/res/raw/") || path.startsWith("app/src/main/res/drawable/") ||
            path.startsWith("app/src/main/res/font/") || path.startsWith("app/src/main/assets/")
        if (!okPath || path.contains("..")) return "Skipped download to $path: use res/raw, res/drawable, res/font or assets."
        if (!path.startsWith("app/src/main/assets/") && !resourceName.matches(path.substringAfterLast('/'))) {
            return "Skipped $path: resource names must be lowercase letters, digits and underscores."
        }
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host == null || !url.startsWith("https://")) return "Skipped $url: only https links work."
        val safe = safeHosts.any { host == it || host.endsWith(".$it") }
        if (!safe && !req.allowOtherSites) {
            step(BuildEvent.Kind.DOWNLOAD, "Blocked download from $host (turn on \"Allow other sites\")")
            return "Download from $host was blocked. Use a file from GitHub, Google Fonts or Wikimedia, or draw it in code."
        }
        step(BuildEvent.Kind.DOWNLOAD, "Downloading ${path.substringAfterLast('/')} from $host")
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val len = r.body?.contentLength() ?: -1
                    if (len > MAX_DOWNLOAD) return@use null
                    r.body?.bytes()?.takeIf { it.size <= MAX_DOWNLOAD }
                }
            }.getOrNull()
        } ?: return "Download failed or was too large (max 15 MB): $url"
        pendingFiles[path] = bytes
        return null
    }

    /**
     * Puts files the model named loosely where they belong in a new app: "MainActivity.kt" or
     * "com/x/Foo.kt" go to the source folder, "src/main/…" gets "app/", "res/…" and the manifest go under app/src/main.
     */
    private fun normalizePath(raw: String): String {
        val p = raw.trim().trim('`', '*', '"', '\'', ' ').removePrefix("./").removePrefix("/")
        if (imported || p.startsWith("app/src/main/")) return p
        val src = "app/src/main/java/${packageName.replace('.', '/')}"
        return when {
            p.startsWith("src/main/") -> "app/$p"
            p.startsWith("main/") -> "app/src/$p"
            p == "AndroidManifest.xml" || p.endsWith("/AndroidManifest.xml") -> "app/src/main/AndroidManifest.xml"
            p.startsWith("res/") -> "app/src/main/$p"
            p.startsWith("java/") || p.startsWith("kotlin/") -> "app/src/main/java/" + p.substringAfter('/')
            p.endsWith(".kt") || p.endsWith(".java") -> {
                // Keep sub-packages under the app's package, e.g. "ui/Theme.kt" or "com/x/app/ui/Theme.kt".
                val inPkg = p.substringAfter(packageName.replace('.', '/') + "/", p)
                if ('/' in inPkg && !inPkg.startsWith("app/")) "$src/$inPkg" else "$src/${p.substringAfterLast('/')}"
            }
            else -> p
        }
    }

    // ---- Output parsing ----

    data class Parsed(
        val files: List<Pair<String, String>>,
        val deps: List<String>,
        val downloads: List<Pair<String, String>>,
        val truncated: Boolean,
    )

    companion object {
        private const val MAX_DOWNLOAD = 15L * 1024 * 1024

        /** Arin's own workflow and the tag prefix of the releases it publishes. */
        const val SELF_WORKFLOW = "build-apk.yml"
        /** Result message when the code is written and the owner must choose where to build ("CODE_READY:" + changed files). */
        const val CODE_READY = "CODE_READY:"
        const val SELF_TAG_PREFIX = "arin-"
        /** Owner name used for projects that live only on the phone (no GitHub). */
        const val LOCAL_OWNER = "phone"
        private const val MAX_WEB_STEPS = 3
        private val fileStart = Regex("^=+\\s*FILE:\\s*(.+?)\\s*=*\\s*$")
        private val fileEnd = Regex("^=+\\s*END FILE\\s*=*\\s*$")
        private val moreLine = Regex("(?m)^=+\\s*MORE\\s*=+\\s*$")
        private val aboutLine = Regex("^=+\\s*ABOUT:\\s*(.+?)\\s*=*\\s*$")

        private val codeish = Regex("""[{};=<>()\[\]]|\b(fun|val|var|import|class|return|@Composable)\b""")

        /** The model's thinking without the code in it, so progress reads as plain English. */
        fun proseOnly(text: String): String {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }
            val prose = lines.filter { l -> codeish.findAll(l).count() * 12 < l.length.coerceAtLeast(1) && l.count { it.isLetter() } > l.length / 2 }
            return prose.joinToString(" ").ifBlank { "working out the code" }
        }

        /** The main build error, said simply. */
        fun plainError(errors: String): String {
            val e = errors.lines().firstOrNull { it.startsWith("e: ") || it.contains("error:", true) } ?: errors.lines().firstOrNull { it.isNotBlank() }.orEmpty()
            val where = Regex("""([A-Za-z0-9_]+\.(kt|java|xml))""").find(e)?.groupValues?.get(1)?.let { " in $it" } ?: ""
            val name = Regex("""Unresolved reference:?\s*'?([A-Za-z0-9_]+)""").find(e)?.groupValues?.get(1)
            return when {
                name != null -> "the code uses \"$name\"$where, which doesn't exist or isn't imported"
                e.contains("Type mismatch", true) || e.contains("Argument type mismatch", true) -> "a value has the wrong type$where"
                e.contains("No value passed for parameter", true) -> "something is called without all the information it needs$where"
                e.contains("Could not resolve", true) || e.contains("Could not find", true) -> "a library couldn't be downloaded (wrong name or version)"
                e.contains("@Composable", true) -> "screen code is called from a place that can't draw screens$where"
                e.contains("resource", true) || e.contains("AAPT", true) -> "a picture, color, text or other resource is missing or broken$where"
                e.contains("Manifest merger", true) -> "the app's Android settings (manifest) conflict"
                e.contains("Expecting", true) || e.contains("Syntax", true) -> "the code has a typing mistake (like a missing bracket)$where"
                e.contains("Overload resolution ambiguity", true) || e.contains("Conflicting", true) -> "two things have the same name$where"
                e.contains("OutOfMemory", true) || e.contains("heap space", true) -> "the build ran out of memory"
                else -> "a problem$where"
            }
        }

        /** A plain-English guess at what a file is for, when the model didn't say. */
        fun plainEnglish(path: String): String {
            val file = path.substringAfterLast('/')
            val name = file.substringBeforeLast('.')
            val words = name.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').lowercase()
            return when {
                file == "MainActivity.kt" || file == "MainActivity.java" -> "The app's starting point: opens the first screen when you tap the icon"
                file == "AndroidManifest.xml" -> "The app's settings for Android: its name, icon, screens and permissions"
                file.startsWith("build.gradle") || file.startsWith("settings.gradle") || file == "libs.versions.toml" -> "The build setup: which tools and ready-made libraries the app uses"
                file == "strings.xml" -> "The words and labels shown in the app"
                file == "colors.xml" || file == "themes.xml" || name in setOf("Color", "Theme", "Type") -> "The app's look: colors, fonts and style"
                name.endsWith("Screen") -> "The ${words.removeSuffix(" screen")} screen: what you see and tap there"
                name.endsWith("ViewModel") -> "The brain behind the ${words.removeSuffix(" view model")} screen: keeps its data and reacts to your taps"
                name.endsWith("Dao") || name.endsWith("Database") || name.endsWith("Repository") || name.endsWith("Store") -> "Saving and loading the app's data on the phone"
                name.endsWith("Api") || name.endsWith("Client") || name.endsWith("Service") -> "Talking to the internet or running work in the background (${words})"
                path.contains("/model/") || path.contains("/data/") -> "The kind of information the app keeps: $words"
                path.contains("/res/drawable") || path.contains("/res/mipmap") -> "A picture or icon used in the app"
                path.contains("/res/raw") || path.contains("/assets/") -> "A media or data file the app uses ($file)"
                file.endsWith(".kt") || file.endsWith(".java") -> "Code for the $words part of the app"
                else -> "Supporting file: $file"
            }
        }
        private val depLine = Regex("^=+\\s*DEPENDENCY:\\s*(.+?)\\s*=*\\s*$")
        private val downloadLine = Regex("^=+\\s*DOWNLOAD:\\s*(\\S+)\\s*->\\s*(\\S+?)\\s*=*\\s*$")

        fun parse(text: String): Parsed {
            val files = mutableListOf<Pair<String, String>>()
            val deps = mutableListOf<String>()
            val downloads = mutableListOf<Pair<String, String>>()
            var path: String? = null
            val body = StringBuilder()
            for (raw in text.lines()) {
                val line = raw.trim()
                val current = path
                if (current == null) {
                    val start = fileStart.find(line)
                    val dep = depLine.find(line)
                    val dl = downloadLine.find(line)
                    when {
                        start != null -> {
                            path = start.groupValues[1].trim('`', ' ')
                            body.clear()
                        }
                        dep != null -> deps.add(dep.groupValues[1].trim('`', ' '))
                        dl != null -> downloads.add(dl.groupValues[1] to dl.groupValues[2])
                    }
                } else if (fileEnd.matches(line)) {
                    files.add(current to stripFences(body.toString()))
                    path = null
                } else {
                    body.appendLine(raw)
                }
            }
            if (files.isEmpty()) {
                // No "=== FILE:" blocks: many models write Markdown instead. Read those, so nothing is lost.
                val md = parseMarkdown(text)
                if (md.isNotEmpty()) return Parsed(md, deps, downloads, truncated = false)
            }
            return Parsed(files, deps, downloads, truncated = path != null)
        }

        private val fence = Regex("^\\s*(`{3,}|~{3,})\\s*([A-Za-z0-9_+#.-]*)\\s*(.*)$")
        private val pathLike = Regex("""([A-Za-z0-9_./-]+\.(?:kt|kts|java|xml|gradle|json|txt|properties|toml|pro|md|html|css|js))\b""")

        /**
         * Files written as Markdown code blocks, with the file's path in a heading, bold text, "File:" line or
         * backticks just above the block, in the opening fence (```kotlin title="path" or ```kotlin:path), or as a
         * comment on the block's first line (// path, <!-- path -->, # path).
         */
        fun parseMarkdown(text: String): List<Pair<String, String>> {
            val lines = text.lines()
            val out = linkedMapOf<String, String>()
            var i = 0
            while (i < lines.size) {
                val open = fence.find(lines[i])
                if (open == null) { i++; continue }
                val marker = open.groupValues[1]
                // Collect the block.
                var j = i + 1
                val body = mutableListOf<String>()
                while (j < lines.size && !lines[j].trim().startsWith(marker.take(3))) { body += lines[j]; j++ }
                // Where's the path? Opening fence, then the 3 lines above, then the first line of the block.
                var path = pathLike.find(open.groupValues[3])?.groupValues?.get(1)
                    ?: pathLike.find(open.groupValues[2].substringAfter(':', ""))?.groupValues?.get(1)
                if (path == null) {
                    for (k in (i - 1) downTo maxOf(0, i - 3)) {
                        val above = lines[k].trim()
                        if (above.isEmpty()) continue
                        if (fence.matches(above)) break
                        path = pathLike.findAll(above).map { it.groupValues[1] }.lastOrNull { '/' in it || '.' in it }
                        if (path != null) break
                    }
                }
                if (path == null && body.isNotEmpty()) {
                    val first = body.first().trim()
                    if (first.startsWith("//") || first.startsWith("<!--") || first.startsWith("#")) {
                        path = pathLike.find(first)?.groupValues?.get(1)
                        if (path != null) body.removeAt(0)
                    }
                }
                val content = body.joinToString("\n").trimEnd()
                if (path != null && content.isNotBlank() && !path.startsWith("http")) out[path] = content + "\n"
                i = j + 1
            }
            return out.toList()
        }

        /** Small models often wrap code in ``` fences inside the file block. */
        private fun stripFences(s: String): String {
            val lines = s.trimEnd().lines().toMutableList()
            while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
            if (lines.firstOrNull()?.trim()?.startsWith("```") == true) lines.removeAt(0)
            if (lines.lastOrNull()?.trim() == "```") lines.removeAt(lines.lastIndex)
            return lines.joinToString("\n") + "\n"
        }
    }
}
