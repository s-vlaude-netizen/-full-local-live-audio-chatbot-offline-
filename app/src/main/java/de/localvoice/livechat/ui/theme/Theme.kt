package de.localvoice.livechat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF00344A),
    secondary = Color(0xFF9DD5C0),
    background = Color(0xFF0B1220),
    surface = Color(0xFF121C2E),
    surfaceVariant = Color(0xFF1B2740),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0369A1),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2E7D6B),
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3E9F2),
)

@Composable
fun LocalLiveChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
