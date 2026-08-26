package com.cerocoder.meshrelay.transport

import android.content.Context
import com.cerocoder.meshrelay.ble.BleRadioTransport
import com.cerocoder.meshrelay.ble.nordic.openNordicSession
import com.cerocoder.meshrelay.emulator.Scenarios
import kotlinx.coroutines.CoroutineScope

/**
 * Picks the transport implementation from the address prefix.
 *
 * This is the single branch point between connection methods: stage 2 will add a
 * second branch here for BLE, and nowhere else will need to change.
 */
class RadioTransportFactoryImpl(
    private val scope: CoroutineScope,
    private val isDebugBuild: Boolean,
    private val context: Context,
) : RadioTransportFactory {

    override fun create(address: String, callback: RadioTransportCallback): RadioTransport {
        MeshProtocol.scenarioIdOrNull(address)?.let { scenarioId ->
            // Demo devices must not exist in a release build: the address arrives from
            // outside the code (a device list, saved settings), so a release build must
            // refuse it no matter how that address ended up saved.
            require(isDebugBuild) { "demo devices are only available in a debug build" }
            val scenario = checkNotNull(Scenarios.byId(scenarioId)) { "unknown scenario: $scenarioId" }
            return FakeRadioTransport(scenario = scenario, callback = callback, parentScope = scope)
        }

        MeshProtocol.bleMacOrNull(address)?.let { mac ->
            return BleRadioTransport(
                mac = mac,
                callback = callback,
                parentScope = scope,
                openSession = { address -> openNordicSession(context, address) },
            )
        }

        error("unrecognized address format: $address")
    }
}
