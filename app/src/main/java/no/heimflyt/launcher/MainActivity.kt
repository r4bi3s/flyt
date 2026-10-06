package no.heimflyt.launcher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import no.heimflyt.launcher.gesture.CancelReason
import no.heimflyt.launcher.theme.startup

class MainActivity : ComponentActivity() {
    internal var touch: RadialTouchView? = null
    private val homeEpoch=mutableIntStateOf(0)
    /** Bumped when Android asks for Flyt's settings (APPLICATION_PREFERENCES); Home then opens Tune. */
    private val tuneRequest=mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app=application as HeimflytApplication
        // THEME_ARCHITECTURE.md §8.4: one synchronous read of active.json (< 1 KiB) for the pre-content window only.
        val t0=android.os.SystemClock.elapsedRealtimeNanos()
        val startup=app.themes.startup()
        android.util.Log.i("HeimflytTheme","active.json read ${(android.os.SystemClock.elapsedRealtimeNanos()-t0)/1000} us, pointer=${startup!=null}")
        if (startup!=null) {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(startup.ground or (0xff shl 24)))
            applyBarAppearance(startup.statusLight,startup.navLight)
        } else {
            val light=no.heimflyt.launcher.ui.theme.FallbackTokens.resolved.isLight
            applyBarAppearance(light,light)
        }
        importFrom(intent)
        if (intent?.action==Intent.ACTION_APPLICATION_PREFERENCES) tuneRequest.intValue++
        setContent { HeimflytScreen(app,homeEpoch.intValue,{ touch=it },::openHomeSettings,tuneRequest.intValue) }
    }
    /** A shared (SEND) or opened (VIEW) theme .zip: queue it for the Themes Report. Returns true if the intent was one. */
    private fun importFrom(intent: Intent?): Boolean {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                                  else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return false
        (application as HeimflytApplication).themes.pendingImport.value = uri
        return true
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (importFrom(intent)) return
        touch?.cancel(CancelReason.HOME)
        if (intent.action==Intent.ACTION_APPLICATION_PREFERENCES) { tuneRequest.intValue++; return }
        homeEpoch.intValue++
    }
    override fun onStop() {
        super.onStop()
        // The multi-image rotation boundary: the screen turning off while Heimflyt was in front (never per Home render).
        // Android stops the activity when the lock screen comes up, ~3 s before the display reports non-interactive
        // (measured on the phone), so a keyguard-caused stop counts as the boundary too; opening another app never does.
        val screenOff = getSystemService(android.os.PowerManager::class.java)?.isInteractive == false ||
            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (screenOff) (application as HeimflytApplication).themes.wallpaper.advance()
    }
    override fun onPause() {
        touch?.cancel(CancelReason.LIFECYCLE)
        super.onPause()
    }
    /**
     * Icon appearance follows the resolved surface beneath the bars (VISUAL_SYSTEM.md §2.2), set after enableEdgeToEdge()
     * so its system-mode defaults cannot overwrite it, and again on apply and on Home/other route changes. Navigation-bar
     * contrast enforcement is left on.
     */
    internal fun applyBarAppearance(statusLight: Boolean, navLight: Boolean) {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            if (isAppearanceLightStatusBars!=statusLight) isAppearanceLightStatusBars = statusLight
            if (isAppearanceLightNavigationBars!=navLight) isAppearanceLightNavigationBars = navLight
        }
    }
    /** Status spike: hide Android's status bar on Home only; a swipe from the top shows it transiently. Never hides navigation. */
    /**
     * Status spike: hide Android's status bar on Home only; a swipe from the top shows it transiently. Never hides navigation.
     * Measured cost: Android animates the hide inside this window on every return to Home, so the window
     * draws ~44 frames per return and keeps its whole buffer set (+~58 MB). Neither WindowInsetsController, FLAG_FULLSCREEN
     * nor stopping Compose's insets-animation dispatch changes that; the experiment therefore stays off by default.
     */
    internal fun setHomeStatusHidden(hidden: Boolean) {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hidden) hide(androidx.core.view.WindowInsetsCompat.Type.statusBars()) else show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
    }
    /**
     * Android's three-button contrast scrim is a dark strip under the navigation bar. On Home over a verified image the
     * nav zone's icon contrast is already guaranteed in the stored pixels, so the strip is dropped there (owner feedback);
     * everywhere else it stays on.
     */
    internal fun setNavContrastEnforced(enforced: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 29 && window.isNavigationBarContrastEnforced != enforced) window.isNavigationBarContrastEnforced = enforced
    }
    internal fun applyWindowGround(ground: Int) {
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(ground or (0xff shl 24)))
    }
    private fun openHomeSettings() {
        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        catch (_: RuntimeException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}
