package com.cerocoder.meshrelay.ui.theme

import androidx.compose.ui.graphics.Color

val SnrTrack = Color(0xFF2E7D32)
val SnrMarker = Color(0xFF81C784)
val RssiTrack = Color(0xFF1565C0)
val RssiMarker = Color(0xFF64B5F6)
val FlashMarker = Color(0xFFFFC107)

// Relay-candidate-comparison verdicts (2026-09-04 design, section 6). Named for
// the verdict they colour, not for the colour itself, so a later palette change
// does not have to hunt for call sites. Deliberately not aliases of the gauge
// constants above even where a shade coincides (VerdictUncertain reuses
// FlashMarker's own amber numerically) - the packet flash and an uncertain
// verdict mean unrelated things, and a shared constant would tie them together
// by accident.
val VerdictConsistent = Color(0xFF81C784) // the SNR marker's green, already this app's "good"
val VerdictUncertain = Color(0xFFFFC107) // the same amber FlashMarker uses
val VerdictInconsistent = Color(0xFFE57373) // new: a red muted to match the others' weight
