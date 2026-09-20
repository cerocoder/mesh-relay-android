package com.cerocoder.meshrelay.export

/**
 * Which of the four export commands this is - carried explicitly rather than
 * inferred from the key list, because an empty list export (nothing tracked
 * yet on that screen) must still name itself correctly. See
 * [ExportFileNames.suggest].
 */
enum class ExportKind { RELAY_DETAIL, NEIGHBOUR_DETAIL, RELAY_LIST, NEIGHBOUR_LIST }
