package com.cerocoder.meshrelay.stats

/**
 * The only place the clock is read.
 *
 * An interface rather than a direct call because a later stage replays recorded
 * packets, and every age on every screen has to be measured against the replayed
 * epoch rather than the wall clock. One stray System.currentTimeMillis() outside
 * this file makes that impossible to add without hunting it down.
 */
fun interface TimeSource {
    fun nowMillis(): Long
}

object SystemTimeSource : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
