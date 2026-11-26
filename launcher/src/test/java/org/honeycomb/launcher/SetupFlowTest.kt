package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupFlowTest {
    @Test fun onlyGesturalModeCompletesNavigationSetup() {
        assertTrue(Setup.usesGestureNavigation(2))
        for (mode in listOf(-1, 0, 1, 3)) assertFalse(Setup.usesGestureNavigation(mode))
    }

    @Test fun nothingGrantedStartsWithHome() {
        assertEquals(Step.Home, SetupFlow.next(emptySet(), emptySet()))
    }

    @Test fun grantedStepsAreSkipped() {
        assertEquals(Step.Quiet, SetupFlow.next(setOf(Step.Home, Step.Navigation, Step.Notifications), emptySet()))
        assertEquals(Step.Brightness, SetupFlow.next(Step.entries.toSet() - Step.Brightness, emptySet()))
    }

    @Test fun skippedOrAnsweredStepsAreNotShownAgain() {
        assertEquals(Step.Navigation, SetupFlow.next(emptySet(), setOf(Step.Home)))
        assertEquals(Step.Usage, SetupFlow.next(setOf(Step.Home, Step.Navigation, Step.Notifications), setOf(Step.Quiet)))
    }

    @Test fun allGrantedOrPassedEndsTheFlow() {
        assertNull(SetupFlow.next(Step.entries.toSet(), emptySet()))
        assertNull(SetupFlow.next(setOf(Step.Home), Step.entries.toSet() - Step.Home))
    }

    @Test fun firstRunShowsOnlyWhenUnfinishedAndSomethingIsMissing() {
        assertTrue(SetupFlow.shows(done = false, granted = setOf(Step.Home)))
        assertFalse(SetupFlow.shows(done = true, granted = emptySet()))
        assertFalse(SetupFlow.shows(done = false, granted = Step.entries.toSet()))
    }

    @Test fun theQuietStepSaysQuietWillBeOnWhatRingsAndWhereToTurnItOff() {
        val body = Step.Quiet.body
        assertTrue(body.contains("Quiet will be on"))
        assertTrue(body.contains("contacts") && body.contains("alarms"))
        assertTrue(body.contains("Control Centre"))
    }

    @Test fun theQuietScreenSaysNotificationsAreOffOnlyWhenQuietIsOn() {
        assertTrue(Quiet.words(on = true, contactsCall = true).first.startsWith("Notifications are off"))
        assertTrue(Quiet.words(on = false, contactsCall = true).first.startsWith("Quiet is off"))
        assertEquals("Your contacts can still call you. Other calls show as missed.", Quiet.words(true, true).second)
        assertEquals("Every call shows as missed.", Quiet.words(true, false).second)
        assertTrue(Quiet.words(false, true).second.startsWith("When Quiet is on"))
    }

    @Test fun restrictedSettingsAreExplainedOnlyToAnAppInstalledFromAFile() {
        assertTrue(Restricted.sideloaded(android.content.pm.PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)) // Files
        assertTrue(Restricted.sideloaded(android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)) // a browser
        assertFalse(Restricted.sideloaded(android.content.pm.PackageInstaller.PACKAGE_SOURCE_STORE))
        assertFalse(Restricted.sideloaded(android.content.pm.PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED)) // adb
        assertTrue(Restricted.BODY.contains("\u22EE \u203A Allow restricted settings"))
    }
}
