package com.cerocoder.meshrelay.location

import com.cerocoder.meshrelay.stats.model.StampedPosition
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's own idea of where it is.
 *
 * **The latest fix only, and no history.** A measurement carries the position it
 * was taken at (`SignalSeriesBuffer`), so nothing ever needs to search backwards
 * through this - which is the whole reason storing the position per sample was
 * chosen over searching for one afterwards.
 *
 * Plain JVM, no `android.*`, so `AppContainer`'s wiring and the engine's
 * behaviour under it are testable without a device.
 */
interface PhoneLocationSource {

    /** null until the first fix arrives, and after a refused permission, forever. */
    val fix: StateFlow<StampedPosition?>

    /** Idempotent. A no-op when the permission has not been granted. */
    fun start()

    /** Idempotent. Stops the updates rather than merely ignoring them. */
    fun stop()
}
