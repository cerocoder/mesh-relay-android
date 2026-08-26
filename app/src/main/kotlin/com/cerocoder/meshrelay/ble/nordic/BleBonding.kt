package com.cerocoder.meshrelay.ble.nordic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.ble.protocol.BleFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bonding with the node - mandatory **before** the GATT connection.
 *
 * The Meshtastic firmware requires an encrypted channel. If we connect first, the
 * CCCD write that the library performs inside initialisation goes out over an
 * unencrypted channel, the firmware rejects it (GATT status 5 or 15), the
 * subscription never happens, and the session dies without receiving a single
 * frame. The pairing dialog still appears regardless - Android raises bonding
 * itself upon hitting a protected characteristic - but by then the session is
 * already lost.
 *
 * The state is polled, not taken from the broadcast: `ACTION_BOND_STATE_CHANGED`
 * arrives late on some devices, or not at all.
 */
@SuppressLint("MissingPermission")
suspend fun ensureBondedBeforeConnect(context: Context, device: BluetoothDevice) {
    if (device.bondState == BluetoothDevice.BOND_BONDED) return

    var sawBonding = device.bondState == BluetoothDevice.BOND_BONDING
    if (!sawBonding && !device.createBond()) {
        // false here is not necessarily a refusal: bonding could already have
        // started on its own, triggered by a GATT operation on a protected
        // characteristic. Trust the state, not the return value.
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> return
            BluetoothDevice.BOND_BONDING -> sawBonding = true
            // We land here when the system refused to start bonding and did not say
            // why. A disabled adapter was already filtered out in openNordicSession,
            // so what remains is the rare case: the node is out of range, or the
            // stack is busy with a previous attempt. Both are cured by the next lap
            // of the loop.
            else -> throw BleFailure(BleFailureMessage(R.string.ble_failure_bonding_no_response).resolve(context))
        }
    }

    // createBond() returns true before Android reports BOND_BONDING. Tolerate a
    // few initial BOND_NONE readings and only then count it as a refusal.
    var graceLeft = BOND_NONE_GRACE_POLLS

    val bonded = withTimeoutOrNull(BOND_TIMEOUT_MS) {
        var outcome: Boolean? = null
        while (outcome == null) {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> outcome = true

                BluetoothDevice.BOND_BONDING -> {
                    // Having seen bonding in progress, from now on treat BOND_NONE as
                    // a refusal: it is no longer a delay, it is the user pressing
                    // "cancel" or entering the wrong code.
                    sawBonding = true
                    graceLeft = 0
                }

                else -> if (sawBonding || graceLeft-- <= 0) outcome = false
            }
            if (outcome == null) delay(BOND_POLL_INTERVAL_MS)
        }
        outcome
    } ?: false

    if (!bonded) {
        throw BleFailure(BleFailureMessage(R.string.ble_failure_bonding_incomplete).resolve(context))
    }
    Log.i(TAG, "bonding with ${device.address} confirmed")
}

private const val TAG = "BleBonding"

/**
 * The bonding limit. This is the one timeout in the project that measures not a
 * machine's work but a person's action: seeing the dialog, reading the code off
 * the node's screen, looking back, and typing it in. Thirty seconds was not
 * enough during live acceptance testing - bonding failed on the very first lap,
 * and a second dialog only appeared because the reconnect loop kicked in.
 */
private const val BOND_TIMEOUT_MS = 120_000L
private const val BOND_POLL_INTERVAL_MS = 500L
private const val BOND_NONE_GRACE_POLLS = 2
