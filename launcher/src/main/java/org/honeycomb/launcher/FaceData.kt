package org.honeycomb.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow

/** Our own packages (org.honeycomb.*) never show in the grid or the launcher. */
fun isInternal(pkg: String): Boolean = pkg.startsWith("org.honeycomb.")

/**
 * Notification access, granted to this NotificationListenerService, is what lets the face read other apps' media
 * sessions (MediaSessionManager.getActiveSessions).
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() { Defaults.live = this; Defaults.changes.value++ }
    override fun onListenerDisconnected() { if (Defaults.live === this) Defaults.live = null; Defaults.changes.value++ }
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) { Defaults.posted.value++ }
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) { Defaults.posted.value++ }
    override fun onNotificationRemoved(
        sbn: android.service.notification.StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        Defaults.posted.value++
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return
        when (reason) {
            REASON_CLICK -> runCatching { recordNotifOutcome(applicationContext, pkg, opened = true) }
            REASON_CANCEL, REASON_CANCEL_ALL -> runCatching { recordNotifOutcome(applicationContext, pkg, opened = false) }
        }
    }

    object Defaults {
        @Volatile var live: MediaListener? = null
        /** Bumped when notification access connects or drops, so the face re-reads its media sessions. */
        val changes = MutableStateFlow(0)
        /** Bumped when a notification is posted or removed, so Notification Centre re-reads the list. */
        val posted = MutableStateFlow(0)
    }
}

class Media(
    val pkg: String,
    val title: String,
    val artist: String?,
    val playing: Boolean,
    val art: ImageBitmap? = null,
    val appName: String? = null,
)

/**
 * Opens what is playing as Now Playing on watchOS opens its app: the session's own activity
 * ([MediaController.getSessionActivity], the player screen), else the app's launcher entry.
 */
fun openMedia(ctx: Context, pkg: String) {
    val session = sessions(ctx)?.firstOrNull { it.packageName == pkg }?.sessionActivity
    if (session != null && runCatching { session.send() }.isSuccess) return
    ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/** Plays or pauses the top media session of [pkg]. */
fun playPause(ctx: Context, pkg: String, playing: Boolean) {
    val c = sessions(ctx)?.firstOrNull { it.packageName == pkg } ?: return
    if (playing) c.transportControls.pause() else c.transportControls.play()
}

/** Skips to the previous track on [pkg]'s media session. */
fun skipPrevious(ctx: Context, pkg: String) {
    sessions(ctx)?.firstOrNull { it.packageName == pkg }?.transportControls?.skipToPrevious()
}

/** Skips to the next track on [pkg]'s media session. */
fun skipNext(ctx: Context, pkg: String) {
    sessions(ctx)?.firstOrNull { it.packageName == pkg }?.transportControls?.skipToNext()
}

/** Re-read on every resume, so a permission granted in Settings shows at once. */
object FaceTick { val resumes = MutableStateFlow(0) }

/** The top active media session (preferring one that is actively playing), or null when none or no notification access. */
fun currentMedia(ctx: Context): Media? {
    val active = sessions(ctx).orEmpty()
    val c = active.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
        ?: active.firstOrNull { !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
        ?: return null
    val md = c.metadata ?: return null
    val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
    val rawArt = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    val art = rawArt?.let { bmp ->
        runCatching {
            val maxSide = 200
            val scaled = if (bmp.width > maxSide || bmp.height > maxSide) {
                val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
            } else bmp
            scaled.asImageBitmap()
        }.getOrNull()
    }
    val appName = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString()
    }.getOrNull()
    return Media(
        pkg = c.packageName,
        title = title,
        artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() },
        playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
        art = art,
        appName = appName,
    )
}

private fun sessions(ctx: Context): List<MediaController>? = try {
    ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(ctx, MediaListener::class.java))
} catch (_: SecurityException) {
    null
}

/** Calls [changed] when a session starts, stops, or changes what it plays; returns the unsubscribe. */
fun watchMedia(ctx: Context, changed: () -> Unit): () -> Unit {
    val msm = ctx.getSystemService(MediaSessionManager::class.java)
    val main = Handler(Looper.getMainLooper())
    val watched = mutableListOf<Pair<MediaController, MediaController.Callback>>()
    val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
        override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
    }
    fun rewatch(list: List<MediaController>?) {
        watched.forEach { (c, k) -> c.unregisterCallback(k) }
        watched.clear()
        list?.forEach { it.registerCallback(cb, main); watched += it to cb }
        changed()
    }
    val listener = MediaSessionManager.OnActiveSessionsChangedListener { rewatch(it) }
    try {
        msm.addOnActiveSessionsChangedListener(listener, ComponentName(ctx, MediaListener::class.java), main)
    } catch (_: SecurityException) {
        return {}
    }
    rewatch(sessions(ctx))
    return {
        msm.removeOnActiveSessionsChangedListener(listener)
        watched.forEach { (c, k) -> c.unregisterCallback(k) }
    }
}
