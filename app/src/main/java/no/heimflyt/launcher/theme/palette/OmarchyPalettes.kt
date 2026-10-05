package no.heimflyt.launcher.theme.palette

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A palette file or palette that cannot be used. [message] is owner-facing; [detail] is the mono colophon line. */
class PaletteException(message: String, val detail: String? = null) : Exception(message)

/**
 * The strict `colors.toml` subset of THEME_ARCHITECTURE.md §3.1. Line semantics follow Omarchy's parser
 * (`bin/omarchy-theme-color`): quotes and spaces are stripped from keys, a quoted value is the text between the first pair of
 * quotes, an unquoted value is trimmed, and lines outside the key/value charsets are skipped with a note. Stricter than
 * upstream: a size and line cap, invalid UTF-8 and NUL reject the file, a `[section]` header ends parsing, and multi-line
 * strings, arrays and inline tables are skipped.
 */
object ColorsToml {
    const val MAX_BYTES = 64 * 1024
    const val MAX_LINES = 512
    private val KEY = Regex("^[A-Za-z0-9_-]+$")
    private val VALUE = Regex("^[A-Za-z0-9#(),._+/% -]*$")
    private val BOM = 0xFEFF.toChar().toString()

    class Parsed(val values: LinkedHashMap<String, String>, val notes: List<String>)

    /** Decodes bounded, valid UTF-8 without NUL and strips one leading BOM. Shared with the alacritty extractor. */
    fun text(bytes: ByteArray, what: String): String {
        if (bytes.size > MAX_BYTES) throw PaletteException("The theme's colours couldn't be read.", "$what: over 64 KiB")
        if (bytes.any { it == 0.toByte() }) throw PaletteException("The theme's colours couldn't be read.", "$what: contains NUL bytes")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { throw PaletteException("The theme's colours couldn't be read.", "$what: not UTF-8") }
        return text.removePrefix(BOM)
    }

    fun parse(bytes: ByteArray): Parsed {
        val values = LinkedHashMap<String, String>()
        val notes = mutableListOf<String>()
        for (line in text(bytes, "colors.toml").split('\n').take(MAX_LINES)) {
            if (line.trim().startsWith("[")) break
            val eq = line.indexOf('=')
            val rawKey = if (eq < 0) line else line.substring(0, eq)
            var value = if (eq < 0) "" else line.substring(eq + 1)
            val key = rawKey.filterNot { it == '"' || it == '\'' || it == ' ' }
            if (key.isEmpty() || key.startsWith("#")) continue
            val v = value.trim()
            if (v.startsWith("\"\"\"") || v.startsWith("'''") || v.startsWith("[") || v.startsWith("{")) { notes += "skipped $key: not a single-line value"; continue }
            val q = value.indexOfFirst { it == '"' || it == '\'' }
            value = if (q >= 0) value.substring(q + 1).let { rest -> rest.substring(0, rest.indexOfFirst { it == '"' || it == '\'' }.let { if (it < 0) rest.length else it }) }
                    else value.trim { it == ' ' || it in "\t\n\r\u000B\u000C" }
            if (!KEY.matches(key)) { notes += "skipped a key with unsupported characters"; continue }
            if (!VALUE.matches(value)) { notes += "skipped $key: unsupported characters in value"; continue }
            values[key] = value
        }
        return Parsed(values, notes)
    }

    /** Heimflyt's normalised `colors.toml`: the resolved canonical palette in Omarchy syntax (never the original bytes). */
    fun write(palette: OmarchyPalette): String = buildString {
        append("mode = \"").append(if (palette.light) "light" else "dark").append("\"\n")
        for (k in OmarchyResolver.CANONICAL) palette[k]?.let { append(k).append(" = \"#%06x\"\n".format(it)) }
    }
}

/** Port of `omarchy-theme-colors-from-alacritty` (THEME_ARCHITECTURE.md §3.2): only lone hex colours under `[colors*]` are read. */
object AlacrittyPalette {
    private val NAMES = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white")
    private val QUOTED = Regex("^[\"'](0[xX]|#)?[0-9a-fA-F]{6}([\"']([ \t]*#.*)?)?$")
    private val BARE = Regex("^(0[xX])?[0-9a-fA-F]{6}([ \t]*#.*)?$")
    private val HEX6 = Regex("[0-9a-fA-F]{6}")

    /** The generated `colors.toml` values, or null when the eight normal colours are not all present. */
    fun extract(bytes: ByteArray): LinkedHashMap<String, String>? {
        val direct = HashMap<String, String>(); val dotted = HashMap<String, String>()
        var section = ""
        for (line in ColorsToml.text(bytes, "alacritty.toml").split('\n').take(4096)) {
            val trimmedStart = line.trimStart(' ', '\t')
            if (trimmedStart.startsWith("[")) {
                section = if (Regex("^\\[[^]]*][ \t]*$").matches(trimmedStart)) trimmedStart.substring(1, trimmedStart.indexOf(']')) else ""
                continue
            }
            if (section.isEmpty() || !section.startsWith("colors")) continue
            val eq = line.indexOf('='); if (eq < 0) continue
            val key = line.substring(0, eq).trim(' ', '\t'); if (key.isEmpty() || key.startsWith("#")) continue
            val value = line.substring(eq + 1).trim(' ', '\t', '\r')
            if (!QUOTED.matches(value) && !BARE.matches(value)) continue
            val hex = HEX6.find(value)!!.value.lowercase()
            val path = "$section.$key"
            if (section == "colors" && key.contains('.')) dotted.putIfAbsent(path, hex) else direct.putIfAbsent(path, hex)
        }
        fun get(path: String) = direct[path] ?: dotted[path]
        val normal = NAMES.map { get("colors.normal.$it") ?: return null }
        val bright = NAMES.mapIndexed { i, n -> get("colors.bright.$n") ?: normal[i] }
        val background = get("colors.primary.background") ?: normal[0]
        val foreground = get("colors.primary.foreground") ?: normal[7]
        val colors = (listOf(background) + normal.subList(1, 7) + foreground + bright)
        val out = LinkedHashMap<String, String>()
        out["accent"] = "#" + colors[4]
        out["selection"] = "#" + (get("colors.selection.background") ?: foreground)
        out["background"] = "#$background"; out["foreground"] = "#$foreground"
        colors.forEachIndexed { i, c -> out["color$i"] = "#$c" }
        return out
    }
}

/** Port of `bin/omarchy-theme-color` `resolve_theme_colors` + mode resolution (THEME_ARCHITECTURE.md §3.3–3.4), step for step. */
object OmarchyResolver {
    val CANONICAL = listOf("background", "dark_background", "darker_background", "lighter_background", "foreground", "dark_foreground",
        "light_foreground", "bright_foreground", "accent", "selection", "muted", "red", "yellow", "orange", "green", "cyan", "blue", "magenta",
        "brown", "bright_red", "bright_yellow", "bright_green", "bright_cyan", "bright_blue", "bright_magenta")
    private val LEGACY_PALETTE = linkedMapOf("background" to "bg", "dark_background" to "dark_bg", "darker_background" to "darker_bg",
        "lighter_background" to "lighter_bg", "foreground" to "fg", "dark_foreground" to "dark_fg", "light_foreground" to "light_fg", "bright_foreground" to "bright_fg")
    private val LEGACY_ANSI = linkedMapOf("red" to "color1", "green" to "color2", "yellow" to "color3", "blue" to "color4", "magenta" to "color5",
        "cyan" to "color6", "bright_red" to "color9", "bright_green" to "color10", "bright_yellow" to "color11", "bright_blue" to "color12",
        "bright_magenta" to "color13", "bright_cyan" to "color14")
    private val ANSI = linkedMapOf("color0" to "background", "color1" to "red", "color2" to "green", "color3" to "yellow", "color4" to "blue",
        "color5" to "magenta", "color6" to "cyan", "color7" to "foreground", "color8" to "muted", "color9" to "bright_red", "color10" to "bright_green",
        "color11" to "bright_yellow", "color12" to "bright_blue", "color13" to "bright_magenta", "color14" to "bright_cyan", "color15" to "bright_foreground")
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    /**
     * Omarchy's `mix_color`: 8-bit channels, `int(a*(1-t) + b*t + 0.5)`. Upstream prints undefined values when an input is
     * not a colour; Heimflyt leaves the key empty instead (a documented deviation that only affects unusable palettes).
     */
    fun mixColor(start: String, end: String, amount: Double): String {
        fun channels(v: String): IntArray? { val h = v.removePrefix("#"); if (h.length < 6 || !h.substring(0, 6).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            return IntArray(3) { h.substring(it * 2, it * 2 + 2).toInt(16) } }
        val s = channels(start) ?: return ""; val e = channels(end) ?: return ""
        return "#" + (0..2).joinToString("") { "%02x".format((s[it] * (1 - amount) + e[it] * amount + 0.5).toInt()) }
    }

    /** Every resolved key, including Omarchy's aliases and empty values, exactly as `omarchy-theme-color --all` prints them. */
    fun resolve(raw: Map<String, String>, lightModeFile: Boolean): Map<String, String> {
        val m = HashMap(raw)
        fun has(k: String) = !m[k].isNullOrEmpty()
        fun v(k: String) = m[k] ?: ""
        fun alias(key: String, fallback: String) { if (!has(key)) m[key] = v(fallback) }
        fun or(vararg keys: String) = keys.firstOrNull { has(it) }?.let(::v) ?: ""
        LEGACY_PALETTE.forEach { (k, short) -> alias(k, short) }
        if (!has("background")) m["background"] = v("color0")
        if (!has("foreground")) m["foreground"] = v("color7")
        if (has("background")) m["color0"] = v("background")
        if (has("foreground")) m["color7"] = v("foreground")
        LEGACY_ANSI.forEach { (k, c) -> alias(k, c) }
        alias("magenta", "purple"); alias("bright_magenta", "bright_purple")
        if (!has("light_foreground")) m["light_foreground"] = or("color7", "foreground")
        if (!has("bright_foreground")) m["bright_foreground"] = or("color15", "foreground")
        m["cursor"] = v("bright_foreground")
        if (!has("lighter_background")) m["lighter_background"] = or("color0", "background")
        if (!has("dark_foreground")) m["dark_foreground"] = or("color8", "foreground")
        if (!has("muted")) m["muted"] = or("color8", "dark_foreground")
        if (!has("selection")) m["selection"] = or("selection_background", "color8", "color0", "background")
        if (!has("selection_background")) m["selection_background"] = v("selection")
        if (!has("selection_foreground")) m["selection_foreground"] = v("bright_foreground")
        if (!has("orange")) m["orange"] = v("yellow")
        if (!has("brown")) m["brown"] = mixColor(v("orange"), "#000000", 0.5)
        if (!has("dark_background")) m["dark_background"] = mixColor(v("background"), "#000000", 0.25)
        if (!has("darker_background")) m["darker_background"] = mixColor(v("background"), "#000000", 0.5)
        for (c in listOf("red", "yellow", "green", "cyan", "blue", "magenta")) if (!has("bright_$c")) m["bright_$c"] = mixColor(v(c), "#ffffff", 0.2)
        alias("purple", "magenta"); alias("bright_purple", "bright_magenta")
        ANSI.forEach { (k, c) -> alias(k, c) }
        LEGACY_PALETTE.forEach { (k, short) -> if (has(k)) m[short] = v(k) }
        if (!has("mode")) m["mode"] = v("theme_type")
        if (!has("mode")) m["mode"] = when {
            lightModeFile -> "light"
            HEX.matches(v("background")) -> v("background").substring(1).chunked(2).sumOf { it.toInt(16) }.let { if (it > 382) "light" else "dark" }
            else -> "dark"
        }
        m["theme_type"] = v("mode")
        return m
    }

    class Result(val palette: OmarchyPalette, val notes: List<String>)

    /** Canonical colours only (unknown keys dropped), `#RRGGBBAA` alpha dropped with a note, and the §3.3 validity check. */
    fun palette(resolved: Map<String, String>): Result {
        val notes = mutableListOf<String>()
        val colors = LinkedHashMap<String, Int>()
        for (k in CANONICAL) {
            val value = resolved[k].orEmpty(); if (value.isEmpty()) continue
            val hex = value.removePrefix("#")
            when {
                value.startsWith("#") && hex.length == 6 && hex.all(::isHex) -> colors[k] = hex.toInt(16)
                value.startsWith("#") && hex.length == 8 && hex.all(::isHex) -> { colors[k] = hex.substring(0, 6).toInt(16); notes += "$k: alpha ignored" }
                else -> notes += "$k: '$value' is not a colour"
            }
        }
        val bg = colors["background"] ?: throw PaletteException("The theme's colours couldn't be read.", "background: '${resolved["background"].orEmpty()}' is not a colour")
        val fg = colors["foreground"] ?: throw PaletteException("The theme's colours couldn't be read.", "foreground: '${resolved["foreground"].orEmpty()}' is not a colour")
        if (Contrast.ratio(fg, bg) < 1.5) throw PaletteException("The theme's colours are unreadable.", "foreground on background %.2f:1".format(Contrast.ratio(fg, bg)))
        return Result(OmarchyPalette(colors, resolved["mode"] == "light"), notes)
    }

    private fun isHex(c: Char) = c.isDigit() || c.lowercaseChar() in 'a'..'f'

    /** colors.toml (preferred) or alacritty.toml bytes → a validated palette. */
    fun fromFiles(colorsToml: ByteArray?, alacrittyToml: ByteArray?, lightModeFile: Boolean): Pair<Result, PaletteSource> {
        if (colorsToml != null) {
            val parsed = ColorsToml.parse(colorsToml)
            val r = palette(resolve(parsed.values, lightModeFile))
            return Result(r.palette, parsed.notes + r.notes) to PaletteSource.COLORS_TOML
        }
        val extracted = alacrittyToml?.let(AlacrittyPalette::extract)
            ?: throw PaletteException("This theme doesn't contain an Omarchy theme palette (colors.toml).")
        return palette(resolve(extracted, lightModeFile)) to PaletteSource.ALACRITTY_TOML
    }
}

enum class PaletteSource { BUNDLED, COLORS_TOML, ALACRITTY_TOML, GENERATED }
