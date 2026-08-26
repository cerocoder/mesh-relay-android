package com.cerocoder.meshrelay.ble.protocol

import kotlinx.coroutines.flow.Flow

/**
 * The lowest BLE layer: exactly the operations on Meshtastic characteristics that
 * the protocol needs, and nothing more.
 *
 * There are two implementations: [com.cerocoder.meshrelay.ble.nordic.NordicMeshGattClient]
 * over real GATT, and a test double. Thanks to this, the drain loop - the most
 * fragile part of the protocol - is verified by plain JVM tests, without Android
 * and without a node.
 */
interface MeshGattClient {

    /**
     * Notifications from the FROMNUM characteristic.
     *
     * The value carries no meaning: the firmware only signals "there is data", and
     * the actual packets are read out via [readFromRadio].
     */
    val fromNumNotifications: Flow<Unit>

    /**
     * Suspends until the CCCD has actually been written, i.e. until notifications
     * are truly enabled.
     *
     * Without this wait, the configuration request goes out into the void: the
     * firmware will answer with a notification that no one is there to receive, and
     * the app will hang in Connecting.
     */
    suspend fun awaitSubscriptionReady()

    /** One read of FROMRADIO. An empty array means the queue is empty. */
    suspend fun readFromRadio(): ByteArray

    /** Write one encoded ToRadio to TORADIO. */
    suspend fun writeToRadio(bytes: ByteArray)
}
