package org.honeycomb.launcher

import android.content.Context

/** This device is taller than 3:2, where the face opens the full grid with no favourites level (Space.kt). */
fun tallDevice(ctx: Context): Boolean = ctx.resources.displayMetrics.let { tallScreen(it.widthPixels, it.heightPixels) }
