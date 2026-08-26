package com.cerocoder.meshrelay.stats.model

/**
 * Running statistics for one signal metric. Ports mesh_stats.py:223-251.
 *
 * The sum is a Double where the original used a Python float: a survey collects
 * tens of thousands of samples, and a Float accumulator drifts far enough to be
 * visible in the average.
 */
data class SignalStats(
    val minVal: Float = Float.POSITIVE_INFINITY,
    val maxVal: Float = Float.NEGATIVE_INFINITY,
    val sumVal: Double = 0.0,
    val count: Int = 0,
    val lastVal: Float = 0f,
) {
    val avg: Float get() = if (count > 0) (sumVal / count).toFloat() else 0f

    val hasData: Boolean get() = count > 0

    fun plus(value: Float): SignalStats = SignalStats(
        minVal = minOf(minVal, value),
        maxVal = maxOf(maxVal, value),
        sumVal = sumVal + value,
        count = count + 1,
        lastVal = value,
    )

    companion object {
        val EMPTY = SignalStats()
    }
}
