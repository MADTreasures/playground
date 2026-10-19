package ch.madtreasures.fluency.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import ch.madtreasures.fluency.ui.components.SwapChips
import ch.madtreasures.fluency.ui.components.rememberSwapHighlight
import ch.madtreasures.fluency.ui.settings.SourceLanguagesActions
import ch.madtreasures.fluency.ui.settings.SourceLanguagesScreen
import ch.madtreasures.fluency.ui.settings.SourceLanguagesUiState
import ch.madtreasures.fluency.ui.settings.WordListActions
import ch.madtreasures.fluency.ui.settings.WordListEntry
import ch.madtreasures.fluency.ui.settings.WordListScreen
import ch.madtreasures.fluency.ui.settings.WordListUiState
import ch.madtreasures.fluency.ui.settings.WordListsScreen
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

/** The word list pages and the replace buttons, operated like on the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WordListScreenTest {
    @get:Rule val compose = createComposeRule()

    /** the English list: two groups for German, one for French */
    private val all = WordGroups.parse(
        "[de]\nbench, benches (Sitzbank) | bank, banks (Geldinstitut) < Bank\nmessages | news < Nachrichten\n[fr]\ntime | weather < temps",
    )
    private val german = all.filter { it.language == "de" }

    /** shows the page German → English; [onSave] gets the whole list as the store would save it */
    private fun show(onSave: (List<WordGroup>) -> Unit) = compose.setContent {
        FluencyTheme(dynamicColor = false) {
            WordListScreen(
                WordListUiState("en", "Englisch", "de", "Deutsch", german),
                WordListActions(onChange = { change -> onSave(change(all)) }),
            )
        }
    }

    @Test fun `a group is changed in the editor`() {
        var saved: List<WordGroup>? = null
        show { saved = it }
        compose.onNodeWithText("Nachrichten").performClick()
        compose.onNodeWithText("Doppeldeutung bearbeiten").assertExists()
        compose.onNodeWithText("Übersetzung 1 (Formen mit Komma)").performTextClearance()
        compose.onNodeWithText("Übersetzung 1 (Formen mit Komma)").performTextInput("message, messages")
        compose.onNodeWithText("Speichern").performClick()
        assertEquals(
            listOf(
                "bench, benches (Sitzbank) | bank, banks (Geldinstitut) < Bank",
                "message, messages | news < Nachrichten",
                "time | weather < temps",
            ),
            saved!!.map(WordGroups::formatLine),
        )
        assertEquals(listOf("de", "de", "fr"), saved!!.map { it.language })
    }

    @Test fun `a new group needs two translations and gets the language of the page`() {
        var saved: List<WordGroup>? = null
        show { saved = it }
        compose.onNodeWithText("Doppeldeutung", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Neue Doppeldeutung").assertExists()
        compose.onNodeWithText("Speichern").assertIsNotEnabled()
        compose.onNodeWithText("Wort auf Deutsch (im Original)").performTextInput("Zeit")
        compose.onNodeWithText("Übersetzung 1 (Formen mit Komma)").performTextInput("time")
        compose.onNodeWithText("Übersetzung 2 (Formen mit Komma)").performTextInput("tense")
        compose.onNodeWithText("Speichern").performClick()
        assertEquals("time | tense < Zeit", WordGroups.formatLine(saved!!.last()))
        assertEquals("de", saved!!.last().language)
        assertEquals(4, saved!!.size)
    }

    @Test fun `a group is deleted, the other languages stay`() {
        var saved: List<WordGroup>? = null
        show { saved = it }
        compose.onNodeWithText("Bank").performClick()
        compose.onNodeWithText("Löschen").performClick()
        assertEquals(listOf("messages", "time"), saved!!.map { it.words[0].text })
    }

    @Test fun `a star makes a language a favourite, a row opens its page`() {
        var favorite: String? = null
        var opened: String? = null
        compose.setContent {
            FluencyTheme(dynamicColor = false) {
                WordListsScreen(
                    listOf(WordListEntry("de", "Deutsch", 73), WordListEntry("en", "Englisch", 91), WordListEntry("nl", "Niederländisch", 0)),
                    favorites = setOf("nl"),
                    onOpen = { opened = it }, onFavorite = { favorite = it }, onBack = {},
                )
            }
        }
        compose.onNodeWithText("Favoriten").assertExists()
        compose.onNodeWithContentDescription("Englisch als Favorit").performClick()
        assertEquals("en", favorite)
        compose.onNodeWithContentDescription("Niederländisch: Favorit entfernen").performClick()
        assertEquals("nl", favorite)
        compose.onNodeWithText("Englisch").performClick()
        assertEquals("en", opened)
    }

    @Test fun `the languages of the original open their pages`() {
        val opened = mutableListOf<String?>()
        compose.setContent {
            FluencyTheme(dynamicColor = false) {
                SourceLanguagesScreen(
                    SourceLanguagesUiState(
                        "en", "Englisch",
                        listOf(WordListEntry(null, "Alle Sprachen", 0), WordListEntry("de", "Deutsch", 45), WordListEntry("fr", "Französisch", 14)),
                    ),
                    SourceLanguagesActions(onOpen = { opened += it }),
                )
            }
        }
        compose.onNodeWithText("Deutsch").performClick()
        compose.onNodeWithText("Alle Sprachen").performClick()
        assertEquals(listOf("de", null), opened)
    }

    @Test fun `touching a button marks the word, releasing replaces it, sliding off does nothing`() {
        val swaps = WordGroups.swaps("I sat on a bank.", "Ich sass auf einer Bank.", german)
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
