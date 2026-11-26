package org.honeycomb.launcher

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOrderMigrationTest {
    /** An in-memory SharedPreferences, enough for the migration's reads and writes. */
    private class FakePrefs(val map: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
        override fun getAll(): Map<String, *> = map.toMap()
        override fun getString(k: String, d: String?) = map[k] as String? ?: d
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String, d: Set<String>?) = map[k] as Set<String>? ?: d
        override fun getInt(k: String, d: Int) = map[k] as Int? ?: d
        override fun getLong(k: String, d: Long) = map[k] as Long? ?: d
        override fun getFloat(k: String, d: Float) = map[k] as Float? ?: d
        override fun getBoolean(k: String, d: Boolean) = map[k] as Boolean? ?: d
        override fun contains(k: String) = k in map
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            override fun putString(k: String, v: String?) = apply { pending[k] = v }
            override fun putStringSet(k: String, v: Set<String>?) = apply { pending[k] = v }
            override fun putInt(k: String, v: Int) = apply { pending[k] = v }
            override fun putLong(k: String, v: Long) = apply { pending[k] = v }
            override fun putFloat(k: String, v: Float) = apply { pending[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { pending[k] = v }
            override fun remove(k: String) = apply { pending.remove(k) }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean { map.putAll(pending); return true }
            override fun apply() { commit() }
        }
    }

    @Test fun migrationKeepsFavouritesListOrderAndGridChoice() {
        val old = FakePrefs(mutableMapOf("pins" to "a/.A\nb/.B", "list" to "b/.B\na/.A\nc/.C", "grid" to true))
        val new = FakePrefs()
        assertTrue(AppOrder.migrate(old, new))
        assertEquals(old.map, new.map)
    }

    @Test fun nothingToMigrateLeavesTheNewFileUntouched() {
        val new = FakePrefs(mutableMapOf("grid" to false))
        assertFalse(AppOrder.migrate(FakePrefs(), new))
        assertEquals(mapOf<String, Any?>("grid" to false), new.map)
    }
}
