package org.honeycomb.launcher

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import org.honeycomb.launcher.design.Native

/**
 * The phone's Themed icons switch (Settings > Wallpaper & style > Themed icons). Android keeps that switch in the home
 * app, not in Settings: Wallpaper & style (AOSP ThemePicker, ThemedIconUtils.java) resolves the home activity's
 * `com.android.launcher3.themedicon.option` meta-data to a content provider, reads `icon_themed` (column
 * `boolean_value`) to draw the switch and writes it back when the user flips it. Launcher3 (GridCustomizationsProvider),
 * Lawnchair (LauncherCustomizationProvider) and Neo Launcher declare the same contract; [IconThemeProvider] is Honeycomb's.
 * Off by default, as on a phone that never turned it on.
 */
object IconTheme {
    private const val PREFS = "icon_theme"
    private const val KEY = "themed"

    fun themed(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setThemed(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        AppCache.refresh()
    }

    /** A themed icon's two colours, from the phone's scheme. */
    class Colours(val background: Int, val glyph: Int)

    /** The themed colours when the switch is on, else null (full-colour icons). */
    fun colours(ctx: Context): Colours? = if (!themed(ctx)) null else Native.scheme(ctx).let {
        Colours(it.secondaryContainer.toArgb(), it.onSecondaryContainer.toArgb())
    }
}

/**
 * The provider Wallpaper & style talks to (see [IconTheme]): `content://<package>.grid_control/icon_themed`, one row with
 * `boolean_value` 1 or 0; an update with `boolean_value` sets it. Only a caller holding BIND_WALLPAPER (the system's
 * wallpaper and style app) may read or write it, as Launcher3 and Lawnchair require.
 */
class IconThemeProvider : ContentProvider() {
    override fun onCreate() = true

    private fun allowed(): Boolean = context?.checkCallingPermission(Manifest.permission.BIND_WALLPAPER) == PackageManager.PERMISSION_GRANTED

    private fun themedPath(uri: Uri) = uri.pathSegments.firstOrNull() == ICON_THEMED

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (!allowed()) throw SecurityException("requires ${Manifest.permission.BIND_WALLPAPER}")
        if (!themedPath(uri)) return null
        return MatrixCursor(arrayOf(BOOLEAN_VALUE)).apply { addRow(arrayOf(if (IconTheme.themed(context!!)) 1 else 0)) }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (!allowed()) throw SecurityException("requires ${Manifest.permission.BIND_WALLPAPER}")
        if (!themedPath(uri) || values?.containsKey(BOOLEAN_VALUE) != true) return 0
        IconTheme.setThemed(context!!, values.getAsBoolean(BOOLEAN_VALUE) == true)
        context!!.contentResolver.notifyChange(uri, null)
        return 1
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null.also { update(uri, values, null, null) }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun getType(uri: Uri) = "vnd.android.cursor.dir/launcher_grid"

    companion object {
        const val ICON_THEMED = "icon_themed"
        const val BOOLEAN_VALUE = "boolean_value"
    }
}
