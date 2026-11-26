package org.honeycomb.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceDataTest {

    @Test fun ourPackagesStayOutOfTheGrid() {
        assertTrue(isInternal("org.honeycomb.other"))
        assertTrue(isInternal("org.honeycomb.launcher"))
        assertFalse(isInternal("com.google.android.apps.youtube.music"))
        assertFalse(isInternal("org.honeycombfish.app"))
    }
}
