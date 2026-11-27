package com.arin.selffix

/**
 * ARIN-PROTECTED: project-graph — a project-scoped map of one app's source (no AI): files, classes, functions, imports
 * and which file uses which. The Boss uses it to send executors to the few files that matter (targeted inspection)
 * and to see what a change could break (impact), instead of re-scanning the whole project. One graph per project id;
 * never mixed with another project.
 */
class ProjectGraph private constructor(val projectId: String, val files: Map<String, Node>) {
    data class Node(val path: String, val pkg: String, val classes: List<String>, val functions: List<String>,
                    val imports: List<String>, val uses: Set<String>)

    /** Files that use something declared in [path] (who could break if it changes). */
    fun dependents(path: String): List<String> {
        val n = files[path] ?: return emptyList()
        val names = n.classes.toSet() + n.functions.toSet()
        return files.values.filter { it.path != path && it.uses.any { u -> u in names } }.map { it.path }.sorted()
    }

    /** Files that [path] relies on. */
    fun dependencies(path: String): List<String> {
        val n = files[path] ?: return emptyList()
        return files.values.filter { it.path != path && (it.classes + it.functions).any { d -> d in n.uses } }.map { it.path }.sorted()
    }

    /** Where a class or function is declared. */
    fun declaring(name: String): List<String> = files.values.filter { name in it.classes || name in it.functions }.map { it.path }

    /** The [max] files most related to [query]: declarations named in it first, then word matches in names. */
    fun relevant(query: String, max: Int = 6): List<String> {
        val words = Regex("""[A-Za-z_][A-Za-z0-9_]{2,}""").findAll(query).map { it.value }.toSet()
        val lower = words.map { it.lowercase() }.toSet()
        return files.values.map { n ->
            val decl = (n.classes + n.functions).count { it in words } * 5
            val name = lower.count { w -> n.path.lowercase().contains(w) } * 3
            val fuzzy = (n.classes + n.functions).count { d -> lower.any { w -> w.length > 3 && d.lowercase().contains(w) } }
            n.path to decl + name + fuzzy
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    /** Impact before a change: what to edit, what depends on it, what to re-test. */
    fun impact(paths: List<String>): String = paths.joinToString("\n") { p ->
        val d = dependents(p)
        "$p → used by ${if (d.isEmpty()) "nothing else" else d.joinToString()}"
    }

    companion object {
        private val classRe = Regex("""(?m)^\s*(?:(?:public|private|internal|protected|abstract|open|sealed|data|enum|inner|annotation|value)\s+)*(?:class|object|interface)\s+([A-Z][A-Za-z0-9_]*)""")
        private val funRe = Regex("""(?m)^\s*(?:(?:public|private|internal|protected|override|suspend|inline|operator|infix|tailrec|open)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z0-9_.]+\.)?([a-zA-Z_][A-Za-z0-9_]*)\s*\(""")
        private val composableRe = Regex("""@Composable\s+(?:(?:private|internal)\s+)?fun\s+([A-Z][A-Za-z0-9_]*)""")
        private val pkgRe = Regex("""(?m)^\s*package\s+([\w.]+)""")
        private val importRe = Regex("""(?m)^\s*import\s+([\w.*]+)""")
        private val word = Regex("""\b[A-Za-z_][A-Za-z0-9_]{2,}\b""")
        private val noise = setOf("fun", "val", "var", "class", "object", "return", "import", "package", "private", "override", "String", "Int",
            "Boolean", "List", "Map", "Unit", "this", "null", "true", "false", "when", "else", "Long", "Double", "Any")

        /** Builds the graph from source text (path → content). Only .kt / .java / .kts files are read. */
        fun build(projectId: String, sources: Map<String, String>): ProjectGraph {
            val nodes = sources.filterKeys { it.endsWith(".kt") || it.endsWith(".java") || it.endsWith(".kts") }.map { (path, text) ->
                val noComments = text.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//[^\n]*"""), " ")
                val classes = classRe.findAll(noComments).map { it.groupValues[1] }.distinct().toList()
                val funs = (funRe.findAll(noComments) + composableRe.findAll(noComments)).map { it.groupValues[1] }.distinct().toList()
                val own = (classes + funs).toSet()
                val uses = word.findAll(noComments).map { it.value }.filter { it !in own && it !in noise }.toSet()
                path to Node(path, pkgRe.find(noComments)?.groupValues?.get(1).orEmpty(), classes, funs,
                    importRe.findAll(noComments).map { it.groupValues[1] }.toList(), uses)
            }.toMap()
            return ProjectGraph(projectId, nodes)
        }

        /** Reads a project folder on disk (skipping build output), up to [maxFiles] source files. */
        fun scan(projectId: String, root: java.io.File, maxFiles: Int = 600): ProjectGraph {
            val src = root.walkTopDown().onEnter { it.name !in setOf("build", ".gradle", ".git", "node_modules", ".idea") }
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java" || it.extension == "kts") && it.length() < 400_000 }
                .take(maxFiles).associate { it.relativeTo(root).path to runCatching { it.readText() }.getOrDefault("") }
            return build(projectId, src)
        }
    }
}
