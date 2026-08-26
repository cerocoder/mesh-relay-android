package com.cerocoder.meshrelay.transport

import android.util.Log
import com.cerocoder.meshrelay.emulator.MeshScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.ToRadio
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Node emulator: parses incoming ToRadio messages and answers with frames from [scenario].
 *
 * Frames are handed out with a [frameDelay] pause rather than in one instant burst:
 * delivering them all at once would hide races that only show up on a real
 * asynchronous transport. Tests set both delays to zero.
 */
class FakeRadioTransport(
    private val scenario: MeshScenario,
    private val callback: RadioTransportCallback,
    parentScope: CoroutineScope,
    private val connectDelay: Duration = 300.milliseconds,
    private val frameDelay: Duration = 25.milliseconds,
) : RadioTransport {

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    // Atomic, not a plain Int: emit() launches coroutines on parentScope, which in the
    // app is multi-threaded (Dispatchers.Default). A plain increment here is a race that
    // breaks id uniqueness.
    private val nextFrameId = AtomicInteger(1)

    override fun start() {
        scope.launch {
            delay(connectDelay)
            callback.onConnect()
        }
    }

    override fun send(bytes: ByteArray) {
        val message = try {
            ToRadio.ADAPTER.decode(bytes)
        } catch (e: IOException) {
            Log.w(TAG, "failed to parse ToRadio (${bytes.size} bytes)", e)
            return
        }

        // Local variable rather than message.want_config_id directly: the property is
        // declared in another module (a protobuf-generated artifact), and the compiler
        // does not smart-cast it to non-null on equality checks - only a local val does.
        val nonce = message.want_config_id
        when {
            nonce == MeshProtocol.CONFIG_NONCE ->
                emit(scenario.configStageFrames(nonce))

            nonce == MeshProtocol.NODE_INFO_NONCE ->
                emit(scenario.nodeStageFrames(nonce))

            // Real firmware answers a heartbeat with a queue status - that is what
            // proves the link is alive. Without a reply, the demo would not behave
            // like a node.
            message.heartbeat != null ->
                emit(listOf(FromRadio(queueStatus = QueueStatus(res = 0, free = 16, maxlen = 16))))

            message.disconnect == true -> {
                Log.i(TAG, "received a goodbye, closing the session")
                callback.onDisconnect(isPermanent = true)
            }

            else -> Log.d(TAG, "ignored ToRadio: $message")
        }
    }

    private fun emit(frames: List<FromRadio>) {
        scope.launch {
            frames.forEach { frame ->
                delay(frameDelay)
                callback.onDataReceived(frame.copy(id = nextFrameId.getAndIncrement()).encode())
            }
        }
    }

    override suspend fun close() {
        job.cancelAndJoin()
    }

    private companion object {
        const val TAG = "FakeRadioTransport"
    }
}
