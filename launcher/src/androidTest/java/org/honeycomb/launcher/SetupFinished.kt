package org.honeycomb.launcher

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Marks first-run setup finished before the home activity starts. On a fresh emulator nothing is granted, so
 * Setup.firstRun would open SetupActivity over the home and the home would never resume. Setup's own order of
 * screens is covered by SetupFlowTest.
 */
class SetupFinished : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            Setup.finish(InstrumentationRegistry.getInstrumentation().targetContext)
            base.evaluate()
        }
    }
}
