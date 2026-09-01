package com.cerocoder.meshrelay.ui.nav

/**
 * The bottom bar's tabs, in the order they are drawn: ordinal is tab order, and
 * [backStackSaver] encodes the ordinal, so an entry may be appended but not
 * reordered without invalidating a saved stack. [MY_NODE] is last so that Relays
 * stays the landing tab after a handshake.
 */
enum class MainTab { RELAYS, NEIGHBOURS, MY_NODE }

/**
 * What a detail screen is about.
 *
 * A relay is a one-byte guess that may match several nodes; a neighbour is a
 * known node. The two detail screens differ in shape because of it, and this
 * type is what carries the difference.
 */
sealed interface DetailSubject {
    data class Relay(val relayByte: Int) : DetailSubject
    data class Neighbour(val nodeNum: Int) : DetailSubject
}

sealed interface Screen {
    data object Devices : Screen
    data class Main(val tab: MainTab) : Screen
    data object Settings : Screen
    data class Detail(val subject: DetailSubject) : Screen
    data class RemoteNode(val nodeNum: Int, val viaRelayByte: Int?) : Screen
}
