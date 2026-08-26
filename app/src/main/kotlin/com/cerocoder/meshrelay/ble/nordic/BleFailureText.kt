package com.cerocoder.meshrelay.ble.nordic

import android.content.Context
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.ble.protocol.BleFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import no.nordicsemi.android.ble.exception.BluetoothDisabledException
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.InvalidRequestException
import no.nordicsemi.android.ble.exception.RequestFailedException

/**
 * A BLE failure translated into text fit to show on screen: a string resource id
 * plus whatever arguments its placeholder needs, resolved lazily.
 *
 * Kept as an id-and-args pair rather than a resolved [String] so this file needs
 * no [Context] of its own - knowledge of the library's exception types stays here,
 * and the caller resolves the text exactly where a [Context] is at hand and the
 * message is about to be stored or shown, the same way the rest of `ble/nordic`
 * keeps Android plumbing at the edges and logic in the middle. An argument may
 * itself be a [BleFailureMessage] - [resolve] resolves it first - which is how a
 * decoded GATT status is embedded inside the sentence that reports the failed
 * operation.
 */
class BleFailureMessage(private val resId: Int, private val args: List<Any> = emptyList()) {
    fun resolve(context: Context): String {
        val resolvedArgs = args.map { arg -> if (arg is BleFailureMessage) arg.resolve(context) else arg }
        return context.getString(resId, *resolvedArgs.toTypedArray())
    }
}

/**
 * Turn a Nordic failure into a message fit to show on screen.
 *
 * Knowledge of the library's types lives only here: the transport receives
 * already-finished text. Status codes are decoded per the table from
 * `Research/connection/03-ble-protocol.md` - without decoding, "status 133" tells
 * the user nothing, and it is in fact the most common Android misery of all.
 */
fun describeBleFailure(e: Throwable): BleFailureMessage = when (e) {
    is RequestFailedException -> BleFailureMessage(R.string.ble_failure_request_rejected, listOf(gattStatusText(e.status)))
    is DeviceDisconnectedException -> BleFailureMessage(R.string.ble_failure_device_disconnected)
    is BluetoothDisabledException -> BleFailureMessage(R.string.ble_failure_bluetooth_off)
    is InvalidRequestException -> BleFailureMessage(R.string.ble_failure_invalid_request)
    is SecurityException -> BleFailureMessage(R.string.ble_failure_permission_denied)
    is CancellationException -> BleFailureMessage(R.string.ble_failure_operation_cancelled)
    else -> BleFailureMessage(
        R.string.ble_failure_unknown,
        listOf(e::class.simpleName ?: BleFailureMessage(R.string.ble_failure_unknown_cause)),
    )
}

/**
 * Decode a failure status code.
 *
 * There are two different tables here, and they are easy to confuse. Negative
 * values are the library's own reasons (`FailCallback.REASON_*`): why the request
 * failed from its point of view. Non-negative values are GATT statuses from the
 * Android stack. Showing a person a bare number is useless in both cases, but
 * especially in the first one: "status -5" on screen says nothing, whereas behind
 * it stands the perfectly clear "the node did not respond to the connection
 * attempt".
 */
private fun gattStatusText(status: Int): BleFailureMessage = when (status) {
    -1 -> BleFailureMessage(R.string.ble_failure_status_link_lost)
    -2 -> BleFailureMessage(R.string.ble_failure_status_missing_service)
    -3 -> BleFailureMessage(R.string.ble_failure_status_missing_characteristic)
    -4 -> BleFailureMessage(R.string.ble_failure_status_request_rejected)
    -5 -> BleFailureMessage(R.string.ble_failure_status_no_response)
    -6 -> BleFailureMessage(R.string.ble_failure_status_unexpected_reply)
    -7 -> BleFailureMessage(R.string.ble_failure_status_request_cancelled)
    -8 -> BleFailureMessage(R.string.ble_failure_status_notifications_disabled)
    -9 -> BleFailureMessage(R.string.ble_failure_status_unsupported_setting)
    -100 -> BleFailureMessage(R.string.ble_failure_bluetooth_off)

    5 -> BleFailureMessage(R.string.ble_failure_status_needs_auth, listOf(status))
    8 -> BleFailureMessage(R.string.ble_failure_status_out_of_range, listOf(status))
    15 -> BleFailureMessage(R.string.ble_failure_status_needs_encryption, listOf(status))
    19 -> BleFailureMessage(R.string.ble_failure_status_peer_terminated, listOf(status))
    22 -> BleFailureMessage(R.string.ble_failure_status_radio_hang, listOf(status))
    62 -> BleFailureMessage(R.string.ble_failure_status_connection_failed, listOf(status))
    129, 133 -> BleFailureMessage(R.string.ble_failure_status_stale_connection, listOf(status))
    else -> BleFailureMessage(R.string.ble_failure_status_unrecognized, listOf(status))
}

/**
 * Run a Nordic call without letting the library forge a cancellation of our own
 * coroutine.
 *
 * A trap that cost a live debugging session on a phone: the ktx wrapper turns
 * `FailCallback.REASON_CANCELLED` into a genuine [CancellationException] - even
 * though only the request was cancelled, for example by our own connection
 * timeout. The surrounding code honestly rethrows the cancellation further, as it
 * should under structured concurrency, and as a result the entire reconnect loop
 * died: an attempt to connect to an unreachable node silently killed the whole
 * transport.
 *
 * The only way to tell them apart is by one thing - whether our own coroutine is
 * still alive. If it is, the cancellation came from inside the library and is an
 * ordinary failure.
 */
suspend fun <T> nordicCall(context: Context, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    if (currentCoroutineContext().isActive) throw BleFailure(describeBleFailure(e).resolve(context), e) else throw e
} catch (e: Throwable) {
    throw BleFailure(describeBleFailure(e).resolve(context), e)
}
