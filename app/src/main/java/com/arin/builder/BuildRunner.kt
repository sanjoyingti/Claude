package com.arin.builder

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

/** A saved Build Mode project: the app, its whole agent conversation, and when it was last used. */
@Serializable
data class SavedProject(
    val id: String,
    val updated: Long = System.currentTimeMillis(),
    val project: Project? = null,
    val timeline: List<TimelineItem> = emptyList(),
) {
    val title: String get() = project?.appName
        ?: (timeline.firstOrNull { it is TimelineItem.User } as? TimelineItem.User)?.text?.take(50)
        ?: "Untitled"
    val lastResult: TimelineItem.Result? get() = timeline.lastOrNull { it is TimelineItem.Result } as? TimelineItem.Result
}

/** The single-project save format used before projects history. */
@Serializable
private data class OldSaved(val project: Project? = null, val timeline: List<TimelineItem> = emptyList())

/** The live state of one project: its steps, its app and whether a build of it is running now. */
class ProjectState(val id: String) {
    val timeline = mutableStateListOf<TimelineItem>()
    var project by mutableStateOf<Project?>(null)
    var running by mutableStateOf(false)
    /** Live progress of the current long step (upload/unpack): 0..1, or null when there's none. */
    var progress by mutableStateOf<Float?>(null)
    var progressText by mutableStateOf("")
}

/**
 * Shared Build Mode state: the open project's agent timeline. Every project is saved, so you can
 * come back to any of them later and keep asking for changes (like chats).
 */
object BuildRunner {
    /** Called on the main thread when a build ends, so the chat can post the result and its links. */
    var onFinished: ((BuildResult, Project?, ProjectState) -> Unit)? = null

    /** Download link for the source as a zip: the exact commit that was built, else the branch. */
    fun sourceZipUrl(p: Project): String =
        if (!p.lastSha.isNullOrBlank()) "https://github.com/${p.owner}/${p.repo}/archive/${p.lastSha}.zip"
        else "https://github.com/${p.owner}/${p.repo}/archive/refs/heads/${p.branch}.zip"

    /** The repo page on GitHub. */
    fun repoPage(p: Project): String = "https://github.com/${p.owner}/${p.repo}" + if (p.selfUpdate) "/tree/${p.branch}" else ""

    /** The project on screen (Build tab, chat). Builds of other projects keep running in [active]. */
    var open by mutableStateOf(ProjectState(UUID.randomUUID().toString()))
        private set

    /** Every project with a build running now (several can run at once); the open one may be among them. */
    val active = androidx.compose.runtime.mutableStateMapOf<String, ProjectState>()

    val timeline get() = open.timeline
    /** A build of the open project is running. */
    var running: Boolean
        get() = open.running
        internal set(v) { open.running = v }
    var project: Project?
        get() = open.project
        internal set(v) { open.project = v }
    val projectId: String get() = open.id
    var progress: Float?
        get() = open.progress
        internal set(v) { open.progress = v }
    var progressText: String
        get() = open.progressText
        internal set(v) { open.progressText = v }

    /** Some build (of any project) is running. */
    val anyRunning: Boolean get() = open.running || active.values.any { it.running }

    /** The live state of project [id]: the open one, or one building in the background. */
    fun state(id: String): ProjectState? = if (open.id == id) open else active[id]

    /** Requests the service should start, by project. */
    internal val pending = java.util.concurrent.ConcurrentHashMap<String, BuildRequest>()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false

    /** Changes the open project between builds (e.g. the answer to "make a preview first?") and saves it. */
    fun updateProject(context: Context, p: Project) {
        if (running) return
        project = p
        save(context)
    }

    private fun dir(context: Context) = File(context.filesDir, "build_projects").apply { mkdirs() }
    private fun prefs(context: Context) = context.getSharedPreferences("build_mode", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        // One-time move from the single-project file used before.
        val old = File(context.filesDir, "build_mode.json")
        if (old.exists()) {
            runCatching {
                val o = json.decodeFromString<OldSaved>(old.readText())
                if (o.timeline.isNotEmpty()) {
                    val id = UUID.randomUUID().toString()
                    File(dir(context), "$id.json").writeText(json.encodeToString(SavedProject(id, old.lastModified(), o.project, o.timeline)))
                    prefs(context).edit().putString("current", id).apply()
                }
            }
            old.delete()
        }
        prefs(context).getString("current", null)?.let { open(context, it) }
    }

    private val io = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO +
            com.arin.CrashLog.handler { runCatching { com.arin.ArinApp.instance }.getOrNull() },
    )
    private val pendingSaves = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Saves soon (batched, off the main thread) so frequent progress updates don't make the app stutter. */
    fun save(context: Context, st: ProjectState = open) {
        if (pendingSaves[st.id]?.isActive == true) return
        val app = context.applicationContext
        pendingSaves[st.id] = io.launch {
            kotlinx.coroutines.delay(1_500)
            writeNow(app, st)
        }
    }

    /** Saves right away (end of a build). */
    fun saveNow(context: Context, st: ProjectState = open) {
        pendingSaves.remove(st.id)?.cancel()
        val app = context.applicationContext
        val snapshot = SavedProject(st.id, System.currentTimeMillis(), st.project, st.timeline.toList())
        io.launch { write(app, snapshot) }
    }

    private suspend fun writeNow(context: Context, st: ProjectState) {
        val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            SavedProject(st.id, System.currentTimeMillis(), st.project, st.timeline.toList())
        }
        write(context, snapshot)
    }

    /** One save at a time, so two never write the same file at once. */
    private val writeLock = Any()

    private fun write(context: Context, s: SavedProject) {
        if (s.timeline.isEmpty() && s.project == null) return
        // Keep saved builds a sensible size: very long event texts (logs) are cut.
        val small = s.copy(timeline = s.timeline.takeLast(1_500).map { t ->
            if (t is TimelineItem.Event && t.event.text.length > 4_000) TimelineItem.Event(t.event.copy(text = t.event.text.take(4_000))) else t
        })
        synchronized(writeLock) { writeFile(context, small) }
    }

    private fun writeFile(context: Context, s: SavedProject) {
        runCatching {
            val f = File(dir(context), "${s.id}.json")
            val tmp = File(dir(context), "${s.id}.tmp")
            tmp.writeText(json.encodeToString(s))
            tmp.renameTo(f)
            // A project building in the background doesn't become the one Sanjoyingti reopens.
            if (s.id == open.id) prefs(context).edit().putString("current", s.id).apply()
        }
    }

    /** All saved projects, most recently used first. */
    fun list(context: Context): List<SavedProject> = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { json.decodeFromString<SavedProject>(f.readText()) }.getOrNull() }
        .sortedByDescending { it.updated }

    /** Opens a saved project to keep working on it. */
    fun open(context: Context, id: String): Boolean {
        if (id == open.id) return true
        // Building in the background: show its live state.
        val live = active[id]
        if (live != null) {
            leaveOpen(context)
            open = live
            prefs(context).edit().putString("current", id).apply()
            return true
        }
        val saved = runCatching { json.decodeFromString<SavedProject>(File(dir(context), "$id.json").readText()) }.getOrNull()
            ?: return false
        leaveOpen(context)
        open = ProjectState(saved.id).apply {
            project = saved.project
            timeline.addAll(saved.timeline)
        }
        prefs(context).edit().putString("current", id).apply()
        return true
    }

    /** Before showing another project: a running one keeps building in the background, an idle one is saved. */
    private fun leaveOpen(context: Context) {
        val st = open
        if (st.running || pending.containsKey(st.id)) active[st.id] = st else saveNow(context, st)
    }

    fun delete(context: Context, id: String) {
        if (state(id)?.running == true) return
        File(dir(context), "$id.json").delete()
        AppPreview.forget(context, id)
        if (id == projectId) newProject(context)
    }

    /** Starts a fresh project; the current one stays saved in Projects (and its repo stays on GitHub). */
    fun newProject(context: Context) {
        leaveOpen(context)
        open = ProjectState(UUID.randomUUID().toString())
        prefs(context).edit().remove("current").apply()
    }
}
