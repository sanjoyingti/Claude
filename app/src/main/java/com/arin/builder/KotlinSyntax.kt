package com.arin.builder

/**
 * ARIN-PROTECTED: syntax-guard — Arin itself (no AI, no credit) checks the structure of Kotlin files before every build
 * and repairs the usual typing mistakes models make: ``` fences or explanation text left in the code, a stray or
 * missing closing brace/bracket. One such mistake causes dozens of follow-on errors ("Expecting a top level
 * declaration"), so catching it here saves whole fix rounds.
 */
object KotlinSyntax {
    data class Report(
        val braces: Int, val parens: Int, val brackets: Int,
        /** 1-based line where a closing } first appears with nothing open (a stray brace), or -1. */
        val strayLine: Int,
        val unclosedString: Boolean,
    ) {
        val ok get() = braces == 0 && parens == 0 && brackets == 0 && strayLine < 0 && !unclosedString
        fun describe(): String = buildList {
            if (strayLine > 0) add("an extra '}' at line $strayLine")
            if (braces > 0) add("$braces '}' missing")
            if (braces < 0 && strayLine < 0) add("${-braces} '}' too many")
            if (parens != 0) add("brackets ( ) don't match (${if (parens > 0) "$parens ')' missing" else "${-parens} ')' too many"})")
            if (brackets != 0) add("brackets [ ] don't match")
            if (unclosedString) add("a string that is never closed")
        }.joinToString(", ").ifBlank { "fine" }
    }

    /** Counts brackets outside strings, chars and comments (string templates ${…} are code). */
    fun scan(text: String): Report {
        var braces = 0; var parens = 0; var brackets = 0; var stray = -1; var line = 1
        var i = 0; val n = text.length
        // Stack of string kinds we're inside: 1 = "…", 3 = """…""". A template ${ pushes 0 (code) with its own brace depth.
        val modes = ArrayDeque<Int>(); val tplDepth = ArrayDeque<Int>()
        var unclosed = false
        while (i < n) {
            val c = text[i]
            if (c == '\n') line++
            val mode = modes.lastOrNull()
            if (mode == 1 || mode == 3) {
                when {
                    mode == 1 && c == '\\' -> { i += 2; continue }
                    mode == 1 && c == '\n' -> { modes.removeLast(); unclosed = true } // a "…" string can't span lines
                    c == '$' && i + 1 < n && text[i + 1] == '{' -> { modes.addLast(0); tplDepth.addLast(0); i += 2; continue }
                    mode == 3 && text.startsWith("\"\"\"", i) -> {
                        var j = i + 3; while (j < n && text[j] == '"') j++ // """"" ends with the last three quotes
                        modes.removeLast(); i = j; continue
                    }
                    mode == 1 && c == '"' -> modes.removeLast()
                }
                i++; continue
            }
            when {
                text.startsWith("//", i) -> { while (i < n && text[i] != '\n') i++; continue }
                text.startsWith("/*", i) -> {
                    var depth = 1; i += 2
                    while (i < n && depth > 0) {
                        if (text[i] == '\n') line++
                        if (text.startsWith("/*", i)) { depth++; i += 2 } else if (text.startsWith("*/", i)) { depth--; i += 2 } else i++
                    }
                    continue
                }
                text.startsWith("\"\"\"", i) -> { modes.addLast(3); i += 3; continue }
                c == '"' -> { modes.addLast(1); i++; continue }
                c == '\'' -> {
                    // A char literal: 'a', '\n', 'A'.
                    val end = if (i + 1 < n && text[i + 1] == '\\') text.indexOf('\'', i + 2) else i + 2
                    i = if (end in (i + 1)..(i + 8) && end < n && text[end] == '\'') end + 1 else i + 1
                    continue
                }
                c == '{' -> { braces++; if (mode == 0) tplDepth.addLast(tplDepth.removeLast() + 1) }
                c == '}' -> {
                    if (mode == 0) {
                        val d = tplDepth.removeLast()
                        if (d == 0) { modes.removeLast(); i++; continue } // end of ${…}
                        tplDepth.addLast(d - 1)
                    }
                    braces--
                    if (braces < 0 && stray < 0) stray = line
                }
                c == '(' -> parens++
                c == ')' -> parens--
                c == '[' -> brackets++
                c == ']' -> brackets--
            }
            i++
        }
        if (modes.any { it != 0 }) unclosed = true
        return Report(braces, parens, brackets, stray, unclosed)
    }

    /** Removes ``` fences and explanation text a model left around the code. */
    fun clean(path: String, text: String): String {
        var t = text.replace("\r\n", "\n")
        t = t.lines().filterNot { Regex("""^\s*```[\w+-]*\s*$""").matches(it) }.joinToString("\n")
        if (path.endsWith(".kt")) {
            // Anything before `package` (or the first import / annotation / declaration) is prose.
            val lines = t.lines()
            val first = lines.indexOfFirst { l ->
                Regex("""^\s*(package |import |@|//|/\*|(private |internal |public |data |sealed |enum |abstract |open |inline |value )*(class|object|interface|fun|val|var|typealias) )""").containsMatchIn(l)
            }
            if (first > 0 && lines.take(first).all { it.isBlank() || !it.trimEnd().endsWith(";") && !it.contains('{') && !it.contains('}') }) t = lines.drop(first).joinToString("\n")
        }
        if (path.endsWith(".kt")) {
            // Explanation sentences after the code ("That should fix it.") are not Kotlin.
            val lines = t.trimEnd().lines().toMutableList()
            val prose = Regex("""^[A-Z][A-Za-z ,'’]+[^(){}=;\[\]<>"]*[.!:]?$""")
            val code = Regex("""^(package|import|class|object|interface|fun|val|var|private|internal|public|data|enum|sealed|abstract|open|typealias|annotation|const|override|suspend|inline|value)\b""")
            while (lines.size > 1 && lines.last().trim().let { it.isNotEmpty() && prose.matches(it) && !code.containsMatchIn(it) && it.contains(' ') }) lines.removeAt(lines.lastIndex)
            t = lines.joinToString("\n")
        }
        return t.trimEnd() + "\n"
    }

    /**
     * Fixes the brace problems that can be fixed safely without understanding the code: a line holding only a stray
     * `}` is removed; closing braces missing at the very end of the file are added. Returns null if it can't make
     * the file balanced (then a model rewrites the whole file).
     */
    fun repair(path: String, text0: String): String? {
        var text = clean(path, text0)
        var r = scan(text)
        var guard = 0
        while (r.strayLine > 0 && guard++ < 6) {
            val lines = text.lines().toMutableList()
            val idx = r.strayLine - 1
            if (idx !in lines.indices || lines[idx].trim() != "}") break
            lines.removeAt(idx); text = lines.joinToString("\n"); r = scan(text)
        }
        // Extra lone `}` lines at the end of the file.
        while (r.braces < 0 && r.strayLine < 0 && guard++ < 12) {
            val lines = text.trimEnd().lines().toMutableList()
            if (lines.lastOrNull()?.trim() != "}") break
            lines.removeAt(lines.lastIndex); text = lines.joinToString("\n") + "\n"; r = scan(text)
        }
        if (r.braces in 1..4 && r.parens == 0 && r.brackets == 0 && r.strayLine < 0 && !r.unclosedString) {
            text = text.trimEnd() + "\n" + "}\n".repeat(r.braces); r = scan(text)
        }
        return if (r.ok) text else null
    }

    /** Is this an error report about broken code structure (a typing mistake), not about types or libraries? */
    fun isSyntaxError(errors: String): Boolean = Regex(
        """(?i)(syntax error|expecting a top level declaration|expecting ['"]?[})\]]|expecting member declaration|unexpected tokens|unclosed comment|missing ['"]?\}|unexpected end of file|incomplete code|expecting an element|unresolved reference: ``)""",
    ).containsMatchIn(errors)
}
