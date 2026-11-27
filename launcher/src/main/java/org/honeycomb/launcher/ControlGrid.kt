package org.honeycomb.launcher

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt

/*
 * Control Centre's grid, copied from watchOS 27's Control Center (watchos-sim/screens/34-control-centre-top.png and
 * 06-control-centre.png, 422 x 514 px at 2x, so 211 pt wide; support.apple.com/guide/watch/apd3dfa5bc8b): two columns
 * of glyph-only buttons under the battery capsule, scrolling as one list. Measured there: each button 88 x 60 pt, a
 * capsule (30 pt corners); 3 pt between the columns, 4 pt between rows; 16 pt at each side (16 + 88 + 3 + 88 + 16 =
 * 211); the battery capsule 78 x 21 pt, 16 pt from the top and 14 pt above the first row. Every number is in watch
 * points, so the grid is the watch's enlarged to the phone's content width.
 */

/** A control's place on the grid: its column (0 or 1) and row. */
data class Cell(val id: String, val col: Int, val row: Int)

/** A control's rectangle in watch points. */
data class Box4(val x: Float, val y: Float, val w: Float, val h: Float)

object ControlGrid {
    const val COLS = 2

    /** The watch screen's width, pt. */
    const val WIDTH = 211f

    /** One button, pt (34-control-centre-top: 176 x 120 px). */
    const val BUTTON_W = 88f
    const val BUTTON_H = 60f

    /** Between the two columns and between rows, pt (6 px and 8 px). */
    const val GAP_X = 3f
    const val GAP_Y = 4f

    /** The watch's side margin, pt (32 px). */
    const val SIDE = 16f

    /** The battery capsule, pt (155 x 42 px), its top inset and the space under it to the first row (33 px, 28 px). */
    const val CAPSULE_W = 78f
    const val CAPSULE_H = 21f
    const val CAPSULE_TOP = 16f
    const val CAPSULE_GAP = 14f

    /** The glyphs in the battery capsule, pt (26 px tall). */
    const val CAPSULE_GLYPH = 13f

    /**
     * The order, the watch's read row by row: cellular (Bluetooth here) and Wi-Fi, Airplane and the battery's
     * percentage, Silent and Flashlight, then Focus (Quiet) and Theatre (Rotation lock), the speaker (Volume) and
     * AirPlay (Audio output), Display (Brightness) and Timer, then the phone's own: Calculator, Camera, Screen
     * mirroring and Hotspot.
     */
    val order: List<String> = listOf(
        "bluetooth", "wifi",
        "airplane", "battery",
        "silent", "flashlight",
        "quiet", "rotation",
        "volume", "output",
        "brightness", "timer",
        "calculator", "camera",
        "mirroring", "hotspot",
    )

    /** Where each control goes: two to a row, left then right. */
    val cells: List<Cell> = order.mapIndexed { i, id -> Cell(id, i % COLS, i / COLS) }

    /** How many rows [cells] use. */
    fun rows(cells: List<Cell>): Int = cells.maxOf { it.row } + 1

    /** The grid's height, pt. */
    fun height(cells: List<Cell>): Float = rows(cells).let { r -> r * BUTTON_H + (r - 1) * GAP_Y }

    /** Each control's rectangle, pt, x from the screen's left edge and y from the first row's top. */
    fun place(cells: List<Cell>): Map<String, Box4> = cells.associate { c ->
        c.id to Box4(SIDE + c.col * (BUTTON_W + GAP_X), c.row * (BUTTON_H + GAP_Y), BUTTON_W, BUTTON_H)
    }
}

// ---- brightness and volume ----------------------------------------------------------------------------------------

object Levels {
    private fun audio(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    fun volume(ctx: Context): Float = audio(ctx).let { a ->
        a.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    /** Media volume, as the slider's fraction of the stream's steps. */
    fun setVolume(ctx: Context, f: Float) {
        val a = audio(ctx)
        val index = (f.coerceIn(0f, 1f) * a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt()
        runCatching { a.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
    }

    fun canWriteBrightness(ctx: Context) = Settings.System.canWrite(ctx)

    fun brightness(ctx: Context): Float =
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f

    /** 1..255; the slider sets a manual level, so adaptive brightness goes off while it is used, as on a Pixel's own slider drag. */
    fun brightnessValue(f: Float): Int = (f.coerceIn(0f, 1f) * 254).roundToInt() + 1

    fun setBrightness(ctx: Context, f: Float) {
        if (!canWriteBrightness(ctx)) return
        runCatching {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessValue(f))
        }
    }

    /** Without WRITE_SETTINGS: the system's brightness dialog (SystemUI's BrightnessDialog), else Display settings. */
    fun openBrightness(ctx: Context) = Device.open(ctx, "com.android.intent.action.SHOW_BRIGHTNESS_DIALOG", Settings.ACTION_DISPLAY_SETTINGS)
}

/**
 * The areas (root coordinates, px) whose vertical drags belong to them. The one gesture recognizer ([spaceInput])
 * leaves a vertical swipe that starts in one alone. Control Centre has no sliders now, so nothing registers here.
 */
object DragOwners {
    private val areas = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.geometry.Rect>()
    /** True while an icon hold/drag reorder owns the pointer stream, preventing root navigation gestures from hijacking it. */
    @Volatile var activeDrag: Boolean = false
    /** True while a grid or favourites screen is in explicit jiggle/edit mode so root swipes never hijack icon drags. */
    @Volatile var editingLock: Boolean = false
    fun owns(p: Offset) = areas.values.any { it.contains(p) }
    internal fun set(key: String, r: androidx.compose.ui.geometry.Rect?) { if (r == null) areas.remove(key) else areas[key] = r }
}

/** The Audio output tile's action, kept from the earlier tile: Android's volume panel, which lists the outputs. */
fun openOutput(ctx: Context) = Device.open(ctx, Settings.Panel.ACTION_VOLUME)
