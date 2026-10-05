package no.heimflyt.launcher.ui.home

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Home's date line: weekday, day and month without the year. Derived from the locale's FULL date pattern (the same
 * formatter H4.2 used), with the year field removed, instead of ICU's best-pattern generator, which measured ~31–144 ms
 * of main-thread or contending ICU work at cold start. Formatters are cached per locale; composition only formats.
 */
object HomeDate {
    private class Formats(val locale: Locale, val time: DateFormat, val date: DateFormat)
    private val cache = AtomicReference<Formats?>(null)

    private fun build(locale: Locale): Formats {
        val full = DateFormat.getDateInstance(DateFormat.FULL, locale)
        val date = (full as? SimpleDateFormat)?.toPattern()?.let(::withoutYear)?.let { SimpleDateFormat(it, locale) } ?: full
        return Formats(locale, DateFormat.getTimeInstance(DateFormat.SHORT, locale), date)
    }

    private fun formats(locale: Locale): Formats = cache.get()?.takeIf { it.locale == locale } ?: build(locale).also { cache.set(it) }

    fun time(now: Date, locale: Locale): String = formats(locale).time.format(now)
    fun date(now: Date, locale: Locale, week: Boolean): String {
        val text = formats(locale).date.format(now)
        return if (!week) text else "$text · ${weekLabel(weekNumber(now, locale), locale)}"
    }
}

/**
 * Removes the year field (and the separator joining it) from a SimpleDateFormat pattern, ignoring quoted literals.
 * Returns null when removal is not safe (CJK-style patterns with attached year markers), so the full date is kept.
 */
fun withoutYear(pattern: String): String? {
    if (!pattern.contains('y')) return pattern
    if (pattern.any { it in "年년" }) return null
    val out = StringBuilder(); var quoted = false; var i = 0
    while (i < pattern.length) {
        val ch = pattern[i]
        if (ch == '\'') { quoted = !quoted; out.append(ch); i++; continue }
        if (!quoted && ch == 'y') {
            while (i < pattern.length && pattern[i] == 'y') i++
            // Drop the separator before the year ("d. MMMM y", "MMMM d, y") ...
            while (out.isNotEmpty() && out.last() in " ,") out.setLength(out.length - 1)
            // ... or, if the year leads ("y MMMM d"), the separator after it.
            if (out.isEmpty()) while (i < pattern.length && pattern[i] in " ,.-/") i++
            continue
        }
        out.append(ch); i++
    }
    val result = out.toString().trim().trimEnd(',', '-', '/')
    return result.ifEmpty { null }
}

private val WEEK_LANGUAGES = setOf("nb", "nn", "no", "sv", "da", "fi", "is", "de")
fun weekNumberDefault(locale: Locale) = locale.language in WEEK_LANGUAGES

/** The locale's week rule via Calendar (ISO in the Nordics: Monday first, four-day minimum), without java.time's zone load. */
fun weekNumber(now: Date, locale: Locale): Int = Calendar.getInstance(locale).apply { time = now }.get(Calendar.WEEK_OF_YEAR)
fun weekLabel(week: Int, locale: Locale) = if (locale.language in setOf("nb", "nn", "no")) "uke $week" else "wk $week"
