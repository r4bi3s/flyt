package no.heimflyt.launcher.ui.tune

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.ViewConfiguration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import no.heimflyt.launcher.*
import no.heimflyt.launcher.ui.browse.BROWSE_EXPERIMENT_EXPOSED
import no.heimflyt.launcher.ui.browse.BrowseRenderer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.heimflyt.launcher.R
import no.heimflyt.launcher.gesture.Fan
import no.heimflyt.launcher.gesture.Familiarity
import no.heimflyt.launcher.gesture.Nest
import no.heimflyt.launcher.gesture.RadialGeometry
import no.heimflyt.launcher.gesture.TuningParams
import no.heimflyt.launcher.ui.components.*
import no.heimflyt.launcher.ui.home.HomeHints
import no.heimflyt.launcher.ui.home.slotLabel
import no.heimflyt.launcher.ui.home.weekNumberDefault
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.theme.BundledThemes
import no.heimflyt.launcher.theme.store.BackgroundChoice
import no.heimflyt.launcher.theme.store.BackgroundKind
import no.heimflyt.launcher.ui.theme.Shapes
import no.heimflyt.launcher.ui.theme.Space
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class TunePage(val route: String, val title: String) {
    INDEX("settings", "Tune"), LOOK("settings/look", "Look"), DIRECTIONS("settings/directions", "Radial"), APPS("settings/apps", "Apps & tags"),
    GUIDANCE("settings/guidance", "Guidance"), SEARCH("settings/search", "Search"), ADVANCED("settings/advanced", "Advanced"),
    ABOUT("settings/about", "About");
    companion object { fun from(route: String) = entries.firstOrNull { it.route == route } }
}

class TuneActions(
    val onTuning: (TuningParams) -> Unit, val onBind: (Int, HomeAction) -> Unit, val onPickApp: (Int) -> Unit,
    val onResetLearning: () -> Unit, val onHome: ((HomePrefs) -> HomePrefs) -> Unit, val onOpenHomeSettings: () -> Unit,
    val onShowDirections: () -> Unit, val onNavigate: (TunePage) -> Unit, val onBack: () -> Unit,
    val onEditGroup: (Int) -> Unit = {},
    val onEditTag: (String) -> Unit = {}, val onTags: () -> Unit = {},
    val onThemes: () -> Unit = {}, val onTheme: (String) -> Unit = {},
)

@Composable
fun TuneSurface(page: TunePage, settings: LocalSettings, app: HeimflytApplication, actions: TuneActions) {
    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter - Space.xs)) {
        ScreenHeader(page.title, onBack = actions.onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (page) {
                TunePage.INDEX -> TuneIndex(settings, app, actions)
                TunePage.LOOK -> LookPage(settings, app, actions)
                TunePage.DIRECTIONS -> DirectionsPage(settings, app, actions)
                TunePage.GUIDANCE -> GuidancePage(settings, actions)
                TunePage.SEARCH -> SearchPage(app, settings, actions)
                TunePage.APPS -> {
                    HRow("Tags", subtitle = "App membership and fixed radial choices", onClick = actions.onTags)
                    HRow("Search", subtitle = "Sources, recents and search placement", onClick = { actions.onNavigate(TunePage.SEARCH) })
                }
                TunePage.ADVANCED -> AdvancedPage(settings, app, actions)
                TunePage.ABOUT -> AboutPage()
            }
            Spacer(Modifier.height(Space.xl))
        }
    }
}

private fun isDefaultHome(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 29) return context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return context.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
}

@Composable
private fun TuneIndex(settings: LocalSettings, app: HeimflytApplication, a: TuneActions) {
    val t = Heimflyt.t; val c = t.color
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var isDefault by remember { mutableStateOf(isDefaultHome(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) isDefault = isDefaultHome(context) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { isDefault = isDefaultHome(context) }
    if (!isDefault) {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.s).background(c.raised, Shapes.m).padding(Space.l)) {
            Text("Krets isn't your Home app yet.", style = t.type.body)
            Spacer(Modifier.height(Space.s))
            PrimaryButton("Make default", Modifier.align(Alignment.End)) {
                if (Build.VERSION.SDK_INT >= 29) {
                    val role = context.getSystemService(RoleManager::class.java)
                    if (role != null && role.isRoleAvailable(RoleManager.ROLE_HOME)) roleLauncher.launch(role.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    else a.onOpenHomeSettings()
                } else a.onOpenHomeSettings()
            }
        }
    }
    val p = settings.tuning
    val learned = settings.familiarity.take(p.sectorCount).count { it.level == Familiarity.MINIMAL }
    @Composable fun entry(page: TunePage, summary: String) = HRow(page.title, subtitle = summary, onClick = { a.onNavigate(page) },
        trailing = { Glyph(R.drawable.glyph_chevron, c.inkMuted, 18.dp) })
    val active by app.themes.store.active.collectAsState()
    entry(TunePage.LOOK, active?.let { "${it.name} · ${backgroundLabel(it.choice.background)}" } ?: "Krets · Krets")
    entry(TunePage.DIRECTIONS, "${p.sectorCount} · ${if (p.arcSpan >= 360f) "full circle" else "half circle"} · ${if (p.anywhere) "anywhere" else "fixed"}")
    entry(TunePage.GUIDANCE, if (p.adaptive) "$learned of ${p.sectorCount} learned" else "progressive invisibility off")
    entry(TunePage.APPS, "tags · radial choices · search")
    entry(TunePage.ADVANCED, "experiments · geometry · timing · haptics")
    entry(TunePage.ABOUT, versionName(context))
}

private fun versionName(context: Context) = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

private fun backgroundLabel(b: BackgroundChoice) = if (b.kind == BackgroundKind.IMAGE) "image ${b.index + 1}" else b.kind.label

@Composable
private fun LookPage(settings: LocalSettings, app: HeimflytApplication, a: TuneActions) {
    val t = Heimflyt.t; val c = t.color
    val active by app.themes.store.active.collectAsState()
    val name = active?.name ?: "Krets"
    Column(Modifier.fillMaxWidth().padding(vertical = Space.s).clip(Shapes.m).background(c.raised)
        .clickable(role = Role.Button, onClickLabel = "Choose a theme", onClick = a.onThemes).padding(Space.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = t.type.bodyStrong)
                Text("${if (c.isLight) "light" else "dark"} · ${active?.let { backgroundLabel(it.choice.background) } ?: "Dusk"}" +
                    (active?.choice?.takeIf { it.background.kind == BackgroundKind.IMAGE }?.let { " · ${it.strength.label.lowercase()}" } ?: ""), style = t.type.meta)
            }
            Glyph(R.drawable.glyph_chevron, c.inkMuted, 18.dp)
        }
        Spacer(Modifier.height(Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf(c.ground, c.ink, c.accent, c.identity[0], c.identity[3]).forEach { Box(Modifier.size(14.dp).background(it, androidx.compose.foundation.shape.CircleShape)) }
        }
    }
    HRow("Themes", subtitle = "included and installed themes", onClick = a.onThemes, trailing = { Glyph(R.drawable.glyph_chevron, c.inkMuted, 18.dp) })
    HRow("Background", subtitle = "source, strength and framing", onClick = { a.onTheme(active?.choice?.recordId ?: "bundled:krets") },
        trailing = { Glyph(R.drawable.glyph_chevron, c.inkMuted, 18.dp) })
    SectionLabel("status bar on home · experiment")
    Segmented(HomeStatusMode.entries.map { it.label to it }, settings.home.statusMode, { m -> a.onHome { it.copy(statusMode = m) } })
    Text(when (settings.home.statusMode) {
        HomeStatusMode.ANDROID -> "Android's own status bar (white or dark icons only)."
        HomeStatusMode.HIDDEN -> "No status bar on Home. Swipe down from the top edge to show Android's; swipe again for notifications."
        HomeStatusMode.HEIMFLYT -> "Time and battery in the theme's colours on Home. Swipe down from the top edge for Android's bar and notifications."
    }, style = t.type.caption, modifier = Modifier.padding(Space.xs))
    SectionLabel("home hints")
    Segmented(listOf("Auto" to HomeHints.AUTO, "Show" to HomeHints.SHOW, "Hide" to HomeHints.HIDE), settings.home.hints,
        { h -> a.onHome { it.copy(hints = h) } })
    Text("Auto shows the ring, caption and Tune on Home until you have made 10 clean gestures.", style = t.type.caption, modifier = Modifier.padding(Space.xs))
    val week = settings.home.weekNumber ?: weekNumberDefault(Locale.getDefault())
    ToggleRow("Week number on Home", week) { v -> a.onHome { it.copy(weekNumber = v) } }
    ToggleRow("Apps on thumb side", settings.home.appsOnThumb,
        subtitle = "Apps in the bottom corner under your thumb, Search in the other. Off: the other way round.") { v -> a.onHome { it.copy(appsOnThumb = v) } }
}

@Composable
private fun DirectionsPage(settings: LocalSettings, app: HeimflytApplication, a: TuneActions) {
    val t = Heimflyt.t; val c = t.color
    var p by remember(settings.tuning) { mutableStateOf(settings.tuning) }
    var bindingSlot by remember { mutableStateOf<Int?>(null) }
    fun change(next: TuningParams) { p = next; a.onTuning(next) }
    Compass(p, settings.bindings.take(p.sectorCount).map(::slotLabel), null)
    SectionLabel("shape")
    Segmented(listOf("Half circle" to 180f, "Full circle" to 360f), if (p.arcSpan >= 360f) 360f else 180f, { change(p.copy(arcSpan = it)) })
    SectionLabel("number of choices")
    Segmented((5..8).map { "$it" to it }, p.sectorCount, { change(p.copy(sectorCount = it)) })
    SectionLabel("activation")
    Segmented(listOf("Fixed" to false, "Anywhere" to true), p.anywhere, { change(p.copy(anywhere = it)) })
    Text(if (p.anywhere) "Start a gesture wherever your thumb rests on empty Home space. The circle's centre is where you touch. Tap the circle for the list."
        else "Start a gesture on the circle.", style = t.type.caption, modifier = Modifier.padding(Space.xs))
    ToggleRow("Left-handed (mirror directions)", p.leftHanded) { change(p.copy(leftHanded = it)) }
    SectionLabel("nested radial")
    ToggleRow("Show child items", settings.home.h7, subtitle = "Small tags show all items automatically. Fixed choices are kept.") { v -> a.onHome { it.copy(h7 = v) } }
    ToggleRow("Launch child items", settings.home.h7Launch, subtitle = "Off: rehearse. On: release on a child to open it.") { v -> a.onHome { it.copy(h7Launch = v) } }
    if (H7.prompts) Text("Rehearsal prompts are on in Advanced; child launch is blocked.", style = t.type.caption)
    SectionLabel("directions")
    val activeTags = settings.activeTagGroups()
    settings.bindings.take(p.sectorCount).forEachIndexed { i, action ->
        val active = if (action is HomeAction.Tag) activeTags[action.name] else null
        val subtitle = if (active != null && action is HomeAction.Tag)
            "tag · ${active.children.count { it != null }} radial items · ${settings.tagMode(action.name).label.lowercase()}"
        else kindOf(action)
        HRow(slotLabel(action), subtitle = subtitle, onClick = { bindingSlot = i },
            leading = { DirectionArrow(RadialGeometry.sectorAngle(i, p.safe()), c.accent) },
            trailing = { Text("${i + 1}", style = t.type.meta) })
    }
    QuietButton("Show directions list", onClick = a.onShowDirections)
    bindingSlot?.let { slot -> BindingSheet(slot, settings.bindings[slot], app, a, onDismiss = { bindingSlot = null }) }
}

private fun kindOf(action: HomeAction) = when (action) {
    is HomeAction.Semantic -> "action · follows Android's default app"
    is HomeAction.App -> "app"
    is HomeAction.Tag -> "tag search"
    is HomeAction.Group -> "group · ${action.children.count { it != null }} fixed items"
    HomeAction.Apps, HomeAction.Search -> "Krets"
    is HomeAction.Probe -> "rehearsal · launches nothing"
}

/** What a direction does. Tags are compact words, so the whole choice usually fits one screen; [current] is marked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BindingSheet(slot: Int, current: HomeAction, app: HeimflytApplication, a: TuneActions, onDismiss: () -> Unit) {
    val t = Heimflyt.t; val c = t.color
    fun pick(action: HomeAction) { a.onBind(slot, action); onDismiss() }
    val mark: @Composable () -> Unit = { Glyph(R.drawable.glyph_check, c.accentInk, 18.dp, "Current") }
    // Scrolls as a fallback: at large font sizes the choices can still be taller than the screen.
    HSheet(onDismiss) { Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Direction ${slot + 1}", style = t.type.title)
        if (current is HomeAction.Tag) HRow("Radial items for ${app.tags.label(current.name)}",
            subtitle = "Automatic, fixed choices or most used.", onClick = { onDismiss(); a.onEditTag(current.name) })
        HRow(if (current is HomeAction.Group) "Edit group…" else "Create group…",
            subtitle = "Choose 2–4 apps in fixed fan positions.", onClick = { onDismiss(); a.onEditGroup(slot) },
            leading = { Glyph(R.drawable.glyph_apps, c.ink) })
        SectionLabel("actions")
        Text("Follow Android's default app. Change the default in Android settings, not here.", style = t.type.caption)
        SemanticDestination.entries.forEach { d ->
            HRow(d.label, subtitle = d.description, onClick = { pick(HomeAction.Semantic(d)) }, leading = { Glyph(glyphFor(HomeAction.Semantic(d)), c.ink) },
                trailing = mark.takeIf { current == HomeAction.Semantic(d) })
        }
        SectionLabel("specific app")
        HRow("Choose installed application…", subtitle = (current as? HomeAction.App)?.let { "Now: ${it.label}" },
            onClick = { onDismiss(); a.onPickApp(slot) }, leading = { Glyph(R.drawable.glyph_apps, c.ink) },
            trailing = mark.takeIf { current is HomeAction.App })
        SectionLabel("heimflyt")
        HRow("Open Apps", onClick = { pick(HomeAction.Apps) }, leading = { Glyph(R.drawable.glyph_apps, c.ink) },
            trailing = mark.takeIf { current == HomeAction.Apps })
        HRow("Open Search", onClick = { pick(HomeAction.Search) }, leading = { Glyph(R.drawable.glyph_search, c.ink) },
            trailing = mark.takeIf { current == HomeAction.Search })
        SectionLabel("tags")
        Text("Tags with up to four items show them automatically, most used from left to right. Saved fixed choices take priority. Release outside a child opens the full tag in Search.", style = t.type.caption)
        if (app.tags.names.isEmpty()) Text("Create a tag in Apps first.", style = t.type.caption)
        else FlowRow(Modifier.fillMaxWidth().padding(horizontal = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            app.tags.names.forEach { tag -> TagWord(app.tags.label(tag), selected = current == HomeAction.Tag(tag), onClick = { pick(HomeAction.Tag(tag)) }) }
        }
        SectionLabel("testing")
        HRow("Unassigned (safe rehearsal, launches nothing)", onClick = { pick(HomeAction.Probe(slot + 1)) }, leading = { Glyph(R.drawable.glyph_direction, c.inkMuted) },
            trailing = mark.takeIf { current is HomeAction.Probe })
    } }
}

/** Static drawing of the current geometry; redrawn only when its inputs change. [levels] marks familiarity when non-null. */
@Composable
private fun Compass(p: TuningParams, labels: List<String>, levels: List<Int>?) {
    val c = Heimflyt.t.color
    val safe = p.safe()
    val description = labels.mapIndexed { i, l -> "${i + 1} $l ${directionWord(RadialGeometry.sectorAngle(i, safe))}" }.joinToString(", ")
    Canvas(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = "Direction compass: $description" }) {
        val center = Offset(size.width / 2, size.height / 2)
        val r = size.height * 0.36f
        drawCircle(c.hairline, 18.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
        for (i in 0 until safe.sectorCount) {
            val a = Math.toRadians(RadialGeometry.sectorAngle(i, safe).toDouble())
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            drawLine(c.hairline, center + dir * 24.dp.toPx(), center + dir * (r - 14.dp.toPx()), 1.dp.toPx())
            val at = center + dir * r
            when (levels?.getOrNull(i)) {
                null -> drawCircle(c.accent, 5.dp.toPx(), at)
                Familiarity.FULL -> { drawCircle(c.ink, 11.dp.toPx(), at, style = Stroke(1.5.dp.toPx())); drawCircle(c.ink, 5.dp.toPx(), at) }
                Familiarity.HINTED -> drawCircle(c.ink, 5.dp.toPx(), at)
                else -> drawCircle(c.accent, 6.dp.toPx(), at, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

@Composable
private fun GuidancePage(settings: LocalSettings, a: TuneActions) {
    val t = Heimflyt.t; val c = t.color
    val p = settings.tuning
    var confirmReset by remember { mutableStateOf(false) }
    ToggleRow("Progressive invisibility", p.adaptive, subtitle = if (p.adaptive) "Help fades per direction as you learn it and returns when you hesitate."
        else "Guidance stays at the level chosen under Advanced.") { a.onTuning(p.copy(adaptive = it)) }
    if (p.adaptive) {
        Compass(p, settings.bindings.take(p.sectorCount).map(::slotLabel), settings.familiarity.take(p.sectorCount).map { it.level })
        Text("◉ full   • hinted   ○ minimal", style = t.type.meta, modifier = Modifier.padding(Space.xs))
        settings.familiarity.take(p.sectorCount).forEachIndexed { i, e ->
            val next = when (e.level) { Familiarity.FULL -> "hinted at ${p.hintAfter}"; Familiarity.HINTED -> "minimal at ${p.minimalAfter}"; else -> "learned" }
            HRow("${i + 1} · ${slotLabel(settings.bindings[i])}", compact = true, subtitle = "${Familiarity.name(e.level).lowercase()} · streak ${e.streak} · $next",
                leading = { DirectionArrow(RadialGeometry.sectorAngle(i, p.safe()), c.accent) })
        }
    }
    Spacer(Modifier.height(Space.s))
    if (!confirmReset) QuietButton("Reset familiarity…") { confirmReset = true }
    else Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Every direction starts at full guidance again.", style = t.type.secondary, modifier = Modifier.weight(1f))
        QuietButton("Cancel") { confirmReset = false }
        QuietButton("Reset", color = c.dangerInk) { a.onResetLearning(); confirmReset = false }
    }
    Text("Learning is local and changes only when you finish a gesture. Rehearsal directions launch nothing.", style = t.type.caption, modifier = Modifier.padding(Space.xs))
}

@Composable
private fun SearchPage(app: HeimflytApplication, settings: LocalSettings, a: TuneActions) {
    val t = Heimflyt.t
    ToggleRow("Search word on Home", settings.home.searchWord,
        subtitle = "Off: Search stays on your radial direction, in the directions list and at the bottom of Apps.") { v -> a.onHome { it.copy(searchWord = v) } }
    SectionLabel("search field")
    Segmented(listOf("Bottom" to true, "Top" to false), settings.home.searchAtBottom, { v -> a.onHome { it.copy(searchAtBottom = v) } })
    Text(if (settings.home.searchAtBottom) "Above the keyboard; the best match sits right above the field, near your thumb."
        else "At the top; results read from the top down.", style = t.type.caption, modifier = Modifier.padding(Space.xs))
    var sources by remember { mutableStateOf(app.searchSources.read()) }
    SectionLabel("sources")
    ToggleRow("Apps", sources.apps) { sources = sources.copy(apps = it); app.searchSources.save(sources) }
    ToggleRow("Contacts", sources.contacts) { sources = sources.copy(contacts = it); app.searchSources.save(sources) }
    ToggleRow("Actions", sources.actions) { sources = sources.copy(actions = it); app.searchSources.save(sources) }
    Text("Tags filter apps; they are not a source.", style = t.type.caption, modifier = Modifier.padding(Space.xs))
    SectionLabel("recent destinations")
    Segmented(listOf("Off" to 0, "3" to 3, "5" to 5), app.recents.count, { app.recents.changeCount(it) })
    Text("Shown when Search is empty. Local, from launches in Apps and Search only. Off also clears the list.", style = t.type.caption, modifier = Modifier.padding(Space.xs))
    QuietButton("Clear recent destinations") { app.recents.clear() }
}

/** One titled, bordered block of related Advanced settings. */
@Composable
private fun Group(title: String, experiment: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val t = Heimflyt.t; val c = t.color
    Column(Modifier.fillMaxWidth().padding(top = Space.l).border(1.dp, if (experiment) c.accent else c.hairline, Shapes.m).padding(Space.m)) {
        Text(title, style = t.type.bodyStrong, modifier = Modifier.padding(start = Space.xs, bottom = Space.xs).semantics { heading() })
        content()
    }
}

@Composable
private fun TuneSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val t = Heimflyt.t; val c = t.color
    Text(label, style = t.type.secondary.copy(color = c.ink), modifier = Modifier.padding(top = Space.s, start = Space.xs))
    Slider(value, onChange, valueRange = range, onValueChangeFinished = onDone,
        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.control))
}

@Composable
private fun Note(text: String) = Text(text, style = Heimflyt.t.type.caption, modifier = Modifier.padding(Space.xs))

@Composable
private fun AdvancedPage(settings: LocalSettings, app: HeimflytApplication, a: TuneActions) {
    // TEMPORARY (H6.0): the owner's A/B/C comparison of Browse renderers. Hidden since H6.1 chose Spatial.
    if (BROWSE_EXPERIMENT_EXPOSED) Group("Experiment · Browse layout", experiment = true) {
        val browse by app.browse.config.collectAsStateWithLifecycle()
        Segmented(BrowseRenderer.entries.map { it.label to it }, browse.renderer, { app.browse.setRenderer(it) })
        Note("Which Apps layout Home's \"Apps\" opens. Switching never changes your tags or slots.")
    }
    H7Section(settings, a)
    var p by remember(settings.tuning) { mutableStateOf(settings.tuning) }
    @Composable fun slider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, set: (Float) -> TuningParams) =
        TuneSlider("$label: ${value.toInt()} $unit", value, range, { p = set(it) }, { a.onTuning(p) })
    Group("Home circle") {
        slider("Distance from side", p.edgeDistance, 48f..260f, "dp") { p.copy(edgeDistance = it) }
        slider("Distance from bottom", p.bottomDistance, 64f..400f, "dp") { p.copy(bottomDistance = it) }
        slider("Activation target", p.affordanceSize, 48f..112f, "dp") { p.copy(affordanceSize = it) }
    }
    Group("Radial shape") {
        slider("Dead zone", p.deadZone, 8f..72f, "dp") { p.copy(deadZone = it) }
        Note("Anywhere ignores touches within ${RadialGeometry.anywhereInset(p).toInt()} dp of the Home edge (dead zone + ${RadialGeometry.ANYWHERE_EDGE_TRAVEL.toInt()} dp).")
        slider("Drawn menu radius", p.menuRadius, 60f..180f, "dp") { p.copy(menuRadius = it) }
        slider("Arc rotation (right-hand coordinates)", p.arcStart, 0f..359f, "°") { p.copy(arcStart = it) }
        slider("Arc span (360 = circle)", p.arcSpan, 60f..360f, "°") { p.copy(arcSpan = it) }
        slider("Boundary hysteresis", p.hysteresis, 0f..12f, "°") { p.copy(hysteresis = it) }
    }
    Group("Timing") {
        slider("Tap timeout", p.tapTimeout.toFloat(), 100f..600f, "ms") { p.copy(tapTimeout = it.toLong()) }
        slider("Minimum press (0 = immediate)", p.minPress.toFloat(), 0f..80f, "ms") { p.copy(minPress = it.toLong()) }
        slider("Center help delay", p.hesitationDelay.toFloat(), 100f..1500f, "ms") { p.copy(hesitationDelay = it.toLong()) }
        Note("Effective center delay: ${(p.hesitationDelay * (ViewConfiguration.getLongPressTimeout() / 500f).coerceIn(1f, 4f)).toLong()} ms (system touch-and-hold setting)")
        slider("Slow-motion dwell", p.dwellTime.toFloat(), 100f..1500f, "ms") { p.copy(dwellTime = it.toLong()) }
        slider("Dwell speed threshold", p.dwellSpeed, 5f..200f, "dp/s") { p.copy(dwellSpeed = it) }
    }
    Group("Guidance and learning") {
        slider("Hinted after clean uses", p.hintAfter.toFloat(), 1f..50f, "") { p.copy(hintAfter = it.toInt()) }
        slider("Minimal after clean uses", p.minimalAfter.toFloat(), 2f..100f, "") { p.copy(minimalAfter = it.toInt()) }
        SectionLabel("guidance when progressive invisibility is off")
        Segmented(listOf("Minimal" to 0, "Hints" to 1, "Full" to 2), p.initialGuidance, { p = p.copy(initialGuidance = it); a.onTuning(p) })
    }
    Group("Haptics") {
        ToggleRow("Haptic on touch", p.hapticDown) { p = p.copy(hapticDown = it); a.onTuning(p) }
        ToggleRow("Haptic on direction change", p.hapticSelection) { p = p.copy(hapticSelection = it); a.onTuning(p) }
        ToggleRow("Haptic on release/selection", p.hapticCommit) { p = p.copy(hapticCommit = it); a.onTuning(p) }
    }
    Group("Debug and reset") {
        ToggleRow("Debug geometry", p.debug, subtitle = "Readout on Home. Also outlines the H7 touch targets.") { p = p.copy(debug = it); a.onTuning(p) }
        QuietButton("Reset tuning (keep bindings)") { p = TuningParams(); a.onTuning(p) }
        QuietButton("Android Home app settings", onClick = a.onOpenHomeSettings)
    }
}

/** TEMPORARY (H7.1): disposable icon-target rehearsal and its owner-tuned fan. */
@Composable
private fun H7Section(settings: LocalSettings, a: TuneActions) {
    val p = settings.tuning
    var fan by remember(settings.home.h7Fan) { mutableStateOf(settings.home.h7Fan) }
    val save = { a.onHome { it.copy(h7Fan = fan) } }
    fun cm(dp: Float) = String.format(Locale.US, "%.1f", dp / 160f * 2.54f)
    Group("Experiment · H7 groups", experiment = true) {
        SectionLabel("fan geometry")
        FanPreview(fan, p)
        Note("Actual size. Dot: where the thumb starts. Box: the #agenter label.")
        TuneSlider("Icon size: ${fan.size.roundToInt()} dp", fan.size, Fan.SIZE, { fan = fan.copy(size = it.roundToInt().toFloat()) }, save)
        TuneSlider("Distance: ${fan.beyond.roundToInt()} dp (${cm(fan.beyond)} cm) beyond the ring", fan.beyond, Fan.BEYOND,
            { fan = fan.copy(beyond = it.roundToInt().toFloat()) }, save)
        val spacing = Nest.spacing(fan, p.menuRadius)
        TuneSlider("Spread: ${fan.spread.roundToInt()}° · ${spacing.roundToInt()} dp (${cm(spacing)} cm) between icons", fan.spread, Fan.SPREAD,
            { fan = fan.copy(spread = it.roundToInt().toFloat()) }, save)
        QuietButton("Reset fan") { fan = Fan(); save() }
        SectionLabel("rehearsal")
        ToggleRow("Show icon targets", settings.home.h7Guide, subtitle = "Off: a blind check of the positions.") { v -> a.onHome { it.copy(h7Guide = v) } }
        ToggleRow("Prompts", H7.prompts, subtitle = "Rehearse the first configured group. Launch is blocked while scoring. This session only.") { H7.prompts = it }
        ToggleRow("Record with H7 off", H7.recordBaseline, subtitle = "Baseline of ordinary strokes. This session only.") { H7.recordBaseline = it }
        SectionLabel("results")
        Note(remember(H7.revision) { H7.summary() })
        QuietButton("Clear H7 results") { H7.clear() }
    }
}

/** The fan at actual size, drawn from the same centres recognition uses, with the parent axis pointing up. */
@Composable
private fun FanPreview(fan: Fan, p: TuningParams) {
    val c = Heimflyt.t.color
    val hit = Nest.hitRadius(fan, p.menuRadius)
    val lane = Nest.lane(fan, p.menuRadius)
    val height = p.menuRadius + fan.beyond + hit + 16f
    Canvas(Modifier.fillMaxWidth().height(height.dp).clipToBounds()
        .semantics { contentDescription = "Preview of the four icon targets fanning out beyond the radial" }) {
        val d = 1.dp.toPx()
        val origin = Offset(size.width / 2, size.height - 8f * d)
        fun at(x: Float, y: Float) = origin + Offset(x * d, y * d)
        drawCircle(c.hairline, p.menuRadius * d, origin, style = Stroke(1.5f * d))
        drawCircle(c.ink, 4f * d, origin)
        drawLine(c.hairline, at(-lane, -Nest.R1), at(-lane, -height), d)
        drawLine(c.hairline, at(lane, -Nest.R1), at(lane, -height), d)
        drawRoundRect(c.raised, at(-50f, -(p.menuRadius + 26f) - 16f), androidx.compose.ui.geometry.Size(100f * d, 32f * d),
            androidx.compose.ui.geometry.CornerRadius(10f * d))
        for (slot in 0..3) {
            val (x, y) = Nest.target(slot, 0f, 0f, 270f, p.leftHanded, fan, p.menuRadius)
            drawCircle(c.inkMuted, hit * d, at(x, y), style = Stroke(d))
            drawCircle(c.accent, fan.size / 2f * d, at(x, y))
        }
    }
}

@Composable
private fun AboutPage() {
    val t = Heimflyt.t
    val context = LocalContext.current
    HRow("Krets", subtitle = versionName(context))
    Text("Krets works offline. It connects to GitHub only when you choose to inspect or install a theme. Your settings and learning stay on this phone.", style = t.type.body, modifier = Modifier.padding(Space.xs))
    SectionLabel("credits")
    Text("Palettes from Omarchy (MIT, © David Heinemeier Hansson and contributors). " +
        BundledThemes.all.joinToString("; ") { it.credit } + ". Grounds are drawn by Krets from each palette.",
        style = t.type.secondary, modifier = Modifier.padding(Space.xs))
    Text("Themes you install from a .zip file are used for your personal use; Krets keeps only their colours and re-encoded images.",
        style = t.type.secondary, modifier = Modifier.padding(Space.xs))
    SectionLabel("limitations")
    Text("Android Private Space is not supported in this version.", style = t.type.secondary, modifier = Modifier.padding(Space.xs))
}
