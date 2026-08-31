package com.cerocoder.meshrelay.transport

import android.content.Context

/**
 * A connection failure named without needing a [Context], resolved into text
 * exactly where a [Context] is at hand and the reason is about to be shown.
 *
 * Mirrors the pattern `ble/nordic/BleFailureText.kt` already establishes for BLE
 * exceptions: a resource id plus whatever arguments its placeholder needs, kept
 * unresolved until [resolve] is called. That is what lets the connection layer
 * and the transport layer name what went wrong while staying free of Android
 * plumbing, and it is also what keeps a reason correct across a runtime language
 * change - resolving eagerly at the point of failure would freeze the text in
 * whatever locale was active at that moment.
 *
 * [Literal] is the other half of the same contract: some reasons - notably a BLE
 * stack disconnect (`MeshBleManager.awaitDisconnect`) and a decoded GATT failure
 * (`BleFailureMessage.resolve`) - are already resolved into localized text close
 * to where a [Context] already lives, deeper in `ble/nordic`, before they ever
 * reach the transport. That finished text is carried onward as-is rather than
 * re-described with a resource id it does not have.
 */
sealed class FailureReason {

    /** A resource id plus its format arguments, resolved lazily. */
    data class Resource(val resId: Int, val args: List<Any> = emptyList()) : FailureReason()

    /** Text that is already resolved and ready to show, verbatim. */
    data class Literal(val text: String) : FailureReason()

    fun resolve(context: Context): String = when (this) {
        is Resource -> context.getString(resId, *args.toTypedArray())
        is Literal -> text
    }
}
