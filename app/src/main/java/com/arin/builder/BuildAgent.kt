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
    /** Change Sanjoyingti itself: edit [selfRepo] on GitHub and build it with Sanjoyingti's own workflow. */
    val selfUpdate: Boolean = false,
    /** Sanjoyingti's source: owner/repo and branch. */
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
    /** [requirements] already covers earlier requests too (a chat), so it replaces what the project has saved. */
    val requirementsComplete: Boolean = false,
    /** Stop once the code is written, so the app can be tried (View app) before it's compiled into an APK. */
    val previewFirst: Boolean = false,
    /** A new fixing pass on a build that ran out of fix rounds: it gets a full set of rounds again. */
    val continueFixing: Boolean = false,
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
    /** The phone-built APK in Sanjoyingti's storage, ready for Android's installer. */
    val phoneInstallPath: String? = null,
    /** The Codespace opened for this project, if any. */
    val codespaceUrl: String? = null,
    /** Save the APK to the phone automatically (used for codespace builds). */
    val autoDownloadApk: Boolean = false,
    /** Nothing was compiled: the code is written and waits for "View app", then "Build APK". */
    val awaitingPreview: Boolean = false,
    /** It stopped because of an error in Sanjoyingti's own code (a crash), not in the app being built. */
    val internal: Boolean = false,
)

/**
 * Build Mode agent: the model writes or edits the app, GitHub Actions builds it, and build errors
 * go back to the model to fix, until the APK builds or the fix rounds for the Brain level run out.
 */
class BuildAgent(
    private val req: BuildRequest,
    private val emit: (BuildEvent) -> Unit,
    /** Called whenever the project changes, so it can be saved. */
    private val onProject: (Project) -> Unit = {},
    /** Live progress for long steps (null = done), with a short description. */
    private val onProgress: (Float?, String) -> Unit = { _, _ -> },
    /** For building on the phone in the built-in Linux, when its Android build tools are installed. */
    private val context: android.content.Context? = null,
) {
    private val gh = GitHubClient(req.githubToken)
    private val slug = (req.project?.appName ?: req.appName).lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "app" }.take(30)
    /** The package the person asked for ("package com.example.calendar"), else one made from the app name. */
    private val packageName = req.project?.packageName
        ?: Regex("""(?i)\bpackage(?:\s+name)?\s*[:=]?\s*`?([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,5})`?""")
            .find(req.description)?.groupValues?.get(1)?.lowercase()
        ?: ("com.aibuilt." + (if (slug.first().isLetter()) slug else "a$slug"))
    private val appName get() = req.project?.appName ?: importedName ?: req.appName

    /** Updating Sanjoyingti itself (its own repo, workflow and signing key). */
    private val self = req.selfUpdate || req.project?.selfUpdate == true
    private val imported = req.project?.imported == true || req.zipPath != null || self
    private val workflowFile = when {
        self -> SELF_WORKFLOW
        imported -> ProjectTemplate.IMPORT_WORKFLOW_FILE
        else -> "build.yml"
    }
    private var importedName: String? = null
    private var uploadedOnce = false
    private var currentSha: String? = req.project?.lastSha
    private var currentRound = 0
    /** True while this run ends with the code written but not compiled (waiting for View app → Build APK). */
    private var paused = false
    /** The project's repository hasn't been made yet: where it's compiled is chosen after the code is written. */
    private val pendingRepo = req.project?.owner == PENDING_OWNER
    /** This run only writes code and stops, so it needs no GitHub and makes no repository. */
    private val writeOnly = req.previewFirst && !req.resume && !self
    /** Files of an uploaded zip that were deleted before its first upload: left out when the zip is read again. */
    private val droppedFromZip = linkedSetOf<String>()

    private val files = linkedMapOf<String, String>().apply { req.project?.files?.let { putAll(it) } }
    private val deps = linkedSetOf<String>().apply { req.project?.deps?.let { addAll(it) } }

    /** Files changed since the last commit (null value = delete). */
    private val pendingFiles = linkedMapOf<String, ByteArray?>()
    /** Big uploaded files waiting to be committed straight from storage. */
    private val pendingDisk = linkedMapOf<String, java.io.File>()
    private var depsChanged = false
    /** gradle.properties was brought up to date in this run. */
    private var propsChecked = false
    /** Times the model-free quick fix was used in this run (it never loops for ever). */
    private var quickFixes = 0

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

    private val maxFixRounds: Int = when (req.effort) {
        Effort.LOW -> 1
        Effort.MEDIUM, Effort.AUTO -> 3
        Effort.HIGH -> 6
        Effort.MAX -> 10
    } + if (false) 2 else 0

    private fun step(kind: BuildEvent.Kind, text: String) = emit(BuildEvent(kind, text))

    /** The model's last answer, kept so a failure report can show what it sent. */
    private var lastReply = ""

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
    private var localOnly = req.project?.owner == LOCAL_OWNER || (req.localOnly && !self)

    private fun localRepo(name: String) = GitHubClient.Repo(LOCAL_OWNER, name, "main", "")

    /**
     * Makes the repository chosen after the code was written (GitHub, or the phone only), and opens a Codespace
     * on it when that was picked. A phone-only build needs no GitHub sign-in.
     */
    private suspend fun makeRepo(name: String, description: String): GitHubClient.Repo {
        // A compile run already checked GitHub when it started; a write-only run didn't.
        if (!localOnly && writeOnly) {
            step(BuildEvent.Kind.STEP, "Checking your GitHub token")
            try {
                if (req.githubToken.isBlank()) throw ProviderException(401, "Not signed in to GitHub")
                gh.login()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!phoneCan) throw e
                step(BuildEvent.Kind.STEP, "GitHub isn't available (${e.message}); building on this phone only")
                localOnly = true
            }
        }
        val repo = createRepoOrLocal(name, description)
        if (req.codespace && !localOnly) {
            pendingFiles[DEVCONTAINER] = ProjectTemplate.devcontainer.toByteArray()
            step(BuildEvent.Kind.STEP, "Opening a GitHub Codespace on the project")
            codespaceUrl = gh.createCodespace(repo) ?: gh.codespaceLink(repo)
        }
        return repo
    }

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

    /** Where files that can't be rebuilt from text (pictures, fonts, downloads) wait while a build is paused for its preview. */
    private fun pendingStore(repoName: String): java.io.File? =
        context?.let { java.io.File(it.filesDir, "build_pending/" + repoName.replace(Regex("[^A-Za-z0-9._-]"), "_")) }

    /** The text of a changed file that hasn't reached GitHub yet, if it can be rebuilt. */
    private fun pendingText(path: String, template: Map<String, String>): String? = files[path] ?: template[path] ?: when {
        imported -> null
        path == MANIFEST -> ProjectTemplate.defaultManifest(appName)
        path == DEVCONTAINER -> ProjectTemplate.devcontainer
        else -> null
    }

    /**
     * Puts back the changes of [existing] that were written but never uploaded (a build that stopped, or one
     * waiting for "Build APK"). Changes already made in this run win. Returns how many were put back.
     */
    private fun restorePending(existing: Project): Int {
        val template = if (!imported) ProjectTemplate.files(appName, packageName, deps.toList()) else emptyMap()
        val store = pendingStore(existing.repo)
        var count = 0
        existing.pendingPaths.forEach { p ->
            if (p in pendingFiles) return@forEach
            if (p in existing.pendingDeletes) {
                // Deleting only matters for files GitHub already has.
                if (existing.lastSha != null) { pendingFiles[p] = null; count++ }
                return@forEach
            }
            val bytes = pendingText(p, template)?.toByteArray()
                ?: store?.let { java.io.File(it, p) }?.takeIf { it.isFile }?.let { f -> runCatching { f.readBytes() }.getOrNull() }
            if (bytes != null) { pendingFiles[p] = bytes; count++ }
        }
        // Nothing of this project is on GitHub yet, so there is nothing to delete there.
        if (existing.lastSha == null && !existing.uploaded) pendingFiles.entries.removeAll { it.value == null }
        return count
    }

    /**
     * Stops before compiling when the request asked to see the app first. Returns the result to hand back,
     * or null when this build can't wait (then it carries on and compiles).
     */
    private suspend fun pauseForPreview(repo: GitHubClient.Repo, zipKept: Boolean): BuildResult? {
        if (!req.previewFirst || req.resume || self) return null
        if (!zipKept) {
            val template = if (!imported) ProjectTemplate.files(appName, packageName, deps.toList()) else emptyMap()
            val binary = pendingFiles.filter { (p, bytes) -> bytes != null && pendingText(p, template) == null }
            val store = pendingStore(repo.name)
            val kept = pendingDisk.isEmpty() && (binary.isEmpty() || store != null && runCatching {
                store.deleteRecursively()
                binary.forEach { (p, bytes) ->
                    java.io.File(store, p).apply { parentFile?.mkdirs() }.writeBytes(bytes!!)
                }
            }.isSuccess)
            if (!kept) {
                step(BuildEvent.Kind.STEP, "Not stopping for a preview: this build has files that have to be uploaded right away")
                return null
            }
        } else {
            // The zip is read again when the build continues; its unpacked big files aren't needed until then.
            pendingDisk.values.forEach { it.delete() }
        }
        paused = true
        saveProject(repo, null)
        step(BuildEvent.Kind.DONE, "The code is ready (${files.size} file${if (files.size == 1) "" else "s"}). Nothing is compiled yet")
        return BuildResult(
            true,
            "The code is ready, but not compiled yet. Choose below: make a preview first, or compile it directly.",
            awaitingPreview = true,
        )
    }

    suspend fun run(): BuildResult {
        if (!localOnly && !writeOnly) {
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
        var repo: GitHubClient.Repo
        var notes: List<String>
        val neverUploaded = existing != null && existing.imported && !existing.uploaded && existing.lastApkUrl == null
        val resumeZip = existing?.takeIf { neverUploaded }?.zipPath?.takeIf { java.io.File(it).exists() }
        val zipToRead = req.zipPath ?: resumeZip
        var checkSha: String? = null
        var startRound = 0
        if (req.resume && existing != null && resumeZip == null) {
            // Retry: continue from the saved checkpoint instead of starting over.
            repo = if (pendingRepo) makeRepo(existing.repo, "Built by Sanjoyingti: ${existing.description.take(200)}")
            else GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            if (!pendingRepo && req.codespace && !localOnly && existing.owner != LOCAL_OWNER) {
                step(BuildEvent.Kind.STEP, "Opening a GitHub Codespace on the project")
                codespaceUrl = gh.createCodespace(repo) ?: gh.codespaceLink(repo)
            }
            notes = emptyList()
            startRound = if (req.continueFixing) 0 else existing.round
            val restored = restorePending(existing)
            when {
                restored > 0 && existing.awaitingPreview ->
                    step(BuildEvent.Kind.STEP, "Compiling the code you looked at ($restored file${if (restored == 1) "" else "s"})")
                restored > 0 ->
                    step(BuildEvent.Kind.STEP, "Resuming: uploading $restored file(s) that didn't reach GitHub last time")
                existing.lastSha != null && !existing.awaitingPreview -> {
                    checkSha = existing.lastSha
                    step(BuildEvent.Kind.STEP, "Resuming: checking the last build on GitHub (no code is rewritten)")
                }
                else -> step(BuildEvent.Kind.STEP, "Resuming: starting a fresh build of the current code")
            }
        } else if ((existing == null && req.zipPath != null) || resumeZip != null) {
            val zipLabel = req.zipName ?: existing?.zipName ?: "the project"
            val viewedFirst = resumeZip != null && existing?.awaitingPreview == true
            if (resumeZip != null && !viewedFirst) step(BuildEvent.Kind.STEP, "The first upload didn't finish — uploading the whole project again")
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
            repo = when {
                writeOnly && (existing == null || pendingRepo) -> GitHubClient.Repo(PENDING_OWNER, existing?.repo ?: repoName, "main", "")
                existing != null && pendingRepo -> makeRepo(existing.repo, "Uploaded to Sanjoyingti: ${zip.name}")
                existing != null -> GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
                else -> createRepoOrLocal(repoName, "Uploaded to Sanjoyingti: ${zip.name}")
            }
            notes = emptyList()
            if (viewedFirst && existing != null) {
                // Changes made while the project waited for its preview are newer than what's in the zip.
                existing.files.forEach { (p, text) ->
                    if (files[p] != text) { files[p] = text; pendingFiles[p] = text.toByteArray() }
                }
                droppedFromZip += existing.pendingDeletes
                droppedFromZip.forEach { p -> files.remove(p); pendingFiles.remove(p); pendingDisk.remove(p) }
                if (!req.resume && req.description.isNotBlank()) {
                    step(BuildEvent.Kind.THINKING, "Working out the changes with ${req.provider.label}")
                    val assets = addAttachedAssets()
                    notes = apply(ask(changePrompt() + assets, req.attachments))
                    // The project isn't on GitHub yet: a deleted file is simply left out of the upload.
                    droppedFromZip += pendingFiles.filterValues { it == null }.keys
                    pendingFiles.entries.removeAll { it.value == null }
                }
            }
        } else if (self) {
            // Sanjoyingti updating itself: read its source from GitHub, change it, build it with its own workflow.
            val (owner, name) = req.selfRepo.split('/').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            if (owner.isBlank() || name.isBlank()) return BuildResult(false, "Set Sanjoyingti's source repository in Settings → GitHub first.")
            repo = GitHubClient.Repo(owner, name, req.selfBranch.ifBlank { "main" }, "https://github.com/$owner/$name/tree/${req.selfBranch}")
            importedName = "Sanjoyingti"
            step(BuildEvent.Kind.STEP, "Reading Sanjoyingti's source from ${req.selfRepo} (${repo.branch})")
            onProgress(-1f, "Downloading source files…")
            files.clear()
            files.putAll(gh.readTextFiles(repo))
            onProgress(null, "")
            if (files.isEmpty()) return BuildResult(false, "Couldn't read Sanjoyingti's source from ${req.selfRepo}@${repo.branch}. Check the repository in Settings → GitHub.")
            step(BuildEvent.Kind.DONE, "Read ${files.size} source files")
            step(BuildEvent.Kind.THINKING, "Working out the changes to Sanjoyingti with ${req.provider.label}")
            val assets = addAttachedAssets()
            notes = apply(ask(changePrompt() + assets, req.attachments))
            if (pendingFiles.isEmpty() && !depsChanged) {
                return BuildResult(false, "The model didn't change any of Sanjoyingti's files. Try describing the change differently.", repo.url, log = modelReplyLog())
            }
            uploadedOnce = true
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
            repo = if (writeOnly) GitHubClient.Repo(PENDING_OWNER, repoName, "main", "")
            else createRepoOrLocal(repoName, "Built by Sanjoyingti: ${req.description}")
            ProjectTemplate.files(appName, packageName, deps.toList()).forEach { (p, c) -> pendingFiles[p] = c.toByteArray() }
            if (req.codespace && !localOnly && !writeOnly) {
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
            // The earlier code was never compiled (it was waiting for its preview): it goes up together with this change.
            if (existing.awaitingPreview) restorePending(existing)
            if (!imported) {
                notes = finishWriting(notes)
                notes = reviewAgainstRequirements(notes)
            }
        }
        saveProject(repo, null)

        // Build on the phone first when Linux has the Android build tools: errors are found and fixed here,
        // and GitHub then only builds code that already compiles (and gives the download links).
        // Code written: stop here and let the person choose a preview and where to compile it.
        pauseForPreview(repo, zipKept = zipToRead != null && java.io.File(zipToRead).exists())?.let { return it }
        if (repo.owner == PENDING_OWNER) {
            // Couldn't stop for the questions: make the repository now, the usual way.
            repo = makeRepo(repo.name, "Built by Sanjoyingti: ${req.description.take(200)}")
        }
        val localSlug = repo.name
        val newCopy = existing == null || pendingRepo || !(context?.let { LocalBuild.hasCopy(it, localSlug) } ?: false)
        var useLocal = localOnly || phoneCan && (existing == null || pendingRepo || LocalBuild.hasCopy(context!!, localSlug))
        if (useLocal && newCopy) {
            LocalBuild.write(context!!, localSlug, files.mapValues { it.value.toByteArray() })
        }
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
            if (!imported && cleanDeps()) depsChanged = true
            if (depsChanged) {
                val gradle = ProjectTemplate.files(appName, packageName, deps.toList())
                pendingFiles["app/build.gradle.kts"] = gradle.getValue("app/build.gradle.kts").toByteArray()
                // The top-level file changes too when a code generator (KSP, for Room) is added.
                pendingFiles["build.gradle.kts"] = gradle.getValue("build.gradle.kts").toByteArray()
                depsChanged = false
            }
            // Projects made before Sanjoyingti knew it: KSP (Room) needs this setting with AGP 9, or nothing configures.
            if (!self && !imported && !propsChecked) {
                propsChecked = true
                val props = files["gradle.properties"]
                val updated = when {
                    props == null -> ProjectTemplate.files(appName, packageName, deps.toList())["gradle.properties"]
                    !props.contains("disallowKotlinSourceSets") -> props.trimEnd() + "\n" + ProjectTemplate.KSP_SOURCES_SETTING + "\n"
                    else -> null
                }
                if (updated != null) pendingFiles["gradle.properties"] = updated.toByteArray()
            }
            // Make sure GitHub always has the build instructions (harmless if unchanged).
            if (self) {
                // Sanjoyingti's repo already has its workflow and signing key: leave them alone.
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
                        val quick = quickFix(out.log)
                        if (quick.isNotEmpty() && quickFixes < MAX_QUICK_FIXES) {
                            quickFixes++
                            step(BuildEvent.Kind.DONE, "Fixed without the model: " + quick.joinToString("; "))
                            continue
                        }
                        if (localRounds < maxFixRounds) {
                            localRounds++
                            val query = mainError(out.log)
                            val web = if (query.isNotBlank()) {
                                step(BuildEvent.Kind.WEB, "Searching the web for the error: $query")
                                runCatching { WebTools.search(query) }.getOrNull()?.take(6_000).orEmpty()
                            } else ""
                            val count = out.log.lines().count { it.startsWith("e: ") || it.contains("error:", true) }.coerceAtLeast(1)
                            step(BuildEvent.Kind.ERROR, "Phone build failed with $count error${if (count == 1) "" else "s"}: ${plainError(out.log)}. Fixing on the phone (round $localRounds of $maxFixRounds)")
                            notes = apply(ask(fixPrompt(out.log, notes, web)))
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
                self -> "Sanjoyingti: ${req.description.take(60)}"
                existing != null -> "Change: ${req.description.take(60)}"
                req.zipPath != null -> "Uploaded project from Sanjoyingti"
                else -> "Initial app from Sanjoyingti"
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
            runCatching { pendingStore(repo.name)?.deleteRecursively() }
            saveProject(repo, null)
          }

            step(BuildEvent.Kind.BUILD, "Building the APK on GitHub (usually 3–5 minutes)")
            val run = waitForRun(repo, sha)
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
                step(BuildEvent.Kind.DONE, "Done")
                return BuildResult(
                    true,
                    when {
                        self -> "The new version of Sanjoyingti is ready. Tap Install to update."
                        existing != null -> "Your changes are built."
                        req.zipPath != null -> "Your project compiled to an APK."
                        else -> "Your app is ready."
                    },
                    repo.url,
                    apk,
                    phoneApk = phoneApk,
                    codespaceUrl = codespaceUrl,
                    autoDownloadApk = req.codespace,
                )
            }

            step(BuildEvent.Kind.STEP, "Reading the build errors")
            val errors = gh.failureSummary(repo, run.id)
            // Common mistakes (a forgotten import or library) are fixed here, without the model and without using a round.
            val quick = quickFix(errors)
            if (quick.isNotEmpty() && quickFixes < MAX_QUICK_FIXES) {
                quickFixes++
                step(BuildEvent.Kind.DONE, "Fixed without the model: " + quick.joinToString("; "))
                saveProject(repo, null)
                continue
            }
            if (round >= maxFixRounds) {
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
            val web = kotlin.run {
                val query = mainError(errors)
                if (query.isNotBlank()) {
                    step(BuildEvent.Kind.WEB, "Searching the web for the error: $query")
                    runCatching { WebTools.search(query) }.getOrNull()?.take(6_000).orEmpty()
                } else ""
            }
            val count = errors.lines().count { it.startsWith("e: ") || it.contains("error:", true) }.coerceAtLeast(1)
            step(BuildEvent.Kind.ERROR, "Build failed with $count error${if (count == 1) "" else "s"}: ${plainError(errors)}. Fixing (round $round of $maxFixRounds)")
            notes = apply(ask(fixPrompt(errors, notes, web)))
            saveProject(repo, null)
        }
    }

    private fun mb(bytes: Long) = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)

    private fun saveProject(repo: GitHubClient.Repo, apk: String?) = onProject(currentProject(repo, apk))

    /** The project as it stands now. */
    private fun currentProject(repo: GitHubClient.Repo, apk: String?): Project =
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
                pendingDeletes = (pendingFiles.filterValues { it == null }.keys + droppedFromZip).toList(),
                awaitingPreview = paused,
                round = currentRound,
            )

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
            ProjectTemplate.webInstructions
        val cap = if (false) 3_000 else 8_000
        val working = mutableListOf(ChatMessage(Role.USER, prompt, attachments))
        var steps = 0
        var toldToWrite = false
        while (true) {
            val sb = StringBuilder()
            val thinking = StringBuilder()
            var scanned = 0
            var about: String? = null
            resilient(
                attempt = { model -> streamModel(req.provider, req.apiKey, model, working.toList(), system) },
                model = req.model,
                fallback = { alternativeModel(req.provider, req.apiKey, req.model) },
                onRetry = { step(BuildEvent.Kind.STEP, it) },
            ).collect { raw ->
                if (raw.startsWith(THOUGHT)) {
                    // Show what the model is thinking, live, under the current step.
                    thinking.append(raw.substring(1))
                    // Only the newest part is shown, so only look at the end (the whole text grows with every word).
                    val now = System.currentTimeMillis()
                    if (now - lastThinkingNote > 400) {
                        lastThinkingNote = now
                        onProgress(-1f, "Thinking: " + proseOnly(thinking.takeLast(1_200).toString()).takeLast(160))
                    }
                    return@collect
                }
                if (thinking.isNotEmpty()) {
                    step(BuildEvent.Kind.THINKING, "Planned it: " + proseOnly(thinking.take(3_000).toString()).trim().take(160) + "…")
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
            }
            val reply = sb.toString()
            lastReply = reply
            if (reply.contains("=== FILE:")) return reply
            val search = WebTools.searchTag.find(reply)?.groupValues?.get(1)?.trim()
            val fetch = WebTools.fetchTag.find(reply)?.groupValues?.get(1)?.trim()
            if (steps >= MAX_WEB_STEPS) {
                // Out of lookups but still asking for one: tell it to write the code now (once), instead of giving up.
                if ((search == null && fetch == null) || toldToWrite) return reply
                toldToWrite = true
                step(BuildEvent.Kind.STEP, "Enough reading: asking the model to write the code with what it found")
                working += ChatMessage(Role.ASSISTANT, reply.trim())
                working += ChatMessage(
                    Role.USER,
                    "No more web lookups are available. Don't <search> or <fetch> anything else. Write the files now, " +
                        "in the required format, using what you already have.",
                )
                continue
            }
            val result = when {
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
        appendLine()
        append(sizeRule)
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

    private fun changePrompt() = buildString {
        if (self) {
            appendLine("Here is the source of Sanjoyingti itself: the Android AI assistant app you are running in (package com.arin, Kotlin + Jetpack Compose).")
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
        appendLine()
        appendLine("Reply with every file you change or add, in full, in the same format. Files you don't send stay as they are.")
        if (!self) appendLine(sizeRule)
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
        appendLine("The app below failed to build. Fix it.")
        appendLine("What the app must do (keep every feature; never fix an error by removing what was asked for):")
        appendLine(if (self) req.description else requirements.take(14_000))
        appendLine()
        appendLine("Build errors:")
        appendLine(errors)
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("Notes from the build tool:")
            notes.forEach { appendLine("- $it") }
        }
        if (web.isNotBlank()) {
            appendLine()
            appendLine("I searched the web for the main error. Results:")
            appendLine(web)
            appendLine("Use what fits (you may also <search> or <fetch> for more), and try a different approach than last time.")
        }
        appendLine()
        appendLine("Current files:")
        appendFiles(errors)
        appendLine()
        appendLine("Reply with every file you change, in full, in the same format. Files you don't send stay as they are.")
        appendLine("To delete a file, send it with the single line: DELETE")
    }

    /** Applies the model's output; returns notes to pass back to the model (skipped downloads etc.). */
    private suspend fun apply(output: String): List<String> {
        val notes = mutableListOf<String>()
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
        if (!imported) parsed.deps.forEach { raw -> addDependency(raw)?.let { notes += it } }
        parsed.downloads.forEach { (url, path) -> download(url, path)?.let { notes += it } }
        return notes
    }

    /** Adds a library to the app's build setup; returns a note for the model when it can't be found. */
    /**
     * Removes libraries that are really build tools (KSP itself, the Kotlin compiler or a Gradle plugin): added as
     * libraries they clash with the toolchain, and an old KSP version would be picked for the whole project.
     * Returns true when something changed.
     */
    private fun cleanDeps(): Boolean {
        val before = deps.toList()
        deps.removeAll { !it.startsWith("plugin:") && isToolchain(it.removePrefix("ksp:")) }
        val ksp = deps.firstOrNull { it.startsWith("plugin:com.google.devtools.ksp:") }
        if (ksp != null && !ksp.substringAfterLast(':').startsWith("2.")) {
            deps.remove(ksp)
            deps += "plugin:com.google.devtools.ksp:" + ProjectTemplate.KSP
        }
        val changed = deps.toList() != before
        if (changed) step(BuildEvent.Kind.STEP, "Removed build tools that were added as libraries (Sanjoyingti sets those up itself)")
        return changed
    }

    private fun isToolchain(coordinate: String): Boolean {
        val c = coordinate.trim()
        return c.startsWith("com.google.devtools.ksp:") || c.startsWith("org.jetbrains.kotlin:kotlin-compiler") ||
            c.startsWith("org.jetbrains.kotlin:kotlin-gradle-plugin") || c.startsWith("org.jetbrains.kotlin:kotlin-build-common") ||
            c.startsWith("com.android.tools.build:")
    }

    private suspend fun addDependency(raw: String): String? {
            val d = raw.trim().removePrefix("ksp:").removePrefix("ksp ").removePrefix("implementation ").trim().trim('"', '(', ')')
            if (isToolchain(d)) {
                return "Don't add $d: KSP, Kotlin and the Android Gradle plugin are set up by Sanjoyingti. For Room, add only " +
                    "androidx.room:room-runtime, androidx.room:room-ktx and ksp:androidx.room:room-compiler."
            }
            // Code generators (Room's compiler) are added through KSP, with its Gradle plugin.
            val generator = raw.trim().startsWith("ksp") || ProjectTemplate.kspArtifacts.any { d.contains(":$it") }
            val resolved = resolveDependency(d)
            if (resolved == null) {
                return "Dependency $d was not found on Maven; use a different library."
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
                    ?.takeIf { v -> v.none { it.isLetter() } && v.startsWith("2.") } // a finished KSP2 release, not a beta or RC
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
        return null
    }

    /**
     * Fixes the most common compile errors without the model (no tokens, no waiting): a missing import of a well-known
     * class or function, and a library the code uses but the build setup lacks. Returns what it fixed (empty: nothing).
     */
    private suspend fun quickFix(errors: String): List<String> {
        val fixed = mutableListOf<String>()
        val wanted = linkedMapOf<String, MutableSet<String>>() // file -> imports to add
        val unresolved = Regex("""^e: (?:file://)?(\S+?\.kt):\d+:\d+:? Unresolved reference:? '?([A-Za-z_][A-Za-z0-9_]*)'?""")
        errors.lines().forEach { line ->
            val m = unresolved.find(line.trim()) ?: return@forEach
            val errPath = m.groupValues[1]
            val path = files.keys.filter { it.endsWith(".kt") && errPath.endsWith("/$it") }.maxByOrNull { it.length } ?: return@forEach
            val text = files[path] ?: return@forEach
            val name = m.groupValues[2]
            // A library missing from the build setup: its package can't be found at all.
            if (!imported) LIBRARY_FOR_PACKAGE.forEach { (pkg, dep) ->
                if (pkg.substringAfterLast('.') == name && text.contains("import $pkg.") &&
                    deps.none { it.removePrefix("ksp:").startsWith(dep.removePrefix("ksp:").substringBeforeLast(':')) }
                ) {
                    if (addDependency(dep) == null) fixed += "added the missing library ${dep.removePrefix("ksp:").substringBeforeLast(':')}"
                }
            }
            val fq = KNOWN_IMPORTS[name] ?: return@forEach
            if (name in FLOW_ONLY && !text.contains("Flow")) return@forEach
            if (text.contains("import $fq\n") || text.contains("import $fq\r") || text.contains("import ${fq.substringBeforeLast('.')}.*")) return@forEach
            wanted.getOrPut(path) { linkedSetOf() } += fq
            // DataStore code needs its library too.
            if (!imported && fq.startsWith("androidx.datastore.") &&
                deps.none { it.startsWith("androidx.datastore:datastore-preferences") }
            ) {
                if (addDependency("androidx.datastore:datastore-preferences:latest") == null) fixed += "added the missing library androidx.datastore:datastore-preferences"
            }
        }
        wanted.forEach { (path, imports) ->
            val text = files[path] ?: return@forEach
            val lines = text.lines().toMutableList()
            val at = lines.indexOfLast { it.startsWith("import ") }.takeIf { it >= 0 }?.plus(1)
                ?: lines.indexOfFirst { it.startsWith("package ") }.takeIf { it >= 0 }?.plus(1)
                ?: 0
            val add = imports.map { "import $it" }
            lines.addAll(at, if (at > 0 && lines.getOrNull(at - 1)?.startsWith("package ") == true) listOf("") + add else add)
            val updated = lines.joinToString("\n")
            files[path] = updated
            pendingFiles[path] = updated.toByteArray()
            fixed += "added ${imports.size} missing import${if (imports.size == 1) "" else "s"} to ${path.substringAfterLast('/')}"
        }
        return fixed.distinct()
    }

    private suspend fun waitForRun(repo: GitHubClient.Repo, sha: String): GitHubClient.Run? {
        var run: GitHubClient.Run? = null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 35 * 60_000) {
            run = runCatching { gh.runFor(repo, sha, workflowFile) }.getOrNull() ?: run
            if (run == null && System.currentTimeMillis() - start > 3 * 60_000) return null
            if (run?.status == "completed") return run
            delay(10_000)
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

        /** Sanjoyingti's own workflow and the tag prefix of the releases it publishes. */
        const val SELF_WORKFLOW = "build-apk.yml"
        const val SELF_TAG_PREFIX = "arin-"
        /** Owner name used for projects that live only on the phone (no GitHub). */
        const val LOCAL_OWNER = "phone"
        /** Owner name of a project whose repository is made only once the person picks where to compile it. */
        const val PENDING_OWNER = "pending"
        private const val MAX_WEB_STEPS = 5
        private const val MAX_QUICK_FIXES = 3

        /** Libraries the quick fix may add, by the package the code imports. */
        private val LIBRARY_FOR_PACKAGE = mapOf(
            "androidx.datastore" to "androidx.datastore:datastore-preferences:latest",
            "coil3" to "io.coil-kt.coil3:coil-compose:latest",
            "retrofit2" to "com.squareup.retrofit2:retrofit:latest",
            "androidx.work" to "androidx.work:work-runtime-ktx:latest",
            "androidx.room" to "ksp:androidx.room:room-compiler:latest",
        )

        /** Names that only need the Flow import when the file works with Flows (List.map etc. need nothing). */
        private val FLOW_ONLY = setOf("map", "first", "firstOrNull", "catch", "combine", "update", "filterNotNull", "filter", "onEach", "distinctUntilChanged")

        /** Where well-known names live, for adding a forgotten import. */
        private val KNOWN_IMPORTS: Map<String, String> = buildMap {
            listOf(
                "Flow", "StateFlow", "MutableStateFlow", "SharedFlow", "MutableSharedFlow", "SharingStarted", "flowOf", "emptyFlow",
                "asStateFlow", "asSharedFlow", "stateIn", "shareIn", "collectLatest", "launchIn", "flow", "combine", "map", "first",
                "firstOrNull", "catch", "onEach", "update", "filterNotNull", "filter", "distinctUntilChanged", "flatMapLatest",
            ).forEach { put(it, "kotlinx.coroutines.flow.$it") }
            listOf("launch", "delay", "Dispatchers", "withContext", "CoroutineScope", "Job", "async", "coroutineScope", "isActive", "SupervisorJob")
                .forEach { put(it, "kotlinx.coroutines.$it") }
            put("viewModelScope", "androidx.lifecycle.viewModelScope")
            put("ViewModel", "androidx.lifecycle.ViewModel")
            put("AndroidViewModel", "androidx.lifecycle.AndroidViewModel")
            put("collectAsStateWithLifecycle", "androidx.lifecycle.compose.collectAsStateWithLifecycle")
            put("viewModel", "androidx.lifecycle.viewmodel.compose.viewModel")
            put("DataStore", "androidx.datastore.core.DataStore")
            put("preferencesDataStore", "androidx.datastore.preferences.preferencesDataStore")
            listOf(
                "Preferences", "edit", "emptyPreferences", "stringPreferencesKey", "intPreferencesKey", "longPreferencesKey",
                "booleanPreferencesKey", "floatPreferencesKey", "doublePreferencesKey", "stringSetPreferencesKey",
            ).forEach { put(it, "androidx.datastore.preferences.core.$it") }
            listOf(
                "mutableStateOf", "mutableIntStateOf", "mutableLongStateOf", "mutableFloatStateOf", "mutableDoubleStateOf",
                "mutableStateListOf", "mutableStateMapOf", "remember", "LaunchedEffect", "DisposableEffect", "SideEffect",
                "collectAsState", "rememberCoroutineScope", "derivedStateOf", "rememberUpdatedState", "snapshotFlow", "Composable",
            ).forEach { put(it, "androidx.compose.runtime.$it") }
            put("rememberSaveable", "androidx.compose.runtime.saveable.rememberSaveable")
            put("Modifier", "androidx.compose.ui.Modifier")
            put("Alignment", "androidx.compose.ui.Alignment")
            put("dp", "androidx.compose.ui.unit.dp")
            put("sp", "androidx.compose.ui.unit.sp")
            put("Color", "androidx.compose.ui.graphics.Color")
            put("FontWeight", "androidx.compose.ui.text.font.FontWeight")
            put("TextAlign", "androidx.compose.ui.text.style.TextAlign")
            put("KeyboardOptions", "androidx.compose.foundation.text.KeyboardOptions")
            put("KeyboardType", "androidx.compose.ui.text.input.KeyboardType")
            put("LocalContext", "androidx.compose.ui.platform.LocalContext")
            listOf(
                "Column", "Row", "Box", "Spacer", "Arrangement", "PaddingValues", "fillMaxSize", "fillMaxWidth", "fillMaxHeight",
                "padding", "size", "height", "width", "aspectRatio", "weight", "wrapContentSize", "imePadding",
            ).forEach { put(it, "androidx.compose.foundation.layout.$it") }
            listOf("LazyColumn", "LazyRow", "items", "itemsIndexed", "rememberLazyListState").forEach { put(it, "androidx.compose.foundation.lazy.$it") }
            listOf("clickable", "background", "border", "Image", "rememberScrollState", "verticalScroll", "horizontalScroll")
                .forEach { put(it, "androidx.compose.foundation.$it") }
            put("RoundedCornerShape", "androidx.compose.foundation.shape.RoundedCornerShape")
            put("CircleShape", "androidx.compose.foundation.shape.CircleShape")
            listOf(
                "Text", "Button", "TextButton", "OutlinedButton", "IconButton", "Icon", "Card", "Scaffold", "TopAppBar",
                "CenterAlignedTopAppBar", "MaterialTheme", "Surface", "OutlinedTextField", "TextField", "FloatingActionButton",
                "AlertDialog", "Switch", "Checkbox", "Slider", "HorizontalDivider", "CircularProgressIndicator",
                "LinearProgressIndicator", "DropdownMenu", "DropdownMenuItem", "SnackbarHost", "SnackbarHostState", "NavigationBar",
                "NavigationBarItem", "ExperimentalMaterial3Api", "TopAppBarDefaults", "CardDefaults", "ButtonDefaults",
                "FilledTonalButton", "ElevatedButton", "ModalBottomSheet", "Tab", "TabRow", "RadioButton", "lightColorScheme",
                "darkColorScheme", "Typography",
            ).forEach { put(it, "androidx.compose.material3.$it") }
            put("Icons", "androidx.compose.material.icons.Icons")
            put("Log", "android.util.Log")
            put("Toast", "android.widget.Toast")
            put("Context", "android.content.Context")
            put("Intent", "android.content.Intent")
            put("Uri", "android.net.Uri")
            put("Bundle", "android.os.Bundle")
            put("Serializable", "kotlinx.serialization.Serializable")
            put("Json", "kotlinx.serialization.json.Json")
        }
        private const val MANIFEST = "app/src/main/AndroidManifest.xml"
        private const val DEVCONTAINER = ".devcontainer/devcontainer.json"
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
