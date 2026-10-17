package tw.hoonsor.voicehub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 唯一色票（docs/DESIGN.md 引用這裡）。深色底＋極光漸層強調色，全部由 HSL 調出，不用原色。
 */
object C {
    val bg = Color.hsl(225f, 0.26f, 0.07f)
    val bgDeep = Color.hsl(226f, 0.30f, 0.05f)
    val surface = Color.hsl(226f, 0.22f, 0.11f)
    val surfaceHi = Color.hsl(226f, 0.20f, 0.15f)
    val field = Color.hsl(226f, 0.24f, 0.09f)
    val border = Color.hsl(226f, 0.18f, 0.21f)
    val borderHi = Color.hsl(226f, 0.16f, 0.30f)
    val text = Color.hsl(222f, 0.30f, 0.94f)
    val textSoft = Color.hsl(222f, 0.18f, 0.82f)
    val muted = Color.hsl(222f, 0.12f, 0.62f)
    val dim = Color.hsl(222f, 0.10f, 0.46f)

    // 極光四色（對話光影、按鈕）
    val auroraBlue = Color.hsl(222f, 0.92f, 0.66f)
    val auroraViolet = Color.hsl(268f, 0.86f, 0.70f)
    val auroraTeal = Color.hsl(172f, 0.64f, 0.56f)
    val auroraPink = Color.hsl(328f, 0.78f, 0.68f)

    val accent = auroraBlue
    val onAccent = Color.hsl(226f, 0.40f, 0.10f)
    val ok = Color.hsl(150f, 0.56f, 0.58f)
    val warn = Color.hsl(32f, 0.90f, 0.64f)
    val danger = Color.hsl(8f, 0.82f, 0.68f)
    val userBubble = Color.hsl(222f, 0.40f, 0.22f)
    val botBubble = Color.hsl(226f, 0.20f, 0.13f)
    val scrim = Color.hsl(228f, 0.40f, 0.04f)
}

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun VoiceHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            onPrimary = C.onAccent,
            secondary = C.auroraViolet,
            tertiary = C.auroraTeal,
            background = C.bg,
            onBackground = C.text,
            surface = C.surface,
            onSurface = C.text,
            surfaceVariant = C.surfaceHi,
            onSurfaceVariant = C.muted,
            surfaceContainer = C.surface,
            surfaceContainerHigh = C.surfaceHi,
            surfaceContainerHighest = C.surfaceHi,
            outline = C.borderHi,
            outlineVariant = C.border,
            error = C.danger,
        ),
        typography = typography,
        content = content,
    )
}
