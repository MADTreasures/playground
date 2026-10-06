package ch.madtreasures.fluency.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {
    private val ch = Languages.require("de-CH")
    private val de = Languages.require("de")

    @Test fun swissGermanNeverUsesEszett() {
        assertEquals("Die Strasse ist gross, Grüsse aus der STRASSE", TextNormalizer.forLanguage("Die Straße ist groß, Grüße aus der STRAẞE", ch))
    }

    @Test fun standardGermanKeepsEszett() {
        assertEquals("Die Straße ist groß", TextNormalizer.forLanguage("Die Straße ist groß", de))
    }

    @Test fun otherLanguagesUntouched() {
        assertEquals("Straße", TextNormalizer.forLanguage("Straße", Languages.require("en")))
        assertEquals("Straße", TextNormalizer.forLanguage("Straße", null))
    }

    @Test fun stripsEchoedLanguageNameAndQuotes() {
        assertEquals("Guten Morgen", TextNormalizer.cleanTranslation("German: Guten Morgen", "Good morning", listOf("German")))
        assertEquals("Guten Morgen", TextNormalizer.cleanTranslation("\"Guten Morgen\"", "Good morning", listOf("German")))
        assertEquals("\"Hallo\"", TextNormalizer.cleanTranslation("\"Hallo\"", "\"Hello\"", listOf("German")))
    }

    @Test fun comparableIgnoresCaseAndPunctuation() {
        assertEquals(TextNormalizer.comparable("Hallo, Welt!"), TextNormalizer.comparable("hallo welt"))
    }
}

class TextSegmenterTest {
    @Test fun keepsShortSentencesTogetherAndParagraphsApart() {
        val pieces = TextSegmenter.split("Hallo. Wie geht's?\n\nMir geht es gut.")
        assertEquals(listOf("Hallo. Wie geht's?", "", "Mir geht es gut."), pieces.map { it.text })
        assertEquals("Hallo. Wie geht's?\n\nMir geht es gut.", pieces.joinToString("") { it.text + it.separatorAfter })
    }

    @Test fun splitsLongTextAtSentenceBoundaries() {
        val sentence = "Das ist ein ziemlich langer Satz mit vielen Wörtern darin."
        val text = List(20) { sentence }.joinToString(" ")
        val pieces = TextSegmenter.split(text, maxChars = 200)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.text.length <= 200 })
        assertTrue(pieces.all { it.text.endsWith(".") })
    }

    @Test fun hardSplitsGiantSentences() {
        val giant = (1..200).joinToString(" ") { "wort$it" }
        val pieces = TextSegmenter.split(giant, maxChars = 100)
        assertTrue(pieces.all { it.text.length <= 101 })
        assertEquals(giant.split(" ").size, pieces.flatMap { it.text.split(" ") }.size)
    }
}

class LanguageGuesserTest {
    @Test fun latinScriptByStopWords() {
        assertEquals("de", LanguageGuesser.guess("Ich bin heute nicht zu Hause, aber morgen schon."))
        assertEquals("en", LanguageGuesser.guess("I think we should meet at the station"))
        assertEquals("fr", LanguageGuesser.guess("Je ne sais pas, c'est pour vous"))
        assertEquals("it", LanguageGuesser.guess("Il treno per Milano non è in ritardo, grazie"))
        assertEquals("es", LanguageGuesser.guess("Hola, no está muy lejos de la estación"))
    }

    @Test fun nonLatinScripts() {
        assertEquals("ru", LanguageGuesser.guess("Привет, как дела?"))
        assertEquals("uk", LanguageGuesser.guess("Привіт, як справи? Їжа є"))
        assertEquals("ja", LanguageGuesser.guess("こんにちは、元気ですか"))
        assertEquals("ko", LanguageGuesser.guess("안녕하세요"))
        assertEquals("zh", LanguageGuesser.guess("你好，今天天气很好"))
        assertEquals("el", LanguageGuesser.guess("Καλημέρα σας"))
        assertEquals("ar", LanguageGuesser.guess("مرحبا كيف حالك"))
    }

    @Test fun unsureReturnsNull() {
        assertNull(LanguageGuesser.guess("12345 !!!"))
        assertNull(LanguageGuesser.guess(""))
    }

    @Test fun respectsCandidates() {
        assertEquals("de", LanguageGuesser.guess("Ich bin hier", listOf("de", "fr")))
        assertNull(LanguageGuesser.guess("Привет", listOf("de", "fr")))
    }
}

class LanguagesTest {
    @Test fun codesAreUnique() {
        assertEquals(Languages.all.size, Languages.all.map { it.code }.toSet().size)
    }

    @Test fun coreLanguagesAreFullyCovered() {
        for (code in listOf("de", "de-CH", "en", "fr", "it", "es", "pt")) {
            val l = Languages.require(code)
            assertTrue("$code Hy-MT2", l.hyMt)
            assertTrue("$code MiLMMT", l.milmmtName != null)
            assertTrue("$code Parakeet", l.parakeet)
            assertTrue("$code Whisper", l.whisper != null)
        }
    }

    @Test fun everyLanguageIsTranslatableByAtLeastOneModel() {
        for (l in Languages.all) assertTrue(l.code, l.hyMt || l.milmmtName != null)
    }

    @Test fun displayNamesUseSwissSpelling() {
        assertFalse(Languages.all.any { 'ß' in it.nameDe })
    }

    @Test fun pickerStartsWithCoreLanguages() {
        assertEquals(Languages.core, Languages.sortedForPicker.take(Languages.core.size))
        assertEquals(Languages.all.size, Languages.sortedForPicker.size)
    }
}
