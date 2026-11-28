package com.arin

import com.arin.terminal.DeviceTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading the on-phone test's result: pass, crash (with its log), pairing needed, and a test that didn't finish. */
class DeviceTestParseTest {
    @Test fun pass() = assertEquals(DeviceTest.Kind.PASS, DeviceTest.parse("connected\nARIN_RESULT PASS\n").kind)

    @Test fun crashKeepsReasonAndLog() {
        val r = DeviceTest.parse("x\nARIN_RESULT CRASH The app closed by itself right after it opened\nFATAL EXCEPTION: main\njava.lang.NullPointerException")
        assertEquals(DeviceTest.Kind.CRASH, r.kind)
        assertEquals("The app closed by itself right after it opened", r.detail)
        assertTrue(r.log.contains("NullPointerException"))
    }

    @Test fun needsPairing() = assertEquals(DeviceTest.Kind.NEEDS_PAIRING, DeviceTest.parse("ARIN_RESULT NEEDS_PAIRING unauthorized").kind)

    @Test fun unfinishedIsAnErrorNotAPass() = assertEquals(DeviceTest.Kind.ERROR, DeviceTest.parse("adb: device offline").kind)
}
