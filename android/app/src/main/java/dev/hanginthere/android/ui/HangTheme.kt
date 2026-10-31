package dev.hanginthere.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable internal fun HangTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(primary = Color(0xFFD5F49A), onPrimary = Color(0xFF18392E),
        primaryContainer = Color(0xFF2A4030), surface = Color(0xFF16221D), background = Color(0xFF16221D),
        secondary = Color(0xFFD5F49A), secondaryContainer = Color(0xFF2A4030), onSecondaryContainer = Color(0xFFF6F5ED),
        onPrimaryContainer = Color(0xFFF6F5ED), surfaceVariant = Color(0xFF23372C), onSurfaceVariant = Color(0xFFADBEAF),
        surfaceContainerLowest = Color(0xFF121C17), surfaceContainerLow = Color(0xFF23372C), surfaceContainer = Color(0xFF23372C),
        surfaceContainerHigh = Color(0xFF293D31), surfaceContainerHighest = Color(0xFF304637),
        outline = Color(0xFF58705F), outlineVariant = Color(0xFF354B3D), onSurface = Color(0xFFF6F5ED), onBackground = Color(0xFFF6F5ED))
    else lightColorScheme(primary = Color(0xFF244638), onPrimary = Color.White,
        primaryContainer = Color(0xFFE1EFD9), surface = Color(0xFFF7F6EF), background = Color(0xFFF7F6EF),
        secondary = Color(0xFF244638), secondaryContainer = Color(0xFFE1EFD9), onSecondaryContainer = Color(0xFF203C30),
        onPrimaryContainer = Color(0xFF203C30), surfaceVariant = Color(0xFFEDF0E6), onSurfaceVariant = Color(0xFF607366),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color.White,
        surfaceContainerHigh = Color(0xFFF0F2E8), surfaceContainerHighest = Color(0xFFE8EDE1),
        outline = Color(0xFF8B9D8D), outlineVariant = Color(0xFFDCE3D7), onSurface = Color(0xFF203C30), onBackground = Color(0xFF203C30))
    MaterialTheme(colorScheme = scheme, content = content)
}
