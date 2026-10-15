package ch.madtreasures.fluency.integration

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.AmbiguousWord
import ch.madtreasures.fluency.engine.llm.AmbiguousWords
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import ch.madtreasures.fluency.engine.llm.WordCollection
import ch.madtreasures.fluency.engine.llm.WordUse
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The module "Mehrdeutige Wörter" with the built-in collection on the real Hy-MT2 (x86 host, same
 * JNI code as the app) - nothing entered by the user. Measured: how often the translation has a
 * wrong meaning with and without the module, whether the module ever makes a right translation
 * wrong, and that sentences without a listed word stay exactly the same.
 */
class AmbiguousWordsIntegrationTest {
    /** a sentence with an ambiguous word of the collection: [label] is the meaning that is right; [before]: the sentence before */
    data class Case(val src: String, val tgt: String, val word: String, val label: String, val text: String, val before: String? = null)

    companion object {
        /** the 40 sentences of the earlier measurement (the 41st, "charge", is not in the collection), with their sentence before */
        val EARLIER = listOf(
            Case("de", "en", "Nachrichten", "Mitteilungen (Chat, SMS)", "Die Nachrichten kommen aber nicht an.", "Ich schreibe meinem Bruder gerade über die App."),
            Case("de", "en", "Nachrichten", "Mitteilungen (Chat, SMS)", "Hast du meine Nachrichten nicht gelesen?", "Ich habe dir gestern auf WhatsApp geschrieben."),
            Case("de", "en", "Nachrichten", "Neuigkeiten (TV, Radio)", "Dann schauen wir die Nachrichten.", "Um acht Uhr schalten wir immer den Fernseher ein."),
            Case("de", "en", "Nachrichten", "Mitteilungen (Chat, SMS)", "Aber wenn ich eine kurze Pause mache, werden die Nachrichten getrennt.", "Ich habe die Übersetzungs-App getestet. Das Modell ist sehr schnell."),
            Case("de", "en", "Nachrichten", "Mitteilungen (Chat, SMS)", "Aber das wenn ich eine kurze Pause mache, dass die Nachrichten getrennt werden.", "Modell ist sehr schnell, also ich merke."),
            Case("de", "en", "Bank", "Sitzbank", "Wir haben uns auf eine Bank gesetzt.", "Wir sind im Park spazieren gegangen und waren müde."),
            Case("de", "en", "Bank", "Geldinstitut", "Morgen gehe ich zur Bank.", "Ich brauche einen Kredit für das Auto."),
            Case("de", "en", "Schloss", "Türschloss", "Das Schloss klemmt schon wieder.", "Ich komme nicht in meine Wohnung."),
            Case("de", "en", "Schloss", "Burg, Palast", "Das Schloss war wunderschön.", "In den Ferien waren wir in Bayern."),
            Case("de", "en", "Leiter", "Gerät zum Hochsteigen", "Hast du eine Leiter?", "Die Glühbirne an der Decke ist kaputt."),
            Case("de", "en", "Leiter", "Chef, Verantwortlicher", "Ich muss mit dem Leiter sprechen.", "Ich habe heute ein Problem mit meinem Projekt."),
            Case("de", "en", "Decke", "Bettdecke", "Kannst du mir die Decke geben?", "Mir ist so kalt auf dem Sofa."),
            Case("de", "en", "Decke", "Zimmerdecke", "Morgen streichen wir die Decke.", "Wir renovieren gerade das Wohnzimmer."),
            Case("de", "en", "Zug", "Spielzug", "Das war ein guter Zug.", "Wir spielen gerade Schach."),
            Case("de", "en", "Gericht", "Speise", "Das Gericht war ausgezeichnet.", "Wir waren gestern in einem italienischen Restaurant."),
            Case("de", "en", "Gericht", "Justiz", "Nächste Woche muss ich vor Gericht.", "Mein Nachbar hat mich wegen des Zauns verklagt."),
            Case("de", "en", "Birne", "Glühbirne", "Die Birne ist durchgebrannt.", "Im Flur ist es ganz dunkel."),
            Case("de", "en", "Steuer", "Lenkrad", "Er lässt mich nur ungern ans Steuer.", "Ich fahre heute das Auto meines Vaters."),
            Case("de", "en", "Schlange", "Warteschlange", "Ich musste zwanzig Minuten in der Schlange stehen.", "Im Supermarkt war heute extrem viel los."),
            Case("de", "en", "Hahn", "Wasserhahn", "Der Hahn ist undicht.", "In der Küche tropft es die ganze Nacht."),
            Case("de", "en", "Kater", "nach zu viel Alkohol", "Heute habe ich einen Kater.", "Gestern Abend haben wir viel zu viel getrunken."),
            Case("de", "en", "Mutter", "Schraubenmutter", "Mir fehlt noch eine Mutter für diese Schraube.", "Ich baue gerade das Regal zusammen."),
            Case("de", "en", "Strom", "Elektrizität", "Der Strom war zwei Stunden lang weg.", "Gestern Abend war es im ganzen Quartier dunkel."),
            Case("de", "en", "Absatz", "am Schuh", "Dabei ist mein Absatz abgebrochen.", "Ich bin auf der Treppe gestolpert."),
            Case("de", "en", "Blatt", "Blatt Papier", "Kannst du mir ein neues Blatt geben?", "Der Drucker hat schon wieder einen Papierstau."),
            Case("de", "en", "Note", "Schulnote", "Leider war die Note schlecht.", "Ich habe die Mathematikprüfung zurückbekommen."),
            Case("en", "de", "bill", "Rechnung", "Could you ask for the bill, please?", "We had a wonderful dinner at the restaurant."),
            Case("en", "de", "bat", "Fledermaus", "Suddenly a bat flew right past my head.", "We explored the cave with our flashlights."),
            Case("en", "de", "bat", "Schläger", "He needs a new bat.", "My son started playing baseball this summer."),
            Case("en", "de", "fine", "Busse", "I had to pay a fine.", "The police stopped me because I was driving too fast."),
            Case("en", "de", "date", "Verabredung", "Tomorrow we have our first date.", "I met a nice woman last week."),
            Case("en", "de", "match", "Streichholz", "Do you have a match?", "The power went out and the house was completely dark."),
            Case("en", "de", "spring", "Sprungfeder", "I can feel a spring in my back.", "This old mattress is really uncomfortable."),
            Case("en", "de", "trunk", "Kofferraum", "Put the suitcases in the trunk.", "We are packing the car for the holidays."),
            Case("en", "de", "crane", "Kranich", "We saw a crane standing in the water.", "We went birdwatching at the lake this morning."),
            Case("en", "de", "pupil", "Schüler", "Her best pupil won the competition.", "The teacher was very proud today."),
            Case("en", "de", "key", "Taste", "One key is stuck.", "My old piano needs to be repaired."),
            Case("en", "de", "tip", "Trinkgeld", "Let's leave him a good tip.", "The waiter was very friendly and fast."),
            Case("en", "de", "change", "Wechselgeld", "The cashier gave me the wrong change.", "I paid with a fifty franc note."),
            Case("en", "de", "glasses", "Brille", "Where are my glasses?", "I can't read the menu without help."),
        )

        /** new sentences in other directions and languages (written for this test, not used to choose the hints) */
        val LANGUAGES = listOf(
            Case("de", "fr", "Bank", "Sitzbank", "Wir haben uns im Park auf eine Bank gesetzt."),
            Case("de", "it", "Bank", "Geldinstitut", "Ich muss noch Geld auf der Bank abheben."),
            Case("de", "it", "Hahn", "Wasserhahn", "Der Hahn in der Küche tropft schon wieder."),
            Case("de", "es", "Schloss", "Türschloss", "Das Schloss an der Tür klemmt."),
            Case("de", "fr", "Leiter", "Chef, Verantwortlicher", "Morgen habe ich ein Gespräch mit dem Leiter der Abteilung."),
            Case("de", "it", "Decke", "Bettdecke", "Mir ist kalt, gib mir bitte die Decke."),
            Case("de", "fr", "Zug", "Stadt Zug", "Meine Schwester wohnt in Zug."),
            Case("de", "en", "Pass", "Bergpass", "Der Gotthard ist im Winter gesperrt, der Pass ist zu."),
            Case("de", "en", "Stift", "Lehrling (Schweiz)", "Unser neuer Stift beginnt im August seine Lehre."),
            Case("de", "en", "Estrich", "Dachboden (Schweiz)", "Die Weihnachtskisten sind im Estrich."),
            Case("de", "fr", "zügeln", "umziehen (Schweiz)", "Wir zügeln nächsten Monat nach Bern."),
            Case("de", "en", "Kasten", "Schrank (Schweiz)", "Die Pullover sind im Kasten im Schlafzimmer."),
            Case("en", "de", "key", "Schlüssel", "I lost the key to the front door."),
            Case("en", "fr", "bill", "Rechnung", "The waiter brought the bill after dinner."),
            Case("en", "es", "crane", "Kranich", "A crane with long legs was flying over the lake."),
            Case("en", "it", "tip", "Ratschlag", "Thanks for the tip, that advice was really helpful."),
            Case("en", "de", "seal", "Robbe", "At the zoo we watched a seal swimming."),
            Case("fr", "de", "avocat", "Anwalt", "Mon avocat m'a défendu au tribunal."),
            Case("fr", "de", "avocat", "Avocado", "Je mange un avocat bien mûr avec du sel."),
            Case("fr", "en", "vol", "Flug", "Le vol pour Londres a du retard à cause de l'avion."),
            Case("fr", "en", "vol", "Diebstahl", "Il y a eu un vol dans mon appartement, la police est venue."),
            Case("fr", "de", "temps", "Wetter", "Quel temps fait-il ? Il pleut depuis ce matin."),
            Case("fr", "de", "glace", "Spiegel", "Elle se regarde dans la glace de la salle de bain."),
            Case("it", "de", "riso", "Reis", "Stasera cuciniamo un risotto, compra il riso."),
            Case("it", "en", "calcio", "Fusstritt", "Mi ha dato un calcio sulla gamba."),
            Case("it", "de", "borsa", "Börse", "La borsa di Milano ha perso molto oggi, le azioni sono crollate."),
            Case("es", "de", "gato", "Wagenheber", "Necesito el gato para cambiar la rueda del coche."),
            Case("es", "en", "muñeca", "Handgelenk", "Me duele la muñeca, no puedo llevar el reloj."),
            Case("es", "de", "cola", "Warteschlange", "Hay que hacer cola en el supermercado."),
            Case("pt", "de", "manga", "Ärmel", "A manga da camisa está rasgada."),
            Case("pt", "en", "manga", "Mango", "Comprei uma manga madura para fazer suco."),
            Case("pt", "de", "rede", "Hängematte", "Vou dormir na rede da varanda."),
        )

        private val engine = TestEnv.engine(AppSettings(llmThreads = 4, ambiguousWords = AmbiguousWords.format(WordCollection.entries)))

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
        previous: List<String> = emptyList(),
        forced: Map<String, Int> = emptyMap(),
        useWords: Boolean = true,
    ): TranslationEngine.Result = runBlocking {
        // an empty cache for every sentence: the numbers do not depend on the order
        engine.resetCaches()
        engine.translate(
            TranslationEngine.Request(
                text, Languages.require(src), Languages.require(tgt), Role.FINAL, ModelCatalog.HY_MT2,
                useWords = useWords, previousTexts = previous, forcedWords = forced,
            ),
        )
    }

    private fun entry(c: Case): AmbiguousWord = WordCollection.entries.first { it.word == c.word && AmbiguousWords.sameLanguage(it.language, c.src) }

    /** right: the meaning's translation is there and no other one; wrong: another meaning's translation is there */
    private data class Score(val right: Boolean, val wrong: Boolean)

    private fun score(c: Case, output: String): Score {
        val e = entry(c)
        val i = e.meanings.indexOfFirst { it.label == c.label }
        check(i >= 0) { "${c.word}: ${c.label}" }
        val accepted = e.meanings[i].into(c.tgt)
        val others = e.meanings.filterIndexed { j, _ -> j != i }.flatMap { it.into(c.tgt) }.filter { o -> accepted.none { it.equals(o, true) } }
        val wrong = AmbiguousWords.containsAny(output, others, c.tgt)
        return Score(AmbiguousWords.containsAny(output, accepted, c.tgt) && !wrong, wrong)
    }

    private fun describe(r: TranslationEngine.Result) =
        r.words.joinToString { "${it.from} → ${it.to ?: "?"} (${it.reasonLabel}, ${it.statusLabel}${if (it.doubtful) ", Warnung" else ""})" }

    /** runs [cases] with and without the module and prints one line per sentence */
    private fun measure(title: String, cases: List<Case>, withBefore: Boolean): Map<String, Int> {
        val n = linkedMapOf("Sätze" to cases.size)
        for (c in cases) {
            val previous = if (withBefore) listOfNotNull(c.before) else emptyList()
            val plain = t(c.text, c.src, c.tgt, useWords = false)
            val r = t(c.text, c.src, c.tgt, previous = previous)
            val p = score(c, plain.text)
            val m = score(c, r.text)
            val u = r.words.firstOrNull { it.word == c.word }
            fun count(key: String, yes: Boolean) { if (yes) n.merge(key, 1, Int::plus) }
            count("ohne Modul richtig", p.right)
            count("ohne Modul falsche Bedeutung", p.wrong)
            count("mit Modul richtig", m.right)
            count("mit Modul falsche Bedeutung", m.wrong)
            count("verschlechtert", p.right && !m.right)
            count("verbessert", !p.right && m.right)
            count("Bedeutung vorgegeben", u?.chosen == true)
            count("Modell entschied", u?.chosen == false)
            count("Warnung", u?.doubtful == true)
            count("Warnung berechtigt", u?.doubtful == true && !m.right)
            val mark = when {
                p.right && !m.right -> "SCHLECHTER"
                !p.right && m.right -> "besser"
                m.right -> "richtig"
                else -> "falsch"
            }
            println("%-10s %s→%s %-60s ohne: %-55s mit: %s   [%s]".format(mark, c.src, c.tgt, c.text, plain.text, r.text, describe(r)))
            if (r.words.none { it.chosen }) assertEquals("nothing given: the same translation", plain.text, r.text)
        }
        println("$title: $n")
        return n
    }

    @Test fun theEarlierSentencesWithTheCollection() {
        val n = measure("40 Sätze der früheren Messung (Satz davor nur als Hinweis)", EARLIER, withBefore = true)
        // the module never turns a right translation into a wrong one, and never adds wrong meanings
        assertEquals(0, n["verschlechtert"] ?: 0)
        assertTrue(n.toString(), (n["mit Modul falsche Bedeutung"] ?: 0) <= (n["ohne Modul falsche Bedeutung"] ?: 0))
        assertTrue(n.toString(), (n["mit Modul richtig"] ?: 0) > (n["ohne Modul richtig"] ?: 0))
    }

    @Test fun otherLanguagesAndDirections() {
        val n = measure("Neue Sätze in anderen Sprachen", LANGUAGES, withBefore = false)
        assertEquals(0, n["verschlechtert"] ?: 0)
        assertTrue(n.toString(), (n["mit Modul falsche Bedeutung"] ?: 0) <= (n["ohne Modul falsche Bedeutung"] ?: 0))
    }

    @Test fun rulesEndToEnd() {
        // a hint word in the sentence (the live screenshot)
        val park = t("Wir haben uns im Park auf eine Bank gesetzt.")
        println("${park.text}  [${describe(park)}]")
        assertEquals(WordUse.Reason.HINT, park.words.single().reason)
        assertTrue(park.text, park.text.contains("bench", ignoreCase = true))
        // a hint word in the sentence before: the model decides, the chip warns if it took the other meaning
        val chat = t("Hast du meine Nachrichten schon gelesen?", previous = listOf("Ich habe dir vorhin im Chat geschrieben."))
        println("${chat.text}  [${describe(chat)}]")
        val u = chat.words.single()
        assertEquals(WordUse.Reason.MODEL, u.reason)
        assertEquals(u.to == "news", u.doubtful)
        // tapped: the meaning is given and checked
        val key = WordCollection.entries.first { it.word == "Nachrichten" }.key
        val tapped = t("Hast du meine Nachrichten schon gelesen?", forced = mapOf(key to 0))
        println("${tapped.text}  [${describe(tapped)}]")
        assertTrue(tapped.text, tapped.text.contains("message", ignoreCase = true))
        assertTrue(tapped.words.single().status in setOf(WordUse.Status.CONFIRMED, WordUse.Status.CONFIRMED_AFTER_HINT))
        // remembered for the conversation
        val later = runBlocking {
            engine.resetCaches()
            engine.translate(TranslationEngine.Request("Die Nachrichten sind angekommen.", Languages.require("de"), Languages.require("en"), Role.FINAL, ModelCatalog.HY_MT2, wordChoices = mapOf(key to 0)))
        }
        println("${later.text}  [${describe(later)}]")
        assertEquals(WordUse.Reason.CHOICE, later.words.single().reason)
        assertTrue(later.text, later.text.contains("message", ignoreCase = true))
    }

    /** With the whole collection: sentences without a listed word get the same prompt, so the same translation. */
    @Test fun sentencesWithoutListedWordStayExactlyTheSame() {
        val sentences = listOf(
            Triple("de", "en", "Ich gehe jetzt einkaufen."),
            Triple("de", "en", "Ich war mit meinen Kindern im Zoo."),
            Triple("de", "en", "Kannst du mir eine Tablette geben?"),
            Triple("de", "en", "Könnten Sie mir bitte sagen, wie ich am schnellsten zum Hauptbahnhof komme?"),
            // compound words and verbs do not count as the listed noun
            Triple("de", "en", "Gestern gab es einen Bankraub in der Stadt."),
            Triple("de", "en", "Ich habe dir eine Sprachnachricht geschickt."),
            Triple("de", "en", "Wir müssen das Boot gut steuern."),
            Triple("en", "de", "Please bring your laptop."),
            Triple("en", "de", "The meeting has been moved to Thursday afternoon because two colleagues are still on vacation."),
            Triple("fr", "de", "Je voudrais réserver une table pour quatre personnes ce soir à vingt heures."),
            Triple("it", "en", "Il treno per Milano è in ritardo di circa venti minuti a causa di un guasto tecnico."),
        )
        for ((src, tgt, text) in sentences) {
            val with = t(text, src, tgt)
            val without = t(text, src, tgt, useWords = false)
            println("$text  ⇒  ${with.text}  [${describe(with)}]")
            assertTrue(with.words.none { it.chosen })
            assertEquals(without.text, with.text)
            assertEquals(without.promptTokens, with.promptTokens)
        }
    }
}
