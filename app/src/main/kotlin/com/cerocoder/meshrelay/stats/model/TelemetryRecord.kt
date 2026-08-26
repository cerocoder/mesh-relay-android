package com.cerocoder.meshrelay.stats.model

/**
 * Per-node telemetry: uptime/restart tracking plus a signal history per metric
 * key. Ports NodeTelemetryRecord, mesh_stats.py:454-459.
 *
 * [withUptime] and [withMetric] exist so the engine never reaches into these
 * fields directly - restart detection in particular is a rule to apply
 * consistently ([withUptime]), not a field the caller should update by hand.
 */
data class TelemetryRecord(
    val lastUptimeSeconds: Int? = null,
    val observedRestartCount: Int = 0,
    val metrics: Map<String, SignalHistory> = emptyMap(),
) {
    /**
     * Records a fresh uptime reading. A node's uptime counter only ever climbs
     * while it stays up, so a new value lower than the one already stored is the
     * only way to notice it rebooted - the counter starts again from zero. When
     * that happens, [observedRestartCount] advances.
     */
    fun withUptime(seconds: Int): TelemetryRecord {
        val previous = lastUptimeSeconds
        val restarted = previous != null && seconds < previous
        return copy(
            lastUptimeSeconds = seconds,
            observedRestartCount = if (restarted) observedRestartCount + 1 else observedRestartCount,
        )
    }

    /** Records one telemetry metric sample under [key]. */
    fun withMetric(key: String, atMillis: Long, value: Float): TelemetryRecord {
        val history = (metrics[key] ?: SignalHistory()).plus(atMillis, value)
        return copy(metrics = metrics + (key to history))
    }
}
