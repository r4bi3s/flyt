package no.heimflyt.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Fixed experiment destinations, not a provider registry. IDs are persisted; labels are UI only. */
enum class SemanticDestination(val id: String, val label: String, val description: String) {
    BROWSER("browser", "Browser", "Opens your default browser"),
    PHONE("phone", "Phone", "Opens the dialer; never places a call"),
    MESSAGES("messages", "Messages", "Opens your default messaging app; sends nothing"),
    TORCH("torch", "Flashlight", "Turns the flashlight on or off");
}

/**
 * Follow Android's own default for each destination. Browser and Messages resolve the Browser/SMS role's
 * probe intent once, at release, and open that app's front door; the probe (no real URL or recipient) is
 * never launched. Nothing resolved is cached, so a changed default cannot go stale. Without a default,
 * Android returns its resolver, which has no launcher entry: fall back to the category selector and let
 * Android's chooser decide. Phone uses ACTION_DIAL, which already targets the default dialer.
 */
class SemanticActions {
    private var torch: TorchControl? = null
    fun prepareTorch(context: Context) { if (torch == null) torch = TorchControl(context.applicationContext) }
    private val selectors = SemanticDestination.entries.filterNot { it == SemanticDestination.TORCH }.associateWith { destination ->
        when (destination) {
            SemanticDestination.BROWSER -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
            SemanticDestination.PHONE -> Intent(Intent.ACTION_DIAL)
            SemanticDestination.MESSAGES -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
            SemanticDestination.TORCH -> error("Flashlight does not start an activity")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    private val defaultProbes = mapOf(
        SemanticDestination.BROWSER to Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE),
        SemanticDestination.MESSAGES to Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
    )

    fun launch(context: Context, destination: SemanticDestination): Boolean = try {
        if (destination == SemanticDestination.TORCH) {
            prepareTorch(context)
            torch?.toggle() == true
        } else {
            // Copy because Android may prepare/mutate an Intent during dispatch.
            context.startActivity(defaultFrontDoor(context, destination) ?: Intent(selectors.getValue(destination)))
            true
        }
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun defaultFrontDoor(context: Context, destination: SemanticDestination): Intent? = try {
        defaultProbes[destination]?.let { probe ->
            val pm = context.packageManager
            pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                ?.let { pm.getLaunchIntentForPackage(it) }
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } catch (_: RuntimeException) {
        null
    }
}
