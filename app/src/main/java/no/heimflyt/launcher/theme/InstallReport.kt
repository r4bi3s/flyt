package no.heimflyt.launcher.theme

import no.heimflyt.launcher.theme.palette.OmarchyPalette
import no.heimflyt.launcher.theme.palette.PaletteSource
import no.heimflyt.launcher.theme.zip.IgnoredKind

/** What an inspect pass found, whatever the byte source (`.zip` or GitHub): the owner's consent screen (SURFACES.md §9.3). */
interface InstallReport {
    val id: String
    val name: String
    val palette: OmarchyPalette
    val source: PaletteSource
    val notes: List<String>
    /** `github.com/owner/repo · a1b2c3d`, or the file name and hash. */
    val origin: String
    val backgroundCount: Int
    val backgroundBytes: Long
    val hasPreview: Boolean
    val ignored: Map<IgnoredKind, List<String>>
    /** Bytes the install pass processes (for progress). */
    val totalBytes: Long
}
