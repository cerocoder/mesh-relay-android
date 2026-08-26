package com.cerocoder.meshrelay.transport

import android.app.Application
import com.cerocoder.meshrelay.ble.BleRadioTransport
import com.cerocoder.meshrelay.emulator.Scenarios
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private object NoopCallback : RadioTransportCallback {
    override fun onConnect() = Unit
    override fun onDisconnect(isPermanent: Boolean, reason: String?) = Unit
    override fun onDataReceived(bytes: ByteArray) = Unit
}

/**
 * A stand-in context for the factory constructor.
 *
 * The factory only stores the context and passes it into the deferred [openSession] -
 * which these tests never invoke (a BLE session only opens on `BleRadioTransport.start`) -
 * so any object of the right type will do. `Application` is chosen for its no-argument
 * constructor: no need to guess at the nullability of `ContextWrapper`'s `base`
 * parameter, and `testOptions.unitTests.isReturnDefaultValues` in this module already
 * makes calls on it safe.
 */
private fun fakeContext() = Application()

class RadioTransportFactoryImplTest {

    @Test
    fun `a demo address yields a fake transport`() = runTest {
        val factory = RadioTransportFactoryImpl(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            isDebugBuild = true,
            context = fakeContext(),
        )

        val transport = factory.create("m:${Scenarios.FIVE_NODES_ID}", NoopCallback)

        assertTrue(transport is FakeRadioTransport)
    }

    @Test
    fun `a demo device is unavailable in a release build`() = runTest {
        val factory = RadioTransportFactoryImpl(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            isDebugBuild = false,
            context = fakeContext(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            factory.create("m:${Scenarios.FIVE_NODES_ID}", NoopCallback)
        }
    }

    @Test
    fun `an unknown scenario is rejected`() = runTest {
        val factory = RadioTransportFactoryImpl(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            isDebugBuild = true,
            context = fakeContext(),
        )

        assertThrows(IllegalStateException::class.java) {
            factory.create("m:no-such-scenario", NoopCallback)
        }
    }

    @Test
    fun `a BLE address yields a BLE transport`() = runTest {
        val factory = RadioTransportFactoryImpl(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            isDebugBuild = true,
            context = fakeContext(),
        )

        val transport = factory.create("xAA:BB:CC:DD:EE:FF", NoopCallback)

        assertTrue(transport is BleRadioTransport)
    }
}
