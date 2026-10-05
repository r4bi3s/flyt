package no.heimflyt.launcher.theme

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import no.heimflyt.launcher.theme.image.Images
import no.heimflyt.launcher.theme.store.Framing
import no.heimflyt.launcher.theme.store.LoadedRecord
import no.heimflyt.launcher.theme.store.ThemeStore
import java.io.File

/**
 * Android wallpaper harmony (owner-approved): Heimflyt's current image, framed as on Home but without its
 * shading, set with the flagged setter (FLAG_SYSTEM and/or FLAG_LOCK, never the unflagged both-targets overloads), no
 * backup. Only on an explicit tap or, if the owner opted in, when Heimflyt's own image changes (theme apply or the
 * screen-off rotation boundary). Never retried, never undone automatically.
 */
object AndroidWallpaper {
    const val NONE = 0

    /** The opt-in "follow Heimflyt" targets (0 = off). */
    fun follow(context: Context) = prefs(context).getInt("follow", NONE)
    fun setFollow(context: Context, which: Int) = prefs(context).edit().putInt("follow", which).apply()
    private fun prefs(context: Context) = context.getSharedPreferences("android_wallpaper", Context.MODE_PRIVATE)

    sealed interface Result { data object Set : Result; data object NotAllowed : Result; data object Failed : Result }

    /** Blocking; call off the main thread. */
    fun set(context: Context, store: ThemeStore, record: LoadedRecord, imageIndex: Int, framing: Framing, which: Int): Result {
        val wm = WallpaperManager.getInstance(context)
        if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) return Result.NotAllowed
        val stored = record.record.backgrounds.getOrNull(imageIndex) ?: return Result.Failed
        val dm = context.resources.displayMetrics
        val w = minOf(dm.widthPixels, dm.heightPixels); val h = maxOf(dm.widthPixels, dm.heightPixels)
        val t0 = SystemClock.elapsedRealtime()
        val id = runCatching {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val src = (if (record.record.id == "bundled:krets")
                context.assets.open(stored.file).use { BitmapFactory.decodeStream(it, null, options) }
            else store.pin(record.dir).use { BitmapFactory.decodeFile(File(record.dir, stored.file).path, options) })
                ?: error("unreadable")
            val crop = Images.crop(src, w, h, framing); src.recycle()
            wm.setBitmap(crop, null, false, which).also { crop.recycle() }
        }
        Log.i("HeimflytTheme", "android wallpaper which=$which image=$imageIndex ${SystemClock.elapsedRealtime() - t0} ms id=${id.getOrNull()} error=${id.exceptionOrNull()?.javaClass?.simpleName}")
        return if (id.getOrNull()?.let { it != 0 } == true) Result.Set else Result.Failed
    }
}
