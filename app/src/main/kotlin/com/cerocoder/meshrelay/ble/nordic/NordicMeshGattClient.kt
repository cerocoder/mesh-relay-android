package com.cerocoder.meshrelay.ble.nordic

import com.cerocoder.meshrelay.ble.protocol.MeshGattClient
import kotlinx.coroutines.flow.Flow

/** [MeshGattClient] over the real GATT. There is no logic here - just translating calls. */
class NordicMeshGattClient(private val manager: MeshBleManager) : MeshGattClient {

    override val fromNumNotifications: Flow<Unit> get() = manager.notifications

    override suspend fun awaitSubscriptionReady() = manager.awaitReady()

    override suspend fun readFromRadio(): ByteArray = nordicCall(manager.appContext) { manager.read() }

    override suspend fun writeToRadio(bytes: ByteArray) = nordicCall(manager.appContext) { manager.write(bytes) }
}
