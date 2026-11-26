package org.honeycomb.launcher

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Active media sessions for the Smart Stack while the launcher is visible. */
object Providers {
    val media = MutableStateFlow<Media?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var unwatch: (() -> Unit)? = null

    fun start(ctx: Context) {
        if (job != null) return
        val app = ctx.applicationContext
        job = scope.launch {
            MediaListener.Defaults.changes.collect {
                unwatch?.invoke()
                media.value = currentMedia(app)
                unwatch = watchMedia(app) { media.value = currentMedia(app) }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        unwatch?.invoke(); unwatch = null
    }
}
