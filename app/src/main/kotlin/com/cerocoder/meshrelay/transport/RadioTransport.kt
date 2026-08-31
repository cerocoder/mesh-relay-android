package com.cerocoder.meshrelay.transport

/**
 * Transport to the node: raw bytes in both directions.
 *
 * Implementations: [FakeRadioTransport] (demo device) and BleRadioTransport (a live node).
 * Nothing above this interface knows what is actually connected.
 */
interface RadioTransport {

    /** Start establishing the connection. The result arrives via the callback. */
    fun start()

    /** Send an encoded ToRadio. The call does not block. */
    fun send(bytes: ByteArray)

    /** Tear down the connection and release resources. Safe to call again. */
    suspend fun close()
}

/** Narrow callback transport -> app. */
interface RadioTransportCallback {

    fun onConnect()

    /**
     * @param isPermanent true - connection attempts have stopped (the user disconnected,
     *   the device is unreachable); false - the link may recover on its own.
     * @param reason a [FailureReason] fit for the UI, or null if the disconnect is
     *   routine and there is nothing to explain. The transport must hand over a reason
     *   that names the failure, not an exception message: a raw exception string on
     *   screen is useless to the user and leaks implementation detail. It may be
     *   already-resolved text carried up from a lower layer ([FailureReason.Literal]) or
     *   an unresolved resource id ([FailureReason.Resource]) - either way, nothing above
     *   this callback needs a [android.content.Context] to produce it.
     */
    fun onDisconnect(isPermanent: Boolean, reason: FailureReason? = null)

    /** An encoded FromRadio has arrived. */
    fun onDataReceived(bytes: ByteArray)
}

/** Creates a transport from a device's internal address. */
interface RadioTransportFactory {
    fun create(address: String, callback: RadioTransportCallback): RadioTransport
}
