package no.heimflyt.launcher

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/** One local, explicit flashlight action. The callback tracks changes made by Android or another app too. */
class TorchControl(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val cameraId: String? = runCatching {
        manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
    @Volatile private var enabled = false
    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, on: Boolean) { if (cameraId == this@TorchControl.cameraId) enabled = on }
        override fun onTorchModeUnavailable(cameraId: String) { if (cameraId == this@TorchControl.cameraId) enabled = false }
    }

    init { runCatching { manager.registerTorchCallback(callback, Handler(Looper.getMainLooper())) } }

    fun toggle(): Boolean = try {
        val id = cameraId ?: return false
        val next = !enabled
        manager.setTorchMode(id, next)
        enabled = next
        true
    } catch (_: Exception) { false }
}
