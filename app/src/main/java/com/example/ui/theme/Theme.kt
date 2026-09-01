package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val BentoDarkColorScheme = darkColorScheme(
    primary = BentoHeroLilac,
    onPrimary = BentoHeroOnLilac,
    primaryContainer = BentoDarkSurface,
    onPrimaryContainer = BentoHeroLilac,
    secondary = EmeraldAccent,
    onSecondary = BentoDarkBackground,
    secondaryContainer = BentoDarkSurfaceVariant,
    onSecondaryContainer = EmeraldAccent,
    tertiary = AmberWarning,
    onTertiary = BentoDarkBackground,
    background = BentoDarkBackground,
    onBackground = TextPrimaryDark,
    surface = BentoDarkSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = BentoDarkSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = BentoDarkBorder,
    error = RoseDestructive,
    onError = BentoHeroOnLilac
)

private val BentoLightColorScheme = lightColorScheme(
    primary = BentoHeroContainerLight,
    onPrimary = BentoHeroOnContainerLight,
    primaryContainer = BentoLightSurfaceVariant,
    onPrimaryContainer = BentoHeroContainerLight,
    secondary = EmeraldAccentDark,
    onSecondary = BentoLightSurface,
    secondaryContainer = BentoLightSurfaceVariant,
    onSecondaryContainer = EmeraldAccentDark,
    tertiary = AmberWarning,
    onTertiary = BentoLightSurface,
    background = BentoLightBackground,
    onBackground = TextPrimaryLight,
    surface = BentoLightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = BentoLightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = BentoLightBorder,
    error = RoseDestructive,
    onError = BentoLightSurface
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> BentoDarkColorScheme
        else -> BentoLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

