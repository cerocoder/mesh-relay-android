package com.cerocoder.meshrelay.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ReconnectPolicyTest {

    private val policy = ReconnectPolicy()

    @Test
    fun `delay doubles and caps out`() {
        assertEquals(5.seconds, policy.backoffFor(1))
        assertEquals(10.seconds, policy.backoffFor(2))
        assertEquals(20.seconds, policy.backoffFor(3))
        assertEquals(40.seconds, policy.backoffFor(4))
        assertEquals(60.seconds, policy.backoffFor(5))
        assertEquals("the cap holds beyond that too", 60.seconds, policy.backoffFor(12))
    }

    @Test
    fun `a stable connection resets the failure counter`() {
        policy.onOutcome(wasStable = false)
        policy.onOutcome(wasStable = false)

        assertEquals(0, policy.onOutcome(wasStable = true))
    }

    @Test
    fun `unstable connections accumulate the counter`() {
        assertEquals(1, policy.onOutcome(wasStable = false))
        assertEquals(2, policy.onOutcome(wasStable = false))
    }

    @Test
    fun `the pause before an attempt is pinned against an accidental edit`() {
        // This test measures nothing: comparing the constant to itself only fails
        // in exactly one case - if someone edits the literal. That is enough. The
        // source of the value itself is documented on the class, and only a live
        // node can verify it.
        assertEquals(3.seconds, policy.settleDelay)
    }

    @Test
    fun `a zero counter gives the base delay`() {
        // onOutcome returns 0 after every successful connection, and Task 8 feeds
        // that zero straight into backoffFor: this is the normal path, not an edge
        // case.
        assertEquals(5.seconds, policy.backoffFor(0))
        assertEquals("a negative value must not break the backoff", 5.seconds, policy.backoffFor(-3))
    }
}
