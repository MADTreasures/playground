package ch.madtreasures.fluency.integration

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.AmbiguousWord
import ch.madtreasures.fluency.engine.llm.AmbiguousWords
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import ch.madtreasures.fluency.engine.llm.WordUse
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.settings.AppSettings
import ch.madtreasures.fluency.ui.settings.EXAMPLE_WORD
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The module "Mehrdeutige Wörter" on the real Hy-MT2 (x86 host, same JNI code as the app):
 * forcing the chosen meaning on 41 ambiguous sentences, the rules end to end, and sentences
 * without a listed word that must come out exactly as without the module.
 */
class AmbiguousWordsIntegrationTest {
    /** a sentence with an ambiguous word: [wanted] is the meaning chosen, [others] the other listed ones */
    data class Case(val src: String, val tgt: String, val word: String, val text: String, val wanted: String, val others: List<String>)

    companion object {
        /** the 41 sentences of the measurement in the README (26 German → English, 15 English → German) */
        val CASES = listOf(
            Case("de", "en", "Nachrichten", "Die Nachrichten kommen aber nicht an.", "messages", listOf("news")),
            Case("de", "en", "Nachrichten", "Hast du meine Nachrichten nicht gelesen?", "messages", listOf("news")),
            Case("de", "en", "Nachrichten", "Dann schauen wir die Nachrichten.", "news", listOf("messages")),
            Case("de", "en", "Nachrichten", "Aber wenn ich eine kurze Pause mache, werden die Nachrichten getrennt.", "messages", listOf("news")),
            Case("de", "en", "Nachrichten", "Aber das wenn ich eine kurze Pause mache, dass die Nachrichten getrennt werden.", "messages", listOf("news")),
            Case("de", "en", "Bank", "Wir haben uns auf eine Bank gesetzt.", "bench", listOf("bank")),
            Case("de", "en", "Bank", "Morgen gehe ich zur Bank.", "bank", listOf("bench")),
            Case("de", "en", "Schloss", "Das Schloss klemmt schon wieder.", "lock", listOf("castle")),
            Case("de", "en", "Schloss", "Das Schloss war wunderschön.", "castle", listOf("lock")),
            Case("de", "en", "Leiter", "Hast du eine Leiter?", "ladder", listOf("manager", "leader")),
            Case("de", "en", "Leiter", "Ich muss mit dem Leiter sprechen.", "manager", listOf("ladder")),
            Case("de", "en", "Decke", "Kannst du mir die Decke geben?", "blanket", listOf("ceiling")),
            Case("de", "en", "Decke", "Morgen streichen wir die Decke.", "ceiling", listOf("blanket")),
            Case("de", "en", "Zug", "Das war ein guter Zug.", "move", listOf("train")),
            Case("de", "en", "Gericht", "Das Gericht war ausgezeichnet.", "dish", listOf("court")),
            Case("de", "en", "Gericht", "Nächste Woche muss ich vor Gericht.", "court", listOf("dish")),
            Case("de", "en", "Birne", "Die Birne ist durchgebrannt.", "light bulb", listOf("pear")),
            Case("de", "en", "Steuer", "Er lässt mich nur ungern ans Steuer.", "steering wheel", listOf("tax")),
            Case("de", "en", "Schlange", "Ich musste zwanzig Minuten in der Schlange stehen.", "queue", listOf("snake")),
            Case("de", "en", "Hahn", "Der Hahn ist undicht.", "tap", listOf("rooster")),
            Case("de", "en", "Kater", "Heute habe ich einen Kater.", "hangover", listOf("tomcat")),
            Case("de", "en", "Mutter", "Mir fehlt noch eine Mutter für diese Schraube.", "nut", listOf("mother")),
            Case("de", "en", "Strom", "Der Strom war zwei Stunden lang weg.", "power", listOf("stream", "current")),
            Case("de", "en", "Absatz", "Dabei ist mein Absatz abgebrochen.", "heel", listOf("paragraph")),
            Case("de", "en", "Blatt", "Kannst du mir ein neues Blatt geben?", "sheet of paper", listOf("leaf")),
            Case("de", "en", "Note", "Leider war die Note schlecht.", "grade", listOf("note")),
            Case("en", "de", "bill", "Could you ask for the bill, please?", "Rechnung", listOf("Gesetzentwurf", "Schnabel")),
            Case("en", "de", "bat", "Suddenly a bat flew right past my head.", "Fledermaus", listOf("Schläger")),
            Case("en", "de", "bat", "He needs a new bat.", "Schläger", listOf("Fledermaus")),
            Case("en", "de", "fine", "I had to pay a fine.", "Bussgeld", listOf("gut")),
            Case("en", "de", "date", "Tomorrow we have our first date.", "Verabredung", listOf("Datum")),
            Case("en", "de", "match", "Do you have a match?", "Streichholz", listOf("Spiel")),
            Case("en", "de", "spring", "I can feel a spring in my back.", "Sprungfeder", listOf("Frühling", "Quelle")),
            Case("en", "de", "trunk", "Put the suitcases in the trunk.", "Kofferraum", listOf("Rüssel", "Stamm")),
            Case("en", "de", "crane", "We saw a crane standing in the water.", "Kranich", listOf("Kran")),
            Case("en", "de", "pupil", "Her best pupil won the competition.", "Schüler", listOf("Pupille")),
            Case("en", "de", "key", "One key is stuck.", "Taste", listOf("Schlüssel")),
            Case("en", "de", "tip", "Let's leave him a good tip.", "Trinkgeld", listOf("Tipp", "Spitze")),
            Case("en", "de", "change", "The cashier gave me the wrong change.", "Wechselgeld", listOf("Veränderung")),
            Case("en", "de", "charge", "I need to charge my phone.", "aufladen", listOf("Gebühr")),
            Case("en", "de", "glasses", "Where are my glasses?", "Brille", listOf("Gläser")),
        )

        /** a user's list: every word once, with hint words */
        val USER_LIST: List<AmbiguousWord> = listOf(EXAMPLE_WORD) + CASES.filter { it.word != "Nachrichten" }.distinctBy { it.word }.map { c ->
            val meanings = (listOf(c.wanted) + c.others).distinct()
            AmbiguousWord(c.word, c.src, c.tgt, meanings.map { AmbiguousWord.Meaning(it) })
        }.map { e ->
            when (e.word) {
                "Bank" -> e.copy(meanings = listOf(AmbiguousWord.Meaning("bench", listOf("Park", "sitz*", "gesetzt")), AmbiguousWord.Meaning("bank", listOf("Geld", "Konto", "Kredit"))))
                else -> e
            }
        }

        private val engine = TestEnv.engine(AppSettings(llmThreads = 4, ambiguousWords = AmbiguousWords.format(USER_LIST)))

        @BeforeClass @JvmStatic fun check() {
            TestEnv.assumeLlama()
            TestEnv.assumeFile(TestEnv.files.getValue(ModelCatalog.HY_MT2))
        }

        @AfterClass @JvmStatic fun close() = runBlocking { engine.unloadAll() }
    }

    private fun t(
        text: String,
        src: String = "de",
        tgt: String = "en",
        previous: String? = null,
        forced: Map<String, Int> = emptyMap(),
        words: List<AmbiguousWord>? = null,
        useWords: Boolean = true,
    ): TranslationEngine.Result = runBlocking {
        // an empty cache for every sentence: the numbers do not depend on the order
        engine.resetCaches()
        engine.translate(
            TranslationEngine.Request(
                text, Languages.require(src), Languages.require(tgt), Role.FINAL, ModelCatalog.HY_MT2,
                useWords = useWords, previousText = previous, forcedWords = forced, words = words,
            ),
        )
    }

    private fun describe(r: TranslationEngine.Result) = r.words.joinToString { "${it.from} → ${it.to} (${it.reasonLabel}, ${it.statusLabel})" }

    /**
     * The guarantee: no other listed meaning stays in the translation, and the chosen one does not
     * replace another word as well ("suitcases" → "Kofferraum").
     */
    private fun assertNoOtherMeaning(r: TranslationEngine.Result) {
        for (u in r.words) {
            if (u.status == WordUse.Status.SYNONYM || u.status == WordUse.Status.REPEATED) continue
            assertTrue("${u.to} fehlt in: ${r.text}", AmbiguousWords.contains(r.text, u.to))
            assertTrue("${u.to} zu oft in: ${r.text}", AmbiguousWords.count(u.to, r.text) <= u.occurrences)
        }
        for (u in r.words) for (alt in u.alternatives) assertFalse("„$alt“ steht noch in: ${r.text}", AmbiguousWords.contains(r.text, alt) && !AmbiguousWords.contains(alt, u.to))
    }

    @Test fun theChosenMeaningOn41AmbiguousSentences() {
        val counts = sortedMapOf<WordUse.Status, Int>()
        var plainRight = 0
        var plainWrong = 0
        var plainMs = 0L
        var withMs = 0L
        for (c in CASES) {
            val entry = AmbiguousWord(c.word, c.src, c.tgt, (listOf(c.wanted) + c.others).map { AmbiguousWord.Meaning(it) })
            val plain = t(c.text, c.src, c.tgt, useWords = false)
            val r = t(c.text, c.src, c.tgt, words = listOf(entry))
            val u = r.words.single()
            counts.merge(u.status, 1, Int::plus)
            plainMs += plain.wallMs
            withMs += r.wallMs
            if (AmbiguousWords.contains(plain.text, c.wanted)) plainRight++
            if (c.others.any { AmbiguousWords.contains(plain.text, it) }) plainWrong++
            println("%-26s %-55s ohne: %-50s mit: %s".format("${u.statusLabel}:", c.text, plain.text, r.text))
            assertNoOtherMeaning(r)
        }
        println("Ohne Modul: gewünschte Bedeutung in $plainRight/${CASES.size}, andere eingetragene Bedeutung in $plainWrong/${CASES.size}")
        println("Mit Modul: " + counts.entries.joinToString { "${it.key}: ${it.value}" })
        println("Zeit pro Satz (x86, 4 Threads, leerer Cache): ohne ${plainMs / CASES.size} ms, mit ${withMs / CASES.size} ms")
        // most are taken at once; corrections and synonyms are rare (measured: see README)
        assertTrue(counts.toString(), (counts[WordUse.Status.CONFIRMED] ?: 0) >= 30)
        assertEquals(CASES.size, counts.values.sum())
    }

    @Test fun theRulesEndToEnd() {
        // hint word in the sentence before: the field-test word after a chat sentence (the live screenshot)
        val before = t("Ich habe dir vorhin im Chat geschrieben.")
        println("${before.text}  [${describe(before)}]")
        assertTrue(before.words.isEmpty())
        val chat = t("Hast du meine Nachrichten schon gelesen?", previous = "Ich habe dir vorhin im Chat geschrieben.")
        println("${chat.text}  [${describe(chat)}]")
        assertEquals(WordUse.Reason.HINT_BEFORE, chat.words.single().reason)
        assertTrue(chat.text, chat.text.contains("message", ignoreCase = true))
        assertNoOtherMeaning(chat)
        // ... and after a television sentence
        val tv = t("Dann schauen wir die Nachrichten.", previous = "Um acht Uhr schalten wir immer den Fernseher ein.")
        println("${tv.text}  [${describe(tv)}]")
        assertEquals("news", tv.words.single().to)
        assertTrue(tv.text, tv.text.contains("news", ignoreCase = true))
        // the sentence of the field test, no hint: the standard
        val field = t("Aber das wenn ich eine kurze Pause mache, dass die Nachrichten getrennt werden.", previous = "Modell ist sehr schnell, also ich merke.")
        println("${field.text}  [${describe(field)}]")
        assertEquals(WordUse.Reason.STANDARD, field.words.single().reason)
        assertTrue(field.text, field.text.contains("message", ignoreCase = true))
        assertNoOtherMeaning(field)
        // tapped: the other meaning for this sentence
        val tapped = t("Hast du meine Nachrichten schon gelesen?", forced = mapOf(EXAMPLE_WORD.key to 1))
        println("${tapped.text}  [${describe(tapped)}]")
        assertTrue(tapped.text, tapped.text.contains("news", ignoreCase = true))
        assertNoOtherMeaning(tapped)
        // hint word in the sentence
        val park = t("Wir haben uns im Park auf eine Bank gesetzt.")
        val money = t("Ich brauche Geld von der Bank.")
        println("${park.text}  [${describe(park)}]\n${money.text}  [${describe(money)}]")
        assertTrue(park.text, park.text.contains("bench", ignoreCase = true))
        assertTrue(money.text, money.text.contains("bank", ignoreCase = true) && !money.text.contains("bench", ignoreCase = true))
        // the other direction: the listed word
        val back = t("Did you read my messages?", "en", "de")
        println("${back.text}  [${describe(back)}]")
        assertEquals(WordUse.Reason.REVERSE, back.words.single().reason)
        assertTrue(back.text, back.text.contains("Nachrichten"))
    }

    /** With 31 listed words: sentences without one get the same prompt, so the same translation. */
    @Test fun sentencesWithoutListedWordStayExactlyTheSame() {
        val sentences = listOf(
            Triple("de", "en", "Ich gehe jetzt einkaufen."),
            Triple("de", "en", "Ich war mit meinen Kindern im Zoo."),
            Triple("de", "en", "Kannst du mir eine Tablette geben?"),
            Triple("de", "en", "Könnten Sie mir bitte sagen, wie ich am schnellsten zum Hauptbahnhof komme?"),
            // compound words do not count as the listed word
            Triple("de", "en", "Gestern gab es einen Bankraub in der Stadt."),
            Triple("de", "en", "Ich habe dir eine Sprachnachricht geschickt."),
            Triple("en", "de", "Please bring your laptop."),
            Triple("en", "de", "The meeting has been moved to Thursday afternoon because two colleagues are still on vacation."),
        )
        for ((src, tgt, text) in sentences) {
            val with = t(text, src, tgt)
            val without = t(text, src, tgt, useWords = false)
            println("$text  ⇒  ${with.text}")
            assertTrue(with.words.isEmpty())
            assertEquals(without.text, with.text)
            assertEquals(without.promptTokens, with.promptTokens)
        }
    }
}
