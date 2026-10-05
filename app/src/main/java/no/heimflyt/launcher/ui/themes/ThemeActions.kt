package no.heimflyt.launcher.ui.themes

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.heimflyt.launcher.HeimflytApplication
import no.heimflyt.launcher.R
import no.heimflyt.launcher.theme.create.ExportImages
import no.heimflyt.launcher.theme.create.OmarchyExport
import no.heimflyt.launcher.theme.create.DesktopVariants
import no.heimflyt.launcher.theme.create.PhotoPrep
import no.heimflyt.launcher.theme.image.Images
import no.heimflyt.launcher.theme.store.AndroidThemeFiles
import no.heimflyt.launcher.theme.store.BackgroundChoice
import no.heimflyt.launcher.theme.store.Framing
import no.heimflyt.launcher.theme.store.LoadedRecord
import no.heimflyt.launcher.theme.store.Outcome
import no.heimflyt.launcher.theme.store.ThemeOrigin
import no.heimflyt.launcher.ui.components.*
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Space
import java.io.File
import java.io.IOException

private fun decode(file: File): Bitmap? = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })

/** The created theme's masters and their compositions, in theme order. */
private fun masters(record: LoadedRecord): Pair<List<File>, List<Framing>> {
    val recipe = (record.record.origin as? ThemeOrigin.Created)?.recipe
    val files = record.record.backgrounds.map { File(record.dir, it.file) }
    return files to List(files.size) { recipe?.framings?.getOrNull(it) ?: recipe?.framing ?: Framing() }
}

/** Select one reviewed wide image for an Omarchy export slot; it is never used on Home. */
@Composable
fun DesktopVariantAction(app: HeimflytApplication, record: LoadedRecord, index: Int, status: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = record.record.desktopVariants.any { it.index == index }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val scratch = File(context.cacheDir, "desktop-variant-import")
                    val photo = PhotoPrep.load(context.contentResolver, uri, PhotoPrep.masterLimit(context), scratch, AndroidThemeFiles)
                    try {
                        val bitmap = decode(photo.masterFile) ?: throw IOException("Couldn't read the image")
                        try { DesktopVariants(app.themes.store, AndroidThemeFiles).attach(record, index, bitmap) }
                        finally { bitmap.recycle() }
                    } finally {
                        photo.preview.recycle()
                        photo.masterFile.delete()
                    }
                }.getOrElse { Outcome.Failed("Couldn't use this desktop image.") }
            }
            status(when (result) {
                is Outcome.Installed -> "Desktop image saved for Omarchy export."
                is Outcome.Failed -> result.message
                is Outcome.Uncertain -> result.message
                Outcome.Superseded -> "Theme changed while selecting the image. Try again."
                else -> "Couldn't use this desktop image."
            })
        }
    }
    HRow(if (selected) "Replace desktop image" else "Choose desktop image",
        subtitle = "image ${index + 1} for Omarchy export; Home stays the same", compact = true,
        onClick = { picker.launch(arrayOf("image/*")) },
        trailing = { Glyph(R.drawable.glyph_chevron, Heimflyt.t.color.inkMuted, 18.dp) })
    if (selected) HRow("Use original for desktop", subtitle = "remove this image's separate Omarchy version", compact = true,
        onClick = {
            scope.launch {
                val result = withContext(Dispatchers.IO) { DesktopVariants(app.themes.store, AndroidThemeFiles).remove(record, index) }
                status(when (result) {
                    is Outcome.Installed -> "Desktop image removed; export uses the original photo."
                    is Outcome.Failed -> result.message
                    Outcome.Superseded -> "Theme changed. Try again."
                    else -> "Couldn't change the desktop image."
                })
            }
        }, trailing = { Glyph(R.drawable.glyph_chevron, Heimflyt.t.color.inkMuted, 18.dp) })
}

/**
 * Share or save a created theme as a portable Omarchy theme (.zip). Explicit owner actions only; no network. Share hands
 * a read-only content URI from Heimflyt's one FileProvider (cache `exports/` only) to Android's share sheet; Save uses the
 * system "save as" (SAF). The backgrounds are source-quality re-encodes (pixels only).
 */
@Composable
fun ExportAction(app: HeimflytApplication, entry: ThemeEntry, record: LoadedRecord, status: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun write(out: java.io.OutputStream) {
        val (files, framings) = masters(record)
        val variants = record.record.desktopVariants.associate { it.index to File(record.dir, it.file) }
        app.themes.store.pin(record.dir).use { ExportImages.write(out, record.record.name, record.palette, files, framings, variants) }
    }
    fun prepare(): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val temp = File.createTempFile("omarchy-", ".partial", dir)
        try {
            temp.outputStream().use { write(it) }
            return temp
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = prepare()
                    try { context.contentResolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Couldn't open export destination") }
                    finally { temp.delete() }
                }.isSuccess
            }
            status(if (ok) "Saved ${OmarchyExport.directory(record.record.name)}.zip." else "Couldn't export the theme.")
        }
    }
    HRow("Share theme", subtitle = "an Omarchy theme (.zip) for Omarchy or another Krets", compact = true, onClick = {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = prepare()
                    try {
                        val target = File(temp.parentFile, "${OmarchyExport.directory(record.record.name)}.zip")
                        if (!temp.renameTo(target)) throw IOException("Couldn't publish prepared ZIP")
                        target
                    } finally { temp.delete() }
                }.getOrNull()
            }
            if (file == null) { status("Couldn't export the theme."); return@launch }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/zip")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri).putExtra(android.content.Intent.EXTRA_SUBJECT, record.record.name)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share ${record.record.name}")) }
        }
    }, trailing = { Glyph(R.drawable.glyph_download, Heimflyt.t.color.inkMuted, 18.dp) })
    HRow("Save as .zip", subtitle = "choose where to keep it", compact = true,
        onClick = { saver.launch("${OmarchyExport.directory(entry.name)}.zip") },
        trailing = { Glyph(R.drawable.glyph_chevron, Heimflyt.t.color.inkMuted, 18.dp) })
}

/**
 * Wallpaper spike (opt-in experiment): copy this theme's image, framed as on Home but without Heimflyt's readability bands,
 * to Android's Home or Home + Lock Screen wallpaper. Only on an explicit tap, with the consequence stated; never part of
 * Apply, never retried, never undone automatically. Android keeps its lock screen, clock and notifications.
 */
@Composable
fun AndroidWallpaperAction(app: HeimflytApplication, record: LoadedRecord, background: BackgroundChoice, framing: Framing, status: (String?) -> Unit) {
    val t = Heimflyt.t; val c = t.color
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    HRow("Android wallpaper · experiment", subtitle = "also use this image outside Krets", compact = true, onClick = { open = true },
        trailing = { Glyph(R.drawable.glyph_chevron, c.inkMuted, 18.dp) })
    if (!open) return
    var follow by remember { mutableStateOf(no.heimflyt.launcher.theme.AndroidWallpaper.follow(context) != 0) }
    fun set(which: Int, label: String) {
        working = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                no.heimflyt.launcher.theme.AndroidWallpaper.set(context, app.themes.store, record, background.index, framing, which)
            }
            // "Follow Krets" is remembered only when the owner ticked it and the first set succeeded.
            no.heimflyt.launcher.theme.AndroidWallpaper.setFollow(context, if (follow && result == no.heimflyt.launcher.theme.AndroidWallpaper.Result.Set) which else 0)
            working = false; open = false
            status(when (result) {
                no.heimflyt.launcher.theme.AndroidWallpaper.Result.Set -> "Android $label wallpaper set" + (if (follow) ", and it will follow Krets's images." else ".") +
                    " Your previous wallpaper can be chosen again in Android's wallpaper settings."
                no.heimflyt.launcher.theme.AndroidWallpaper.Result.NotAllowed -> "Android doesn't allow apps to set the wallpaper on this phone."
                no.heimflyt.launcher.theme.AndroidWallpaper.Result.Failed -> "Couldn't set the Android wallpaper. Nothing else changed."
            })
        }
    }
    HSheet({ if (!working) open = false }) {
        Text("Use this image as Android's wallpaper?", style = t.type.title)
        Spacer(Modifier.height(Space.s))
        Text("This replaces your current Android wallpaper. Krets can't restore it for you. Android may also change its system colours to match. " +
            "Your lock screen clock, notifications and security stay Android's.", style = t.type.body)
        ToggleRow("Follow Krets", follow, "Change it again whenever Krets's image changes: when a theme is applied and when images rotate.") { follow = it }
        Spacer(Modifier.height(Space.l))
        if (working) Text("Setting wallpaper…", style = t.type.meta) else {
            Row {
                QuietButton("Cancel") { open = false }
                if (no.heimflyt.launcher.theme.AndroidWallpaper.follow(context) != 0) QuietButton("Stop following") {
                    no.heimflyt.launcher.theme.AndroidWallpaper.setFollow(context, 0); open = false; status("Android's wallpaper no longer follows Krets.")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                QuietButton("Home screen") { set(WallpaperManager.FLAG_SYSTEM, "Home") }
                PrimaryButton("Home & Lock Screen") { set(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK, "Home & Lock Screen") }
            }
        }
    }
}
