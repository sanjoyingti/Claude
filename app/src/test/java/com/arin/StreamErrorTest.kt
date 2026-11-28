package com.arin

import com.arin.llm.Http
import com.arin.llm.LlmException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The crash of 28 Nov 2026: a model service answered with an error while streaming, and the error escaped OkHttp's
 * callback and killed the app. Now the stream must end with a readable error — never crash, never hang.
 */
class StreamErrorTest {
    /** A one-shot local HTTP server sending [head], then [body], then (optionally) keeping the connection open. */
    private fun server(head: String, body: String, holdOpenMs: Long = 0): Int {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            ss.use { s ->
                s.accept().use { c ->
                    val input = c.getInputStream().bufferedReader()
                    var len = 0
                    while (true) { val l = input.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) len = l.substringAfter(':').trim().toInt() }
                    repeat(len) { input.read() }
                    c.getOutputStream().apply { write(head.toByteArray()); write(body.toByteArray()); flush() }
                    if (holdOpenMs > 0) Thread.sleep(holdOpenMs)
                }
            }
        }
        return ss.localPort
    }

    @Test fun serverErrorEndsWithReadableErrorInsteadOfCrashing() = runBlocking {
        val port = server("HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\nContent-Length: 44\r\n\r\n", """{"error":{"message":"upstream overloaded!"}}""")
        val e = runCatching { withTimeout(15_000) { Http.sse("http://127.0.0.1:$port/v1/chat/completions", emptyMap(), "{}").toList() } }.exceptionOrNull()
        assertTrue("expected LlmException, got $e", e is LlmException)
        assertTrue(e!!.message!!.contains("The service is having trouble"))
        assertTrue(e.message!!.contains("upstream overloaded"))
    }

    @Test fun errorStreamThatNeverEndsDoesNotHang() = runBlocking {
        // An event-stream error body that is never finished: reading it to the end would wait forever.
        val port = server("HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/event-stream\r\n\r\n", "data: {\"error\":\"busy\"}\n\n", holdOpenMs = 60_000)
        val started = System.currentTimeMillis()
        val e = runCatching { withTimeout(20_000) { Http.sse("http://127.0.0.1:$port/x", emptyMap(), "{}").toList() } }.exceptionOrNull()
        assertTrue("expected LlmException, got $e", e is LlmException)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 15_000)
    }

    @Test fun normalStreamStillWorks() = runBlocking {
        val sse = "data: {\"a\":1}\n\ndata: {\"a\":2}\n\ndata: [DONE]\n\n"
        val port = server("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${sse.length}\r\n\r\n", sse)
        assertEquals(listOf("{\"a\":1}", "{\"a\":2}"), withTimeout(15_000) { Http.sse("http://127.0.0.1:$port/x", emptyMap(), "{}").toList() })
    }
}
