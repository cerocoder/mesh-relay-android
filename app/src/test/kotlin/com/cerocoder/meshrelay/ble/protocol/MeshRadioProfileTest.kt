package com.cerocoder.meshrelay.ble.protocol

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRadioProfileTest {

    private fun frame(marker: Byte) = byteArrayOf(marker, 0x01, 0x02)

    @Test
    fun `subscribing drains the queue until an empty reply`() = runTest {
        val client = FakeMeshGattClient()
        client.enqueue(frame(1), frame(2), frame(3))
        val profile = MeshRadioProfile(client)
        val received = mutableListOf<ByteArray>()

        val job = launch { profile.fromRadio.take(3).toList(received) }
        client.markSubscriptionReady()
        // There is deliberately no notification here. The collector has not
        // started yet (launch only queued it on the StandardTestDispatcher), and
        // `notifications` is a flow with no replay, just like the real BLE
        // doorbell: anything emitted before subscribing is lost. This test checks
        // the seed read and draining down to an empty reply; the path where "a
        // notification wakes the loop" is covered by the transient-error test,
        // where the subscription is guaranteed to have happened before the first
        // notification.
        advanceUntilIdle()
        job.join()

        assertEquals(3, received.size)
        assertArrayEquals(frame(1), received[0])
        assertArrayEquals(frame(3), received[2])
        // Four reads: three frames plus one empty one that closes the loop.
        assertEquals(4, client.reads)
    }

    @Test
    fun `the seed read happens without a single notification`() = runTest {
        val client = FakeMeshGattClient()
        client.enqueue(frame(7))
        val profile = MeshRadioProfile(client)
        val received = mutableListOf<ByteArray>()

        val job = launch { profile.fromRadio.take(1).toList(received) }
        client.markSubscriptionReady()
        advanceUntilIdle()
        job.join()

        assertArrayEquals(
            "the firmware does not send FROMNUM until it reaches a packet-sending state - the loop must start on its own",
            frame(7),
            received.single(),
        )
    }

    @Test
    fun `a write to TORADIO triggers a read of the reply`() = runTest {
        val client = FakeMeshGattClient()
        val profile = MeshRadioProfile(client)
        val received = mutableListOf<ByteArray>()
        val job = launch { profile.fromRadio.take(1).toList(received) }
        client.markSubscriptionReady()
        advanceUntilIdle()

        client.enqueue(frame(9))
        profile.send(frame(8))
        advanceUntilIdle()
        job.join()

        assertArrayEquals(frame(8), client.writes.single())
        assertArrayEquals(frame(9), received.single())
    }

    @Test
    fun `a transient read error does not end the flow`() = runTest {
        val client = FakeMeshGattClient()
        val profile = MeshRadioProfile(client)
        val received = mutableListOf<ByteArray>()
        val job = launch { profile.fromRadio.take(1).toList(received) }
        client.markSubscriptionReady()
        advanceUntilIdle()

        client.failNextRead = true
        client.emitNotification()
        advanceUntilIdle()

        client.enqueue(frame(5))
        client.emitNotification()
        advanceUntilIdle()
        job.join()

        assertArrayEquals(
            "after a read failure the flow must keep working on the next notification",
            frame(5),
            received.single(),
        )
    }

    @Test
    fun `the protocol waits for the subscription to be ready before the first read`() = runTest {
        val client = FakeMeshGattClient()
        client.enqueue(frame(4))
        val profile = MeshRadioProfile(client)

        val job = launch { profile.fromRadio.take(1).toList() }
        advanceUntilIdle()

        assertEquals("must not read before the CCCD is written", 0, client.reads)

        client.markSubscriptionReady()
        advanceUntilIdle()
        job.join()

        assertTrue("reading must start once the subscription is ready", client.reads > 0)
    }
}
