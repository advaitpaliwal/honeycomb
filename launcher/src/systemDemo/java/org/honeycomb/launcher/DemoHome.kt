package org.honeycomb.launcher

import android.app.Application
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Color

/** Product-image build only. Keep one status-bar disable token for the lifetime of HOME. */
class DemoHome : Application() {
    override fun onCreate() {
        super.onCreate()
        val bar = getSystemService("statusbar")
        val flags = listOf("DISABLE_EXPAND", "DISABLE_CLOCK", "DISABLE_SYSTEM_INFO", "DISABLE_NOTIFICATION_ICONS")
            .fold(0) { mask, name -> mask or bar.javaClass.getField(name).getInt(null) }
        bar.javaClass.getMethod("disable", Int::class.javaPrimitiveType).invoke(bar, flags)
        // Prepare a clean HOME task while the screen is off, even after leaving for another app.
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                startActivity(android.content.Intent(context, LauncherActivity::class.java).apply {
                    this.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }
        }, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        val audio = getSystemService(android.media.AudioManager::class.java)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        val theme = getSystemService(android.app.UiModeManager::class.java)
        if (theme.nightMode != android.app.UiModeManager.MODE_NIGHT_YES) {
            theme.nightMode = android.app.UiModeManager.MODE_NIGHT_YES
        }
        if (!Setup.done(this)) {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { black ->
                black.eraseColor(Color.BLACK)
                getSystemService(WallpaperManager::class.java).setBitmap(black)
                black.recycle()
            }
            Setup.finish(this)
        }
    }
}
