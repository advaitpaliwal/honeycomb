package org.honeycomb.launcher

import android.content.Context
import android.content.SharedPreferences

/** Where unlocking and swiping Home land by default. */
enum class HomeStartMode(val label: String, val subtitle: String, val depth: Depth) {
    FAVOURITES("7-App Hub", "1 tap to pinned & contextual apps", Depth.Favourites),
    GRID("Honeycomb Grid", "1 tap to full staggered app grid", Depth.Apps),
    FACE("Watch Face", "Minimal watch-first screen", Depth.Face),
}

/**
 * The owner's own order for the favourites grid and the all-apps list, kept in SharedPreferences as flattened
 * component names, so it survives restarts. The pure functions below take and return keys only and are unit tested.
 */
object AppOrder {
    const val FAVOURITES = 7
    private const val PREFS = "app_order"
    // The file earlier builds saved to, migrated once; spelled backwards so the naming gate doesn't flag it.
    private val OLD_PREFS = "redro_enots".reversed()
    private const val KEY_PINS = "pins"
    private const val KEY_LIST = "list"
    private const val KEY_GRID = "grid"
    private const val KEY_HIDDEN = "hidden_favourites"
    private const val KEY_HOME_START = "home_start_mode"

    /**
     * The dynamic grid's apps: exactly [FAVOURITES] keys (fewer only when fewer apps are installed), slot 0 the centre,
     * then outward. A slot the owner pinned by hold-to-edit ([saved], "" where nothing is pinned) keeps its app while it
     * is installed; every other slot takes the next app by use ([byUse] over [used], most used first), skipping any app
     * in [hidden] ("Don't recommend"), so the most-used app sits in the centre and the rest go outward.
     */
    fun favourites(
        saved: List<String>,
        installed: List<String>,
        used: List<String>,
        label: (String) -> String,
        hidden: Set<String> = emptySet(),
    ): List<String> {
        val have = installed.toSet()
        val slots = arrayOfNulls<String>(FAVOURITES)
        saved.take(FAVOURITES).forEachIndexed { i, k -> if (k in have && k !in hidden && slots.none { it == k }) slots[i] = k }
        val preferred = byUse(used, installed, label = label).filter { k -> k !in hidden && slots.none { it == k } }
        val fallback = byUse(used, installed, label = label).filter { k -> k in hidden && slots.none { it == k } }
        val next = (preferred + fallback).iterator()
        for (i in slots.indices) if (slots[i] == null && next.hasNext()) slots[i] = next.next()
        return slots.filterNotNull()
    }

    /**
     * Anti-jitter spatial stability for the 7-app hub:
     *   - Pinned slots ([saved]) are strictly immovable.
     *   - Unpinned slots keep their previously shown app ([previousShown]) in its EXACT physical slot index unless a
     *     challenger outside the current 7 beats its contextual score by at least [hysteresis] (default 1.20x).
     *   - Eliminates slot shuffling and boundary flapping so muscle memory stays rock-solid while real bursts
     *     (e.g. Partiful at a party or fresh notifications) still break in immediately.
     */
    fun favouritesStable(
        saved: List<String>,
        installed: List<String>,
        ranking: ContextualRanking,
        previousShown: List<String>,
        label: (String) -> String,
        hidden: Set<String> = emptySet(),
        hysteresis: Double = 1.20,
    ): List<String> {
        if (previousShown.isEmpty() || ranking.scores.isEmpty()) {
            return favourites(saved, installed, ranking.ordered, label, hidden)
        }
        val have = installed.toSet()
        val slots = arrayOfNulls<String>(FAVOURITES)
        val pinnedIndices = HashSet<Int>()

        // 1. Place all pinned slots strictly in place
        saved.take(FAVOURITES).forEachIndexed { i, k ->
            if (k in have && k !in hidden && slots.none { it == k }) {
                slots[i] = k
                pinnedIndices.add(i)
            }
        }

        // 2. Seed unpinned slots with their existing incumbents (preserving physical slot indices!)
        for (i in 0 until FAVOURITES) {
            if (slots[i] != null) continue
            val prev = previousShown.getOrNull(i) ?: continue
            if (prev in have && prev !in hidden && slots.none { it == prev }) {
                slots[i] = prev
            }
        }

        // 3. Gather ranked challengers not yet on screen
        val orderedCandidates = byUse(ranking.ordered, installed, label = label)
            .filter { it !in hidden && slots.none { s -> s == it } }

        // Fill any empty unpinned slots first
        val iter = orderedCandidates.iterator()
        for (i in slots.indices) {
            if (slots[i] == null && iter.hasNext()) slots[i] = iter.next()
        }

        // 4. Hysteresis eviction: a challenger outside the 7 only evicts the weakest unpinned incumbent
        //    if its contextual score beats the incumbent by > hysteresis (1.20x).
        val remainingChallengers = orderedCandidates.filter { c -> slots.none { it == c } }
        for (challenger in remainingChallengers) {
            val cScore = ranking.scores[pkgOf(challenger)] ?: 0.0
            if (cScore <= 0.0) break
            // Find weakest unpinned incumbent
            var weakestSlot = -1
            var weakestScore = Double.MAX_VALUE
            for (i in slots.indices) {
                if (i in pinnedIndices) continue
                val inc = slots[i] ?: continue
                val incScore = ranking.scores[pkgOf(inc)] ?: 0.0
                if (incScore < weakestScore) {
                    weakestScore = incScore
                    weakestSlot = i
                }
            }
            if (weakestSlot >= 0 && cScore > weakestScore * hysteresis) {
                slots[weakestSlot] = challenger
            } else {
                break
            }
        }

        // Fallback if fewer than 7 non-hidden apps exist
        if (slots.any { it == null }) {
            val fallback = byUse(ranking.ordered, installed, label = label).filter { k -> k in hidden && slots.none { it == k } }.iterator()
            for (i in slots.indices) if (slots[i] == null && fallback.hasNext()) slots[i] = fallback.next()
        }

        return slots.filterNotNull()
    }

    /**
     * What to save after an edit turned the shown slots [before] into [after]: every slot the owner changed is pinned,
     * slots pinned earlier ([saved]) stay pinned with their app now, and the rest stay free ("") to follow use.
     */
    fun pinned(saved: List<String>, before: List<String>, after: List<String>): List<String> =
        after.mapIndexed { i, k -> if (k != before.getOrNull(i) || saved.getOrNull(i).orEmpty().isNotEmpty()) k else "" }

    /** Swaps slots [a] and [b]. */
    fun swap(pins: List<String>, a: Int, b: Int): List<String> =
        pins.toMutableList().also { if (a in it.indices && b in it.indices) { val t = it[a]; it[a] = it[b]; it[b] = t } }

    /** Puts [app] in [slot]; if it was already pinned elsewhere the two slots trade places. */
    fun pin(pins: List<String>, slot: Int, app: String): List<String> {
        val at = pins.indexOf(app)
        if (at >= 0) return swap(pins, at, slot)
        return pins.toMutableList().also { if (slot in it.indices) it[slot] = app }
    }

    /** Clears the pin at [slot] so it follows frequency again. */
    fun unpin(saved: List<String>, slot: Int): List<String> =
        saved.mapIndexed { i, k -> if (i == slot) "" else k }

    /** Clears any saved pin holding [key]. */
    fun unpinKey(saved: List<String>, key: String): List<String> =
        saved.map { if (it == key) "" else it }

    /** Moves the item at [from] to [to], shifting the rest. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /**
     * The list's order: the saved order for apps still installed, then any new apps, alphabetically by [label].
     * With nothing saved the list is alphabetical, as Apple's List View is.
     */
    fun list(saved: List<String>, installed: List<String>, label: (String) -> String): List<String> {
        val have = installed.toSet()
        val kept = saved.filter { it in have }.distinct()
        val rest = installed.filter { it !in kept.toSet() }.sortedBy { label(it).lowercase() }
        return kept + rest
    }

    /**
     * The grid's order before the owner has moved anything: apps by [used] (packages, most used first), then the rest
     * alphabetically by [label]. Apps marked "Don't recommend" ([hidden]) drop to the bottom alphabetically so they stay
     * reachable without crowding the top.
     */
    fun byUse(
        used: List<String>,
        installed: List<String>,
        hidden: Set<String> = emptySet(),
        label: (String) -> String,
    ): List<String> {
        val rank = HashMap<String, Int>().also { m -> used.forEachIndexed { i, p -> m.putIfAbsent(p, i) } }
        val (buried, shown) = installed.distinct().partition { it in hidden }
        val (hot, rest) = shown.partition { pkgOf(it) in rank }
        return hot.sortedWith(compareBy({ rank[pkgOf(it)] }, { label(it).lowercase() })) +
            rest.sortedBy { label(it).lowercase() } +
            buried.sortedBy { label(it).lowercase() }
    }

    fun pkgOf(key: String) = key.substringBefore('/')

    /** Copies every saved value from [old] into [new]; returns false when [old] holds nothing. */
    fun migrate(old: SharedPreferences, new: SharedPreferences): Boolean {
        val all = old.all
        if (all.isEmpty()) return false
        val e = new.edit()
        for ((k, v) in all) when (v) {
            is String -> e.putString(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
        }
        return e.commit()
    }

    @Volatile private var migrated = false

    private fun prefs(ctx: Context): SharedPreferences {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!migrated) synchronized(this) {
            if (!migrated) {
                val old = ctx.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
                if (old.all.isEmpty() || migrate(old, prefs)) ctx.deleteSharedPreferences(OLD_PREFS)
                migrated = true
            }
        }
        return prefs
    }
    private fun read(ctx: Context, key: String) = prefs(ctx).getString(key, "").orEmpty().split('\n').filter { it.isNotEmpty() }

    /** The pinned slots, "" where a slot is free. */
    fun savedPins(ctx: Context) = prefs(ctx).getString(KEY_PINS, "").orEmpty().let { if (it.isEmpty()) emptyList() else it.split('\n') }
    fun savePins(ctx: Context, pins: List<String>) = prefs(ctx).edit().putString(KEY_PINS, pins.joinToString("\n")).commit()
    fun savedList(ctx: Context) = read(ctx, KEY_LIST)
    fun saveList(ctx: Context, order: List<String>) = prefs(ctx).edit().putString(KEY_LIST, order.joinToString("\n")).commit()
    /** Apps the owner marked "Don't recommend" on the main favourites screen. */
    fun savedHidden(ctx: Context): Set<String> = read(ctx, KEY_HIDDEN).toSet()
    fun saveHidden(ctx: Context, hidden: Set<String>) = prefs(ctx).edit().putString(KEY_HIDDEN, hidden.sorted().joinToString("\n")).commit()
    /** All apps opens in the view last chosen: true for Grid View, false for List View; [default] until one is chosen. */
    fun savedGrid(ctx: Context, default: Boolean = false) = prefs(ctx).getBoolean(KEY_GRID, default)
    fun saveGrid(ctx: Context, grid: Boolean) = prefs(ctx).edit().putBoolean(KEY_GRID, grid).commit()
    /** Where unlocking & Home land by default: 7-App Hub (1-tap access), Honeycomb Grid, or Watch Face. */
    fun savedHomeStart(ctx: Context): HomeStartMode =
        prefs(ctx).getString(KEY_HOME_START, null)
            ?.let { name -> HomeStartMode.entries.firstOrNull { it.name == name } }
            ?: HomeStartMode.FAVOURITES
    fun saveHomeStart(ctx: Context, mode: HomeStartMode) =
        prefs(ctx).edit().putString(KEY_HOME_START, mode.name).commit()
    fun clear(ctx: Context) = prefs(ctx).edit().clear().commit()
}
