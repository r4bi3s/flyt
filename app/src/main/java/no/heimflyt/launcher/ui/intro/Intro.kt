package no.heimflyt.launcher.ui.intro

import android.app.role.RoleManager
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import no.heimflyt.launcher.ui.components.PrimaryButton
import no.heimflyt.launcher.ui.components.QuietButton
import no.heimflyt.launcher.ui.components.ToggleRow
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Shapes
import no.heimflyt.launcher.ui.theme.Space
import no.heimflyt.launcher.ui.tune.isDefaultHome

/** The first-run introduction, shown over Home. PRACTICE leaves Home's gesture live; every other step covers it. */
enum class IntroStep { WELCOME, HAND, PRACTICE, CANCEL, TAGS, DONE }

/** An installed app as the tag suggestion sees it: launcher key, label and Android's own category (ApplicationInfo.category). */
data class IntroApp(val key: String, val label: String, val category: Int)

data class TagSuggestion(val name: String, val apps: List<IntroApp>)

object IntroTags {
    /** Android app categories that make a useful first tag, with the tag name Flyt gives them. */
    private val names = mapOf(
        ApplicationInfo.CATEGORY_SOCIAL to "social", ApplicationInfo.CATEGORY_IMAGE to "photos",
        ApplicationInfo.CATEGORY_MAPS to "maps", ApplicationInfo.CATEGORY_AUDIO to "music",
        ApplicationInfo.CATEGORY_VIDEO to "video", ApplicationInfo.CATEGORY_NEWS to "news",
        ApplicationInfo.CATEGORY_PRODUCTIVITY to "work", ApplicationInfo.CATEGORY_GAME to "games",
    )
    /** A ring shows four apps; a larger tag shows its four most used. */
    const val RING = 4
    /** Many apps call themselves "productivity"; a category this large is not a useful first tag. */
    const val MAX_APPS = 25

    /**
     * Up to [max] suggestions: categories with 2 to [MAX_APPS] apps, largest first, skipping tags that already exist.
     * Every app of the category joins its tag, in alphabetical order; tags larger than [RING] show their most used.
     */
    fun suggest(apps: List<IntroApp>, existing: Set<String>, max: Int = 3): List<TagSuggestion> =
        apps.filter { it.category in names }.groupBy { names.getValue(it.category) }
            .filterKeys { it !in existing }
            .filterValues { it.size in 2..MAX_APPS }
            .entries.sortedWith(compareByDescending<Map.Entry<String, List<IntroApp>>> { it.value.size }.thenBy { it.key })
            .take(max)
            .map { (name, list) -> TagSuggestion(name, list.sortedBy { it.label.lowercase() }) }
}

/** What the overlay needs from Home; Home keeps the state and applies the results. */
class IntroActions(
    val onNext: () -> Unit,
    val onSkip: () -> Unit,
    val onHand: (leftHanded: Boolean) -> Unit,
    val onAddTags: (List<TagSuggestion>) -> Unit,
)

/**
 * One step of the introduction. [target] is the direction the practice step asks for; [feedback] is the last practice
 * result in words. [suggestions] are computed by Home from the installed apps.
 */
@Composable
fun IntroOverlay(step: IntroStep, leftHanded: Boolean, target: String, feedback: String?, suggestions: List<TagSuggestion>,
                 freeDirections: Int, a: IntroActions, modifier: Modifier = Modifier) {
    if (step == IntroStep.PRACTICE || step == IntroStep.CANCEL) { Coach(step, target, feedback, a, modifier); return }
    val t = Heimflyt.t; val c = t.color
    // A full cover: touches never reach Home's gesture behind a card.
    Box(modifier.fillMaxSize().background(c.ground.copy(alpha = 0.98f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).systemBarsPadding()
            .padding(horizontal = Space.gutter, vertical = Space.xl)) {
            when (step) {
                IntroStep.WELCOME -> Welcome(a)
                IntroStep.HAND -> Hand(leftHanded, a)
                IntroStep.TAGS -> Tags(suggestions, freeDirections, a)
                else -> Done(a)
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = Heimflyt.t.type.title.copy(fontSize = Heimflyt.t.type.title.fontSize * 1.4f),
    modifier = Modifier.padding(bottom = Space.m).semantics { heading() })

@Composable
private fun Body(text: String) = Text(text, style = Heimflyt.t.type.body.copy(color = Heimflyt.t.color.inkMuted),
    modifier = Modifier.padding(bottom = Space.l))

@Composable
private fun Actions(primary: String, onPrimary: () -> Unit, secondary: String?, onSecondary: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
        if (secondary != null) QuietButton(secondary, onClick = onSecondary)
        Spacer(Modifier.weight(1f))
        PrimaryButton(primary, onClick = onPrimary)
    }

@Composable
private fun Welcome(a: IntroActions) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(isDefaultHome(context)) }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { isDefault = isDefaultHome(context) }
    Title("Flyt works by direction.")
    Body("Press where your thumb already is, drag toward a direction and let go. Learn a direction once and your thumb just goes there.")
    if (!isDefault && Build.VERSION.SDK_INT >= 29) {
        val manager = context.getSystemService(RoleManager::class.java)
        if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_HOME))
            QuietButton("Make Flyt your Home app") { role.launch(manager.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
    }
    Actions("Start", a.onNext, "Skip intro", a.onSkip)
}

@Composable
private fun Hand(leftHanded: Boolean, a: IntroActions) {
    val t = Heimflyt.t; val c = t.color
    Title("Which hand?")
    Body("Directions follow your thumb. You can change this later in Tune → Radial.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        listOf("Left" to true, "Right" to false).forEach { (label, left) ->
            val chosen = leftHanded == left
            Box(Modifier.weight(1f).height(96.dp).clip(Shapes.m).background(if (chosen) c.accent else c.raised)
                .clickable(onClickLabel = "$label hand") { a.onHand(left) }, contentAlignment = Alignment.Center) {
                Text(label, style = t.type.bodyStrong.copy(color = if (chosen) c.ground else c.ink))
            }
        }
    }
    Actions("Next", a.onNext, "Skip intro", a.onSkip)
}

/** The practice coach sits at the top; Home stays live below it. */
@Composable
private fun Coach(step: IntroStep, target: String, feedback: String?, a: IntroActions, modifier: Modifier) {
    val t = Heimflyt.t; val c = t.color
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.statusBarsPadding().padding(Space.l).fillMaxWidth().clip(Shapes.m).background(c.raised).padding(Space.l)) {
            Text(if (step == IntroStep.PRACTICE) "Try it" else "Changed your mind?", style = t.type.meta.copy(color = c.accent))
            Text(if (step == IntroStep.PRACTICE) "Press anywhere below and drag toward $target. Nothing opens while you practise."
                else "Press, drag toward any direction, then come back to the middle and let go. That cancels.",
                style = t.type.body, modifier = Modifier.padding(top = Space.xs))
            feedback?.let { Text(it, style = t.type.caption, modifier = Modifier.padding(top = Space.s).semantics { liveRegion = LiveRegionMode.Polite }) }
            Row(Modifier.fillMaxWidth().padding(top = Space.xs)) {
                QuietButton("Skip intro", onClick = a.onSkip)
                Spacer(Modifier.weight(1f))
                QuietButton("Next", onClick = a.onNext)
            }
        }
    }
}

@Composable
private fun Tags(suggestions: List<TagSuggestion>, freeDirections: Int, a: IntroActions) {
    Title("Tags unfold under your thumb.")
    if (suggestions.isEmpty()) {
        Body("Put a tag on a direction and its apps open in a second ring. Create your first tag in Apps whenever you like.")
        Actions("Next", a.onNext, null, {})
        return
    }
    Body("Put a tag on a direction and its apps unfold in a second ring, your four most used first. Flyt can start with these, " +
        "made from your apps" + if (freeDirections > 0) " and placed on your free directions." else ".")
    var chosen by remember(suggestions) { mutableStateOf(suggestions.map { it.name }.toSet()) }
    suggestions.forEach { s ->
        val names = s.apps.take(3).joinToString(", ") { it.label } + if (s.apps.size > 3) " and ${s.apps.size - 3} more" else ""
        ToggleRow("#${s.name}", s.name in chosen, subtitle = names) { on ->
            chosen = if (on) chosen + s.name else chosen - s.name
        }
    }
    val picked = suggestions.filter { it.name in chosen }
    Actions(if (picked.isEmpty()) "Next" else "Add ${picked.size} ${if (picked.size == 1) "tag" else "tags"}",
        { if (picked.isEmpty()) a.onNext() else a.onAddTags(picked) }, if (picked.isEmpty()) null else "Not now", a.onNext)
}

@Composable
private fun Done(a: IntroActions) {
    Title("You're set.")
    Body("Drag toward a tag to see its apps. A short tap on Home shows every direction, Apps, Search and Tune. " +
        "Help on Home fades as you learn.")
    Actions("Start using Flyt", a.onNext, null, {})
}
