package ch.madtreasures.fluency.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import ch.madtreasures.fluency.ui.components.SwapChips
import ch.madtreasures.fluency.ui.components.rememberSwapHighlight
import ch.madtreasures.fluency.ui.settings.WordListActions
import ch.madtreasures.fluency.ui.settings.WordListScreen
import ch.madtreasures.fluency.ui.settings.WordListUiState
import ch.madtreasures.fluency.ui.theme.FluencyTheme
import ch.madtreasures.fluency.words.WordGroup
import ch.madtreasures.fluency.words.WordGroups
import ch.madtreasures.fluency.words.WordSwap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The window of one language and the replace buttons, operated like on the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WordListScreenTest {
    @get:Rule val compose = createComposeRule()

    private val groups = WordGroups.parse("bench, benches (Sitzbank) | bank, banks (Geldinstitut) < Bank\nnews | messages < Nachrichten")

    /** shows the window; [onSave] gets the list as the store would save it */
    private fun show(onSave: (List<WordGroup>) -> Unit) = compose.setContent {
        FluencyTheme(dynamicColor = false) {
            WordListScreen(
                WordListUiState("en", "Englisch", groups, changed = true, hasDefaults = true),
                WordListActions(onChange = { change -> onSave(change(groups)) }),
            )
        }
    }

    @Test fun `a group is changed in the editor`() {
        var saved: List<WordGroup>? = null
        show { saved = it }
        compose.onAllNodesWithText("news", substring = true)[0].performClick()
        compose.onNodeWithText("Gruppe bearbeiten").assertExists()
        compose.onNodeWithText("Wort 2 (Formen mit Komma)").performTextClearance()
        compose.onNodeWithText("Wort 2 (Formen mit Komma)").performTextInput("message, messages")
        compose.onNodeWithText("Speichern").performClick()
        assertEquals("bench, benches (Sitzbank) | bank, banks (Geldinstitut) < Bank", WordGroups.formatLine(saved!![0]))
        assertEquals("news | message, messages < Nachrichten", WordGroups.formatLine(saved!![1]))
    }

    @Test fun `a new group needs two words`() {
        var saved: List<WordGroup>? = null
        show { saved = it }
        compose.onNodeWithText("Gruppe", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Neue Gruppe").assertExists()
        compose.onNodeWithText("Speichern").assertIsNotEnabled()
        compose.onNodeWithText("Wort 1 (Formen mit Komma)").performTextInput("time")
        compose.onNodeWithText("Wort 2 (Formen mit Komma)").performTextInput("weather")
        compose.onNodeWithText("Nur wenn im Original steht (optional)").performTextInput("temps, tiempo")
        compose.onNodeWithText("Speichern").performClick()
        assertEquals("time | weather < temps, tiempo", WordGroups.formatLine(saved!!.last()))
        assertEquals(3, saved!!.size)
    }

    @Test fun `a group is deleted`() {
        var saved: List<WordGroup>? = null
        show { saved = it }
        compose.onAllNodesWithText("bench", substring = true)[0].performClick()
        compose.onNodeWithText("Löschen").performClick()
        assertEquals(listOf("news"), saved!!.map { it.words[0].text })
    }

    @Test fun `touching a button marks the word, releasing replaces it, sliding off does nothing`() {
        val swaps = WordGroups.swaps("I sat on a bank.", "Ich sass auf einer Bank.", groups)
        var replaced: WordSwap? = null
        var marked: IntRange? = null
        compose.setContent {
            FluencyTheme(dynamicColor = false) {
                val h = rememberSwapHighlight()
                marked = h.preview
                SwapChips(swaps, h, onReplace = { replaced = it })
            }
        }
        val chip = compose.onNodeWithContentDescription("„bank“ durch „bench“ ersetzen")
        chip.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(11..14, marked)
        assertNull(replaced)
        chip.performTouchInput { moveTo(center.copy(y = center.y + 2000f)); up() }
        compose.waitForIdle()
        assertNull(marked)
        assertNull(replaced)
        chip.performClick()
        compose.waitForIdle()
        assertEquals("bench", replaced!!.replacement)
        assertEquals("I sat on a bench.", WordGroups.apply("I sat on a bank.", replaced!!))
    }
}
