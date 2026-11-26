package org.honeycomb.launcher

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Holds the faces at one moment (9:41:30 today, in the phone's own zone) for the whole test, then gives them the system
 * clock back. Split's lower colour rises with the seconds, so a check of the band under the bars depends on which second
 * the screenshot lands on; at a fixed second it always sees the same face. Apply it before the activity starts.
 */
class FixedClock(private val time: LocalTime = LocalTime.of(9, 41, 30)) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val zone = ZoneId.systemDefault()
            FaceClock.clock = Clock.fixed(LocalDate.now(zone).atTime(time).atZone(zone).toInstant(), zone)
            try { base.evaluate() } finally { FaceClock.clock = Clock.systemDefaultZone() }
        }
    }
}
