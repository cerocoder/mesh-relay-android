package com.cerocoder.meshrelay.nodefile

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The document picker's suggested name for a saved node file - the person can always rename it. */
object NodeFileNames {

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun suggest(nowMillis: Long): String = "meshrelay_nodes_${TIMESTAMP.format(Instant.ofEpochMilli(nowMillis))}.json"
}
