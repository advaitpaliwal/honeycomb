package org.honeycomb.launcher

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/*
 * Zero-permission, zero-token, on-device Recommended People engine:
 *   - Combines Android's ROLE_HOME Conversation Shortcuts (LauncherApps.getShortcuts: Messages, WhatsApp, Signal,
 *     Instagram, Telegram, Messenger, Discord, Phone — real avatars + 1-tap direct chat launch!) with
 *   - Real-time interaction history logged passively by MediaListener (incoming messages) + outbound taps (2x weight).
 *   - Pure-math dual-timescale Hawkes decay (45-min active chat burst + 7-day close-circle habit) + live unread boost
 *     + OS shortcut recency/rank prior so top people work immediately on minute 1, with zero READ_CONTACTS prompt.
 */

private const val PEOPLE_PREFS = "people_signals"
private const val KEY_EVENTS = "interactions_v1"
private const val MAX_PEOPLE_EVENTS = 300

private const val BURST_HALF_LIFE_MS = 45.0 * 60_000.0            // 45 min active conversation burst
private const val HABIT_HALF_LIFE_MS = 7.0 * 24.0 * 3600_000.0    // 7 day close-circle baseline
private const val UNREAD_HALF_LIFE_MS = 10.0 * 60_000.0           // 10 min live unread ping boost
private const val SHORTCUT_HALF_LIFE_MS = 48.0 * 3600_000.0       // 48 hr OS shortcut recency decay

private const val W_P_BURST = 2.2
private const val W_P_HABIT = 1.8
private const val W_P_UNREAD = 2.0
private const val W_P_SHORTCUT = 1.1

/** Known messaging / social / calling packages whose conversation shortcuts & notifications represent real people. */
internal val PEOPLE_MESSAGING_PACKAGES = setOf(
    "com.google.android.apps.messaging",
    "com.samsung.android.messaging",
    "com.android.mms",
    "com.whatsapp",
    "com.whatsapp.w4b",
    "org.thoughtcrime.securesms",     // Signal
    "org.telegram.messenger",
    "org.telegram.messenger.web",
    "com.instagram.android",
    "com.facebook.orca",              // Messenger
    "com.discord",
    "com.snapchat.android",
    "com.Slack",
    "com.google.android.dialer",
    "com.samsung.android.dialer",
    "com.viber.voip",
    "jp.naver.line.android",
    "com.tencent.mm",                 // WeChat
    "com.twitter.android",
    "com.beeper.chat",
    "com.beeper.android",
)

private val NON_PERSON_EXACT = setOf(
    "new message", "new chat", "new conversation", "new group", "compose", "create",
    "search", "camera", "settings", "notifications", "messages", "chats", "calls",
    "voicemail", "saved messages", "archived", "spam", "blocked", "unknown",
    "my profile", "status", "stories", "reels", "explore", "inbox", "requests",
    "scan qr", "qr code", "add contact", "contacts", "dialpad", "keypad",
    "verification", "security", "support", "help", "customer service", "alerts",
    "delivery", "info", "noreply", "no-reply", "do not reply",
)

private val NON_PERSON_KEYWORDS = listOf(
    "verification", "verify", "security alert", "otp", "passcode", "one-time",
    "noreply", "no-reply", "do not reply", "unsubscribe", "order #", "tracking #",
    "your code", "login code", "sign-in", "delivery update", "shipment",
    "bank of ", "chase", "wells fargo", "amex", "citibank", "capital one",
    "uber eats", "doordash", "instacart", "amazon", "fedex", "ups ", "usps",
)

/** Filters out OTP short codes, toll-free automated lines, launcher action shortcuts, and automated brand alerts. */
fun isLikelyRealPerson(rawName: String, pkg: String = ""): Boolean {
    val name = rawName.trim()
    if (name.length < 2 || name.length > 48) return false
    val lower = name.lowercase(Locale.US)
    if (lower in NON_PERSON_EXACT) return false
    if (NON_PERSON_KEYWORDS.any { it in lower }) return false

    // Reject 4–6 digit SMS short codes (e.g. 22395, 732873) and toll-free 800/888/877/866/855/844/833 numbers
    val digitsOnly = name.filter { it.isDigit() }
    val letters = name.count { it.isLetter() }
    if (letters == 0) {
        if (digitsOnly.length in 3..6) return false
        val clean10 = if (digitsOnly.length == 11 && digitsOnly.startsWith("1")) digitsOnly.drop(1) else digitsOnly
        if (clean10.length == 10 && clean10.take(3) in setOf("800", "888", "877", "866", "855", "844", "833")) return false
        if (digitsOnly.length < 7) return false
    }
    // Reject obvious action labels ("New ...", "Start ...", "Create ...", "Scan ...")
    if (lower.startsWith("new ") || lower.startsWith("start ") || lower.startsWith("create ") || lower.startsWith("scan ")) {
        return false
    }
    return true
}

/** Normalizes a contact name for cross-app deduplication & fuzzy matching. */
internal fun normalizePersonName(name: String): String =
    name.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

/** First name (or short display label) for compact circular avatar chips. */
fun shortPersonName(fullName: String): String {
    val clean = fullName.trim()
    val first = clean.substringBefore(' ').trim()
    return if (first.length in 2..14) first else clean.take(12)
}

/** Pure data candidate representing a person/conversation discovered via OS shortcuts or recent notifications. */
data class PersonCandidate(
    val id: String,               // stable key: "pkg|shortcutId" or "pkg|normName"
    val pkg: String,
    val shortcutId: String?,
    val name: String,
    val appLabel: String,
    val shortcutLastChangedMs: Long = 0L,
    val shortcutRank: Int = Int.MAX_VALUE,
    val isPinnedShortcut: Boolean = false,
)

/** One logged interaction (incoming message = 1.0, user tapping/opening chat = 2.0). */
data class PersonInteraction(
    val pkg: String,
    val shortcutId: String?,
    val normName: String,
    val timestampMs: Long,
    val weight: Double = 1.0,
)

/** One live unread messaging notification. */
data class UnreadPersonPing(
    val pkg: String,
    val shortcutId: String?,
    val normName: String,
    val postedAtMs: Long,
)

data class ScoredPerson(
    val candidate: PersonCandidate,
    val score: Double,
    val hasUnread: Boolean,
)

/**
 * Pure-math, deterministic, <1 ms Recommended People ranker:
 *   - Scores every candidate with 45-min burst + 7-day habit + 10-min unread ping + OS shortcut recency/rank prior.
 *   - Filters non-human bots/short-codes via [isLikelyRealPerson].
 *   - Deduplicates same normalized name across apps, keeping the highest-scoring active channel.
 */
fun rankRecommendedPeople(
    candidates: List<PersonCandidate>,
    interactions: List<PersonInteraction>,
    unreadPings: List<UnreadPersonPing>,
    nowMs: Long,
    limit: Int = 8,
): List<ScoredPerson> {
    if (candidates.isEmpty()) return emptyList()
    val ln2 = ln(2.0)

    // Pre-index interactions by (pkg, shortcutId) and by normName for fast O(1) lookup
    val byShortcut = HashMap<String, MutableList<PersonInteraction>>()
    val byName = HashMap<String, MutableList<PersonInteraction>>()
    for (ev in interactions) {
        if (ev.timestampMs > nowMs + 60_000L) continue
        if (!ev.shortcutId.isNullOrEmpty()) {
            byShortcut.getOrPut("${ev.pkg}|${ev.shortcutId}") { ArrayList() }.add(ev)
        }
        if (ev.normName.isNotEmpty()) {
            byName.getOrPut(ev.normName) { ArrayList() }.add(ev)
        }
    }

    val scored = ArrayList<ScoredPerson>(candidates.size)
    for (c in candidates) {
        if (!isLikelyRealPerson(c.name, c.pkg)) continue
        val norm = normalizePersonName(c.name)

        // Gather matching interactions (prefer exact shortcut match, plus cross-app name matches at 0.5x)
        val exactKey = if (!c.shortcutId.isNullOrEmpty()) "${c.pkg}|${c.shortcutId}" else null
        val exactEvents = exactKey?.let { byShortcut[it] }.orEmpty()
        val nameEvents = byName[norm].orEmpty()

        var burst = 0.0
        var habit = 0.0
        val seen = HashSet<Long>()
        for (ev in exactEvents) {
            seen.add(ev.timestampMs)
            val dt = (nowMs - ev.timestampMs).coerceAtLeast(0L).toDouble()
            burst += ev.weight * exp(-ln2 * dt / BURST_HALF_LIFE_MS)
            habit += ev.weight * exp(-ln2 * dt / HABIT_HALF_LIFE_MS)
        }
        for (ev in nameEvents) {
            if (ev.pkg == c.pkg && ev.timestampMs in seen) continue
            val dt = (nowMs - ev.timestampMs).coerceAtLeast(0L).toDouble()
            val factor = if (ev.pkg == c.pkg) 1.0 else 0.5
            burst += ev.weight * factor * exp(-ln2 * dt / BURST_HALF_LIFE_MS)
            habit += ev.weight * factor * exp(-ln2 * dt / HABIT_HALF_LIFE_MS)
        }

        // Unread notification live boost
        var unreadBoost = 0.0
        var hasUnread = false
        for (u in unreadPings) {
            val match = (u.pkg == c.pkg && !u.shortcutId.isNullOrEmpty() && u.shortcutId == c.shortcutId) ||
                (u.pkg == c.pkg && u.normName == norm)
            if (match) {
                hasUnread = true
                val age = (nowMs - u.postedAtMs).coerceAtLeast(0L).toDouble()
                unreadBoost = maxOf(unreadBoost, exp(-ln2 * age / UNREAD_HALF_LIFE_MS))
            }
        }

        // OS shortcut recency + per-app rank prior (gives accurate top people from minute 1!)
        val recencyPrior = if (c.shortcutLastChangedMs in 1L..nowMs) {
            val age = (nowMs - c.shortcutLastChangedMs).toDouble()
            exp(-ln2 * age / SHORTCUT_HALF_LIFE_MS)
        } else 0.0
        val rankPrior = when {
            c.shortcutRank in 0..10 -> 1.0 / (1.0 + c.shortcutRank * 0.45)
            else -> 0.15
        }
        val pinBonus = if (c.isPinnedShortcut) 0.45 else 0.0
        val sShortcut = (recencyPrior * 0.7 + rankPrior * 0.55 + pinBonus)

        val total = W_P_BURST * ln(1.0 + burst * 1.6) +
            W_P_HABIT * ln(1.0 + habit * 0.55) +
            W_P_UNREAD * unreadBoost +
            W_P_SHORTCUT * sShortcut

        if (total > 0.0) {
            scored.add(ScoredPerson(c, total, hasUnread))
        }
    }

    // Deduplicate same normalized person across apps, keeping the highest-scored channel
    val bestByName = LinkedHashMap<String, ScoredPerson>()
    for (sp in scored.sortedWith(compareBy({ -it.score }, { it.candidate.name.lowercase(Locale.US) }))) {
        val norm = normalizePersonName(sp.candidate.name)
        val existing = bestByName[norm]
        if (existing == null || sp.score > existing.score) {
            bestByName[norm] = sp
        }
    }
    return bestByName.values.take(limit)
}

/** Filters recommended people by [query]: prefix matches first, then word-start, then substring. */
fun filterPeople(people: List<RecommendedPerson>, query: String): List<RecommendedPerson> {
    val q = query.trim().lowercase(Locale.US)
    if (q.isEmpty()) return people
    val matches = people.filter {
        val n = it.name.lowercase(Locale.US)
        n.contains(q) || it.appLabel.lowercase(Locale.US).contains(q)
    }
    return matches.sortedWith(
        compareBy(
            { !it.name.lowercase(Locale.US).startsWith(q) },
            { !it.name.lowercase(Locale.US).split(' ').any { w -> w.startsWith(q) } },
            { it.name.lowercase(Locale.US) },
        ),
    )
}

/** UI-ready recommended person with rendered circular avatar, optional app badge, and direct 1-tap launch target. */
data class RecommendedPerson(
    val id: String,
    val name: String,
    val shortName: String,
    val pkg: String,
    val appLabel: String,
    val shortcutId: String?,
    val userHandle: UserHandle?,
    val avatar: ImageBitmap?,
    val appBadge: ImageBitmap?,
    val hasUnread: Boolean,
    val score: Double,
)

/** Android platform bridge for Recommended People (zero new permissions; uses ROLE_HOME LauncherApps + MediaListener). */
object RecommendedPeople {

    /** Called passively by MediaListener whenever a new notification arrives. */
    fun recordNotification(ctx: Context, sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == ctx.packageName || !sbn.isClearable) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val isMessaging = pkg in PEOPLE_MESSAGING_PACKAGES ||
            n.category == Notification.CATEGORY_MESSAGE ||
            n.category == Notification.CATEGORY_CALL ||
            !n.shortcutId.isNullOrEmpty()
        if (!isMessaging) return

        val extras = n.extras ?: return
        val rawName = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()
            ?.trim()
            .orEmpty()
        if (!isLikelyRealPerson(rawName, pkg)) return

        appendInteraction(
            ctx = ctx,
            pkg = pkg,
            shortcutId = n.shortcutId,
            normName = normalizePersonName(rawName),
            timestampMs = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
            weight = 1.0,
        )
    }

    /** Records an outbound chat tap (2x weight — initiating conversation is the strongest closeness signal). */
    fun recordOutboundTap(ctx: Context, person: RecommendedPerson) {
        appendInteraction(
            ctx = ctx,
            pkg = person.pkg,
            shortcutId = person.shortcutId,
            normName = normalizePersonName(person.name),
            timestampMs = System.currentTimeMillis(),
            weight = 2.0,
        )
    }

    private fun appendInteraction(
        ctx: Context,
        pkg: String,
        shortcutId: String?,
        normName: String,
        timestampMs: Long,
        weight: Double,
    ) {
        val prefs = ctx.getSharedPreferences(PEOPLE_PREFS, Context.MODE_PRIVATE)
        val safeShortcut = (shortcutId ?: "").replace("|", "").replace("\n", "")
        val safeName = normName.replace("|", "").replace("\n", "")
        val entry = "$pkg|$safeShortcut|$safeName|$timestampMs|${"%.1f".format(Locale.US, weight)}"
        val existing = prefs.getString(KEY_EVENTS, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        val trimmed = if (existing.size >= MAX_PEOPLE_EVENTS) existing.takeLast(MAX_PEOPLE_EVENTS - 1) else existing
        prefs.edit().putString(KEY_EVENTS, (trimmed + entry).joinToString("\n")).apply()
    }

    private fun readInteractions(ctx: Context): List<PersonInteraction> {
        val raw = ctx.getSharedPreferences(PEOPLE_PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@mapNotNull null
            val ts = parts[3].toLongOrNull() ?: return@mapNotNull null
            val w = parts.getOrNull(4)?.toDoubleOrNull() ?: 1.0
            PersonInteraction(
                pkg = parts[0],
                shortcutId = parts[1].ifEmpty { null },
                normName = parts[2],
                timestampMs = ts,
                weight = w,
            )
        }
    }

    private fun readUnreadPings(ctx: Context): List<UnreadPersonPing> = runCatching {
        val live = MediaListener.Defaults.live?.activeNotifications.orEmpty()
        live.mapNotNull { sbn ->
            val n = sbn.notification ?: return@mapNotNull null
            if (!sbn.isClearable || sbn.packageName == ctx.packageName) return@mapNotNull null
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@mapNotNull null
            val extras = n.extras ?: return@mapNotNull null
            val rawName = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
            if (rawName.isEmpty()) return@mapNotNull null
            UnreadPersonPing(
                pkg = sbn.packageName,
                shortcutId = n.shortcutId,
                normName = normalizePersonName(rawName),
                postedAtMs = sbn.postTime,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Loads and ranks the top recommended people from Android's Conversation Shortcuts + MediaListener interaction log.
     * Runs on Dispatchers.IO (<15 ms even with 100+ shortcuts).
     */
    fun load(ctx: Context, limit: Int = 12): List<RecommendedPerson> {
        val la = ctx.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val pm = ctx.packageManager
        val now = System.currentTimeMillis()

        val shortcutMap = HashMap<String, Pair<ShortcutInfo, UserHandle>>()
        val candidates = ArrayList<PersonCandidate>()
        val appLabelCache = HashMap<String, String>()
        fun appLabelOf(pkg: String): String = appLabelCache.getOrPut(pkg) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }

        // 1. Read conversation shortcuts from all user profiles (ROLE_HOME grants hasShortcutHostPermission!)
        if (runCatching { la.hasShortcutHostPermission() }.getOrDefault(false)) {
            val queryFlags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            val profiles = runCatching { la.profiles }.getOrDefault(listOf(Process.myUserHandle()))
            for (profile in profiles) {
                val query = LauncherApps.ShortcutQuery().setQueryFlags(queryFlags)
                val list = runCatching { la.getShortcuts(query, profile) }.getOrNull().orEmpty()
                for (sc in list) {
                    val pkg = sc.`package` ?: continue
                    if (isInternal(pkg)) continue
                    val isConversation = sc.locusId != null ||
                        sc.isCached ||
                        pkg in PEOPLE_MESSAGING_PACKAGES ||
                        sc.categories?.any { it.contains("SHARE", ignoreCase = true) || it.contains("CONVERSATION", ignoreCase = true) } == true
                    if (!isConversation) continue

                    val label = (sc.shortLabel ?: sc.longLabel)?.toString()?.trim().orEmpty()
                    if (!isLikelyRealPerson(label, pkg)) continue

                    val id = "$pkg|${sc.id}"
                    shortcutMap[id] = sc to profile
                    candidates.add(
                        PersonCandidate(
                            id = id,
                            pkg = pkg,
                            shortcutId = sc.id,
                            name = label,
                            appLabel = appLabelOf(pkg),
                            shortcutLastChangedMs = sc.lastChangedTimestamp,
                            shortcutRank = sc.rank,
                            isPinnedShortcut = sc.isPinned,
                        ),
                    )
                }
            }
        }

        // 2. Also include anyone with a live unread messaging notification even if their app hasn't published a shortcut
        val unread = readUnreadPings(ctx)
        val seenKeys = candidates.map { "${it.pkg}|${normalizePersonName(it.name)}" }.toHashSet()
        for (u in unread) {
            if (u.pkg !in PEOPLE_MESSAGING_PACKAGES) continue
            val display = u.normName.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase(Locale.US) } }
            val k = "${u.pkg}|${u.normName}"
            if (k !in seenKeys && isLikelyRealPerson(display, u.pkg)) {
                seenKeys.add(k)
                candidates.add(
                    PersonCandidate(
                        id = k,
                        pkg = u.pkg,
                        shortcutId = u.shortcutId,
                        name = display,
                        appLabel = appLabelOf(u.pkg),
                        shortcutLastChangedMs = u.postedAtMs,
                    ),
                )
            }
        }

        val interactions = readInteractions(ctx)
        val ranked = rankRecommendedPeople(candidates, interactions, unread, now, limit = limit)
        if (ranked.isEmpty()) return emptyList()

        val dpi = ctx.resources.displayMetrics.densityDpi
        val badgeCache = HashMap<String, ImageBitmap?>()
        return ranked.map { sp ->
            val c = sp.candidate
            val pair = shortcutMap[c.id]
            val avatar = pair?.let { (sc, _) ->
                runCatching { la.getShortcutIconDrawable(sc, dpi)?.let { renderCircularBitmap(it, 128) } }.getOrNull()
            }
            val badge = badgeCache.getOrPut(c.pkg) {
                runCatching { renderCircularBitmap(pm.getApplicationIcon(c.pkg), 48) }.getOrNull()
            }
            RecommendedPerson(
                id = c.id,
                name = c.name,
                shortName = shortPersonName(c.name),
                pkg = c.pkg,
                appLabel = c.appLabel,
                shortcutId = c.shortcutId,
                userHandle = pair?.second,
                avatar = avatar,
                appBadge = badge,
                hasUnread = sp.hasUnread,
                score = sp.score,
            )
        }
    }

    /** Opens the exact conversation thread for [person] in 1 tap (falls back to active notification or app launch). */
    fun open(ctx: Context, person: RecommendedPerson) {
        recordOutboundTap(ctx, person)
        val la = ctx.getSystemService(LauncherApps::class.java)
        if (la != null && !person.shortcutId.isNullOrEmpty() && person.userHandle != null) {
            val started = runCatching {
                la.startShortcut(person.pkg, person.shortcutId, null, null, person.userHandle)
            }.isSuccess
            if (started) return
        }
        // Fallback 1: live unread notification PendingIntent for this exact conversation
        val norm = normalizePersonName(person.name)
        val liveSbn = runCatching {
            MediaListener.Defaults.live?.activeNotifications?.firstOrNull { sbn ->
                sbn.packageName == person.pkg && (
                    (!person.shortcutId.isNullOrEmpty() && sbn.notification.shortcutId == person.shortcutId) ||
                        normalizePersonName(
                            (sbn.notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                                ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty(),
                        ) == norm
                    )
            }
        }.getOrNull()
        if (liveSbn?.notification?.contentIntent != null && runCatching { liveSbn.notification.contentIntent.send() }.isSuccess) {
            return
        }
        // Fallback 2: launch the messaging app itself
        ctx.packageManager.getLaunchIntentForPackage(person.pkg)?.let {
            runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun renderCircularBitmap(drawable: Drawable, px: Int): ImageBitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(canvas)
        val outside = Path().apply {
            addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW)
            fillType = Path.FillType.INVERSE_WINDING
        }
        canvas.drawPath(outside, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        return out.asImageBitmap()
    }
}
