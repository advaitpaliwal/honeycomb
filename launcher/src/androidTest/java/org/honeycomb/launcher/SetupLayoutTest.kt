package org.honeycomb.launcher

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.honeycomb.launcher.design.CapsuleButton
import org.honeycomb.launcher.design.ProvideScale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SetupLayoutTest {
    @get:Rule val rule = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test fun everyExplanationCanScrollClearOfItsAction() {
        val current = mutableStateOf(Step.Home)
        val size = mutableStateOf(1f)
        rule.setContent {
            val step = current.value
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(size.value)) {
                ProvideScale {
                    OnboardingPage(step.title, step.body) {
                        CapsuleButton(step.button, {}, Modifier.fillMaxWidth())
                        CapsuleButton("Skip", {}, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) for (step in Step.entries) {
            rule.runOnIdle { size.value = scale; current.value = step }
            rule.onNodeWithText(step.title).assertIsDisplayed()
            val button = rule.onNodeWithText(step.button).assertIsDisplayed()
            val body = rule.onNodeWithText(step.body)
            if (bottomPx(body) > topPx(button)) {
                rule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            }
            val bottom = bottomPx(body)
            val top = topPx(button)
            body.assertIsDisplayed()
            assertTrue("${step.name} at ${scale}x: explanation bottom $bottom px, action top $top px", bottom <= top)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun everyTitleSitsAboveItsExplanation() {
        val current = mutableStateOf(Step.Home)
        val size = mutableStateOf(1f)
        rule.setContent {
            val step = current.value
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(size.value)) {
                ProvideScale {
                    OnboardingPage(step.title, step.body) {
                        CapsuleButton(step.button, {}, Modifier.fillMaxWidth())
                        CapsuleButton("Skip", {}, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) for (step in Step.entries) {
            rule.runOnIdle { size.value = scale; current.value = step }
            val bottom = bottomPx(rule.onNodeWithText(step.title))
            val top = topPx(rule.onNodeWithText(step.body))
            assertTrue("${step.name} at ${scale}x: title bottom $bottom px, explanation top $top px", bottom <= top)
        }
    }

    // Pixels, not dp: the explanation is laid out under its own ProvideScale density, so its dp and the button's differ.
    private fun bottomPx(n: SemanticsNodeInteraction) = n.fetchSemanticsNode().let { it.positionInRoot.y + it.size.height }
    private fun topPx(n: SemanticsNodeInteraction) = n.fetchSemanticsNode().positionInRoot.y
}
