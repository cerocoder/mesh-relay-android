package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.SignalSeries
import com.cerocoder.meshrelay.stats.model.StampedPosition

/**
 * One relay's or one neighbour's measurements, oldest first, capped at
 * [MAX_SAMPLES].
 *
 * Mutable and confined to the engine's coroutine, exactly as `relays` and
 * `neighbours` already are. Nothing outside the engine ever holds one; what
 * leaves is [snapshot], which copies.
 *
 * **Six parallel primitive arrays, not a list of objects.** A measurement is 25
 * bytes here - 8 for the timestamp, 4 each for RSSI, SNR, latitude and
 * longitude, 1 for the position's origin - so a full buffer is 125 KB. An object
 * per measurement, with its header and its reference, costs more than twice that
 * in the same worst case, and this is already the largest allocation in the
 * application: a typical session of sixty subjects holds about 7.5 MB, and the
 * theoretical worst case (every relay byte seen, plus neighbours) about 32 MB.
 * That figure is stated in the design rather than discovered in a heap dump, and
 * [MAX_SAMPLES] is the one line to change if the field says 5000 is too many.
 *
 * A ring rather than a list that trims: trimming a 5000-element list on every
 * packet copies 5000 elements per packet, at mesh traffic rates, per subject.
 */
class SignalSeriesBuffer(private val capacity: Int = MAX_SAMPLES) {

    init {
        require(capacity > 0) { "a series buffer needs room for at least one sample" }
    }

    private val times = LongArray(capacity)
    private val rssiValues = FloatArray(capacity)
    private val snrValues = FloatArray(capacity)
    private val latI = IntArray(capacity)
    private val lonI = IntArray(capacity)

    /** `0` is "no position"; see [PositionOrigin.NONE]. */
    private val source = ByteArray(capacity)

    /** Index of the oldest retained sample. */
    private var head = 0

    /** How many of [capacity] slots are in use. */
    private var size = 0

    /**
     * Every sample ever appended, retained or evicted. See
     * [SignalSeries.totalAppended] for what reads it and why `size` cannot serve.
     */
    var totalAppended = 0L
        private set

    fun append(atMillis: Long, rssi: Float, snr: Float, position: StampedPosition?) {
        val slot = (head + size) % capacity
        times[slot] = atMillis
        rssiValues[slot] = rssi
        snrValues[slot] = snr
        // Written unconditionally, including the "no position" case. Writing them
        // only when a position exists would leave the evicted sample's coordinates
        // in a reused slot, and the new measurement would inherit somebody else's
        // hillside.
        latI[slot] = position?.latI ?: 0
        lonI[slot] = position?.lonI ?: 0
        source[slot] = position?.origin?.code ?: PositionOrigin.NONE

        if (size < capacity) size++ else head = (head + 1) % capacity
        totalAppended++
    }

    /**
     * A copy, oldest first, trimmed to [size].
     *
     * Copied slot by slot rather than with two `copyOfRange` calls: the ring wraps,
     * so the retained window is one or two runs depending on where the head sits,
     * and one loop that is always right beats two paths of which one is exercised
     * only after 5000 packets.
     */
    fun snapshot(): SignalSeries {
        val outTimes = LongArray(size)
        val outRssi = FloatArray(size)
        val outSnr = FloatArray(size)
        val outLat = IntArray(size)
        val outLon = IntArray(size)
        val outSource = ByteArray(size)
        for (i in 0 until size) {
            val slot = (head + i) % capacity
            outTimes[i] = times[slot]
            outRssi[i] = rssiValues[slot]
            outSnr[i] = snrValues[slot]
            outLat[i] = latI[slot]
            outLon[i] = lonI[slot]
            outSource[i] = source[slot]
        }
        return SignalSeries(outTimes, outRssi, outSnr, outLat, outLon, outSource, totalAppended)
    }

    /**
     * Forgets everything, including the count.
     *
     * The arrays themselves are not zeroed: nothing reads past [size], and
     * [append] overwrites every field of a slot before it becomes readable again.
     */
    fun clear() {
        head = 0
        size = 0
        totalAppended = 0L
    }

    companion object {
        /**
         * 5000 measurements per relay and per neighbour. At 25 bytes each that is
         * 125 KB per subject; see this class's own KDoc for the session and
         * worst-case totals that follow from it.
         */
        const val MAX_SAMPLES = 5000
    }
}
