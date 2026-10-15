package ch.madtreasures.fluency.engine

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.AccelMode
import ch.madtreasures.fluency.engine.llm.AmbiguousWord
import ch.madtreasures.fluency.engine.llm.AmbiguousWords
import ch.madtreasures.fluency.engine.llm.LlamaModel
import ch.madtreasures.fluency.engine.llm.LlmSession
import ch.madtreasures.fluency.engine.llm.Processor
import ch.madtreasures.fluency.engine.llm.PromptFormat
import ch.madtreasures.fluency.engine.llm.SessionLoader
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import ch.madtreasures.fluency.engine.llm.TranslationModelSource
import ch.madtreasures.fluency.engine.llm.WordCollection
import ch.madtreasures.fluency.engine.llm.WordUse
import ch.madtreasures.fluency.engine.llm.WordUse.Reason
import ch.madtreasures.fluency.engine.llm.WordUse.Status
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelInfo
import ch.madtreasures.fluency.models.PromptStyle
import ch.madtreasures.fluency.settings.AppSettings
import ch.madtreasures.fluency.ui.settings.entryProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

private val de = Languages.require("de")
private val deCh = Languages.require("de-CH")
private val en = Languages.require("en")
private val fr = Languages.require("fr")
private val it = Languages.require("it")
private val es = Languages.require("es")
private val zh = Languages.require("zh")

private fun entry(language: String, word: String) = WordCollection.entries.first { it.language == language && it.word == word }

/** from the collection: no standard, the model decides without a hint */
private val BANK = entry("de", "Bank")
private val NACHRICHTEN = entry("de", "Nachrichten")
private val TRUNK = entry("en", "trunk")

/** a user's own entry with a standard (as in 1.5.0) */
private val OWN = AmbiguousWord(
    "Nachrichten", "de",
    listOf(
        AmbiguousWord.Meaning("Mitteilungen", listOf("Chat", "Handy"), mapOf("en" to listOf("messages"), "fr" to listOf("messages"))),
        AmbiguousWord.Meaning("Neuigkeiten", listOf("Fernseh*", "Radio"), mapOf("en" to listOf("news"), "fr" to listOf("informations", "actualités"))),
    ),
    forms = listOf("Nachricht"),
    standard = true,
)

class WordFormsTest {
    private fun yes(word: String, language: String, vararg texts: String) = texts.forEach { assertTrue("$word in „$it“", AmbiguousWords.occurs(word, it, language)) }
    private fun no(word: String, language: String, vararg texts: String) = texts.forEach { assertFalse("$word in „$it“", AmbiguousWords.occurs(word, it, language)) }

    @Test fun german() {
        yes("Bank", "de", "auf der Bank", "zwei Bänke", "die Banken", "BANK")
        no("Bank", "de", "der Bankraub", "ein Banker", "Datenbank", "Sandbank")
        yes("Nachrichten", "de", "Die Nachrichten", "„Nachrichten“")
        no("Nachrichten", "de", "Sprachnachrichten", "Nachrichtenagentur")
        yes("Schüler", "de", "die beste Schülerin", "die Schülerinnen")
        yes("Schloss", "de", "das Schloß", "des Schlosses")
        // nouns only with a capital letter: not the verbs
        yes("Steuer", "de", "Wir zahlen Steuern.", "am Steuer")
        no("Steuer", "de", "Wer will das Boot steuern?")
        no("Lager", "de", "Wir lagern die Ware.")
        no("Pass", "de", "Die Schuhe passen.")
        yes("Pass", "de", "die Pässe", "des Passes")
        // the Swiss speech recognition writes standard German: de-CH is German
        yes("Bank", "de-CH", "auf der Bank")
    }

    @Test fun english() {
        yes("key", "en", "my keys", "the key's colour")
        yes("battery", "en", "two batteries")
        yes("knife", "en", "the knives")
        yes("date", "en", "dated", "dating", "two dates")
        yes("bench", "en", "the benches")
        yes("light bulb", "en", "The light bulbs are broken.")
        no("news", "en", "the newspaper", "a new phone")
    }

    @Test fun french() {
        yes("banc", "fr", "les bancs")
        yes("journal", "fr", "les journaux")
        yes("avocat", "fr", "l'avocat", "les avocats", "une avocate")
        yes("pomme de terre", "fr", "des pommes de terre")
        no("banc", "fr", "un compte bancaire")
    }

    @Test fun italianKeepsTheGender() {
        yes("banca", "it", "la banca", "le banche")
        yes("banco", "it", "i banchi")
        yes("panchina", "it", "due panchine")
        yes("foglio", "it", "i fogli")
        no("foglio", "it", "le foglie", "la foglia")
        yes("foglia", "it", "le foglie")
        no("foglia", "it", "il foglio", "i fogli")
        yes("città", "it", "la città")
        yes("pesca", "it", "due pesche")
        no("pesca", "it", "un pesce")
    }

    @Test fun spanishAndPortuguese() {
        yes("luz", "es", "las luces")
        yes("banco", "es", "los bancos")
        yes("año", "es", "este año")
        no("año", "es", "el ano")
        yes("avião", "pt", "os aviões")
        yes("papel", "pt", "os papéis")
        yes("mão", "pt", "as mãos")
    }

    @Test fun chineseWithoutSpaces() {
        yes("消息", "zh", "我收到了你的消息")
    }

    @Test fun hintWords() {
        assertTrue(AmbiguousWords.hintOccurs("Chat", "im Chat", "de"))
        assertTrue(AmbiguousWords.hintOccurs("Chat", "die Chats", "de"))
        assertFalse(AmbiguousWords.hintOccurs("App", "guten Appetit", "de"))
        assertTrue(AmbiguousWords.hintOccurs("Fernseh*", "der Fernseher", "de"))
        assertFalse(AmbiguousWords.hintOccurs("Fernseh*", "Kabelfernsehen", "de"))
        assertFalse(AmbiguousWords.hintOccurs("*", "irgendwas", "de"))
        assertTrue(AmbiguousWords.hintOccurs("im Park", "Wir sassen im Park.", "de"))
        assertFalse(AmbiguousWords.hintOccurs("Boot", "Ich boote den PC.", "de"))
        assertTrue(AmbiguousWords.hintOccurs("I'm fine", "Thanks, I'm fine.", "en"))
    }
}

class AmbiguousWordsTest {
    private val list = listOf(BANK, NACHRICHTEN, TRUNK, entry("de-CH", "Estrich"))

    private fun resolve(
        text: String,
        previous: List<String> = emptyList(),
        source: Language? = de,
        target: Language = en,
        choices: Map<String, Int> = emptyMap(),
        forced: Map<String, Int> = emptyMap(),
        entries: List<AmbiguousWord> = list,
    ) = AmbiguousWords.resolve(entries, text, previous, source, target, choices, forced)

    @Test fun storageRoundTrip() {
        assertEquals(WordCollection.entries, AmbiguousWords.parse(AmbiguousWords.format(WordCollection.entries)))
        assertEquals(listOf(OWN), AmbiguousWords.parse(AmbiguousWords.format(listOf(OWN))))
        // separators in user input are removed
        assertEquals("a b c", AmbiguousWords.clean(" a|b~c "))
        assertEquals("a b", AmbiguousWords.cleanWord("a/b"))
        // broken lines are skipped; at most MAX_ENTRIES
        assertEquals(listOf(BANK), AmbiguousWords.parse("kaputt\n2|de\n" + AmbiguousWords.format(listOf(BANK))))
        val many = (1..600).joinToString("\n") { "2|de|W$it||M|x~~en=y" }
        assertEquals(AmbiguousWords.MAX_ENTRIES, AmbiguousWords.parse(many).size)
    }

    @Test fun versionOneIsReadAsEntriesWithAStandard() {
        val e = AmbiguousWords.parse("de>en|Nachrichten|messages:Chat,App|news:Fernsehen,Radio").single()
        assertEquals("de", e.language)
        assertTrue(e.standard)
        assertEquals(listOf("messages", "news"), e.meanings.map { it.label })
        assertEquals(listOf("messages"), e.meanings[0].into("en"))
        assertEquals(listOf("Chat", "App"), e.meanings[0].hints)
    }

    @Test fun theModelDecidesWithoutAHint() {
        val u = resolve("Die Bank ist geschlossen.").single()
        assertEquals(Reason.MODEL, u.reason)
        assertEquals(-1, u.meaningIndex)
        assertNull(u.to)
        assertFalse(u.chosen)
        assertEquals(listOf("Sitzbank", "Geldinstitut"), u.labels)
        assertEquals(listOf(listOf("bench"), listOf("bank")), u.options)
    }

    @Test fun aHintInTheSentenceDecides() {
        resolve("Wir haben uns im Park auf eine Bank gesetzt.").single().let {
            assertEquals(Reason.HINT, it.reason)
            assertEquals("bench", it.to)
            assertEquals(listOf("bank"), it.alternatives)
        }
        assertEquals("bank", resolve("Ich hole Geld auf der Bank.").single().to)
        // hints of two meanings: undecided
        assertEquals(Reason.MODEL, resolve("Im Park sitzt er vor der Bank und zählt sein Geld.").single().reason)
    }

    @Test fun theSentenceBeforeOnlyWarnsForCollectionWords() {
        val u = resolve("Die Nachrichten kommen nicht an.", previous = listOf("Ich habe dir im Chat geschrieben.")).single()
        assertEquals(Reason.MODEL, u.reason)
        assertEquals(0, u.suggested)
        assertEquals("Chat*", u.suggestedHint)
        // the model's choice is compared later: messages fits, news does not
        assertFalse(u.copy(meaningIndex = 0).doubtful)
        assertTrue(u.copy(meaningIndex = 1).doubtful)
    }

    @Test fun ownWordsWithAStandard() {
        val own = listOf(OWN)
        resolve("Die Nachrichten kommen nicht an.", entries = own).single().let { assertEquals(Reason.STANDARD, it.reason); assertEquals("messages", it.to) }
        // the sentence before decides before the standard (here: its translation, the other person's words)
        resolve("Dann kamen die Nachrichten.", previous = listOf("We watched TV.", "Wir haben Fernsehen geschaut."), entries = own).single().let {
            assertEquals(Reason.HINT_BEFORE, it.reason)
            assertEquals("news", it.to)
        }
    }

    @Test fun choicesAndTaps() {
        resolve("Die Bank ist geschlossen.", choices = mapOf(BANK.key to 1)).single().let { assertEquals(Reason.CHOICE, it.reason); assertEquals("bank", it.to) }
        // a hint in the sentence wins over an earlier choice, a tap wins over everything
        assertEquals("bench", resolve("Im Park war die Bank nass.", choices = mapOf(BANK.key to 1)).single().to)
        resolve("Im Park war die Bank nass.", forced = mapOf(BANK.key to 1)).single().let { assertEquals(Reason.FORCED, it.reason); assertEquals("bank", it.to) }
        // the choice counts for every target language
        assertEquals("banque", resolve("Die Bank ist geschlossen.", target = fr, choices = mapOf(BANK.key to 1)).single().to)
        assertEquals(Reason.MODEL, resolve("Die Bank ist geschlossen.", forced = mapOf(BANK.key to 7)).single().reason)
    }

    @Test fun everyTargetLanguageThatIsFilledIn() {
        assertEquals("panchina", resolve("Im Park auf der Bank.", target = it).single().to)
        assertEquals("banc", resolve("Im Park auf der Bank.", target = fr).single().to)
        // Spanish has "banco" for both: nothing to decide
        assertTrue(resolve("Im Park auf der Bank.", target = es).isEmpty())
        // Swiss German target: its own words, else German
        val glace = entry("fr", "glace")
        val u = AmbiguousWords.resolve(listOf(glace), "Une glace à la vanille, s'il te plaît.", emptyList(), fr, deCh).single()
        assertEquals("Glace", u.to)
        assertEquals("Eis", AmbiguousWords.resolve(listOf(glace), "Une glace à la vanille.", emptyList(), fr, de).single().to)
    }

    @Test fun sourceLanguages() {
        // Swiss German is German; Swiss words apply to German too
        assertEquals(Reason.HINT, resolve("Im Park auf der Bank.", source = deCh).single().reason)
        resolve("Wir haben den Estrich aufgeräumt.").single().let { assertEquals(Reason.STANDARD, it.reason); assertEquals("attic", it.to) }
        // unknown source: guessed, only to know which words to look for
        assertEquals("bench", resolve("Wir sitzen im Park auf der Bank.", source = null).single().to)
        val french = AmbiguousWords.resolve(listOf(entry("en", "date")), "Quelle est la date de ton anniversaire ?", emptyList(), null, de)
        assertTrue(french.isEmpty())
        // the target language itself is never "translated"
        assertTrue(resolve("Im Park auf der Bank.", target = de).isEmpty())
        // English entries for English sentences
        assertEquals("Kofferraum", resolve("Put the suitcases in the trunk of the car.", source = en, target = de).single().to)
    }

    @Test fun oneMeaningIsFixed() {
        val fixed = AmbiguousWord("Handy", "de", listOf(AmbiguousWord.Meaning("Mobiltelefon", translations = mapOf("en" to listOf("cell phone")))))
        val u = AmbiguousWords.resolve(listOf(fixed), "Mein Handy ist leer.", emptyList(), de, en).single()
        assertEquals(Reason.ONLY, u.reason)
        assertFalse(u.switchable)
        assertTrue(AmbiguousWords.resolve(listOf(fixed), "Mein Handy ist leer.", emptyList(), de, fr).isEmpty())
    }

    @Test fun whichMeaningTheModelTook() {
        val u = resolve("Die Bank ist geschlossen.").single()
        assertEquals(1, AmbiguousWords.detect("The bank is closed.", u))
        assertEquals(0, AmbiguousWords.detect("The benches are wet.", u))
        assertNull(AmbiguousWords.detect("It is closed.", u))
        assertNull(AmbiguousWords.detect("The bench in front of the bank.", u))
    }

    @Test fun correctingAnotherListedMeaning() {
        val bench = resolve("Wir sassen im Park auf der Bank.").single()
        assertEquals("We sat on the bench in the park.", AmbiguousWords.correct("We sat on the bank in the park.", bench))
        assertEquals("Bench first.", AmbiguousWords.correct("Bank first.", bench))
        assertNull(AmbiguousWords.correct("We sat on the seat.", bench))
        assertNull(AmbiguousWords.correct("The banker sat down.", bench))
        // synonyms of the other meaning are replaced too
        val tax = AmbiguousWords.resolve(listOf(entry("de", "Steuer")), "Wir zahlen die Steuer.", emptyList(), de, en).single()
        assertEquals("tax", tax.to)
        assertEquals("We pay the tax.", AmbiguousWords.correct("We pay the wheel.", tax))
        assertEquals("We pay the tax.", AmbiguousWords.correct("We pay the steering wheel.", tax))
    }

    @Test fun labels() {
        val u = WordUse("k", "Bank", "Bank", 1, Reason.HINT_BEFORE, "Geld", status = Status.CORRECTED)
        assertEquals("Hinweis davor „Geld“", u.reasonLabel)
        assertEquals("nachkorrigiert", u.statusLabel)
        assertEquals("Modell", u.copy(reason = Reason.MODEL).reasonLabel)
    }

    @Test fun migrationAndMerging() {
        val doc = "Ich teste meine App.\nNachrichten = messages\nBank → bench\n„Schloss“ -> lock\n= leer"
        val e = AmbiguousWords.fromContextDocument(doc, "de-CH", "en")
        assertEquals(listOf("Nachrichten", "Bank", "Schloss"), e.map { it.word })
        assertEquals(listOf("messages"), e[0].meanings.single().into("en"))
        // the user's own word stays, the collection adds the others
        val merged = AmbiguousWords.merge(listOf(OWN), WordCollection.entries)
        assertEquals(OWN, merged.first())
        assertEquals(1, merged.count { it.key == OWN.key })
        assertEquals(WordCollection.entries.size, merged.size)
    }

    @Test fun editorChecks() {
        assertNull(entryProblem(OWN, listOf(BANK)))
        assertTrue(entryProblem(OWN.copy(word = "nachrichten"), listOf(NACHRICHTEN))!!.contains("schon eingetragen"))
        assertNull(entryProblem(OWN.copy(language = "en"), listOf(NACHRICHTEN)))
        assertTrue(entryProblem(OWN.copy(word = " "), emptyList())!!.contains("fehlt"))
        val m = OWN.meanings
        assertTrue(entryProblem(OWN.copy(meanings = listOf(m[0], m[1].copy(translations = emptyMap()))), emptyList())!!.contains("fehlt eine Übersetzung"))
        assertTrue(entryProblem(OWN.copy(meanings = listOf(m[0], m[0])), emptyList())!!.contains("zweimal"))
        assertTrue(entryProblem(OWN.copy(meanings = listOf(m[0], m[1].copy(hints = listOf("chat")))), emptyList())!!.contains("mehreren Bedeutungen"))
        assertTrue(entryProblem(OWN.copy(meanings = listOf(m[0].copy(hints = listOf("Fern*")), m[1])), emptyList())!!.contains("passt auch zu"))
        assertTrue(entryProblem(OWN.copy(meanings = listOf(m[0].copy(hints = listOf("Nachricht")), m[1])), emptyList())!!.contains("Form des Worts"))
    }
}

/** The built-in collection: complete, consistent, every hint word decides. */
class WordCollectionTest {
    private val core = listOf("de", "en", "fr", "it", "es", "pt")

    @Test fun manyWordsInSixLanguages() {
        val byLanguage = WordCollection.entries.groupingBy { it.language.substringBefore('-') }.eachCount()
        println("Sammlung: ${WordCollection.entries.size} Wörter, $byLanguage")
        assertTrue(WordCollection.entries.size >= 100)
        assertEquals(core.toSet(), byLanguage.keys)
        assertEquals(WordCollection.entries.size, WordCollection.entries.map { it.key }.distinct().size)
    }

    @Test fun everyEntryCouldBeSavedInTheEditor() {
        for (e in WordCollection.entries) {
            assertNull("${e.word}: ${entryProblem(e, WordCollection.entries - e)}", entryProblem(e, WordCollection.entries - e))
        }
    }

    @Test fun everyMeaningIsTranslatedIntoTheOtherCoreLanguages() {
        for (e in WordCollection.entries) for (m in e.meanings) {
            val missing = core.filter { !AmbiguousWords.sameLanguage(it, e.language) && m.into(it).isEmpty() }
            assertTrue("${e.word} / ${m.label}: $missing", missing.isEmpty())
            assertTrue("${e.word} / ${m.label}: no hints", m.hints.isNotEmpty())
        }
    }

    @Test fun everyWordIsAmbiguousForSomeTarget() {
        for (e in WordCollection.entries) {
            val targets = core.filter { t ->
                !AmbiguousWords.sameLanguage(t, e.language) && e.meanings.map { it.into(t).firstOrNull()?.lowercase() }.distinct().size >= 2
            }
            assertTrue(e.word, targets.isNotEmpty())
        }
    }

    @Test fun everyHintFindsItsMeaning() {
        // a sentence made of the word and one hint gets that meaning (hints with "*" checked as written)
        for (e in WordCollection.entries) {
            val target = core.first { t -> !AmbiguousWords.sameLanguage(t, e.language) && e.meanings.map { it.into(t).firstOrNull() }.distinct().size == e.meanings.size }
                .let { Languages.require(it) }
            e.meanings.forEachIndexed { i, m ->
                for (h in m.hints) {
                    val text = e.word + " " + h.trimEnd('*') + (if (h.endsWith("*")) "x" else "")
                    val u = AmbiguousWords.resolve(listOf(e), text, emptyList(), Languages.require(e.language), target).singleOrNull()
                    assertEquals("${e.word} + „$h“ (${e.language} → ${target.code})", i, u?.meaningIndex)
                }
            }
        }
    }
}

class PromptTermsTest {
    private val terms = listOf(PromptFormat.Term("Nachrichten", "messages"))

    @Test fun terminologyTemplateAsInTheModelCard() {
        val p = PromptFormat.build(PromptStyle.HY_MT, de, en, "Die Nachrichten kommen nicht an.", terms)
        assertEquals(
            "<｜hy_begin▁of▁sentence｜><｜hy_User｜>Reference the following translations:\nNachrichten translates to messages\n\n" +
                "Translate the following text into English. Note that you must ONLY output the translated result " +
                "without any additional explanation:\n\nDie Nachrichten kommen nicht an.<｜hy_Assistant｜>",
            p.text,
        )
        assertTrue(p.stopAtNewline)
    }

    @Test fun theSecondAttemptAlsoStatesTheMeaning() {
        val p = PromptFormat.build(PromptStyle.HY_MT, de, en, "Die Nachrichten", terms + PromptFormat.Term("Bank", "bench"), stateTerms = true)
        assertTrue(
            p.text,
            p.text.startsWith(
                "<｜hy_begin▁of▁sentence｜>In this text, \"Nachrichten\" means \"messages\". In this text, \"Bank\" means \"bench\"." +
                    "<｜hy_place▁holder▁no▁3｜><｜hy_User｜>Reference the following translations:\nNachrichten translates to messages\nBank translates to bench\n\n",
            ),
        )
    }

    @Test fun chineseTerminologyTemplate() {
        val p = PromptFormat.build(PromptStyle.HY_MT, de, zh, "Die Nachrichten", terms)
        assertTrue(p.text, p.text.contains("<｜hy_User｜>参考下面的翻译：\nNachrichten 翻译成 messages\n将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\nDie Nachrichten"))
    }

    @Test fun withoutTermsThePromptIsTheUsualOne() {
        assertEquals(PromptFormat.build(PromptStyle.HY_MT, de, en, "Hallo"), PromptFormat.build(PromptStyle.HY_MT, de, en, "Hallo", emptyList(), stateTerms = true))
        assertTrue(PromptFormat.supportsTerms(PromptStyle.HY_MT))
        assertFalse(PromptFormat.supportsTerms(PromptStyle.MILMMT))
        assertFalse(PromptFormat.supportsTerms(PromptStyle.CHAT_TEMPLATE))
    }
}

/** The engine with a scripted fake model: what goes into the prompts, the check, the second run, the correction. */
class EngineWordsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    /** Answers with the next scripted text (the last one again when the script is used up). */
    class ScriptedSession(private vararg val answers: String) : LlmSession {
        val prompts = CopyOnWriteArrayList<String>()
        override val description = "script"
        override val loadMillis = 1L
        override val processor = Processor.CPU
        override var isClosed = false

        override fun generate(
            prompt: String,
            addSpecial: Boolean,
            maxTokens: Int,
            repeatPenalty: Float,
            stopAtNewline: Boolean,
            onText: ((String) -> Boolean)?,
        ): LlamaModel.Result {
            if (maxTokens == 0) return LlamaModel.Result("", 30, 0, 0, 1.0, 0.0, LlamaModel.StopReason.MAX_TOKENS)
            prompts += prompt
            val text = answers.getOrElse(prompts.size - 1) { answers.last() }
            onText?.invoke(text)
            return LlamaModel.Result(text, 40, 0, 5, 1.0, 1.0, LlamaModel.StopReason.EOG)
        }

        override fun cancel() {}
        override fun resetCache() {}
        override fun applyChatTemplate(user: String): String? = null
        override fun close() {
            isClosed = true
        }
    }

    private fun engine(session: ScriptedSession, on: Boolean = true, model: String = ModelCatalog.HY_MT2, words: List<AmbiguousWord> = listOf(BANK, OWN, TRUNK)) =
        TranslationEngine(
            source = object : TranslationModelSource {
                override fun installedTranslationModels(): List<ModelInfo> = listOf(ModelCatalog.byId(model)!!)
                override fun modelPath(info: ModelInfo) = "/fake/${info.id}.gguf"
            },
            settings = { AppSettings(accel = AccelMode.CPU, useAmbiguousWords = on, ambiguousWords = AmbiguousWords.format(words)) },
            nativeLibDir = null,
            loader = SessionLoader { _, _ -> session },
            scope = scope,
            initBackends = { "test" },
        )

    private fun translate(
        e: TranslationEngine,
        text: String,
        role: Role = Role.FINAL,
        previous: List<String> = emptyList(),
        forced: Map<String, Int> = emptyMap(),
        useWords: Boolean = true,
        source: Language = de,
        target: Language = en,
    ) = runBlocking { e.translate(TranslationEngine.Request(text, source, target, role, previousTexts = previous, forcedWords = forced, useWords = useWords)) }

    private fun plainPrompt(text: String, source: Language = de, target: Language = en) = PromptFormat.build(PromptStyle.HY_MT, source, target, text).text

    @Test fun aSentenceWithoutListedWordGetsExactlyTheUsualPrompt() {
        val s = ScriptedSession("I am going shopping.")
        val r = translate(engine(s), "Ich gehe einkaufen.")
        assertEquals(plainPrompt("Ich gehe einkaufen."), s.prompts.single())
        assertTrue(r.words.isEmpty())
    }

    @Test fun withoutAHintTheModelDecidesAndTheAppOnlyLooks() {
        val s = ScriptedSession("The bank is closed.")
        val r = translate(engine(s), "Die Bank ist geschlossen.")
        assertEquals("the prompt is the usual one", plainPrompt("Die Bank ist geschlossen."), s.prompts.single())
        r.words.single().let {
            assertEquals(Reason.MODEL, it.reason)
            assertEquals(Status.DETECTED, it.status)
            assertEquals(1, it.meaningIndex)
            assertEquals("bank", it.to)
        }
        val unclear = translate(engine(ScriptedSession("It is closed.")), "Die Bank ist geschlossen.").words.single()
        assertEquals(Status.UNCLEAR, unclear.status)
    }

    @Test fun aHintGivesTheMeaningAndItIsChecked() {
        val s = ScriptedSession("We sat on a bench in the park.")
        val r = translate(engine(s), "Wir sassen im Park auf einer Bank.")
        assertTrue(s.prompts.single().contains("Reference the following translations:\nBank translates to bench\n"))
        assertEquals(Status.CONFIRMED, r.words.single().status)
    }

    @Test fun notTakenTheFirstTimeSecondRunWithTheStatement() {
        val s = ScriptedSession("We sat on a bank in the park.", "We sat on a bench in the park.")
        val r = translate(engine(s), "Wir sassen im Park auf einer Bank.")
        assertEquals(2, s.prompts.size)
        assertTrue(s.prompts[1].startsWith("<｜hy_begin▁of▁sentence｜>In this text, \"Bank\" means \"bench\".<｜hy_place▁holder▁no▁3｜>"))
        assertEquals(Status.CONFIRMED_AFTER_HINT, r.words.single().status)
    }

    @Test fun anotherListedMeaningTwiceIsReplaced() {
        val r = translate(engine(ScriptedSession("We sat on a bank in the park.")), "Wir sassen im Park auf einer Bank.")
        assertEquals(Status.CORRECTED, r.words.single().status)
        assertEquals("We sat on a bench in the park.", r.text)
    }

    @Test fun aSynonymStaysAndIsShownAsSuch() {
        val r = translate(engine(ScriptedSession("We sat on a seat in the park.")), "Wir sassen im Park auf einer Bank.")
        assertEquals(Status.SYNONYM, r.words.single().status)
        assertEquals("We sat on a seat in the park.", r.text)
    }

    @Test fun everyListedSynonymCounts() {
        // Steuer = "wheel/steering wheel/helm": "wheel" is right
        val r = translate(engine(ScriptedSession("He lets me take the wheel."), words = listOf(entry("de", "Steuer"))), "Er lässt mich ans Steuer.")
        assertEquals(Status.CONFIRMED, r.words.single().status)
    }

    @Test fun theBetterRunCounts() {
        // first run: one of two given words right; second run: none - the first one stays
        val s = ScriptedSession("On the bench I read the news.", "On the bank I read the news.")
        val r = translate(engine(s), "Auf der Bank im Park las ich die Nachrichten.")
        assertEquals(2, s.prompts.size)
        val byWord = r.words.associateBy { it.word }
        assertEquals(Status.CONFIRMED, byWord.getValue("Bank").status)
        assertEquals(Status.CORRECTED, byWord.getValue("Nachrichten").status)
        assertEquals("On the bench I read the messages.", r.text)
    }

    @Test fun noAnswerWithTheMeaningGivenTranslatesWithoutAndCorrects() {
        val s = ScriptedSession("", "", "We sat on a bank in the park.")
        val r = translate(engine(s), "Wir sassen im Park auf einer Bank.")
        assertEquals(3, s.prompts.size)
        assertEquals(plainPrompt("Wir sassen im Park auf einer Bank."), s.prompts[2])
        assertEquals("We sat on a bench in the park.", r.text)
        assertEquals(Status.CORRECTED, r.words.single().status)
    }

    @Test fun aMeaningThatAlsoReplacedAnotherWordIsNotKept() {
        val s = ScriptedSession("Legen Sie die Kofferraum in den Kofferraum.", "Legen Sie die Koffer in den Kofferraum.")
        val r = translate(engine(s), "Put the suitcases in the trunk of the car.", source = en, target = de)
        assertEquals(2, s.prompts.size)
        assertEquals("Legen Sie die Koffer in den Kofferraum.", r.text)
        assertEquals(Status.CONFIRMED_AFTER_HINT, r.words.single().status)
        // in both runs: the translation without the meaning
        val t = ScriptedSession("Legen Sie die Kofferraum in den Kofferraum.", "Die Kofferraum in den Kofferraum.", "Legen Sie die Koffer in den Kofferraum.")
        val r2 = translate(engine(t), "Put the suitcases in the trunk of the car.", source = en, target = de)
        assertEquals(3, t.prompts.size)
        assertEquals(plainPrompt("Put the suitcases in the trunk of the car.", en, de), t.prompts[2])
        assertEquals("Legen Sie die Koffer in den Kofferraum.", r2.text)
        assertEquals(Status.CONFIRMED, r2.words.single().status)
    }

    @Test fun aChosenMeaningWithoutTranslationIsNotGiven() {
        // a third meaning without French: the hint chooses it, nothing can be given to the model
        val three = OWN.copy(meanings = OWN.meanings + AmbiguousWord.Meaning("Botschaften", listOf("Bibel"), mapOf("en" to listOf("tidings"))))
        val s = ScriptedSession("Les messages de la Bible.")
        val r = translate(engine(s, words = listOf(three)), "Die Nachrichten der Bibel.", target = fr)
        assertEquals(Status.NO_TRANSLATION, r.words.single().status)
        assertEquals(plainPrompt("Die Nachrichten der Bibel.", de, fr), s.prompts.single())
        // only one meaning translated into French: nothing to decide, the word is left alone
        val one = OWN.copy(meanings = listOf(OWN.meanings[0], OWN.meanings[1].copy(translations = mapOf("en" to listOf("news")))))
        assertTrue(translate(engine(ScriptedSession("Les informations."), words = listOf(one)), "Im Radio kamen die Nachrichten.", target = fr).words.isEmpty())
    }

    @Test fun theSentenceBeforeWarnsIfTheModelTookAnotherMeaning() {
        val words = listOf(entry("de", "Nachrichten"))
        val r = translate(engine(ScriptedSession("Did you read the news?"), words = words), "Hast du die Nachrichten gelesen?", previous = listOf("Ich habe dir im Chat geschrieben."))
        r.words.single().let {
            assertEquals(Reason.MODEL, it.reason)
            assertEquals("news", it.to)
            assertTrue(it.doubtful)
        }
    }

    @Test fun tappedMeaningAndSwitches() {
        val s = ScriptedSession("The bank is closed.")
        val r = translate(engine(s), "Die Bank ist geschlossen.", forced = mapOf(BANK.key to 0))
        assertTrue(s.prompts.first().contains("Bank translates to bench"))
        assertEquals(Reason.FORCED, r.words.single().reason)
        val off = ScriptedSession("The bank")
        assertTrue(translate(engine(off, on = false), "Die Bank im Park").words.isEmpty())
        assertEquals(plainPrompt("Die Bank im Park"), off.prompts.single())
        val left = ScriptedSession("The bank")
        assertTrue(translate(engine(left), "Die Bank im Park", useWords = false).words.isEmpty())
    }

    @Test fun theBenchmarkBringsItsOwnEntry() = runBlocking {
        val s = ScriptedSession("We sat on a bench in the park.")
        val r = engine(s, words = emptyList()).translate(
            TranslationEngine.Request("Wir sassen im Park auf einer Bank.", de, en, Role.TEXT, useWords = true, words = listOf(BANK)),
        )
        assertEquals(Status.CONFIRMED, r.words.single().status)
    }

    @Test fun miLmmtOnlyChecksAndCorrects() {
        val s = ScriptedSession("We sat on a bank in the park.")
        val r = translate(engine(s, model = ModelCatalog.MILMMT_1B), "Wir sassen im Park auf einer Bank.")
        assertEquals("Translate this from German to English:\nGerman: Wir sassen im Park auf einer Bank.\nEnglish:", s.prompts.single())
        assertEquals("We sat on a bench in the park.", r.text)
        assertEquals(Status.CORRECTED, r.words.single().status)
    }

    @Test fun textsAreCheckedPieceByPiece() {
        // (short sentences of a paragraph are translated together; paragraphs one by one)
        val s = ScriptedSession("The messages are missing.", "We sat on a bench in the park.")
        val r = translate(engine(s), "Die Nachrichten fehlen.\nWir sassen im Park auf einer Bank.", role = Role.TEXT)
        assertEquals("The messages are missing.\nWe sat on a bench in the park.", r.text)
        assertTrue(s.prompts[0].contains("Nachrichten translates to messages") && !s.prompts[0].contains("Bank translates"))
        assertTrue(s.prompts[1].contains("Bank translates to bench") && !s.prompts[1].contains("Nachrichten translates"))
    }
}
