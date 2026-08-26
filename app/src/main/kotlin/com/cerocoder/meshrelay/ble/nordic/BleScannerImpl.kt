package com.cerocoder.meshrelay.ble.nordic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.ParcelUuid
import android.util.Log
import com.cerocoder.meshrelay.ble.BleScanner
import com.cerocoder.meshrelay.transport.DeviceListEntry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings

/**
 * A scanner with a hardware filter on the Meshtastic service.
 *
 * The filter is set at the OS level, not in code: this way the radio module does
 * not wake the process for every other advertisement, and the list is not
 * cluttered with headphones and watches.
 */
class BleScannerImpl : BleScanner {

    override fun scan(): Flow<DeviceListEntry.Ble> = callbackFlow {
        val scanner = BluetoothLeScannerCompat.getScanner()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setUseHardwareFilteringIfSupported(true)
            .build()
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(MeshBleManager.SERVICE_UUID))
                .build(),
        )

        // callbackFlow's buffer is finite, and trySend silently drops the value on
        // overflow. The drop itself is not fatal - the node keeps advertising itself
        // again and again - but without a log entry a real pressure problem would be
        // invisible.
        val emit: (ScanResult) -> Unit = { result ->
            if (trySend(result.toEntry()).isFailure) {
                Log.w(TAG, "scanner buffer overflowed, a find was dropped")
            }
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emit(result)
            }

            // With setReportDelay(0) the system does not use batched delivery, but the
            // callback is kept anyway: it costs nothing and will survive a settings
            // change.
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(emit)
            }

            override fun onScanFailed(errorCode: Int) {
                // The flow closes without an exception on purpose. The consumer
                // collects it inside a LaunchedEffect, where an uncaught exception
                // kills the composition, i.e. crashes the app. And a failure here is
                // routine: Android throttles scans that start too often, and coming
                // back to this screen again gets exactly this code. The list stays
                // with whatever was found so far, instead of an abnormal termination.
                Log.w(TAG, "scan failed to start, code $errorCode")
                close()
            }
        }

        scanner.startScan(filters, settings, callback)
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (e: Throwable) {
                // The permission could have been revoked between the start and the stop.
                Log.w(TAG, "failed to stop the scan", e)
            }
        }
    }

    /**
     * Turn a scan result into a list entry.
     *
     * The name is taken from the advertising packet, not from [BluetoothDevice.getName]:
     * starting with Android 12 the getter requires `BLUETOOTH_CONNECT` and throws a
     * `SecurityException` if only `BLUETOOTH_SCAN` was granted. That would happen on
     * the system callback thread, outside any coroutine - i.e. as an app crash that
     * neither `awaitClose` nor the flow's consumer would catch. `@SuppressLint`
     * suppresses the lint check, not the OS check itself.
     *
     * `bondState` requires the same permission, so it is wrapped: bonded-ness is only
     * a hint for the list, and the transport rereads it from scratch when connecting
     * anyway.
     */
    @SuppressLint("MissingPermission")
    private fun ScanResult.toEntry(): DeviceListEntry.Ble = DeviceListEntry.Ble(
        name = scanRecord?.deviceName ?: "unknown node",
        mac = device.address,
        bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
        rssi = rssi,
    )

    private companion object {
        const val TAG = "BleScannerImpl"
    }
}
