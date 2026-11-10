package com.tuntas.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Fixed local palette: intentionally dark even when Android is set to light. */
internal val TuntasDarkColors = darkColorScheme(
    primary = Color(0xFF86D6B3), onPrimary = Color(0xFF082B20),
    primaryContainer = Color(0xFF203D32), onPrimaryContainer = Color(0xFFB8EED5),
    secondary = Color(0xFFBACBC2), onSecondary = Color(0xFF20352A),
    secondaryContainer = Color(0xFF293B33), onSecondaryContainer = Color(0xFFD5E7DC),
    background = Color(0xFF111614), onBackground = Color(0xFFF0F3EF),
    surface = Color(0xFF1B221E), onSurface = Color(0xFFF0F3EF),
    surfaceVariant = Color(0xFF28312C), onSurfaceVariant = Color(0xFFBBC7BE),
    outline = Color(0xFF829087), outlineVariant = Color(0xFF3D4A42),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF542923), onErrorContainer = Color(0xFFFFDAD5)
)

@Composable
fun TuntasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TuntasDarkColors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)
        ),
        typography = Typography(),
        content = content
    )
}
