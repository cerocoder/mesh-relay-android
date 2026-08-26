package com.cerocoder.meshrelay.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * Material 3 typography, default in every style except [Typography.bodySmall].
 *
 * Node identifiers and hexadecimal relay bytes are rendered with bodySmall, so
 * it is set to a monospace font family to keep columns of them aligned.
 */
val MeshRelayTypography = Typography(
    bodySmall = Typography().bodySmall.copy(fontFamily = FontFamily.Monospace),
)
