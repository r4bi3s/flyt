package no.heimflyt.launcher.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.heimflyt.launcher.HomeAction
import no.heimflyt.launcher.R
import no.heimflyt.launcher.SemanticDestination
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Shapes
import no.heimflyt.launcher.ui.theme.Space

@Composable
fun Glyph(@DrawableRes res: Int, tint: Color, size: Dp = 22.dp, contentDescription: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(res), contentDescription, modifier.size(size), tint = tint)
}

@DrawableRes fun glyphFor(action: HomeAction): Int = when (action) {
    HomeAction.Apps -> R.drawable.glyph_apps
    HomeAction.Search -> R.drawable.glyph_search
    is HomeAction.Tag -> R.drawable.glyph_tag
    is HomeAction.Group -> R.drawable.glyph_apps
    is HomeAction.Probe -> R.drawable.glyph_direction
    is HomeAction.App -> R.drawable.glyph_apps
    is HomeAction.Semantic -> when (action.destination) {
        SemanticDestination.BROWSER -> R.drawable.glyph_browser
        SemanticDestination.PHONE -> R.drawable.glyph_phone
        SemanticDestination.MESSAGES -> R.drawable.glyph_message
        SemanticDestination.TORCH -> R.drawable.glyph_flashlight
    }
}

/** Nearest of eight compass words for a screen angle (0 = right, 90 = down). */
fun directionWord(angle: Float): String {
    val words = listOf("right", "down-right", "down", "down-left", "left", "up-left", "up", "up-right")
    val a = ((angle % 360f) + 360f) % 360f
    return words[(((a + 22.5f) / 45f).toInt()) % 8]
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.lowercase(), modifier.padding(start = Space.xs, top = Space.l, bottom = Space.xs).semantics { heading() },
        style = Heimflyt.t.type.meta)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** Rows that explain a setting wrap their subtitle; list rows keep one line. */
    subtitleLines: Int = 1,
    compact: Boolean = false,
    singleLine: Boolean = false,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = Heimflyt.t
    val clickModifier = if (onClick != null) Modifier.clip(Shapes.s).combinedClickable(onClick = onClick, onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel, role = Role.Button) else Modifier
    Row(modifier.fillMaxWidth().heightIn(min = if (compact) Space.rowCompact else Space.rowMin).then(clickModifier)
        .padding(horizontal = Space.xs, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }; Spacer(Modifier.width(Space.m)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (strong) t.type.bodyStrong else t.type.body, maxLines = if (singleLine) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = t.type.secondary, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun HField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    token: String? = null,
    onClearToken: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    showGlyph: Boolean = true,
) {
    val t = Heimflyt.t; val c = t.color
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = Space.field).clip(Shapes.m).background(c.raised)
        .border(if (focused) 1.5.dp else 1.dp, if (focused) c.accent else c.control, Shapes.m).padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically) {
        if (showGlyph) { Glyph(R.drawable.glyph_search, c.inkMuted, 20.dp); Spacer(Modifier.width(Space.s)) }
        if (token != null) {
            Row(Modifier.clip(Shapes.xs).background(c.accentVeil).clickable(role = Role.Button, onClickLabel = "Remove filter") { onClearToken?.invoke() }
                .heightIn(min = 36.dp).padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Text(token, style = t.type.tag)
                Spacer(Modifier.width(Space.xs)); Glyph(R.drawable.glyph_close, c.accentInk, 14.dp, "Remove filter")
            }
            Spacer(Modifier.width(Space.s))
        }
        Box(Modifier.weight(1f).padding(vertical = Space.m)) {
            if (value.isEmpty()) Text(placeholder, style = t.type.body.copy(color = c.inkMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onValue, Modifier.fillMaxWidth().then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = placeholder },
                singleLine = true, textStyle = t.type.body.copy(color = c.inkStrong), cursorBrush = SolidColor(c.accent),
                keyboardOptions = keyboardOptions)
        }
        if (value.isNotEmpty()) Box(Modifier.size(Space.target).clickable(role = Role.Button) { onValue("") }, contentAlignment = Alignment.Center) {
            Glyph(R.drawable.glyph_close, c.inkMuted, 18.dp, "Clear")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Heimflyt.t.color
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.raised, contentColor = c.ink, shape = Shapes.l, scrimColor = c.scrim.copy(alpha = 0.56f),
        dragHandle = { Box(Modifier.padding(vertical = Space.s).size(36.dp, 4.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.3f))) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl).padding(bottom = Space.xl), content = content)
    }
}

@Composable
fun <T> Segmented(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val t = Heimflyt.t; val c = t.color
    Row(modifier.fillMaxWidth().clip(Shapes.s).background(c.raised)) {
        options.forEach { (label, value) ->
            val on = value == selected
            Row(Modifier.weight(1f).heightIn(min = Space.target).background(if (on) c.accentVeil else c.raised)
                .clickable(role = Role.RadioButton) { onSelect(value) }.semantics { contentDescription = label + if (on) ", selected" else "" }
                .padding(horizontal = Space.xs), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (on) { Glyph(R.drawable.glyph_check, c.accentInk, 16.dp); Spacer(Modifier.width(Space.xs)) }
                Text(label, style = t.type.label.copy(color = if (on) c.accentInk else c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, value: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    val c = Heimflyt.t.color
    HRow(label, subtitle = subtitle, subtitleLines = Int.MAX_VALUE, onClick = { onChange(!value) }, trailing = {
        Switch(value, onChange, Modifier.padding(start = Space.m), colors = SwitchDefaults.colors(uncheckedBorderColor = c.control, uncheckedThumbColor = c.control,
            uncheckedTrackColor = c.raised, checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
    })
}

private fun initials(name: String) = name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
    .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

@Composable
fun Monogram(name: String, size: Dp = 40.dp) {
    val c = Heimflyt.t.color
    val i = Math.floorMod(name.trim().lowercase().hashCode(), c.identity.size)
    Box(Modifier.size(size).clip(CircleShape).background(c.identity[i]), contentAlignment = Alignment.Center) {
        Text(initials(name), style = Heimflyt.t.type.label.copy(color = c.onIdentity[i], fontSize = 13.sp))
    }
}

@Composable
fun TagWord(name: String, selected: Boolean = false, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val t = Heimflyt.t; val c = t.color
    val base = modifier.heightIn(min = Space.target)
    Box(base.then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier), contentAlignment = Alignment.CenterStart) {
        Text("#$name", Modifier.clip(Shapes.xs).background(if (selected) c.accentVeil else Color.Transparent).padding(horizontal = Space.xs, vertical = 2.dp),
            style = t.type.tag.copy(color = if (onClick != null || selected) c.accentInk else c.ink))
    }
}

@Composable
fun StatusPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = Heimflyt.t; val c = t.color
    Row(modifier.clip(Shapes.s).background(c.raised).clickable(role = Role.Button, onClickLabel = "Dismiss", onClick = onClick)
        .padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Glyph(R.drawable.glyph_warning, c.dangerInk, 18.dp); Spacer(Modifier.width(Space.s))
        Text(text, style = t.type.secondary.copy(color = c.ink))
    }
}

@Composable
fun DirectionArrow(angle: Float, tint: Color, size: Dp = 18.dp) {
    Icon(painterResource(R.drawable.glyph_direction), directionWord(angle), Modifier.size(size).rotate(angle), tint = tint)
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = Heimflyt.t; val c = t.color
    Box(modifier.heightIn(min = Space.target).clip(Shapes.s).background(c.accent).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = Space.l, vertical = Space.s), contentAlignment = Alignment.Center) {
        Text(text, style = t.type.label.copy(color = c.onAccent))
    }
}

@Composable
fun QuietButton(text: String, modifier: Modifier = Modifier, color: Color = Heimflyt.t.color.accentInk, onClick: () -> Unit) {
    Box(modifier.heightIn(min = Space.target).clip(Shapes.s).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.s),
        contentAlignment = Alignment.Center) {
        Text(text, style = Heimflyt.t.type.label.copy(color = color))
    }
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?, meta: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val t = Heimflyt.t
    Row(Modifier.fillMaxWidth().heightIn(min = Space.target), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) Box(Modifier.size(Space.target).clickable(role = Role.Button, onClick = onBack), contentAlignment = Alignment.Center) {
            Glyph(R.drawable.glyph_back, t.color.ink, 22.dp, "Back")
        }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) Space.xs else 0.dp)) {
            Text(title, style = t.type.title, modifier = Modifier.semantics { heading() })
            if (meta != null) Text(meta, style = t.type.meta)
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun IconButtonGlyph(@DrawableRes res: Int, description: String, tint: Color = Heimflyt.t.color.inkMuted, onClick: () -> Unit) {
    Box(Modifier.size(Space.target).clip(CircleShape).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Glyph(res, tint, 22.dp, description)
    }
}
