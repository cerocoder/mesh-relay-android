package com.cerocoder.meshrelay.stats.model

/**
 * One subject's measurements, as an immutable value safe to hand to the
 * interface thread. Built only by
 * [com.cerocoder.meshrelay.stats.SignalSeriesBuffer.snapshot], which copies its
 * arrays trimmed to the number of samples actually held.
 *
 * The arrays are private and the accessors are the whole interface, the same
 * rule [NodeDirectorySnapshot] follows: handing out a `LongArray` would hand out
 * something a caller can write to, and this value's entire purpose is to be
 * safe to read while the engine keeps appending to the buffer it came from.
 *
 * Ordered **oldest first**, index `0` being the earliest retained measurement.
 * That is storage's natural append order; turning it into "newest at the top" is
 * `ChartGeometry`'s job, and doing it in one place is what keeps the two from
 * disagreeing.
 *
 * The accessors do not bounds-check. Every caller derives its index from
 * `ChartGeometry.visibleRows`, which is clamped to `size`, and a check per pixel
 * row is not free; an out-of-range index is a defect in the geometry, and an
 * exception is how it should read.
 */
class SignalSeries(
    private val times: LongArray,
    private val rssiValues: FloatArray,
    private val snrValues: FloatArray,
    private val latI: IntArray,
    private val lonI: IntArray,
    private val source: ByteArray,
    /**
     * Every sample ever appended to the buffer this came from, retained or
     * evicted. The chart's scroll anchor is the difference between two of these
     * (spec section 8.6); `size` cannot serve, because it stops growing the moment
     * the ring saturates while measurements keep arriving.
     *
     * Reset to zero by `clear()`, so a decrease is how a chart learns the
     * statistics were reset under it.
     */
    val totalAppended: Long,
) {
    val size: Int get() = times.size

    fun atMillis(index: Int): Long = times[index]

    fun rssi(index: Int): Float = rssiValues[index]

    fun snr(index: Int): Float = snrValues[index]

    /** Where the observer was for this measurement, or `null` if nothing was known then. */
    fun positionOf(index: Int): StampedPosition? {
        val origin = PositionOrigin.ofCode(source[index]) ?: return null
        return StampedPosition(latI[index], lonI[index], origin)
    }

    companion object {
        val EMPTY = SignalSeries(
            times = LongArray(0),
            rssiValues = FloatArray(0),
            snrValues = FloatArray(0),
            latI = IntArray(0),
            lonI = IntArray(0),
            source = ByteArray(0),
            totalAppended = 0L,
        )
    }
}
