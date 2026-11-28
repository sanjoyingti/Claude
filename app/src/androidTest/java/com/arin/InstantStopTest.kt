package com.arin

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arin.chat.ChatViewModel
import com.arin.data.ChatStore
import com.arin.data.Settings
import com.arin.llm.ApiFormat
import com.arin.llm.ModelOption
import com.arin.llm.Provider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Stop must take effect at the tap: a reply stuck waiting on a slow model is stopped, the chat is free and the reply so
 * far is shown as stopped — immediately, not when the model answers. The stopped reply must not post again later or
 * disturb the next reply.
 */
@RunWith(AndroidJUnit4::class)
class InstantStopTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: ServerSocket
    private var chat: String? = null

    /** A stand-in model: "SLOW" requests send one piece and then hang for a minute; others answer normally. */
    private fun startServer(): Int {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        s.use { sock ->
                            val input = sock.getInputStream().buffered()
                            val head = StringBuilder()
                            while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) return@use; head.append(b.toChar()) }
                            val len = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                            val body = ByteArray(len).also { var r = 0; while (r < len) { val n = input.read(it, r, len - r); if (n < 0) break; r += n } }.toString(Charsets.UTF_8)
                            val out = sock.getOutputStream()
                            if (!head.startsWith("POST") || !head.contains("/chat/completions")) {
                                val j = "{\"data\":[{\"id\":\"stop-test\"}]}"
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${j.length}\r\nConnection: close\r\n\r\n$j".toByteArray())
                                return@use
                            }
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                            val slow = Regex("STOPTEST-\\w+").findAll(body).lastOrNull()?.value == "STOPTEST-slow"
                            if (slow) {
                                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"PARTIAL-PIECE \"}}]}\n\n".toByteArray()); out.flush()
                                Thread.sleep(60_000) // the model goes quiet
                            } else {
                                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"SECOND-REPLY-OK\"}}]}\n\n".toByteArray()); out.flush()
                            }
                            out.write("data: [DONE]\n\n".toByteArray()); out.flush()
                        }
                    }
                }
            }
        }
        return server.localPort
    }

    @After fun tearDown() {
        runCatching { server.close() }
        chat?.let { runCatching { ChatStore(ctx).delete(it) } }
    }

    private fun ui(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test fun stopIsInstantAndTheNextReplyIsUndisturbed() {
        val base = "http://127.0.0.1:${startServer()}/v1"
        lateinit var vm: ChatViewModel
        ui {
            vm = ChatViewModel(rule.activity.application)
            Settings(ctx).setKeyFor(base, "test-key-for-local-server")
            vm.setCustomBase(base, ApiFormat.OpenAI)
            vm.chooseProvider(Provider.Custom)
            vm.chooseModel(ModelOption("stop-test", "Stop test"))
            com.arin.selffix.CreditSaver.setWorkerMode(ctx, false)
            vm.setClaudeHelper(false)
            vm.newChat()
            vm.send("STOPTEST-slow tell me a long story")
            chat = vm.chatId
        }
        // Wait until the reply is streaming and its first piece arrived (then the model hangs).
        var partial = false
        val until = System.currentTimeMillis() + 30_000
        while (!partial && System.currentTimeMillis() < until) { Thread.sleep(200); ui { partial = vm.runFor()?.liveText?.contains("PARTIAL-PIECE") == true } }
        assertTrue("the reply never started streaming", partial)

        // Tap Stop: everything below is checked in the same instant, on the main thread.
        var free = true; var last = ""; var ms = 0L
        ui {
            val t0 = System.nanoTime()
            vm.stop()
            ms = (System.nanoTime() - t0) / 1_000_000
            free = !vm.streaming
            last = vm.messages.lastOrNull()?.text.orEmpty()
        }
        assertTrue("the chat was still busy right after Stop", free)
        assertTrue("the reply so far wasn't posted as stopped: $last", last.contains("PARTIAL-PIECE") && last.contains("Stopped by you"))
        assertTrue("Stop took $ms ms", ms < 500)

        // The next message gets its own reply, and the stopped one never posts a second time.
        ui { vm.send("STOPTEST-next answer briefly") }
        var done = false
        val until2 = System.currentTimeMillis() + 30_000
        while (!done && System.currentTimeMillis() < until2) { Thread.sleep(300); ui { done = !vm.streaming && vm.messages.any { it.text.contains("SECOND-REPLY-OK") } } }
        assertTrue("the next reply didn't arrive", done)
        Thread.sleep(1_500) // give the stopped reply's job time to wind down
        var stoppedCount = 0; var hasSecond = false
        ui { stoppedCount = vm.messages.count { it.text.contains("Stopped by you") }; hasSecond = vm.messages.any { it.text.contains("SECOND-REPLY-OK") } }
        assertEquals("the stopped reply was posted more than once", 1, stoppedCount)
        assertTrue("the stopped reply's job removed the next reply", hasSecond)
        assertFalse("still busy", vm.runs.isNotEmpty())
    }
}
