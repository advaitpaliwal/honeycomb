package org.honeycomb.launcher

import org.honeycomb.launcher.design.DesignLanguageTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Smart Stack is migrated (docs/design-language.md §9): DesignLanguageTest's own scan finds nothing in it. */
class SmartStackDesignTest {
    @Test fun smartStackSpeaksOnlyTheLanguage() {
        val scan = DesignLanguageTest::class.java.getDeclaredMethod("violations").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val v = scan.invoke(DesignLanguageTest()) as Map<String, Map<String, Int>>
        assertEquals(emptyMap<String, Int>(), v["org/honeycomb/launcher/SmartStack.kt"].orEmpty())
    }
}
