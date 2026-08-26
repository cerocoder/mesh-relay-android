package com.cerocoder.meshrelay.transport

/** An entry in the device list. The address uniquely determines the transport. */
sealed class DeviceListEntry {

    abstract val name: String
    abstract val address: String

    /** A virtual device that plays back a scenario. Debug builds only. */
    data class Demo(val scenarioId: String, override val name: String) : DeviceListEntry() {
        override val address: String get() = "${MeshProtocol.DEMO_PREFIX}$scenarioId"
    }

    /** A real node found over the air by the scanner. */
    data class Ble(
        override val name: String,
        val mac: String,
        val bonded: Boolean,
        val rssi: Int?,
    ) : DeviceListEntry() {
        override val address: String get() = "${MeshProtocol.BLE_PREFIX}$mac"
    }
}
