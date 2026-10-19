package ch.madtreasures.fluency.words

import ch.madtreasures.fluency.core.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WordGroupsFormatTest {
    private val line = "bench, benches (Sitzbank) | bank, banks (Geldinstitut) < Bank, Bänke"

    @Test fun `a line has words with forms and notes and the words of the original`() {
        val g = WordGroups.parseLine(line)!!
        assertEquals(listOf("bench", "benches"), g.words[0].forms)
        assertEquals("Sitzbank", g.words[0].note)
        assertEquals(listOf("bank", "banks"), g.words[1].forms)
        assertEquals("Geldinstitut", g.words[1].note)
        assertEquals(listOf("Bank", "Bänke"), g.sources)
        assertEquals(line, WordGroups.formatLine(g))
    }

    @Test fun `comments, empty lines and single words are no groups`() {
        val groups = WordGroups.parse("# comment\n\n  \nnews\nnews | messages\n(only a note) | x\n")
        assertEquals(1, groups.size)
        assertEquals(listOf("news", "messages"), groups[0].words.map { it.text })
        assertTrue(groups[0].sources.isEmpty())
    }

    @Test fun `notes may contain brackets and commas, the arrow works as well as the angle bracket`() {
        val g = WordGroups.parseLine("messages (Mitteilungen (Chat, SMS)) | news (TV, Radio) ← Nachricht")!!
        assertEquals("Mitteilungen (Chat, SMS)", g.words[0].note)
        assertEquals(listOf("messages"), g.words[0].forms)
        assertEquals("TV, Radio", g.words[1].note)
        assertEquals(listOf("Nachricht"), g.sources)
        assertEquals(g, WordGroups.parseLine(WordGroups.formatLine(g)))
    }

    @Test fun `the file with its header reads back the same`() {
        val groups = listOf(
            WordGroups.parseLine(line)!!,
            WordGroup(listOf(GroupWord(listOf("light bulb")), GroupWord(listOf("pear"), "Frucht"))),
        )
        val text = WordGroups.fileText("Englisch (en)", groups)
        assertTrue(text.startsWith("# Fluency"))
        assertEquals(groups, WordGroups.parse(text))
    }

    @Test fun `typed words lose the characters of the format`() {
        assertEquals("bank banks", WordGroups.cleanTerm(" bank,banks "))
        assertEquals("a b c", WordGroups.cleanTerm("a|b<c"))
        assertEquals("Chat, SMS (kurz)", WordGroups.cleanNote("Chat, SMS (kurz)"))
        assertEquals("a b", WordGroups.cleanNote("a|b"))
    }

    @Test fun `merge adds only groups with new words`() {
        val a = WordGroups.parse("bench | bank\nnews | messages")
        val b = WordGroups.parse("Bank, banks | BENCH < Bank\ntime | weather")
        val merged = WordGroups.merge(a, b)
        assertEquals(3, merged.size)
        assertEquals("time", merged[2].words[0].text)
        // the same words for another language of the original are another entry
        assertEquals(4, WordGroups.merge(merged, listOf(a[0].copy(language = "es"))).size)
    }

    @Test fun `sections give the language of the original`() {
        val text = """
            |# comment
            |time | weather
            |
            |[de] Deutsch
            |bench | bank < Bank
            |[de-CH] Schweiz
            |cupboard | crate < Kasten
            |[*] alle
            |news | messages
            |[fr]
            |time | weather < temps
        """.trimMargin()
        val g = WordGroups.parse(text)
        assertEquals(listOf(null, "de", "de", null, "fr"), g.map { it.language })
        // written back: every language first, then in the order of the language pickers
        val written = WordGroups.format(g)
        assertEquals(
            "\n[*] Alle Sprachen\ntime | weather\nnews | messages\n\n[de] Deutsch\nbench | bank < Bank\ncupboard | crate < Kasten\n" +
                "\n[fr] Französisch\ntime | weather < temps\n",
            written,
        )
        assertEquals(g.sortedBy { it.language ?: "" }.toSet(), WordGroups.parse(written).toSet())
        // a list for every language only is written with its section too (files of 1.7.0 have none)
        assertEquals("\n[*] Alle Sprachen\ntime | weather\n", WordGroups.format(listOf(g[0])))
        assertTrue(WordGroups.hasSections(written))
        assertFalse(WordGroups.hasSections("# [x]\ntime | weather"))
    }
}

class WordGroupsSwapTest {
    private fun groups(text: String) = WordGroups.parse(text)
    private val bank = groups("bench, benches (Sitzbank) | bank, banks (Geldinstitut) < Bank")

    @Test fun `a word of a group gets a button with the other word`() {
        val s = WordGroups.swaps("I sat on a bench in the park.", "Ich sass im Park auf einer Bank.", bank)
        assertEquals(1, s.size)
        assertEquals("bench", s[0].found)
        assertEquals("bank", s[0].replacement)
        assertEquals("Geldinstitut", s[0].note)
        assertEquals("I sat on a bank in the park.", WordGroups.apply("I sat on a bench in the park.", s[0]))
    }

    @Test fun `the group needs its word in the original, endings and umlauts included`() {
        val t = "The bank is closed."
        assertTrue(WordGroups.swaps(t, "Das Geldinstitut ist zu.", bank).isEmpty())
        assertEquals(1, WordGroups.swaps(t, "Die Bank ist zu.", bank).size)
        assertEquals(1, WordGroups.swaps(t, "Die Banken sind zu.", bank).size)
        assertEquals(1, WordGroups.swaps(t, "Die Bänke sind nass.", bank).size)
        // a compound is another word
        assertTrue(WordGroups.swaps(t, "Mein Bankkonto ist leer.", bank).isEmpty())
        // without words of the original: always
        assertEquals(1, WordGroups.swaps(t, "egal", groups("bench | bank")).size)
    }

    @Test fun `forms correspond - the plural becomes the plural`() {
        val s = WordGroups.swaps("The banks were closed.", "Die Banken waren zu.", bank)
        assertEquals("banks", s.single().found)
        assertEquals("benches", s.single().replacement)
        // a word with fewer forms gives its first one
        val g = groups("Schlüssel (Türschlüssel) | Taste, Tasten (Tastatur)")
        assertEquals("Schlüssel", WordGroups.swaps("Drück die Taste.", "", g).single().replacement)
        assertEquals("Schlüssel", WordGroups.swaps("Drück die Tasten.", "", g).single().replacement)
    }

    @Test fun `only whole words`() {
        assertTrue(WordGroups.swaps("He went bankrupt at the riverbank.", "Bank", bank).isEmpty())
        assertEquals(1, WordGroups.swaps("A bank-holiday.", "Bank", bank).size)
    }

    @Test fun `capitals at the start of a sentence and in capital words, otherwise as in the list`() {
        assertEquals("Bench", WordGroups.swaps("Bank was closed.", "Bank", bank).single().replacement)
        assertEquals("Bench", WordGroups.swaps("It was late. „Bank“, he said.", "Bank", bank).single().replacement)
        assertEquals("BENCH", WordGroups.swaps("THE BANK", "Bank", bank).single().replacement)
        assertEquals("bench", WordGroups.swaps("the Bank of England", "Bank", bank).single().replacement)
        val g = groups("Licht (Helligkeit) | leicht (nicht schwer) < light")
        assertEquals("leicht", WordGroups.swaps("Die Kiste ist Licht.", "The box is light.", g).single().replacement)
        assertEquals("Licht", WordGroups.swaps("Mach das leicht an.", "Turn on the light.", g).single().replacement)
        assertEquals("Leicht", WordGroups.swaps("Licht ist die Kiste.", "light", g).single().replacement)
    }

    @Test fun `ss and ß are the same, Swiss Standard German writes ss`() {
        val g = groups("Massstab | Waage < scale")
        val found = WordGroups.swaps("Der Maßstab ist falsch.", "The scale is wrong.", g).single()
        assertEquals("Maßstab", found.found)
        assertEquals("Waage", found.replacement)
        val back = groups("Waage | Maßstab")
        assertEquals("Massstab", WordGroups.swaps("Die Waage", "", back, swissSpelling = true).single().replacement)
        assertEquals("Maßstab", WordGroups.swaps("Die Waage", "", back).single().replacement)
    }

    @Test fun `the longer word wins where words overlap`() {
        val g = groups("office | desk\npost office | position")
        val s = WordGroups.swaps("I went to the post office.", "", g)
        assertEquals(listOf("post office" to "position"), s.map { it.found to it.replacement })
        assertEquals("office", WordGroups.swaps("my office", "", g).single().found)
    }

    @Test fun `every place of a word has its own buttons, in reading order`() {
        val s = WordGroups.swaps("The bank near the bank.", "Bank", bank)
        assertEquals(2, s.size)
        assertEquals(listOf(4..7, 18..21), s.map { it.range })
        assertEquals("The bank near the bench.", WordGroups.apply("The bank near the bank.", s[1]))
    }

    @Test fun `groups of a language of the original are only offered for it, Swiss German counts as German`() {
        val g = listOf(WordGroup(listOf(GroupWord(listOf("bench")), GroupWord(listOf("bank"))), listOf("Bank"), "de"))
        assertEquals(1, WordGroups.swaps("the bank", "die Bank", g, sourceLanguage = "de").size)
        assertEquals(1, WordGroups.swaps("the bank", "die Bank", g, sourceLanguage = "de-CH").size)
        assertTrue(WordGroups.swaps("the bank", "la Bank", g, sourceLanguage = "fr").isEmpty())
        // unknown (automatic): every language
        assertEquals(1, WordGroups.swaps("the bank", "die Bank", g, sourceLanguage = null).size)
        // groups for every language are always offered
        assertEquals(1, WordGroups.swaps("the bank", "le banc", listOf(g[0].copy(language = null, sources = emptyList())), sourceLanguage = "fr").size)
    }

    @Test fun `a word in two groups offers the words of both`() {
        val g = groups("bank | bench < Bank\nbank | shore < Ufer")
        assertEquals(listOf("bench"), WordGroups.swaps("the bank", "Bank", g).map { it.replacement })
        assertEquals(listOf("bench", "shore"), WordGroups.swaps("the bank", "die Bank am Ufer", g).map { it.replacement })
    }

    @Test fun `three words give two buttons each`() {
        val g = groups("train (Eisenbahn) | move (Spielzug) | draught (Luftzug) < Zug")
        assertEquals(listOf("move", "draught"), WordGroups.swaps("The train left.", "Der Zug fuhr ab.", g).map { it.replacement })
    }

    @Test fun `a changed text is not touched`() {
        val s = WordGroups.swaps("the bank", "Bank", bank).single()
        assertNull(WordGroups.apply("the bench", s))
        assertNull(WordGroups.apply("the", s))
        assertEquals(4..8, WordGroups.replacedRange(s))
    }

    @Test fun `phrases, apostrophes and languages without spaces`() {
        val en = groups("light bulb | pear < Birne")
        assertEquals("light  bulb", WordGroups.swaps("a light  bulb", "eine Birne", en).single().found)
        val fr = groups("l'eau | la mer")
        assertEquals("l’eau", WordGroups.swaps("Je bois l’eau.", "", fr).single().found)
        val zh = groups("银行 | 长椅 < Bank")
        assertEquals("银行", WordGroups.swaps("我去银行了。", "Ich ging zur Bank.", zh).single().found)
        val ko = groups("은행 | 벤치")
        assertEquals("은행", WordGroups.swaps("은행에서 만나요", "", ko).single().found)
    }

    @Test fun `words of the original with endings`() {
        assertTrue(WordGroups.sameWord("banken", "bank"))
        assertTrue(WordGroups.sameWord("banke", "bank"))
        assertTrue(WordGroups.sameWord("nachricht", "nachrichten"))
        assertFalse(WordGroups.sameWord("bankkonto", "bank"))
        assertFalse(WordGroups.sameWord("ba", "bank"))
        assertTrue(WordGroups.mentions("Die Schlösser sind alt.", listOf("Schloss")))
        assertTrue(WordGroups.mentions("Il a acheté une pêche.", listOf("peche")))
        assertTrue(WordGroups.mentions("Ich gehe zum Strauß.", listOf("Strauss")))
        assertTrue(WordGroups.mentions("Il va à la poste centrale.", listOf("la poste")))
        assertFalse(WordGroups.mentions("Ich gehe zur Post.", listOf("Bank")))
    }
}

class WordGroupStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val start = mapOf(
        "en" to "# start\nbench | bank < Bank\n",
        "de" to "Bank | Ufer < bank\n",
    )

    private fun store() = WordGroupStore(tmp.root) { start[it] }

    @Test fun `the start list is used until the user changes the list`() {
        val s = store()
        assertEquals(1, s.groups("en").size)
        assertFalse(s.isChanged("en"))
        assertTrue(s.hasDefaults("en"))
        assertFalse(s.hasDefaults("nl"))
        assertTrue(s.groups("nl").isEmpty())
        s.save("en", s.groups("en") + WordGroups.parse("news | messages"))
        assertTrue(s.isChanged("en"))
        assertEquals(2, store().groups("en").size)
        assertTrue(s.file("en").readText().startsWith("# Fluency – doppeldeutige Wörter: Englisch (en)"))
        s.reset("en")
        assertFalse(s.isChanged("en"))
        assertEquals(1, s.groups("en").size)
    }

    @Test fun `every language has its own file, Swiss German uses the German one`() {
        val s = store()
        s.save("fr", WordGroups.parse("banc | banque"))
        assertEquals(setOf("fr.txt"), tmp.root.list()!!.toSet())
        assertEquals(s.file("de"), s.file("de-CH"))
        assertEquals(s.groups("de"), s.groups("de-CH"))
        s.save("de-CH", WordGroups.parse("Bank | Ufer\nSchloss | Burg"))
        assertEquals(2, s.groups("de").size)
        assertFalse(WordGroupStore.languages.any { it.code == "de-CH" })
        assertTrue(WordGroupStore.languages.any { it.code == "de" })
    }

    @Test fun `a list of 1_7_0 gets the languages of the original from the start list`() {
        val s = WordGroupStore(tmp.root) {
            if (it == "en") "[de] Deutsch\nbench, benches | bank, banks < Bank\n[es] Spanisch\nbank | bench < banco\n[fr] Französisch\ntime | weather < temps\n" else null
        }
        // 1.7.0: no sections, groups of several languages merged, one group changed, one added, one deleted
        s.file("en").writeText("# Fluency\nbench (Sitzbank) | bank (Geldinstitut) < Bank, banco, Sitzbank\nnews | messages < Nachrichten\n")
        val g = s.groups("en")
        assertEquals(
            listOf(
                "de" to "bench (Sitzbank) | bank (Geldinstitut) < Bank, Sitzbank",
                "es" to "bench (Sitzbank) | bank (Geldinstitut) < banco, Sitzbank",
                null to "news | messages < Nachrichten",
            ),
            g.map { it.language to WordGroups.formatLine(it) },
        )
        // written in the new format once
        assertTrue(WordGroups.hasSections(s.file("en").readText()))
        assertEquals(g.toSet(), WordGroupStore(tmp.root) { null }.groups("en").toSet())
    }

    @Test fun `changes are counted, empty lists stay empty`() {
        val s = store()
        val before = s.changes.value
        s.save("en", emptyList())
        assertTrue(s.groups("en").isEmpty())
        assertTrue(store().groups("en").isEmpty())
        assertEquals(before + 1, s.changes.value)
    }

    @Test fun `adding skips groups that are there, text is parsed`() {
        val s = store()
        assertEquals(1, s.add("en", WordGroups.parse("bank | bench\ntime | weather")))
        assertEquals(0, s.add("en", WordGroups.parse("time | weather")))
        assertEquals(listOf("bench", "time"), s.groups("en").map { it.words[0].text })
        val parsed = s.saveText("en", "# x\nnews | messages\nnot a group\n")
        assertEquals(1, parsed.size)
        assertEquals(1, s.groups("en").size)
    }

    @Test fun `replacements for a translation use the list of the target language`() {
        val s = store()
        val en = Languages.require("en")
        assertEquals("bank", s.swaps("a bench", "eine Bank", en).single().replacement)
        val ch = Languages.require("de-CH")
        s.save("de", WordGroups.parse("Bank | Ufer\nWaage | Maßstab"))
        assertEquals("Massstab", s.swaps("Die Waage", "scale", ch).single().replacement)
    }
}

class LegacyWordsTest {
    @Test fun `own entries of 1_6_0 become groups of every target language`() {
        val old = "2|de|Schild|Schilder|M|Verkehrsschild~Strasse~en=sign/road sign;fr=panneau|Schutzschild~Ritter~en=shield;fr=bouclier"
        val g = LegacyWords.groups(old)
        assertEquals(setOf("en", "fr"), g.keys)
        val en = g.getValue("en").single()
        assertEquals("de", en.language)
        assertEquals(listOf("sign", "shield"), en.words.map { it.text })
        assertEquals(listOf("Verkehrsschild", "Schutzschild"), en.words.map { it.note })
        assertEquals(listOf("Schild", "Schilder"), en.sources)
    }

    @Test fun `the built-in collection, single meanings and lines of 1_5_0`() {
        val old = listOf(
            "2|de|Bank||M|Sitzbank~Park~en=bench|Geldinstitut~Geld~en=bank",
            "2|de|Bremsklotz||M|Klotz~~en=brake pad",
            "de>en|Hut|hat:Kopf|guard:Wache",
            "en>de-CH|boot|Stiefel:Fuss|Kofferraum:Auto",
        ).joinToString("\n")
        val g = LegacyWords.groups(old)
        assertEquals(listOf("hat", "guard"), g.getValue("en").single().words.map { it.text })
        assertEquals(listOf("Hut"), g.getValue("en").single().sources)
        // Swiss Standard German goes into the German list
        assertEquals(listOf("Stiefel", "Kofferraum"), g.getValue("de").single().words.map { it.text })
        assertEquals("en", g.getValue("de").single().language)
    }

    @Test fun `German translations go first, Swiss ones only without them`() {
        val old = "2|en|chips||M|Pommes~~de=Pommes frites;de-CH=Pommes frites|Chips~~de=Kartoffelchips;de-CH=Chips"
        assertEquals(listOf("Pommes frites", "Kartoffelchips"), LegacyWords.groups(old).getValue("de").single().words.map { it.text })
    }
}
