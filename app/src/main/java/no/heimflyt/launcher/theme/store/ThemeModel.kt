package no.heimflyt.launcher.theme.store

import no.heimflyt.launcher.theme.image.Protection
import no.heimflyt.launcher.theme.image.ZoneSignature
import no.heimflyt.launcher.theme.palette.OmarchyPalette
import no.heimflyt.launcher.theme.palette.PaletteSource
import no.heimflyt.launcher.theme.palette.ResolvedColors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Wallpaper strength: a uniform scrim over image backgrounds only (VISUAL_SYSTEM.md §8.2 step 4). */
enum class Strength(val alpha: Double, val label: String) { SOFT(0.45, "Soft"), BALANCED(0.25, "Balanced"), VIVID(0.10, "Vivid") }

enum class BackgroundKind(val label: String) { IMAGE("Image"), DUSK("Dusk"), CONTOUR("Contour"), PLAIN("Plain"), KRETS("Krets") }

data class BackgroundChoice(val kind: BackgroundKind, val index: Int = 0) {
    val hasImage get() = kind != BackgroundKind.PLAIN
    fun encode() = "${kind.name}:$index"
    companion object {
        fun decode(s: String): BackgroundChoice { val (k, i) = s.split(":"); return BackgroundChoice(BackgroundKind.valueOf(k), i.toInt()) }
    }
}

/** Crop window centre as fractions of the upright source, and zoom ≥ 1 (THEME_ARCHITECTURE.md §2). */
data class Framing(val fx: Float = .5f, val fy: Float = .5f, val zoom: Float = 1f)

/**
 * How a multi-image theme shows its images (canonical decision 2026-09-29). The image changes only at a defined boundary
 * (the screen turning off while Heimflyt is running), never per Home render; the next image is decoded while the screen
 * is off, so Home never waits for or performs image work.
 */
enum class BackgroundMode(val label: String) { FIXED("Fixed"), ROTATE("Rotate"), RANDOM("Random") }

/** The maximum number of images a theme shows in one generation (1–4). */
const val MAX_THEME_IMAGES = 4

/** One more image of a multi-image choice: the record's background index and its own composition. */
data class ImageSlot(val index: Int, val framing: Framing)

/** The owner's appearance choice. Stored inside each generation, never in the settings JSON. */
data class ThemeChoice(
    val recordId: String, val recordRev: String, val background: BackgroundChoice,
    val framing: Framing = Framing(), val strength: Strength = Strength.BALANCED,
    /** Further images after [background] (image themes only), each with its own composition; empty for one image. */
    val extras: List<ImageSlot> = emptyList(),
    val mode: BackgroundMode = BackgroundMode.FIXED,
) {
    /** Every image of the choice in order: the first is [background] with [framing]. */
    val slots: List<ImageSlot> get() = if (background.kind == BackgroundKind.IMAGE) listOf(ImageSlot(background.index, framing)) + extras else emptyList()

    fun toJson() = JSONObject().put("recordId", recordId).put("recordRev", recordRev).put("background", background.encode())
        .put("fx", framing.fx.toDouble()).put("fy", framing.fy.toDouble()).put("zoom", framing.zoom.toDouble()).put("strength", strength.name)
        .put("mode", mode.name)
        .put("extras", JSONArray(extras.map { JSONObject().put("index", it.index).put("fx", it.framing.fx.toDouble()).put("fy", it.framing.fy.toDouble()).put("zoom", it.framing.zoom.toDouble()) }))
    companion object {
        private fun framing(o: JSONObject) = Framing(o.getDouble("fx").toFloat(), o.getDouble("fy").toFloat(), o.getDouble("zoom").toFloat())
        fun fromJson(o: JSONObject) = ThemeChoice(o.getString("recordId"), o.getString("recordRev"), BackgroundChoice.decode(o.getString("background")),
            framing(o), Strength.valueOf(o.getString("strength")),
            o.optJSONArray("extras")?.let { a -> List(a.length()) { a.getJSONObject(it).let { e -> ImageSlot(e.getInt("index"), framing(e)) } } } ?: emptyList(),
            runCatching { BackgroundMode.valueOf(o.optString("mode", "FIXED")) }.getOrDefault(BackgroundMode.FIXED))
    }
}

data class StoredBackground(val file: String, val thumb: String, val label: String, val width: Int, val height: Int, val sha256: String, val lowResolution: Boolean)

/** An explicitly selected desktop image for one created-theme background; never used by Home. */
data class DesktopVariant(val index: Int, val file: String, val sha256: String, val width: Int, val height: Int)

sealed interface ThemeOrigin {
    data object Bundled : ThemeOrigin
    data class FileImport(val displayName: String, val sha256: String) : ThemeOrigin
    /** App-generated metadata: the normalised repository the owner typed and the commit it was pinned to (manual update later). */
    /** A theme created on this phone from the owner's photo: everything needed to reopen the Create screen (CREATE_THEME.md §6–7). */
    data class Created(val recipe: CreateRecipe, val createdAt: Long) : ThemeOrigin
    data class GitHub(val owner: String, val repo: String, val ref: String, val commit: String) : ThemeOrigin {
        val display get() = "github.com/$owner/$repo"
    }
}

/**
 * A closed, typed theme value (THEME_ARCHITECTURE.md §2). Nothing in it can name behaviour. The palette itself lives in the
 * revision's Heimflyt-written `colors.toml`; the manifest carries only display metadata derived by Heimflyt.
 */
data class ThemeRecord(
    val id: String, val name: String, val origin: ThemeOrigin, val light: Boolean, val paletteSource: PaletteSource,
    val backgrounds: List<StoredBackground>, val preview: String?, val licenseFile: String?, val notes: List<String>,
    val installedAt: Long?, val defaultGround: BackgroundKind = BackgroundKind.DUSK,
    val desktopVariants: List<DesktopVariant> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("schema", 1).put("id", id).put("name", name).put("light", light)
        .put("paletteSource", paletteSource.name).put("preview", preview ?: JSONObject.NULL).put("licenseFile", licenseFile ?: JSONObject.NULL)
        .put("installedAt", installedAt ?: JSONObject.NULL).put("defaultGround", defaultGround.name)
        .put("notes", JSONArray(notes))
        .put("origin", when (origin) {
            ThemeOrigin.Bundled -> JSONObject().put("kind", "bundled")
            is ThemeOrigin.FileImport -> JSONObject().put("kind", "file").put("displayName", origin.displayName).put("sha256", origin.sha256)
            is ThemeOrigin.Created -> JSONObject().put("kind", "created").put("createdAt", origin.createdAt).put("recipe", origin.recipe.toJson())
            is ThemeOrigin.GitHub -> JSONObject().put("kind", "github").put("owner", origin.owner).put("repo", origin.repo).put("ref", origin.ref).put("commit", origin.commit)
        })
        .put("desktopVariants", JSONArray(desktopVariants.map { JSONObject().put("index", it.index).put("file", it.file).put("sha256", it.sha256)
            .put("width", it.width).put("height", it.height) }))
        .put("backgrounds", JSONArray(backgrounds.map { JSONObject().put("file", it.file).put("thumb", it.thumb).put("label", it.label)
            .put("width", it.width).put("height", it.height).put("sha256", it.sha256).put("lowResolution", it.lowResolution) }))

    companion object {
        fun fromJson(o: JSONObject): ThemeRecord {
            require(o.getInt("schema") == 1)
            val origin = o.getJSONObject("origin").let {
                when (it.getString("kind")) {
                    "file" -> ThemeOrigin.FileImport(it.getString("displayName"), it.getString("sha256"))
                    "created" -> ThemeOrigin.Created(CreateRecipe.fromJson(it.getJSONObject("recipe")), it.getLong("createdAt"))
                    "github" -> ThemeOrigin.GitHub(it.getString("owner"), it.getString("repo"), it.getString("ref"), it.getString("commit"))
                    else -> ThemeOrigin.Bundled
                }
            }
            val bgs = o.getJSONArray("backgrounds").let { a -> List(a.length()) { i -> a.getJSONObject(i).let {
                StoredBackground(it.getString("file"), it.getString("thumb"), it.getString("label"), it.getInt("width"), it.getInt("height"),
                    it.getString("sha256"), it.optBoolean("lowResolution")) } } }
            val notes = o.getJSONArray("notes").let { a -> List(a.length()) { a.getString(it) } }
            val variants = o.optJSONArray("desktopVariants")?.let { a -> List(a.length()) { a.getJSONObject(it).let { v ->
                DesktopVariant(v.getInt("index"), v.getString("file"), v.getString("sha256"), v.getInt("width"), v.getInt("height"))
            } } } ?: emptyList()
            return ThemeRecord(o.getString("id"), o.getString("name"), origin, o.getBoolean("light"), PaletteSource.valueOf(o.getString("paletteSource")),
                bgs, o.optString("preview").takeIf { !o.isNull("preview") && it.isNotEmpty() }, o.optString("licenseFile").takeIf { !o.isNull("licenseFile") && it.isNotEmpty() },
                notes, if (o.isNull("installedAt")) null else o.getLong("installedAt"), BackgroundKind.valueOf(o.optString("defaultGround", "DUSK")), variants)
        }
    }
}

/**
 * The Create recipe (THEME_ARCHITECTURE.md §2): the choices that regenerate a created theme's palette. The saved
 * `colors.toml` stays the source of truth; the recipe only reopens the Create screen with the same state.
 */
data class CreateRecipe(
    val generatorVersion: Int, val variant: String, val keptFromName: String?, val accentPinned: Int?, val mode: String,
    val framing: Framing, val strength: Strength, val clusters: List<Pair<Int, Double>>, val candidates: List<Int>,
    /** One composition per image, in the theme's image order (the first equals [framing]). */
    val framings: List<Framing> = listOf(framing),
    val backgroundMode: BackgroundMode = BackgroundMode.FIXED,
) {
    fun toJson(): JSONObject = JSONObject().put("generatorVersion", generatorVersion).put("variant", variant)
        .put("keptFromName", keptFromName ?: JSONObject.NULL).put("accent", accentPinned?.let { "#%06x".format(it) } ?: "auto").put("mode", mode)
        .put("fx", framing.fx.toDouble()).put("fy", framing.fy.toDouble()).put("zoom", framing.zoom.toDouble()).put("strength", strength.name)
        .put("clusters", JSONArray(clusters.map { JSONObject().put("rgb", "#%06x".format(it.first)).put("share", it.second) }))
        .put("candidates", JSONArray(candidates.map { "#%06x".format(it) }))
        .put("images", JSONArray(framings.map { JSONObject().put("fx", it.fx.toDouble()).put("fy", it.fy.toDouble()).put("zoom", it.zoom.toDouble()) }))
        .put("backgroundMode", backgroundMode.name)
    companion object {
        private fun hex(s: String) = s.removePrefix("#").toInt(16)
        fun fromJson(o: JSONObject) = CreateRecipe(o.getInt("generatorVersion"), o.getString("variant"),
            o.optString("keptFromName").takeIf { !o.isNull("keptFromName") && it.isNotEmpty() },
            o.getString("accent").takeIf { it != "auto" }?.let(::hex), o.getString("mode"),
            Framing(o.getDouble("fx").toFloat(), o.getDouble("fy").toFloat(), o.getDouble("zoom").toFloat()), Strength.valueOf(o.getString("strength")),
            o.getJSONArray("clusters").let { a -> List(a.length()) { a.getJSONObject(it).let { c -> hex(c.getString("rgb")) to c.getDouble("share") } } },
            o.getJSONArray("candidates").let { a -> List(a.length()) { hex(a.getString(it)) } }).let { r ->
                r.copy(framings = o.optJSONArray("images")?.let { a -> List(a.length()) { a.getJSONObject(it).let { f -> Framing(f.getDouble("fx").toFloat(), f.getDouble("fy").toFloat(), f.getDouble("zoom").toFloat()) } } }
                    ?.takeIf { it.isNotEmpty() } ?: listOf(r.framing),
                    backgroundMode = runCatching { BackgroundMode.valueOf(o.optString("backgroundMode", "FIXED")) }.getOrDefault(BackgroundMode.FIXED))
            }
    }
}

/** A record revision as read for preparation: its files live in [dir] (null for bundled records, which come from assets). */
class LoadedRecord(val record: ThemeRecord, val rev: String, val palette: OmarchyPalette, val dir: File?)

/** The published active generation: everything Home needs, from one self-contained directory. */
data class ActiveGeneration(
    val gen: String, val seq: Long, val name: String, val choice: ThemeChoice, val tokens: ResolvedColors,
    val signature: ZoneSignature?, val protection: Protection?, val background: File?,
    /** Every prepared Home image of the generation (index = slot); [background] is the first. */
    val backgrounds: List<File> = listOfNotNull(background),
    val protections: List<Protection?> = listOf(protection),
) {
    fun protectionFor(slot: Int) = protections.getOrNull(slot) ?: protection
}

/** The pre-content window subset of `active.json` (THEME_ARCHITECTURE.md §8.4). */
data class StartupSubset(val ground: Int, val light: Boolean, val statusLight: Boolean, val navLight: Boolean)

object TokensJson {
    private val SCALARS = listOf("ground", "groundDeep", "raised", "selection", "ink", "inkStrong", "inkMuted", "control", "hairline", "accent",
        "accentInk", "onAccent", "accentVeil", "danger", "dangerInk", "onDanger", "positive", "caution", "scrim", "endpoint")
    private fun ResolvedColors.scalars() = listOf(ground, groundDeep, raised, selection, ink, inkStrong, inkMuted, control, hairline, accent,
        accentInk, onAccent, accentVeil, danger, dangerInk, onDanger, positive, caution, scrim, endpoint)
    private fun hex(c: Int) = "#%06x".format(c)
    private fun int(s: String): Int { require(s.length == 7 && s[0] == '#'); return s.substring(1).toInt(16) }

    fun encode(t: ResolvedColors): JSONObject = JSONObject().apply {
        put("schema", 1)
        SCALARS.zip(t.scalars()).forEach { (k, v) -> put(k, hex(v)) }
        put("identity", JSONArray(t.identity.map(::hex))); put("onIdentity", JSONArray(t.onIdentity.map(::hex)))
        put("isLight", t.isLight); put("adjustments", JSONArray(t.adjustments))
    }

    fun decode(o: JSONObject): ResolvedColors {
        require(o.getInt("schema") == 1)
        val v = SCALARS.map { int(o.getString(it)) }
        fun list(k: String) = o.getJSONArray(k).let { a -> List(a.length()) { int(a.getString(it)) } }.also { require(it.size == 7) }
        val adj = o.getJSONArray("adjustments").let { a -> List(a.length()) { a.getString(it) } }
        return ResolvedColors(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15], v[16], v[17],
            list("identity"), list("onIdentity"), v[18], v[19], o.getBoolean("isLight"), adj)
    }
}
