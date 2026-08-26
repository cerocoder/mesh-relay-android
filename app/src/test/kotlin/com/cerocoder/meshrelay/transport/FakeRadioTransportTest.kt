package com.cerocoder.meshrelay.transport

import com.cerocoder.meshrelay.emulator.Scenarios
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.ToRadio
import kotlin.time.Duration.Companion.ZERO

/** Records everything the transport hands upward. */
private class RecordingCallback : RadioTransportCallback {
    var connected = false
    var disconnectedPermanently: Boolean? = null
    val frames = mutableListOf<FromRadio>()

    override fun onConnect() {
        connected = true
    }

    override fun onDisconnect(isPermanent: Boolean, reason: String?) {
        disconnectedPermanently = isPermanent
    }

    override fun onDataReceived(bytes: ByteArray) {
        frames += FromRadio.ADAPTER.decode(bytes)
    }
}

class FakeRadioTransportTest {

    /**
     * A transport on an unconfined test dispatcher: coroutines run immediately on launch,
     * so scheduling order does not affect the result. The scope is deliberately not a
     * child of the test's job - the transport's SupervisorJob never completes on its own,
     * and runTest would wait on it forever.
     */
    private fun TestScope.transport(callback: RadioTransportCallback) =
        FakeRadioTransport(
            scenario = requireNotNull(Scenarios.byId(Scenarios.FIVE_NODES_ID)),
            callback = callback,
            parentScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            connectDelay = ZERO,
            frameDelay = ZERO,
        )

    @Test
    fun `after start the transport reports connection`() = runTest {
        val callback = RecordingCallback()

        transport(callback).start()
        advanceUntilIdle()

        assertTrue(callback.connected)
    }

    @Test
    fun `a config request yields stage 1 in the right order`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.send(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE).encode())
        advanceUntilIdle()

        assertNotNull(callback.frames.first().my_info)
        assertEquals(MeshProtocol.CONFIG_NONCE, callback.frames.last().config_complete_id)
    }

    @Test
    fun `a node-database request yields stage 2`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.send(ToRadio(want_config_id = MeshProtocol.NODE_INFO_NONCE).encode())
        advanceUntilIdle()

        assertEquals(5, callback.frames.count { it.node_info != null })
        assertEquals(MeshProtocol.NODE_INFO_NONCE, callback.frames.last().config_complete_id)
    }

    @Test
    fun `frames are assigned increasing ids`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.send(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE).encode())
        advanceUntilIdle()

        val ids = callback.frames.map { it.id }
        assertTrue("no frames were delivered - the test would pass vacuously", ids.isNotEmpty())
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `a heartbeat is answered with a queue status`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.send(ToRadio(heartbeat = Heartbeat(nonce = 7)).encode())
        advanceUntilIdle()

        assertEquals(1, callback.frames.count { it.queueStatus != null })
    }

    @Test
    fun `a goodbye packet is answered with a permanent disconnect`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.send(ToRadio(disconnect = true).encode())
        advanceUntilIdle()

        assertEquals(true, callback.disconnectedPermanently)
    }

    @Test
    fun `garbage bytes do not take down the transport`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.send(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        advanceUntilIdle()

        assertTrue(callback.frames.isEmpty())
        assertNull(callback.disconnectedPermanently)
    }

    @Test
    fun `no new frames arrive after close`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.close()
        subject.send(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE).encode())
        advanceUntilIdle()

        assertTrue(callback.frames.isEmpty())
    }

    @Test
    fun `stage completion echoes the received nonce, not the constant`() = runTest {
        val callback = RecordingCallback()
        val scenario = requireNotNull(Scenarios.byId(Scenarios.FIVE_NODES_ID))

        val frames = scenario.configStageFrames(4242)

        assertEquals(4242, frames.last().config_complete_id)
    }
}
