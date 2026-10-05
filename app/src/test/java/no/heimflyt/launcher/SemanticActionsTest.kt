package no.heimflyt.launcher

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class SemanticActionsTest {
    private class LaunchContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val launches=mutableListOf<Intent>()
        var failure: RuntimeException?=null
        override fun startActivity(intent: Intent) {
            failure?.let { throw it }
            launches+=intent
        }
    }

    @Test fun withoutADefaultUsesSystemSelectorsWithoutPinningAProviderOrSupplyingContent() {
        val context=LaunchContext(); val actions=SemanticActions()
        for(destination in SemanticDestination.entries.filterNot { it == SemanticDestination.TORCH }) {
            assertTrue(actions.launch(context,destination))
            val intent=context.launches.last()
            assertNull(intent.component); assertNull(intent.`package`)
            assertNull(intent.data); assertNull(intent.extras)
            if(destination==SemanticDestination.PHONE) {
                assertEquals(Intent.ACTION_DIAL,intent.action)
                assertNull(intent.selector)
            } else {
                assertEquals(Intent.ACTION_MAIN,intent.action)
                assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
                assertEquals(Intent.ACTION_MAIN,intent.selector!!.action)
                assertTrue(intent.selector!!.hasCategory(if(destination==SemanticDestination.BROWSER)
                    Intent.CATEGORY_APP_BROWSER else Intent.CATEGORY_APP_MESSAGING))
            }
        }
    }

    @Test fun unavailableOrDeniedHandlerLeavesCallerInControl() {
        val context=LaunchContext(); val actions=SemanticActions()
        for(failure in listOf(ActivityNotFoundException(),SecurityException())) {
            context.failure=failure
            assertFalse(actions.launch(context,SemanticDestination.BROWSER))
        }
        context.failure=null
        assertTrue(actions.launch(context,SemanticDestination.BROWSER))
    }

    @Test fun launchMutationCannotContaminateCachedTemplate() {
        val context=LaunchContext(); val actions=SemanticActions()
        actions.launch(context,SemanticDestination.MESSAGES)
        context.launches.last().selector!!.setPackage("example.stale")
        context.launches.last().putExtra("unexpected","content")
        actions.launch(context,SemanticDestination.MESSAGES)
        assertNull(context.launches.last().selector!!.`package`)
        assertNull(context.launches.last().extras)
    }

    private fun installDefault(probe: Intent, pkg: String, launchable: Boolean=true) {
        val pm=shadowOf(RuntimeEnvironment.getApplication().packageManager)
        pm.addResolveInfoForIntent(probe,ResolveInfo().apply {
            activityInfo=ActivityInfo().apply { packageName=pkg; name="$pkg.Handler" }
            match=IntentFilter.MATCH_CATEGORY_SCHEME
        })
        if(launchable) {
            val main=ComponentName(pkg,"$pkg.Main")
            pm.addActivityIfNotPresent(main)
            pm.addIntentFilterForActivity(main,IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
    }

    @Test fun defaultBrowserAndSmsRolesOpenTheirFrontDoorWithoutContent() {
        installDefault(Intent(Intent.ACTION_VIEW,Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE),"example.browser")
        installDefault(Intent(Intent.ACTION_SENDTO,Uri.parse("smsto:")),"example.sms")
        val context=LaunchContext(); val actions=SemanticActions()
        for((destination,pkg) in listOf(SemanticDestination.BROWSER to "example.browser",SemanticDestination.MESSAGES to "example.sms")) {
            assertTrue(actions.launch(context,destination))
            val intent=context.launches.last()
            assertEquals(pkg,intent.component?.packageName ?: intent.`package`)
            assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
            assertNull(intent.data); assertNull(intent.extras?.keySet()?.takeIf { it.isNotEmpty() })
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK!=0)
        }
    }

    @Test fun noChosenDefaultFallsBackToAndroidChooserPath() {
        // Android's resolver activity has no launcher entry.
        installDefault(Intent(Intent.ACTION_VIEW,Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE),"android",launchable=false)
        val context=LaunchContext()
        assertTrue(SemanticActions().launch(context,SemanticDestination.BROWSER))
        assertTrue(context.launches.last().selector!!.hasCategory(Intent.CATEGORY_APP_BROWSER))
    }
}
