package no.heimflyt.launcher.ui.theme

import android.provider.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import no.heimflyt.launcher.theme.palette.Contrast
import no.heimflyt.launcher.theme.palette.FallbackPalette
import no.heimflyt.launcher.theme.palette.ResolvedColors
import no.heimflyt.launcher.theme.palette.TokenMapper

fun rgb(value: Int) = Color(0xff000000.toInt() or value)

@Immutable
data class ColorTokens(
    val ground: Color, val groundDeep: Color, val raised: Color, val selection: Color,
    val ink: Color, val inkStrong: Color, val inkMuted: Color, val control: Color, val hairline: Color,
    val accent: Color, val accentInk: Color, val onAccent: Color, val accentVeil: Color,
    val danger: Color, val dangerInk: Color, val positive: Color, val caution: Color,
    val identity: List<Color>, val onIdentity: List<Color>, val scrim: Color, val isLight: Boolean,
    /** Home "Dusk" ground stops: groundDeep (top) → ground (65%) and a soft accent glow at the bottom. */
    val duskTop: Color, val duskGlow: Color,
) {
    companion object {
        fun from(r: ResolvedColors) = ColorTokens(
            rgb(r.ground), rgb(r.groundDeep), rgb(r.raised), rgb(r.selection), rgb(r.ink), rgb(r.inkStrong), rgb(r.inkMuted),
            rgb(r.control), rgb(r.hairline), rgb(r.accent), rgb(r.accentInk), rgb(r.onAccent), rgb(r.accentVeil),
            rgb(r.danger), rgb(r.dangerInk), rgb(r.positive), rgb(r.caution), r.identity.map(::rgb), r.onIdentity.map(::rgb),
            rgb(r.scrim), r.isLight, rgb(HomeGround.top(r)), rgb(HomeGround.glow(r)),
        )
    }
}

/** The compiled fallback's Home ground (Tokyo Night + Dusk), drawn statically before any generation exists. Pure so its text pairs are testable. */
object HomeGround {
    fun top(r: ResolvedColors) = r.groundDeep
    fun glow(r: ResolvedColors) = Contrast.mix(r.ground, r.accent, 0.10)
}

@Immutable
data class TypeTokens(
    val display: TextStyle, val dateline: TextStyle, val title: TextStyle, val body: TextStyle, val bodyStrong: TextStyle,
    val secondary: TextStyle, val label: TextStyle, val caption: TextStyle, val meta: TextStyle, val tag: TextStyle,
)

object Space { val xxs = 2.dp; val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp; val xxxl = 48.dp
    val gutter = 20.dp; val rowMin = 56.dp; val rowCompact = 48.dp; val target = 48.dp; val field = 52.dp }
object Shapes { val xs = RoundedCornerShape(6.dp); val s = RoundedCornerShape(10.dp); val m = RoundedCornerShape(14.dp)
    val l = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp) }

/** Token durations; Compose applies the system animator scale itself, so these are never multiplied. */
object Motion { const val QUICK = 90; const val STANDARD = 180; const val EXIT = 120; const val GENTLE = 240 }

@Immutable
data class HeimflytTokens(val color: ColorTokens, val type: TypeTokens, val resolved: ResolvedColors, val animationsOff: Boolean)

private fun typeFor(c: ColorTokens): TypeTokens {
    val sans = FontFamily.Default; val mono = FontFamily.Monospace
    return TypeTokens(
        display = TextStyle(fontFamily = sans, fontSize = 60.sp, lineHeight = 64.sp, fontWeight = FontWeight.Light,
            fontFeatureSettings = "tnum", letterSpacing = (-0.01).em, color = c.inkStrong),
        dateline = TextStyle(fontFamily = sans, fontSize = 16.sp, lineHeight = 22.sp, color = c.inkMuted),
        title = TextStyle(fontFamily = sans, fontSize = 24.sp, lineHeight = 30.sp, color = c.inkStrong),
        body = TextStyle(fontFamily = sans, fontSize = 17.sp, lineHeight = 24.sp, color = c.ink),
        bodyStrong = TextStyle(fontFamily = sans, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = c.inkStrong),
        secondary = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.inkMuted),
        label = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = c.ink),
        caption = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.inkMuted),
        meta = TextStyle(fontFamily = mono, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.02.em, color = c.inkMuted),
        tag = TextStyle(fontFamily = mono, fontSize = 14.sp, lineHeight = 20.sp, color = c.accentInk),
    )
}

/** The resolved compiled fallback (Tokyo Night): used until a validated generation is loaded, and whenever it can't be. */
object FallbackTokens {
    val resolved: ResolvedColors by lazy { TokenMapper.map(FallbackPalette.krets) }
    val color: ColorTokens by lazy { ColorTokens.from(resolved) }
}

val LocalHeimflyt = staticCompositionLocalOf<HeimflytTokens> { error("HeimflytTheme missing") }
object Heimflyt { val t: HeimflytTokens @Composable get() = LocalHeimflyt.current }

private fun materialScheme(c: ColorTokens, r: ResolvedColors): ColorScheme {
    val base = if (c.isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        background = c.ground, surface = c.ground, onBackground = c.ink, onSurface = c.ink, onSurfaceVariant = c.inkMuted,
        surfaceVariant = c.raised, surfaceContainer = c.raised, surfaceContainerLow = c.raised, surfaceContainerHigh = c.raised,
        surfaceContainerHighest = c.raised, surfaceContainerLowest = c.raised, surfaceBright = c.raised, surfaceDim = c.ground,
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentVeil, onPrimaryContainer = c.accentInk,
        secondary = c.accent, onSecondary = c.onAccent, secondaryContainer = c.accentVeil, onSecondaryContainer = c.accentInk,
        tertiary = c.accent, onTertiary = c.onAccent, outline = c.control, outlineVariant = c.hairline,
        error = c.danger, onError = rgb(r.onDanger), inverseSurface = c.ink, inverseOnSurface = c.ground, scrim = c.scrim,
    )
}

/** Reads the system animator scale on resume; only used to skip custom effects at 0 (Compose scales its own tweens). */
@Composable
private fun rememberAnimationsOff(): Boolean {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun read() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var off by remember { mutableStateOf(read()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) off = read() }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    return off
}

/** Tokens for [resolved]; also used for theme miniatures, which provide another theme's tokens to a static subtree. */
fun heimflytTokens(resolved: ResolvedColors, animationsOff: Boolean): HeimflytTokens {
    val colors = if (resolved === FallbackTokens.resolved) FallbackTokens.color else ColorTokens.from(resolved)
    return HeimflytTokens(colors, typeFor(colors), resolved, animationsOff)
}

/**
 * The complete validated token set switches atomically when the active generation changes (never interpolated;
 * VISUAL_SYSTEM.md §9.5). `staticCompositionLocalOf` recomposes everything then, which is fine for an owner-initiated apply.
 */
@Composable
fun HeimflytTheme(resolved: ResolvedColors = FallbackTokens.resolved, content: @Composable () -> Unit) {
    val animationsOff = rememberAnimationsOff()
    val tokens = remember(resolved, animationsOff) { heimflytTokens(resolved, animationsOff) }
    val scheme = remember(resolved) { materialScheme(tokens.color, resolved) }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalHeimflyt provides tokens, content = content)
    }
}

/** A static subtree drawn with another theme's tokens (theme cards, previews). Material components inside follow them too. */
@Composable
fun ThemedPreview(resolved: ResolvedColors, content: @Composable () -> Unit) {
    val tokens = remember(resolved) { heimflytTokens(resolved, true) }
    val scheme = remember(resolved) { materialScheme(tokens.color, resolved) }
    MaterialTheme(colorScheme = scheme) { CompositionLocalProvider(LocalHeimflyt provides tokens, content = content) }
}
