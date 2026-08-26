package com.cerocoder.meshrelay.stats

import org.meshtastic.proto.MeshPacket

/** Signal strength of one received packet. */
data class Signal(val snr: Float, val rssi: Float)

/** What a packet turned out to be. */
sealed interface Ingest {
    data class Relayed(
        val relayByte: Int,
        val fromNode: Int,
        val hopStart: Int,
        val hopLimit: Int,
        val signal: Signal?,
    ) : Ingest

    data class Direct(val fromNode: Int, val signal: Signal?) : Ingest

    data object Dropped : Ingest
}

/**
 * Decides what one packet says about the mesh. Ports mesh_stats.py:1035-1073.
 *
 * Pure and total: no clock, no state, no logging. Everything the engine does with
 * a packet follows from the answer here, which is why this file is tested to the
 * edge of its decision table.
 */
object PacketClassifier {

    fun classify(packet: MeshPacket, skippedRelayNodes: Set<Int>): Ingest {
        val from = packet.from
        val relayByte = packet.relay_node
        val hopStart = packet.hop_start
        val hopLimit = packet.hop_limit
        val hopsMade = hopStart - hopLimit
        val signal = signalOf(packet)

        // No relay byte, or a byte that is just the sender announcing itself on a
        // packet that has not been forwarded yet: we heard this node ourselves.
        if (relayByte == 0 || (hopsMade == 0 && Geo.lastByteOfNodeNum(from) == relayByte)) {
            return Ingest.Direct(from, signal)
        }

        if (from in skippedRelayNodes) {
            // Exactly one hop made means we were the first receiver, so this is the
            // skipped node's own transmission. More than one, and something did
            // forward it, and that something is a relay worth measuring.
            // No hop information at all: keep the original's behaviour and drop.
            val heardFirstHand = hopStart == 0 || hopsMade == 1
            if (heardFirstHand) return Ingest.Dropped
        }

        return Ingest.Relayed(relayByte, from, hopStart, hopLimit, signal)
    }

    /**
     * Signal strength, or null when the packet carried none.
     *
     * rx_snr and rx_rssi are not optional fields, so an unset value and a real 0
     * are the same bits. RSSI decides for both: 0 dBm is not physically
     * observable, while exactly 0.0 dB SNR is ordinary. Read spec section 6.3
     * before changing this - accepting rx_rssi == 0 gives every relay a stream of
     * phantom 0/0 samples that drag its averages toward zero.
     */
    fun signalOf(packet: MeshPacket): Signal? {
        if (packet.rx_rssi == 0) return null
        return Signal(packet.rx_snr, packet.rx_rssi.toFloat())
    }
}
