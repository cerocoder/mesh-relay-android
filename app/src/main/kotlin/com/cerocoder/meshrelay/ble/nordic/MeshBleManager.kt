package com.cerocoder.meshrelay.ble.nordic

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.cerocoder.meshrelay.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.asFlow
import no.nordicsemi.android.ble.ktx.state.ConnectionState
import no.nordicsemi.android.ble.ktx.stateAsFlow
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID

/**
 * A connection to a Meshtastic node over the Nordic BLE Library.
 *
 * The library takes on the very thing it was chosen for: a queue of GATT
 * operations (Android cannot digest parallel requests), retries, MTU negotiation,
 * and workarounds for individual manufacturers' bugs.
 */
class MeshBleManager(context: Context) : BleManager(context) {

    // Kept for resolving user-facing failure and disconnect text via string
    // resources (see [describeBleFailure] and [disconnectReasonText]). [BleManager]
    // itself already holds the context it was constructed with, but does not
    // expose it, and other classes in this package (notably
    // [NordicMeshGattClient]) need one too.
    internal val appContext: Context = context.applicationContext

    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var fromNum: BluetoothGattCharacteristic? = null

    private var subscriptionReady = CompletableDeferred<Unit>()

    /**
     * The stream of FROMNUM notifications: the value does not matter, only the
     * fact of it.
     *
     * Initialisation is one-shot and deliberately lazy. In the Nordic model,
     * `setNotificationCallback` does not add a listener, it replaces the single
     * one, and `asFlow()` closes with an empty `awaitClose`: a displaced collector
     * gets neither an error nor a completion - it simply goes silent for ever. A
     * getter that recomputed this on every access would turn a second read of the
     * property into a silent loss of the inbound stream. Laziness is needed because
     * `fromNum` only appears once a connection is underway, inside
     * [isRequiredServiceSupported].
     */
    val notifications: Flow<Unit> by lazy {
        val characteristic = checkNotNull(fromNum) {
            "FROMNUM not found yet: subscription requested before connecting"
        }
        setNotificationCallback(characteristic).asFlow().map { }
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(SERVICE_UUID) ?: return false
        toRadio = service.getCharacteristic(TORADIO_UUID)
        fromRadio = service.getCharacteristic(FROMRADIO_UUID)
        fromNum = service.getCharacteristic(FROMNUM_UUID)
        return toRadio != null && fromRadio != null && fromNum != null
    }

    override fun initialize() {
        // Android defaults to an ATT MTU of 23, i.e. 20 bytes of payload, while
        // Meshtastic frames go up to 512. Without this request they would not fit.
        requestMtu(MTU).enqueue()
        // A local reference is mandatory: onServicesInvalidated replaces the field,
        // and a callback closed over the field would end up completing a different
        // Deferred, leaving the original waiter hanging forever.
        val latch = subscriptionReady
        enableNotifications(fromNum)
            .done { latch.complete(Unit) }
            .fail { _, status -> latch.completeExceptionally(IllegalStateException("CCCD not written, status $status")) }
            .enqueue()
    }

    override fun onServicesInvalidated() {
        toRadio = null
        fromRadio = null
        fromNum = null
        subscriptionReady = CompletableDeferred()
    }

    /**
     * Connect to the device.
     *
     * There is deliberately no bonding here: it is performed before connecting, in
     * [ensureBondedBeforeConnect]. Calling `ensureBond()` after `connect()` would
     * already be too late - the CCCD write happens inside the connection itself, in
     * [initialize], and without an encrypted channel the firmware rejects it.
     *
     * @param autoConnect mandatory for a bonded device with no fresh advertising:
     *   a direct connection on Android often fails with status 133, especially if
     *   the node uses a rotating address.
     */
    suspend fun connectTo(device: BluetoothDevice, autoConnect: Boolean) {
        connect(device)
            .useAutoConnect(autoConnect)
            .retry(CONNECT_RETRIES, CONNECT_RETRY_DELAY_MS)
            .timeout(CONNECT_TIMEOUT_MS)
            .suspend()
    }

    /** Suspend until the CCCD has actually been written. */
    suspend fun awaitReady() {
        subscriptionReady.await()
    }

    /**
     * Suspend until the link disconnects.
     *
     * `stateAsFlow()` is a hot flow with `replay = 1`, so if the link already
     * dropped before subscribing, the current state arrives immediately and there
     * is no wait. No one else is allowed to observe connection state: the library
     * permits only one such observer and throws if a second one is attached.
     */
    suspend fun awaitDisconnect(): String {
        val state = stateAsFlow().first { it is ConnectionState.Disconnected }
        return disconnectReasonText((state as ConnectionState.Disconnected).reason).resolve(appContext)
    }

    private fun disconnectReasonText(reason: ConnectionState.Disconnected.Reason): BleFailureMessage = when (reason) {
        ConnectionState.Disconnected.Reason.LINK_LOSS -> BleFailureMessage(R.string.ble_failure_disconnect_link_loss)
        ConnectionState.Disconnected.Reason.TERMINATE_PEER_USER -> BleFailureMessage(R.string.ble_failure_disconnect_peer_user)
        ConnectionState.Disconnected.Reason.TERMINATE_LOCAL_HOST -> BleFailureMessage(R.string.ble_failure_disconnect_local_host)
        ConnectionState.Disconnected.Reason.TIMEOUT -> BleFailureMessage(R.string.ble_failure_disconnect_timeout)
        ConnectionState.Disconnected.Reason.NOT_SUPPORTED -> BleFailureMessage(R.string.ble_failure_disconnect_not_supported)
        ConnectionState.Disconnected.Reason.CANCELLED -> BleFailureMessage(R.string.ble_failure_disconnect_cancelled)
        ConnectionState.Disconnected.Reason.SUCCESS -> BleFailureMessage(R.string.ble_failure_disconnect_success)
        ConnectionState.Disconnected.Reason.UNKNOWN -> BleFailureMessage(R.string.ble_failure_disconnect_unknown)
    }

    /**
     * Reads and writes must have a limit, and for a non-obvious reason.
     *
     * The ktx wrapper suspends the coroutine non-cancellably for reads and writes
     * (`suspendNonCancellable`), unlike connect and disconnect. That means
     * cancelling the coroutine does not interrupt such an operation: it hangs until
     * the library's own callback fires. On a link that died in silence this
     * stretches out until the Android stack notices the loss - i.e. tens of
     * seconds.
     *
     * The cost of this is not just delay. The transport cannot end its session
     * while its frame pump is stuck in such an operation, and closing the
     * transport runs under the connection manager's lock: for that whole time both
     * connecting and disconnecting, and handling of disconnects, freeze solid. From
     * outside it looks like "the app hung".
     *
     * The library's own timeout wakes the coroutine itself, without making it
     * cancellable - that is enough to make the limit measurable.
     */
    suspend fun read(): ByteArray =
        readCharacteristic(fromRadio).timeout(OPERATION_TIMEOUT_MS).suspend().value ?: ByteArray(0)

    suspend fun write(bytes: ByteArray) {
        writeCharacteristic(toRadio, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .timeout(OPERATION_TIMEOUT_MS)
            .suspend()
    }

    /**
     * Disconnect and release the library's resources.
     *
     * Order matters: `close()` closes the `BluetoothGatt` but does not tear down
     * the ACL link. Closing without a preceding `disconnect()` is the classic cause
     * of a connection that stays hanging until the node's own timeout, with the
     * next connection attempt failing with status 133. The reconnect loop passes
     * through here on every disconnect, so the cost of this mistake adds up fast.
     */
    suspend fun release() {
        try {
            // The timeout is mandatory. Closing runs under the connection manager's
            // lock, and the silence detector exists precisely because the Android
            // stack is capable of never sending the callback at all. Without a limit
            // that case would lock the mutex forever, and neither connect nor
            // disconnect would ever complete again.
            disconnect().timeout(DISCONNECT_TIMEOUT_MS).suspend()
        } catch (e: CancellationException) {
            // Our own coroutine's cancellation is rethrown; the library's own
            // cancellation of the request is not: see [nordicCall]. This is exactly
            // where a forged cancellation used to kill the reconnect loop, because
            // release() is called on every failure.
            if (!currentCoroutineContext().isActive) throw e
            Log.d(TAG, "disconnect request cancelled by the library", e)
        } catch (e: Throwable) {
            // Already disconnected, or the link was lost - expected, and closing is
            // still required regardless.
            Log.d(TAG, "orderly disconnect failed, closing forcibly", e)
        }
        try {
            close()
        } catch (e: Throwable) {
            Log.w(TAG, "error while closing the manager", e)
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

        private const val MTU = 512
        private const val CONNECT_RETRIES = 3
        private const val CONNECT_RETRY_DELAY_MS = 200
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val DISCONNECT_TIMEOUT_MS = 5_000L
        private const val OPERATION_TIMEOUT_MS = 5_000L
        private const val TAG = "MeshBleManager"
    }
}
