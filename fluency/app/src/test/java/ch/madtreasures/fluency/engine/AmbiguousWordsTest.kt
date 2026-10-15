package ch.madtreasures.fluency.engine

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
import ch.madtreasures.fluency.engine.llm.WordUse
import ch.madtreasures.fluency.engine.llm.WordUse.Reason
import ch.madtreasures.fluency.engine.llm.WordUse.Status
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelInfo
import ch.madtreasures.fluency.models.PromptStyle
import ch.madtreasures.fluency.settings.AppSettings
import ch.madtreasures.fluency.ui.settings.EXAMPLE_WORD
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
private val zh = Languages.require("zh")

/** "Nachrichten" from the field test, as a user would list it */
private val NACHRICHTEN = AmbiguousWord(
    "Nachrichten", "de", "en",
    listOf(
        AmbiguousWord.Meaning("messages", listOf("Chat", "Handy", "schreib*")),
        AmbiguousWord.Meaning("news", listOf("Fernseh*", "Radio")),
    ),
)
private val BANK = AmbiguousWord("Bank", "de", "en", listOf(AmbiguousWord.Meaning("bench", listOf("Park")), AmbiguousWord.Meaning("bank", listOf("Geld", "Konto"))))

class AmbiguousWordsTest {
    private val entries = listOf(NACHRICHTEN, BANK)

    private fun resolve(
        text: String,
        previous: String? = null,
        source: ch.madtreasures.fluency.core.Language? = de,
        target: ch.madtreasures.fluency.core.Language = en,
        choices: Map<String, Int> = emptyMap(),
        forced: Map<String, Int> = emptyMap(),
    ) = AmbiguousWords.resolve(entries, text, previous, source, target, choices, forced)

    @Test fun storageRoundTrip() {
        val storage = AmbiguousWords.format(entries)
        assertEquals("de>en|Nachrichten|messages:Chat,Handy,schreib*|news:Fernseh*,Radio\nde>en|Bank|bench:Park|bank:Geld,Konto", storage)
        assertEquals(entries, AmbiguousWords.parse(storage))
        // broken lines are skipped, separators in user input are removed
        assertEquals(1, AmbiguousWords.parse("kaputt\nde>en|Bank|bench\n|x|y\nde>en||a").size)
        assertEquals("a b c", AmbiguousWords.clean(" a|b:c "))
        val odd = AmbiguousWord("Wort|1", "de", "en", listOf(AmbiguousWord.Meaning("a:b", listOf("x,y"))))
        assertEquals(listOf(AmbiguousWord("Wort 1", "de", "en", listOf(AmbiguousWord.Meaning("a b", listOf("x y"))))), AmbiguousWords.parse(AmbiguousWords.format(listOf(odd))))
        assertEquals(AmbiguousWords.MAX_ENTRIES, AmbiguousWords.parse((1..400).joinToString("\n") { "de>en|w$it|t" }).size)
    }

    @Test fun wordFormsAreFoundButNotInsideOtherWords() {
        for (text in listOf("Die Nachrichten", "eine Nachricht", "NACHRICHTEN!", "„Nachrichten“")) assertTrue(text, AmbiguousWords.occurs("Nachrichten", text))
        for (text in listOf("Sprachnachrichten", "Nachrichtenagentur", "Kurznachricht")) assertFalse(text, AmbiguousWords.occurs("Nachrichten", text))
        for (text in listOf("auf der Bank", "zwei Bänke", "die Banken", "BANK")) assertTrue(text, AmbiguousWords.occurs("Bank", text))
        for (text in listOf("der Bankraub", "ein Banker", "Datenbank", "Sandbank")) assertFalse(text, AmbiguousWords.occurs("Bank", text))
        assertTrue(AmbiguousWords.occurs("Schloss", "das Schloß"))
        assertTrue(AmbiguousWords.occurs("Schloss", "des Schlosses"))
        assertTrue(AmbiguousWords.occurs("messages", "a message"))
        assertTrue(AmbiguousWords.occurs("battery", "two batteries"))
        assertTrue(AmbiguousWords.occurs("Schüler", "die beste Schülerin"))
        assertTrue(AmbiguousWords.occurs("Schülerin", "die Schüler"))
        assertFalse(AmbiguousWords.occurs("news", "the newspaper"))
        assertFalse(AmbiguousWords.occurs("news", "a new phone"))
        assertTrue(AmbiguousWords.occurs("voice message", "Two voice messages."))
        assertTrue(AmbiguousWords.occurs("消息", "我收到了你的消息"))
        assertEquals("Bänke", AmbiguousWords.find("Bank", "Wir sassen auf zwei Bänke."))
    }

    @Test fun hintWordsLikeTheWordOrWithAStarAsPrefix() {
        assertTrue(AmbiguousWords.hintOccurs("Chat", "im Chat"))
        assertTrue(AmbiguousWords.hintOccurs("Chat", "die Chats"))
        assertFalse(AmbiguousWords.hintOccurs("App", "guten Appetit"))
        assertTrue(AmbiguousWords.hintOccurs("Fernseh*", "der Fernseher"))
        assertTrue(AmbiguousWords.hintOccurs("Fernseh*", "beim Fernsehen"))
        assertFalse(AmbiguousWords.hintOccurs("Fernseh*", "Kabelfernsehen"))
        assertFalse(AmbiguousWords.hintOccurs("*", "irgendwas"))
    }

    @Test fun standardWithoutHint() {
        val u = resolve("Die Nachrichten kommen nicht an.").single()
        assertEquals(Reason.STANDARD, u.reason)
        assertEquals("Nachrichten", u.from)
        assertEquals("messages", u.to)
        assertEquals(listOf("news"), u.alternatives)
        assertEquals("de>en:nachrichten", u.key)
        assertTrue(u.switchable)
    }

    @Test fun theRulesInTheirOrder() {
        // a hint word in the sentence
        resolve("Im Radio kamen die Nachrichten.").single().let { assertEquals(Reason.HINT, it.reason); assertEquals("news", it.to); assertEquals("Radio", it.hint) }
        // in the sentence before
        resolve("Dann schauen wir die Nachrichten.", previous = "Um acht schalten wir den Fernseher ein.").single().let {
            assertEquals(Reason.HINT_BEFORE, it.reason)
            assertEquals("news", it.to)
            assertEquals("Fernseh*", it.hint)
        }
        // the sentence itself wins over the one before
        assertEquals("messages", resolve("Ich habe dir Nachrichten geschrieben, im Chat.", previous = "Im Radio war es laut.").single().to)
        // hints of two meanings: undecided, the next rule decides
        resolve("Im Chat ging es um die Nachrichten im Radio.").single().let { assertEquals(Reason.STANDARD, it.reason) }
        // a choice from earlier in the conversation, only without a hint
        resolve("Die Nachrichten waren lang.", choices = mapOf(NACHRICHTEN.key to 1)).single().let { assertEquals(Reason.CHOICE, it.reason); assertEquals("news", it.to) }
        assertEquals(Reason.HINT, resolve("Im Chat die Nachrichten.", choices = mapOf(NACHRICHTEN.key to 1)).single().reason)
        // tapped for this sentence: wins over everything
        resolve("Im Chat die Nachrichten.", forced = mapOf(NACHRICHTEN.key to 1)).single().let { assertEquals(Reason.FORCED, it.reason); assertEquals("news", it.to) }
        // an invalid index is ignored
        assertEquals(Reason.STANDARD, resolve("Die Nachrichten.", forced = mapOf(NACHRICHTEN.key to 7)).single().reason)
    }

    @Test fun severalWordsInOneSentence() {
        val uses = resolve("Auf der Bank im Park las ich die Nachrichten im Radio.")
        assertEquals(2, uses.size)
        assertEquals("news", uses.single { it.word == "Nachrichten" }.to)
        assertEquals("bench", uses.single { it.word == "Bank" }.to)
    }

    @Test fun theOtherDirectionBecomesTheListedWord() {
        val u = resolve("Did you read my message?", source = en, target = de).single()
        assertEquals(Reason.REVERSE, u.reason)
        assertEquals("message", u.from)
        assertEquals("Nachrichten", u.to)
        assertFalse(u.switchable)
        assertEquals("Bank", resolve("We sat on a bench.", source = en, target = de).single().to)
        assertEquals("Bank", resolve("I went to the bank.", source = en, target = de).single().to)
    }

    @Test fun languagesMustMatch() {
        // Swiss German is German, automatic detection is fine, other languages are not
        assertEquals("messages", resolve("D Nachrichten sind cho.", source = deCh).single().to)
        assertEquals("messages", resolve("Die Nachrichten", source = null).single().to)
        assertTrue(resolve("Die Nachrichten", target = fr).isEmpty())
        assertTrue(resolve("Die Nachrichten", source = fr).isEmpty())
        assertTrue(resolve("Ich gehe einkaufen.").isEmpty())
    }

    @Test fun onlyOneMeaningIsFixed() {
        val fixed = AmbiguousWord("Handy", "de", "en", listOf(AmbiguousWord.Meaning("cell phone")))
        val u = AmbiguousWords.resolve(listOf(fixed), "Mein Handy ist leer.", null, de, en).single()
        assertEquals(Reason.ONLY, u.reason)
        assertFalse(u.switchable)
        assertTrue(u.alternatives.isEmpty())
    }

    @Test fun checkingTheTranslation() {
        assertTrue(AmbiguousWords.contains("I sent you a message.", "messages"))
        assertTrue(AmbiguousWords.contains("Wir setzten uns auf die Bänke.", "Bank"))
        assertTrue(AmbiguousWords.contains("Die Strasse", "Straße"))
        assertTrue(AmbiguousWords.contains("The light bulbs are broken.", "light bulb"))
        assertFalse(AmbiguousWords.contains("I read the newspaper.", "news"))
        assertFalse(AmbiguousWords.contains("A key card got stuck.", "Taste"))
        assertTrue(AmbiguousWords.contains("你的消息", "消息"))
    }

    @Test fun correctingAnotherListedMeaning() {
        val messages = resolve("Die Nachrichten kommen nicht an.").single()
        assertEquals("The messages are not arriving.", AmbiguousWords.correct("The news are not arriving.", messages))
        assertEquals("Messages first.", AmbiguousWords.correct("News first.", messages))
        // nothing listed in the text (a synonym, or the word was left out): no change
        assertNull(AmbiguousWords.correct("The texts are not arriving.", messages))
        // never inside another word
        assertNull(AmbiguousWords.correct("I read the newspaper.", messages))
        val bench = resolve("Wir sassen auf der Bank.").single()
        assertEquals("We sat on two benches by the bench.", AmbiguousWords.correct("We sat on two benches by the bank.", bench))
        assertNull(AmbiguousWords.correct("The banker sat down.", bench))
    }

    @Test fun labels() {
        val u = WordUse("k", "Nachrichten", 1, "Nachrichten", "news", Reason.HINT_BEFORE, "Fernseh*", status = Status.CORRECTED)
        assertEquals("Hinweis davor „Fernseh*“", u.reasonLabel)
        assertEquals("nachkorrigiert", u.statusLabel)
    }

    @Test fun migrationOfTheOldContextDocument() {
        val doc = "Ich teste meine App.\nNachrichten = messages\nBank → bench\n„Schloss“ -> lock\nzu=lang=x\n= leer"
        val e = AmbiguousWords.fromContextDocument(doc, "de-CH", "en")
        assertEquals(listOf("Nachrichten", "Bank", "Schloss", "zu"), e.map { it.word })
        assertEquals("messages", e[0].meanings.single().translation)
        assertEquals("lang=x", e[3].meanings.single().translation)
        assertTrue(e.all { it.source == "de-CH" && it.target == "en" })
    }

    @Test fun editorChecks() {
        assertNull(entryProblem(EXAMPLE_WORD, emptyList()))
        assertNull(entryProblem(NACHRICHTEN, listOf(BANK)))
        assertTrue(entryProblem(NACHRICHTEN.copy(word = "nachrichten"), listOf(NACHRICHTEN))!!.contains("schon eingetragen"))
        // the same word for other languages is fine; Swiss German counts as German
        assertNull(entryProblem(NACHRICHTEN.copy(target = "fr"), listOf(NACHRICHTEN)))
        assertTrue(entryProblem(NACHRICHTEN.copy(source = "de-CH"), listOf(NACHRICHTEN))!!.contains("schon eingetragen"))
        assertTrue(entryProblem(NACHRICHTEN.copy(target = "de"), emptyList())!!.contains("gleich"))
        assertTrue(entryProblem(NACHRICHTEN.copy(word = " "), emptyList())!!.contains("fehlt"))
        val m = NACHRICHTEN.meanings
        assertTrue(entryProblem(NACHRICHTEN.copy(meanings = listOf(m[0], m[0])), emptyList())!!.contains("zweimal"))
        val clash = listOf(m[0], m[1].copy(hints = listOf("chat")))
        assertTrue(entryProblem(NACHRICHTEN.copy(meanings = clash), emptyList())!!.contains("mehreren Bedeutungen"))
        val prefix = listOf(m[0].copy(hints = listOf("Fern*")), m[1])
        assertTrue(entryProblem(NACHRICHTEN.copy(meanings = prefix), emptyList())!!.contains("passt auch zu"))
        val self = listOf(m[0].copy(hints = listOf("Nachricht")), m[1])
        assertTrue(entryProblem(NACHRICHTEN.copy(meanings = self), emptyList())!!.contains("Wort selbst"))
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

    private fun engine(session: ScriptedSession, on: Boolean = true, model: String = ModelCatalog.HY_MT2, words: List<AmbiguousWord> = listOf(NACHRICHTEN, BANK)) = TranslationEngine(
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

    private fun translate(e: TranslationEngine, text: String, role: Role = Role.FINAL, previous: String? = null, forced: Map<String, Int> = emptyMap(), useWords: Boolean = true) =
        runBlocking { e.translate(TranslationEngine.Request(text, de, en, role, previousText = previous, forcedWords = forced, useWords = useWords)) }

    @Test fun aSentenceWithoutListedWordGetsExactlyTheUsualPrompt() {
        val s = ScriptedSession("I am going shopping.")
        val r = translate(engine(s), "Ich gehe einkaufen.")
        assertEquals(PromptFormat.build(PromptStyle.HY_MT, de, en, "Ich gehe einkaufen.").text, s.prompts.single())
        assertTrue(r.words.isEmpty())
        assertEquals("I am going shopping.", r.text)
    }

    @Test fun theChosenMeaningIsGivenAndChecked() {
        val s = ScriptedSession("The messages are not arriving.")
        val r = translate(engine(s), "Die Nachrichten kommen nicht an.")
        assertTrue(s.prompts.single().contains("Reference the following translations:\nNachrichten translates to messages\n"))
        assertEquals(Status.CONFIRMED, r.words.single().status)
        assertEquals("The messages are not arriving.", r.text)
    }

    @Test fun notTakenTheFirstTimeSecondRunWithTheStatement() {
        val s = ScriptedSession("The news are not arriving.", "The messages are not arriving.")
        val r = translate(engine(s), "Die Nachrichten kommen nicht an.")
        assertEquals(2, s.prompts.size)
        assertTrue(s.prompts[1].startsWith("<｜hy_begin▁of▁sentence｜>In this text, \"Nachrichten\" means \"messages\".<｜hy_place▁holder▁no▁3｜>"))
        assertEquals(Status.CONFIRMED_AFTER_HINT, r.words.single().status)
        assertEquals("The messages are not arriving.", r.text)
    }

    @Test fun anotherListedMeaningTwiceIsReplaced() {
        val s = ScriptedSession("The news are not arriving.")
        val r = translate(engine(s), "Die Nachrichten kommen nicht an.")
        assertEquals(2, s.prompts.size)
        assertEquals(Status.CORRECTED, r.words.single().status)
        assertEquals("The messages are not arriving.", r.text)
    }

    @Test fun aSynonymStaysAndIsShownAsSuch() {
        val s = ScriptedSession("The texts are not arriving.")
        val r = translate(engine(s), "Die Nachrichten kommen nicht an.")
        assertEquals(Status.SYNONYM, r.words.single().status)
        assertEquals("The texts are not arriving.", r.text)
    }

    @Test fun theBetterRunCounts() {
        // first run: one of two words right; second run: none - the first one stays
        val s = ScriptedSession("On the bench I read the news.", "On the bank I read the news.")
        val r = translate(engine(s), "Auf der Bank im Park las ich die Nachrichten.")
        assertEquals(2, s.prompts.size)
        val byWord = r.words.associateBy { it.word }
        assertEquals(Status.CONFIRMED, byWord.getValue("Bank").status)
        assertEquals(Status.CORRECTED, byWord.getValue("Nachrichten").status)
        assertEquals("On the bench I read the messages.", r.text)
    }

    @Test fun noAnswerWithTheMeaningGivenTranslatesWithoutAndCorrects() {
        val s = ScriptedSession("", "", "The news are not arriving.")
        val r = translate(engine(s), "Die Nachrichten kommen nicht an.")
        assertEquals(3, s.prompts.size)
        assertEquals(PromptFormat.build(PromptStyle.HY_MT, de, en, "Die Nachrichten kommen nicht an.").text, s.prompts[2])
        assertEquals("The messages are not arriving.", r.text)
        assertEquals(Status.CORRECTED, r.words.single().status)
    }

    /** measured on Hy-MT2: "trunk" → Kofferraum also turned "suitcases" into "Kofferraum" */
    private val trunk = AmbiguousWord("trunk", "en", "de", listOf(AmbiguousWord.Meaning("Kofferraum"), AmbiguousWord.Meaning("Rüssel")))

    private fun translateTrunk(s: ScriptedSession) = runBlocking {
        engine(s, words = listOf(trunk)).translate(TranslationEngine.Request("Put the suitcases in the trunk.", en, de, Role.FINAL))
    }

    @Test fun aMeaningThatAlsoReplacedAnotherWordIsNotKept() {
        val s = ScriptedSession("Legen Sie die Kofferraum in den Kofferraum.", "Legen Sie die Koffer in den Kofferraum.")
        val r = translateTrunk(s)
        assertEquals(2, s.prompts.size)
        assertEquals("Legen Sie die Koffer in den Kofferraum.", r.text)
        assertEquals(Status.CONFIRMED_AFTER_HINT, r.words.single().status)
    }

    @Test fun inBothRunsThenTheTranslationWithoutTheMeaning() {
        val s = ScriptedSession("Legen Sie die Kofferraum in den Kofferraum.", "Die Kofferraum in den Kofferraum.", "Legen Sie die Koffer in den Kofferraum.")
        val r = translateTrunk(s)
        assertEquals(3, s.prompts.size)
        assertEquals(PromptFormat.build(PromptStyle.HY_MT, en, de, "Put the suitcases in the trunk.").text, s.prompts[2])
        assertEquals("Legen Sie die Koffer in den Kofferraum.", r.text)
        assertEquals(Status.CONFIRMED, r.words.single().status)
        // the word twice in the sentence may be translated twice
        val twice = ScriptedSession("Der Kofferraum und noch ein Kofferraum.")
        val r2 = runBlocking { engine(twice, words = listOf(trunk)).translate(TranslationEngine.Request("The trunk and another trunk.", en, de, Role.FINAL)) }
        assertEquals(1, twice.prompts.size)
        assertEquals(2, r2.words.single().occurrences)
        assertEquals(Status.CONFIRMED, r2.words.single().status)
    }

    @Test fun hintInThePreviousSentenceAndTappedMeaning() {
        val s = ScriptedSession("Then we watch the news.")
        val r = translate(engine(s), "Dann schauen wir die Nachrichten.", previous = "Um acht schalten wir den Fernseher ein.")
        assertTrue(s.prompts.single().contains("Nachrichten translates to news"))
        assertFalse("the sentence before never goes into the prompt", s.prompts.single().contains("Fernseher"))
        assertEquals(Reason.HINT_BEFORE, r.words.single().reason)
        val t = ScriptedSession("Then we read the messages.")
        val forced = translate(engine(t), "Dann schauen wir die Nachrichten.", previous = "Um acht schalten wir den Fernseher ein.", forced = mapOf(NACHRICHTEN.key to 0))
        assertTrue(t.prompts.single().contains("Nachrichten translates to messages"))
        assertEquals(Reason.FORCED, forced.words.single().reason)
    }

    @Test fun switchedOffOrLeftOut() {
        val off = ScriptedSession("The news")
        assertTrue(translate(engine(off, on = false), "Die Nachrichten").words.isEmpty())
        assertFalse(off.prompts.single().contains("Reference"))
        val left = ScriptedSession("The news")
        assertTrue(translate(engine(left), "Die Nachrichten", useWords = false).words.isEmpty())
        assertFalse(left.prompts.single().contains("Reference"))
    }

    @Test fun theBenchmarkBringsItsOwnEntry() = runBlocking {
        val s = ScriptedSession("Have you read my messages?")
        val r = engine(s, words = emptyList()).translate(
            TranslationEngine.Request("Hast du meine Nachrichten gelesen?", de, en, Role.TEXT, useWords = true, words = listOf(NACHRICHTEN)),
        )
        assertEquals(Status.CONFIRMED, r.words.single().status)
    }

    @Test fun miLmmtOnlyChecksAndCorrects() {
        val s = ScriptedSession("The news are not arriving.")
        val r = translate(engine(s, model = ModelCatalog.MILMMT_1B), "Die Nachrichten kommen nicht an.")
        assertEquals("Translate this from German to English:\nGerman: Die Nachrichten kommen nicht an.\nEnglish:", s.prompts.single())
        assertEquals("The messages are not arriving.", r.text)
        assertEquals(Status.CORRECTED, r.words.single().status)
    }

    @Test fun textsAreCheckedPieceByPiece() {
        // (short sentences of a paragraph are translated together; paragraphs one by one)
        val s = ScriptedSession("The messages are missing.", "We sat on a bench.")
        val r = translate(engine(s), "Die Nachrichten fehlen.\nWir sassen auf einer Bank.", role = Role.TEXT)
        assertEquals("The messages are missing.\nWe sat on a bench.", r.text)
        assertEquals(2, s.prompts.size)
        assertTrue(s.prompts[0].contains("Nachrichten translates to messages") && !s.prompts[0].contains("Bank translates"))
        assertTrue(s.prompts[1].contains("Bank translates to bench") && !s.prompts[1].contains("Nachrichten translates"))
        assertEquals(listOf(Status.CONFIRMED, Status.CONFIRMED), r.words.map { it.status })
    }
}
