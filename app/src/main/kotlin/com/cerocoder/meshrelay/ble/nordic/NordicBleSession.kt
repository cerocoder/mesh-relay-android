package com.cerocoder.meshrelay.ble.nordic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.ble.protocol.BleFailure
import com.cerocoder.meshrelay.ble.protocol.BleSession
import com.cerocoder.meshrelay.ble.protocol.MeshGattClient

/** A session over [MeshBleManager]: holds the manager and closes it. */
private class NordicBleSession(private val manager: MeshBleManager) : BleSession {

    override val client: MeshGattClient = NordicMeshGattClient(manager)

    override suspend fun awaitDisconnect(): String = manager.awaitDisconnect()

    override suspend fun close() = manager.release()
}

/**
 * Open a session with a node by its MAC address.
 *
 * The order is strict: bond, then connect, then wait for the subscription. The
 * reason is in [ensureBondedBeforeConnect] - the firmware needs an encrypted
 * channel, and connecting first dooms the CCCD write to rejection.
 *
 * `autoConnect` is taken from the state **before** bonding. A device we just saw
 * in a scan and are bonding with for the first time has fresh advertising - it is
 * connected to directly. An already-bonded device may not be advertising at all,
 * and a direct connection to it returns status 133; that case needs a patient
 * autoConnect.
 */
@SuppressLint("MissingPermission")
suspend fun openNordicSession(context: Context, mac: String): BleSession {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: throw BleFailure(BleFailureMessage(R.string.ble_failure_bluetooth_unavailable).resolve(context))
    // The adapter's state is checked first thing and separately. Without this
    // check, a disabled Bluetooth shows up further down the code as a failure of
    // createBond(), and the person reads "could not start bonding" on screen - a
    // message that steers them away from the real cause and sends them to
    // inspect the node instead.
    if (!adapter.isEnabled) throw BleFailure(BleFailureMessage(R.string.ble_failure_bluetooth_off).resolve(context))
    val device: BluetoothDevice = adapter.getRemoteDevice(mac)
    val wasBonded = device.bondState == BluetoothDevice.BOND_BONDED

    ensureBondedBeforeConnect(context, device)

    val manager = MeshBleManager(context)
    try {
        // nordicCall describes the failure while Nordic's own types are still at
        // hand, and keeps the library from passing off a cancellation of its own
        // request as a cancellation of our coroutine.
        nordicCall(context) {
            manager.connectTo(device, autoConnect = wasBonded)
            manager.awaitReady()
        }
    } catch (e: Throwable) {
        // Released under NonCancellable: if we ourselves were cancelled, an
        // ordinary call to release() would break at the first suspension point and
        // leave an open GATT behind - which is status 133 on the next attempt.
        withContext(NonCancellable) { manager.release() }
        throw e
    }
    return NordicBleSession(manager)
}
