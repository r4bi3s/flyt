package no.heimflyt.launcher.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Shapes
import no.heimflyt.launcher.ui.theme.Space
import java.util.Locale

/** Battery as Android reports it in the sticky ACTION_BATTERY_CHANGED broadcast. No permission. */
data class BatteryState(val percent: Int, val charging: Boolean, val full: Boolean)

/**
 * Event-driven battery state, registered only while Home is resumed (like the clock). The sticky broadcast answers at
 * registration; afterwards Android delivers changes. No polling, no service, no permission.
 */
@Composable
fun rememberBattery(): BatteryState? {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf<BatteryState?>(null) }
    DisposableEffect(context, lifecycle) {
        fun read(i: Intent?) {
            if (i == null) return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) { state = null; return }   // never show a value we can't trust
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            state = BatteryState((level * 100 + scale / 2) / scale, status == BatteryManager.BATTERY_STATUS_CHARGING, status == BatteryManager.BATTERY_STATUS_FULL)
        }
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) = read(i) }
        var registered = false
        fun update() {
            val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed && !registered) {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                read(if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter))
                registered = true
            } else if (!resumed && registered) { context.unregisterReceiver(receiver); registered = false }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); if (registered) context.unregisterReceiver(receiver) }
    }
    return state
}

/**
 * Status spike: a minimal theme-coloured row drawn in the status-bar band while Android's bar is hidden on Home. Time and
 * battery only: the two signals Heimflyt can represent exactly with public APIs and no new permission. Decorative for
 * accessibility (Android's own bar and shade stay one swipe away).
 */
@Composable
fun HomeStatusRow(backdrop: Boolean, modifier: Modifier = Modifier) {
    val t = Heimflyt.t; val c = t.color
    val now = rememberClock()
    val locale = Locale.getDefault()
    val battery = rememberBattery()
    val style = t.type.label.copy(color = c.inkStrong, fontFeatureSettings = "tnum")
    Row(modifier.fillMaxWidth().padding(horizontal = Space.gutter).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        val pill = if (backdrop) Modifier.clip(Shapes.xs).background(c.raised).padding(horizontal = Space.s, vertical = 2.dp) else Modifier
        Text(remember(now, locale) { HomeDate.time(now, locale) }, style = style, modifier = pill)
        Spacer(Modifier.weight(1f))
        battery?.let { b ->
            Text((if (b.charging) "⚡ " else "") + "${b.percent} %", style = style, modifier = pill)
        }
    }
}
