package com.cerocoder.meshrelay.ble

import com.cerocoder.meshrelay.transport.DeviceListEntry
import kotlinx.coroutines.flow.Flow

/** Scans for Meshtastic nodes over the air. */
interface BleScanner {

    /**
     * Devices found. The flow lives as long as it is collected, and repeats a device
     * on every new advertisement - the consumer is responsible for deduplicating by
     * address.
     *
     * A scan failure ends the flow normally, without an exception: the system
     * throttles scans that start too often, and that is not a reason to bring down
     * the screen. A consumer that wants a retry must subscribe again - the flow
     * itself does not resume attempts.
     */
    fun scan(): Flow<DeviceListEntry.Ble>
}
