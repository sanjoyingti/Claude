package com.arin.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads a picked or shared file as text so it can be sent with a message. */
object Attachments {
    /** Large files are cut to this many characters so they fit in a model's context. */
    const val MAX_CHARS = 120_000

    private val textExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "log",
        "html", "htm", "css", "js", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs",
        "c", "h", "cpp", "hpp", "cs", "swift", "php", "sh", "sql", "gradle", "properties",
    )

    class UnsupportedFile(name: String) :
        Exception("\"$name\" isn't a text file. Arin can read text, Markdown, CSV, JSON and code files for now.")

    suspend fun read(context: Context, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        val looksText = mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" || ext in textExtensions
        if (!looksText) throw UnsupportedFile(name)
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = CharArray(MAX_CHARS + 1)
            val reader = input.bufferedReader()
            var total = 0
            while (total < buf.size) {
                val n = reader.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            String(buf, 0, total)
        } ?: throw UnsupportedFile(name)
        if (raw.contains('\u0000')) throw UnsupportedFile(name)
        val text = if (raw.length > MAX_CHARS) raw.take(MAX_CHARS) + "\n…(file cut here: too long)" else raw
        Attachment(name, text)
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** The message text the model sees: what was typed, followed by each file. */
    fun promptWithFiles(text: String, files: List<Attachment>): String {
        if (files.isEmpty()) return text
        val parts = files.joinToString("\n\n") { "<file name=\"${it.name}\">\n${it.text}\n</file>" }
        return if (text.isBlank()) parts else "$text\n\n$parts"
    }
}
