package org.honeycomb.launcher

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTest {
    @Test fun qwertyRowsHaveIosKeyCounts() {
        val rows = KeyboardLayout.rows(KeyboardPage.LETTERS)
        assertEquals(4, rows.size)
        assertEquals(10, rows[0].size) // qwertyuiop
        assertEquals(9, rows[1].size)  // asdfghjkl (indented)
        assertEquals(9, rows[2].size)  // shift + zxcvbnm + backspace
        assertEquals(3, rows[3].size)  // 123 + space + return
    }

    @Test fun actionLabelsAdaptToEditorInfo() {
        assertEquals("search", KeyboardLayout.actionLabel(EditorInfo.IME_ACTION_SEARCH))
        assertEquals("send", KeyboardLayout.actionLabel(EditorInfo.IME_ACTION_SEND))
        assertEquals("go", KeyboardLayout.actionLabel(EditorInfo.IME_ACTION_GO))
        assertEquals("done", KeyboardLayout.actionLabel(EditorInfo.IME_ACTION_DONE))
        assertEquals("return", KeyboardLayout.actionLabel(EditorInfo.IME_ACTION_NONE))
        assertEquals("return", KeyboardLayout.actionLabel(EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
        assertTrue(KeyboardLayout.isProminentAction(EditorInfo.IME_ACTION_SEARCH))
        assertFalse(KeyboardLayout.isProminentAction(EditorInfo.IME_ACTION_NONE))
    }

    @Test fun doubleSpacePeriodTriggersOnlyAfterWord() {
        assertTrue(KeyboardLayout.shouldDoubleSpacePeriod("a "))
        assertTrue(KeyboardLayout.shouldDoubleSpacePeriod("9 "))
        assertFalse(KeyboardLayout.shouldDoubleSpacePeriod(". "))
        assertFalse(KeyboardLayout.shouldDoubleSpacePeriod("  "))
        assertFalse(KeyboardLayout.shouldDoubleSpacePeriod(" "))
        assertFalse(KeyboardLayout.shouldDoubleSpacePeriod(null))
    }

    @Test fun shiftCyclesOnceCapsLockOff() {
        assertEquals(ShiftMode.ONCE, KeyboardLayout.nextShiftOnTap(ShiftMode.OFF, doubleTap = false))
        assertEquals(ShiftMode.LOCKED, KeyboardLayout.nextShiftOnTap(ShiftMode.ONCE, doubleTap = true))
        assertEquals(ShiftMode.OFF, KeyboardLayout.nextShiftOnTap(ShiftMode.LOCKED, doubleTap = false))
        assertEquals(ShiftMode.OFF, KeyboardLayout.nextShiftOnTap(ShiftMode.ONCE, doubleTap = false))
    }
}
