package com.arin.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * ARIN-PROTECTED: phone-test — the "open it and tap around" test run on the owner's own phone (instead of GitHub's
 * emulator) when they choose to build on the phone. It uses Wireless debugging: Arin's Linux runs adb against this
 * phone only (127.0.0.1), installs the app, opens it, checks it stays open, taps around it and checks for crashes.
 * The APK is handed over only when this test (or GitHub's emulator test) passes. When Developer options or Wireless
 * debugging is off, or Arin isn't paired yet, the owner is reminded what to turn on — nothing is skipped silently.
 */
object DeviceTest {
    enum class Kind { PASS, CRASH, NOT_READY, NEEDS_PAIRING, ERROR }

    data class Result(val kind: Kind, val detail: String, val log: String = "")

    private const val CONNECT = "_adb-tls-connect._tcp"
    private const val PAIRING = "_adb-tls-pairing._tcp"

    fun devModeOn(c: Context): Boolean =
        runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false)

    fun wirelessDebuggingOn(c: Context): Boolean =
        runCatching { Settings.Global.getInt(c.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)

    fun onWifi(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    /** What to turn on before the phone test can run, step by step; null when everything is on. */
    fun reminder(c: Context): String? {
        val steps = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT < 30) return "Testing on this phone needs Android 11 or newer (Wireless debugging). Build on GitHub instead — its emulator tests the app."
        if (!devModeOn(c)) steps += "Turn on Developer options: Settings → About phone → Software information → tap Build number 7 times."
        if (!onWifi(c)) steps += "Connect to Wi-Fi (Wireless debugging only works on Wi-Fi)."
        if (!wirelessDebuggingOn(c)) steps += "Turn on Wireless debugging: Settings → Developer options → Wireless debugging → On → Allow."
        if (steps.isEmpty()) return null
        return "To test on this phone, " + steps.mapIndexed { i, s -> "${i + 1}) $s" }.joinToString(" ") +
            " Then tap \"On this phone\" again — or build on GitHub, whose emulator tests it."
    }

    /** This phone's own Wi-Fi addresses (the wireless-debugging service must be this phone's, not another one's). */
    private fun ownAddresses(): Set<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { n -> n.inetAddresses.toList() }
            .mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
    }.getOrDefault(emptySet())

    /** Finds this phone's wireless-debugging port of [type] (connect, or pairing while its dialog is open). */
    @Suppress("DEPRECATION")
    suspend fun findPort(c: Context, type: String, timeoutMs: Long = 8_000): Int? = withTimeoutOrNull(timeoutMs) {
        val nsd = c.getSystemService(NsdManager::class.java) ?: return@withTimeoutOrNull null
        val own = ownAddresses()
        suspendCancellableCoroutine { cont ->
            var done = false
            var fallback: Int? = null
            lateinit var listener: NsdManager.DiscoveryListener
            fun finish(port: Int?) = synchronized(this@DeviceTest) {
                if (done) return@synchronized
                done = true
                runCatching { nsd.stopServiceDiscovery(listener) }
                if (cont.isActive) cont.resume(port)
            }
            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { finish(null) }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    runCatching {
                        nsd.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress?.substringBefore('%')
                                if (host != null && host in own) finish(info.port) else if (fallback == null) fallback = info.port
                            }
                        })
                    }
                }
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { finish(null) }
            cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
            // Only one wireless-debugging phone found and its address didn't match exactly: it's this one.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish(fallback) }, timeoutMs - 1_000)
        }
    }

    private const val ADB = "command -v adb >/dev/null 2>&1 || { apt-get update -qq >/dev/null 2>&1; " +
        "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends adb >/dev/null 2>&1; }; " +
        "command -v adb >/dev/null 2>&1 || { echo 'ARIN_RESULT ERROR adb could not be installed in Linux'; exit 0; }; " +
        "adb start-server >/dev/null 2>&1; "

    internal fun parse(out: String): Result {
        val line = out.lines().lastOrNull { it.startsWith("ARIN_RESULT ") } ?: return Result(Kind.ERROR, "the test didn't finish", out.takeLast(3_000))
        val rest = line.removePrefix("ARIN_RESULT ")
        val kind = runCatching { Kind.valueOf(rest.substringBefore(' ')) }.getOrDefault(Kind.ERROR)
        return Result(kind, rest.substringAfter(' ', "").trim(), out.substringAfter(line, "").trim().takeLast(4_000))
    }

    /** Runs the open-and-tap test for [pkg] from [apk] on this phone. */
    suspend fun run(c: Context, apk: File, pkg: String): Result {
        reminder(c)?.let { return Result(Kind.NOT_READY, it) }
        if (!Linux.installed(c)) return Result(Kind.NOT_READY, "Linux isn't installed (Settings → Linux).")
        val port = findPort(c, CONNECT) ?: return Result(Kind.NOT_READY,
            "Couldn't find this phone's Wireless debugging. Check it's still On (it turns off after a restart or a Wi-Fi change), then tap \"On this phone\" again.")
        // The APK goes into Linux, where adb runs.
        val inLinux = File(Linux.rootfs(c), "root/.arin-test").apply { mkdirs() }
        val copy = File(inLinux, "test.apk")
        withContext(Dispatchers.IO) { apk.copyTo(copy, overwrite = true) }
        val s = "127.0.0.1:$port"
        val script = ADB + """
            S=$s; P='$pkg'
            adb connect ${'$'}S >/dev/null 2>&1
            st=${'$'}(adb -s ${'$'}S get-state 2>&1)
            [ "${'$'}st" = device ] || { echo "ARIN_RESULT NEEDS_PAIRING ${'$'}st"; exit 0; }
            if adb -s ${'$'}S shell dumpsys window 2>/dev/null | grep -qE 'mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true'; then
              echo "ARIN_RESULT NOT_READY Unlock your phone and keep the screen on, then tap \"On this phone\" again."; exit 0; fi
            adb -s ${'$'}S install -r -t /root/.arin-test/test.apk > /tmp/arin-i.txt 2>&1 || { echo "ARIN_RESULT CRASH The APK wouldn't install: ${'$'}(tail -c 300 /tmp/arin-i.txt | tr '\n' ' ')"; exit 0; }
            adb -s ${'$'}S logcat -c >/dev/null 2>&1
            adb -s ${'$'}S shell monkey -p ${'$'}P -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
            sleep 6
            if [ -z "${'$'}(adb -s ${'$'}S shell pidof ${'$'}P | tr -d '\r')" ]; then
              echo "ARIN_RESULT CRASH The app closed by itself right after it opened"; adb -s ${'$'}S logcat -d -b crash | tail -n 80; exit 0; fi
            adb -s ${'$'}S shell screencap -p /sdcard/Download/arin-test-${'$'}P.png >/dev/null 2>&1
            adb -s ${'$'}S shell monkey -p ${'$'}P --pct-syskeys 0 --pct-appswitch 0 --pct-nav 0 --pct-majornav 0 --pct-anyevent 0 --pct-rotation 0 --throttle 250 -v 150 > /tmp/arin-m.txt 2>&1
            if grep -qE 'CRASH|ANR in|Monkey aborted' /tmp/arin-m.txt || [ -z "${'$'}(adb -s ${'$'}S shell pidof ${'$'}P | tr -d '\r')" ]; then
              echo "ARIN_RESULT CRASH The app crashed while it was being tapped around"; adb -s ${'$'}S logcat -d -b crash | tail -n 80; exit 0; fi
            adb -s ${'$'}S shell am force-stop ${'$'}P >/dev/null 2>&1
            adb -s ${'$'}S shell monkey -p com.arin.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
            echo "ARIN_RESULT PASS"
        """.trimIndent()
        val r = Linux.run(c, script, timeoutMs = 6 * 60_000L)
        val res = parse(r.combined())
        if (res.kind == Kind.NEEDS_PAIRING) showPairing(c)
        return res
    }

    /** Pairs Arin's adb with this phone using the 6-digit code from "Pair device with pairing code". */
    suspend fun pair(c: Context, code: String): Result {
        val port = findPort(c, PAIRING) ?: return Result(Kind.NOT_READY,
            "Couldn't see the pairing screen. Open Wireless debugging → Pair device with pairing code, keep it open, and enter the code again.")
        val digits = code.filter { it.isDigit() }.take(6)
        if (digits.length != 6) return Result(Kind.ERROR, "The pairing code has 6 digits.")
        val r = Linux.run(c, ADB + "out=${'$'}(adb pair 127.0.0.1:$port $digits 2>&1); case \"${'$'}out\" in *Successfully*) echo 'ARIN_RESULT PASS';; *) echo \"ARIN_RESULT ERROR ${'$'}out\";; esac", timeoutMs = 3 * 60_000L)
        return parse(r.combined())
    }

    // ---- Pairing from a notification (the pairing screen stays open while the code is typed) ----

    private const val CHANNEL = "arin-phone-test"
    private const val NOTE_ID = 7_731
    const val KEY_CODE = "code"

    fun showPairing(c: Context, text: String = "Open Settings → Developer options → Wireless debugging → Pair device with pairing code, then type the 6-digit code here.") {
        runCatching {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Testing on this phone", NotificationManager.IMPORTANCE_HIGH))
            val input = android.app.RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
            val pi = PendingIntent.getBroadcast(c, 0, Intent(c, PairReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            val action = android.app.Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(c, android.R.drawable.ic_menu_send), "Enter code", pi,
            ).addRemoteInput(input).build()
            nm.notify(NOTE_ID, android.app.Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Pair Arin for testing on this phone")
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .addAction(action)
                .setAutoCancel(false)
                .build())
        }
    }

    private fun showDone(c: Context, text: String) = runCatching {
        c.getSystemService(NotificationManager::class.java).notify(NOTE_ID, android.app.Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("Testing on this phone")
            .setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build())
    }

    /** Receives the code typed into the pairing notification. */
    class PairReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = android.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
            val app = context.applicationContext
            val pending = goAsync()
            showDone(app, "Pairing…")
            com.arin.ArinApp.scope.launch(Dispatchers.IO) {
                val r = runCatching { pair(app, code) }.getOrElse { Result(Kind.ERROR, it.message ?: "pairing failed") }
                if (r.kind == Kind.PASS) showDone(app, "Paired ✓. Go back to Arin and tap \"On this phone\" again to run the test.")
                else showPairing(app, "Pairing didn't work: ${r.detail.take(160)} — keep the pairing screen open and enter the new code.")
                pending.finish()
            }
        }
    }
}
