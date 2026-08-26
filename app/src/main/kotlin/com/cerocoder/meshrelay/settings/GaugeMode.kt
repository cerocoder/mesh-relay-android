package com.cerocoder.meshrelay.settings

/**
 * Ports VIS_MODE_SIMPLE / VIS_MODE_COMPLEX, mesh_stats.py:176-182.
 *
 * Lives in settings/ rather than ui/ because AppSettings carries it, and the
 * persistence layer must not depend on the interface layer.
 */
enum class GaugeMode { SIMPLE, COMPLEX }
