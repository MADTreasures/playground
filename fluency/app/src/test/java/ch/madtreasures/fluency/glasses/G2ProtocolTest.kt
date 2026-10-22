package ch.madtreasures.fluency.glasses

import ch.madtreasures.fluency.glasses.G2Protocol.Incoming
import ch.madtreasures.fluency.glasses.G2Protocol.Request
import ch.madtreasures.fluency.glasses.G2Protocol.Source
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The messages of "fluency-g2@1" as the app on the glasses (Protocol.kt in MADTreasures/ER-G2-custom) writes and reads them. */
class G2ProtocolTest {

    @Test
    fun readsWhatTheGlassesSend() {
        assertEquals(Incoming.Hello("fluency-g2@1", "1.1.0"), G2Protocol.parse("""{"t":"hello","proto":"fluency-g2@1","app":"1.1.0"}"""))
        assertEquals(
            Incoming.Start(1, Request(Source.WATCH, "auto", "de-CH", speak = false, partials = true)),
            G2Protocol.parse("""{"t":"start","n":1,"source":"watch","from":"auto","to":"de-CH","speak":false,"partials":true}"""),
        )
        assertEquals(
            Incoming.Config(3, Request(Source.PHONE, "en", "de", speak = true, partials = false)),
            G2Protocol.parse("""{"t":"config","n":3,"source":"phone","from":"en","to":"de","speak":true,"partials":false}"""),
        )
        assertEquals(Incoming.Stop(2), G2Protocol.parse("""{"t":"stop","n":2}"""))
    }

    @Test
    fun missingOptionalFieldsTakeTheirDefaultsAndUnknownFieldsAreIgnored() {
        assertEquals(
            Incoming.Start(4, Request(Source.WATCH, "auto", "fr", speak = false, partials = true)),
            G2Protocol.parse("""{"t":"start","n":4,"source":"watch","to":"fr","colour":"green","later":{"x":1}}"""),
        )
    }

    @Test
    fun unknownTypesAndNoJsonAreIgnored() {
        assertNull(G2Protocol.parse("""{"t":"ping","n":1}"""))
        assertNull(G2Protocol.parse("""{"proto":"fluency-g2@1"}"""))
        assertNull(G2Protocol.parse("not json"))
        assertNull(G2Protocol.parse("[1,2]"))
        assertNull(G2Protocol.parse(""))
    }

    @Test
    fun aKnownTypeWithoutItsFieldsIsABadRequest() {
        assertEquals(Incoming.Bad(null, "„n“ fehlt"), G2Protocol.parse("""{"t":"start","source":"watch","to":"de"}"""))
        assertEquals(Incoming.Bad(5, "Unbekannte Tonquelle „glasses“"), G2Protocol.parse("""{"t":"start","n":5,"source":"glasses","to":"de"}"""))
        assertEquals(Incoming.Bad(6, "Zielsprache fehlt"), G2Protocol.parse("""{"t":"config","n":6,"source":"watch"}"""))
        assertEquals(Incoming.Bad(null, "„n“ fehlt"), G2Protocol.parse("""{"t":"stop","n":"zwei"}"""))
    }

    @Test
    fun writesWhatTheGlassesRead() {
        json(G2Protocol.welcome("1.9.0")).let {
            assertEquals("welcome", it.getString("t"))
            assertEquals("fluency-g2@1", it.getString("proto"))
            assertEquals("1.9.0", it.getString("app"))
        }
        json(G2Protocol.state(running = true, note = "Lädt die Modelle …", re = 1)).let {
            assertEquals("state", it.getString("t"))
            assertEquals(true, it.get("running"))
            assertEquals("Lädt die Modelle …", it.getString("note"))
            assertEquals(1, it.get("re"))
        }
        json(G2Protocol.state(running = false)).let {
            assertEquals(false, it.get("running"))
            assertFalse(it.has("note"))
            assertFalse(it.has("re"))
        }
        json(G2Protocol.utterance(17, "Where is the station?", "Wo ist der Bahnhof?", final = true, from = "en")).let {
            assertEquals("utt", it.getString("t"))
            assertEquals(17L, it.getLong("id"))
            assertEquals("Where is the station?", it.getString("src"))
            assertEquals("Wo ist der Bahnhof?", it.getString("text"))
            assertEquals(true, it.get("final"))
            assertEquals("en", it.getString("from"))
            assertFalse(it.has("gone"))
        }
        assertFalse(json(G2Protocol.utterance(18, "Hallo", "Salut", final = false, from = null)).has("from"))
        json(G2Protocol.gone(18)).let {
            assertEquals("utt", it.getString("t"))
            assertEquals(18L, it.getLong("id"))
            assertEquals(true, it.get("gone"))
            assertFalse(it.has("src"))
        }
        json(G2Protocol.error(G2Protocol.Code.NO_MODEL, "Kein Modell für Japanisch")).let {
            assertEquals("error", it.getString("t"))
            assertEquals("no_model", it.getString("code"))
            assertEquals("Kein Modell für Japanisch", it.getString("message"))
        }
    }

    @Test
    fun quotesLineBreaksAndOtherScriptsSurvive() {
        val text = "Er sagte: \"Grüezi\" \\ / 你好 😊\nZweite Zeile"
        val o = json(G2Protocol.utterance(1, text, text, final = true, from = null))
        assertEquals(text, o.getString("src"))
        assertEquals(text, o.getString("text"))
        assertTrue(G2Protocol.utterance(1, text, text, final = true, from = null).lines().size == 1)
    }

    private fun json(s: String) = JSONObject(s)
}
