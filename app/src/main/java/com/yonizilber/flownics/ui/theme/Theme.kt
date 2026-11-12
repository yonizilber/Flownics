package com.yonizilber.flownics.ui.theme

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

private val LightColorScheme = lightColorScheme(
    primary = Sea40,
    onPrimary = Color.White,
    primaryContainer = Sea90,
    onPrimaryContainer = Sea10,
    secondary = Slate40,
    secondaryContainer = Slate90,
    onSecondaryContainer = Slate30,
    tertiary = Coral40,
    tertiaryContainer = Coral90,
    onTertiaryContainer = Coral30,
    background = Neutral96,
    surface = Neutral96,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral90,
)

private val DarkColorScheme = darkColorScheme(
    primary = Sea80,
    onPrimary = Sea10,
    primaryContainer = Sea30,
    onPrimaryContainer = Sea90,
    secondary = Slate80,
    secondaryContainer = Slate30,
    onSecondaryContainer = Slate90,
    tertiary = Coral80,
    tertiaryContainer = Coral30,
    onTertiaryContainer = Coral90,
    background = Neutral6,
    surface = Neutral6,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
)

@Composable
fun FlownicsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default so the app keeps its own palette; becomes a user setting later.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
