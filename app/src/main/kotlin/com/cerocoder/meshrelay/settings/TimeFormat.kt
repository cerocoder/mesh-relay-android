package com.cerocoder.meshrelay.settings

/**
 * Which clock the interface prints. Governs every absolute time it shows - the
 * node database's last-heard, and the Graph's two Time fields and its crosshair.
 *
 * Relative ages ("5min ago") are unaffected: they carry no clock at all.
 */
enum class TimeFormat { TWELVE_HOUR, TWENTY_FOUR_HOUR }
