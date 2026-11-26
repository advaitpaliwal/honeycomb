package org.honeycomb.launcher

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Bundle

/** Android supplies and validates the pin token. A widget is added only after the user's explicit confirmation. */
class PinWidgetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
        val info = request?.getAppWidgetProviderInfo(this)
        if (request == null || !request.isValid || info == null) { finish(); return }
        AlertDialog.Builder(this).setTitle("Add widget?").setMessage(info.loadLabel(packageManager))
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .setPositiveButton("Add") { _, _ ->
                val id = Widgets.host(this).allocateAppWidgetId()
                val accepted = runCatching { request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) }) }.getOrDefault(false)
                if (accepted) Widgets.add(this, id) else Widgets.discard(this, id)
                finish()
            }.show()
    }
}
