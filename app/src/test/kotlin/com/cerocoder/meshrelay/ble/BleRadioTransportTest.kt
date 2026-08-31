package com.cerocoder.meshrelay.ble

import com.cerocoder.meshrelay.ble.protocol.FakeBleSession
import com.cerocoder.meshrelay.transport.FailureReason
import com.cerocoder.meshrelay.transport.RadioTransportCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private class RecordingCallback : RadioTransportCallback {
    var connects = 0
    var disconnects = 0
    val frames = mutableListOf<ByteArray>()

    override fun onConnect() {
        connects++
    }

    val reasons = mutableListOf<FailureReason?>()

    override fun onDisconnect(isPermanent: Boolean, reason: FailureReason?) {
        disconnects++
        reasons += reason
    }

    override fun onDataReceived(bytes: ByteArray) {
        frames += bytes
    }
}

class BleRadioTransportTest {

    private fun TestScope.scope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `an open session delivers frames to the callback`() = runTest {
        val callback = RecordingCallback()
        val session = FakeBleSession()
        session.client.enqueue(byteArrayOf(1), byteArrayOf(2))
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = { session },
        )

        transport.start()
        session.client.markSubscriptionReady()
        advanceTimeBy(4.seconds)
        advanceUntilIdle()

        assertEquals(1, callback.connects)
        assertEquals(2, callback.frames.size)
    }

    @Test
    fun `a failure to open retries with backoff`() = runTest {
        val callback = RecordingCallback()
        var attempts = 0
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = {
                attempts++
                throw IllegalStateException("node unreachable")
            },
        )

        transport.start()
        advanceTimeBy(30.seconds)
        // advanceUntilIdle() must not be called here: the reconnect loop is
        // infinite, and scrolling "to idle" would spin virtual time until runTest
        // kills the test on its own timeout.

        // Schedule: a 3 s pause before every attempt, backoff of 5, 10, 20 s. So
        // attempts land on second 3, 11 and 24, while a fourth would land past
        // second 44. The exact count is what checks the backoff: without it an
        // attempt would happen every three seconds, giving roughly ten of them.
        assertEquals("backoff was not honoured: with no pause there would be ten times as many attempts", 3, attempts)
        assertEquals("every failure is reported upward", 3, callback.disconnects)

        transport.close()
    }

    @Test
    fun `a long failed attempt does not count as a stable connection`() = runTest {
        val callback = RecordingCallback()
        val attempts = mutableListOf<Long>()
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = {
                attempts += currentTime
                // Longer than the stability threshold: exactly what an attempt to
                // connect to a missing node looks like, cut short by the timeout.
                delay(20.seconds)
                throw IllegalStateException("node unreachable")
            },
        )

        transport.start()
        advanceTimeBy(2.minutes)
        transport.close()

        // If the duration of the attempt were counted as stability instead of the
        // time the link was actually alive, the failure counter would reset on
        // every lap, the backoff would stay at its minimum forever, and the phone
        // would keep knocking on an absent node with no slowdown.
        val gaps = attempts.zipWithNext { a, b -> b - a }
        assertTrue("need at least three gaps, there were ${attempts.size} attempts", gaps.size >= 3)
        assertTrue(
            "gaps between attempts must grow, but were $gaps",
            gaps.zipWithNext().all { (earlier, later) -> later > earlier },
        )
    }

    @Test
    fun `the session closes when the transport shuts down`() = runTest {
        val callback = RecordingCallback()
        val session = FakeBleSession()
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = { session },
        )

        transport.start()
        session.client.markSubscriptionReady()
        advanceTimeBy(4.seconds)
        advanceUntilIdle()
        transport.close()
        advanceUntilIdle()

        assertTrue("an unclosed session is a leaked GATT connection", session.closed)
    }

    @Test
    fun `a silent disconnect ends the session and starts a new one`() = runTest {
        val callback = RecordingCallback()
        val sessions = mutableListOf<FakeBleSession>()
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = {
                FakeBleSession().also {
                    it.client.markSubscriptionReady()
                    sessions += it
                }
            },
        )

        transport.start()
        advanceTimeBy(4.seconds)
        assertEquals("the first session must open", 1, sessions.size)

        // The link dies in silence: no operation fails, frames simply stop
        // arriving. This is exactly what a node that has left the area looks like -
        // and this exact case used to hang the reconnect loop forever, because
        // nothing was waiting for the session to end.
        sessions[0].signalDisconnect("the node left the area")
        advanceTimeBy(30.seconds)

        assertTrue("a new session must open after the disconnect", sessions.size >= 2)
        assertTrue("the session of the broken link must be closed", sessions[0].closed)
        // The reason must reach the seam: without it the user sees a bare
        // "disconnected" and cannot tell a node leaving range apart from a
        // bonding failure.
        assertEquals(
            "the disconnect reason must reach the callback",
            FailureReason.Literal("the node left the area"),
            callback.reasons.firstOrNull(),
        )

        transport.close()
    }

    @Test
    fun `reconnecting stops after close`() = runTest {
        val callback = RecordingCallback()
        var attempts = 0
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = {
                attempts++
                throw IllegalStateException("node unreachable")
            },
        )

        transport.start()
        advanceTimeBy(30.seconds)
        val beforeClose = attempts
        // Without this check the test would be empty: if the loop stalls on the
        // first attempt for any reason, the counter freezes on its own and the
        // equality below would hold even with a completely broken close().
        assertTrue("the loop must keep turning until closed, otherwise there is nothing to guard", beforeClose >= 2)

        transport.close()
        advanceTimeBy(120.seconds)

        assertEquals("a closed transport has no right to come back to life", beforeClose, attempts)
    }
}
