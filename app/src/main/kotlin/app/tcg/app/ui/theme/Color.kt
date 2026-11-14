package app.tcg.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Direção "catálogo": papel quente, tinta, verde profundo e latão. Sem lilás, sem gradiente
// decorativo. As superfícies são mais claras que o fundo para o cartão se destacar por contraste
// de valor, não por sombra.
val LightColors = lightColorScheme(
    primary = Color(0xFF1F4A3D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8DF),
    onPrimaryContainer = Color(0xFF10302A),
    inversePrimary = Color(0xFF9FD0BA),
    secondary = Color(0xFF8A5A2B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5E3CC),
    onSecondaryContainer = Color(0xFF3A2410),
    tertiary = Color(0xFF7A2E2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3DADA),
    onTertiaryContainer = Color(0xFF3D1212),
    error = Color(0xFF9B2C2C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F2E7),
    onBackground = Color(0xFF17140F),
    surface = Color(0xFFFFFCF5),
    onSurface = Color(0xFF17140F),
    surfaceVariant = Color(0xFFECE4D4),
    onSurfaceVariant = Color(0xFF5C5446),
    outline = Color(0xFF8B8375),
    outlineVariant = Color(0xFFD8CFBD),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF33302A),
    inverseOnSurface = Color(0xFFF7F2E7),
    surfaceTint = Color(0xFF1F4A3D),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF9FD0BA),
    onPrimary = Color(0xFF0F2A22),
    primaryContainer = Color(0xFF2C5A4B),
    onPrimaryContainer = Color(0xFFD6EFE2),
    inversePrimary = Color(0xFF1F4A3D),
    secondary = Color(0xFFE0B884),
    onSecondary = Color(0xFF3A2410),
    secondaryContainer = Color(0xFF5A3B1C),
    onSecondaryContainer = Color(0xFFF5E3CC),
    tertiary = Color(0xFFE5A3A3),
    onTertiary = Color(0xFF3D1212),
    tertiaryContainer = Color(0xFF5A2A2A),
    onTertiaryContainer = Color(0xFFF3DADA),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF12100D),
    onBackground = Color(0xFFEFE7D8),
    surface = Color(0xFF191612),
    onSurface = Color(0xFFEFE7D8),
    surfaceVariant = Color(0xFF2A241C),
    onSurfaceVariant = Color(0xFFC9BFAE),
    outline = Color(0xFF8A8272),
    outlineVariant = Color(0xFF3A342B),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFEFE7D8),
    inverseOnSurface = Color(0xFF33302A),
    surfaceTint = Color(0xFF9FD0BA),
)
