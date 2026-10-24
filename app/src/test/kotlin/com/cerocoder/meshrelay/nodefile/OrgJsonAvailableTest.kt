package com.cerocoder.meshrelay.nodefile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the one thing that can go wrong silently: with `isReturnDefaultValues` on,
 * Android's stub `org.json` returns zeros instead of failing, so a test class that
 * parses JSON would pass against nothing. This fails unless the real library is on
 * the test classpath.
 */
class OrgJsonAvailableTest {

    @Test
    fun `real org json parses in a jvm test`() {
        assertEquals(2, JSONObject("""{"a":[1,2]}""").getJSONArray("a").length())
    }
}
