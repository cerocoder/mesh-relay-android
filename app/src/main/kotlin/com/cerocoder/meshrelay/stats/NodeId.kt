package com.cerocoder.meshrelay.stats

import java.util.Locale

/**
 * Parses and formats the `!xxxxxxxx` node-id notation used throughout
 * Meshtastic tooling, including the terminal tool's `--skip-relay` option.
 *
 * Lives in stats/ rather than settings/ because both the settings layer and
 * the interface layer need it, and ui/ must not depend on settings/ for a
 * formatter.
 */
object NodeId {

    private val HEX_ID = Regex("^[0-9a-fA-F]{8}$")

    /**
     * Accepts an optional leading `!`, is case-insensitive and trims
     * whitespace. Requires exactly eight hexadecimal digits; anything else is
     * `null` rather than a guess, since stored values can come from a
     * hand-edited preference file.
     *
     * Parses through [Long] and narrows with [Long.toInt]: `"9e75f1a4"`
     * overflows a signed Int, and `Integer.parseInt` would throw.
     */
    fun parse(value: String): Int? {
        val trimmed = value.trim()
        val hex = trimmed.removePrefix("!")
        if (!HEX_ID.matches(hex)) return null
        return hex.toLong(16).toInt()
    }

    /** Renders [nodeNum] as `"!%08x"`, lower case. */
    fun format(nodeNum: Int): String = String.format(Locale.ROOT, "!%08x", nodeNum)
}
