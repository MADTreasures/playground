package ch.madtreasures.fluency.glasses

import ch.madtreasures.fluency.settings.AUTO
import org.json.JSONException
import org.json.JSONObject

/**
 * The messages between Fluency and its app on the G2 glasses, "fluency-g2@1" (the other side is
 * packages/fluency in MADTreasures/ER-G2-custom, PLATTFORM.md §7): one JSON object per message, its type
 * in "t". Both sides ignore types and fields they do not know, so either can grow; a changed meaning gets
 * a new name ("fluency-g2@2").
 */
object G2Protocol {
    const val NAME = "fluency-g2@1"

    /** Where the sound comes from: the watch's microphone (the watch app streams it) or the phone's. */
    enum class Source(val json: String, val label: String) {
        WATCH("watch", "Uhr-Mikrofon"),
        PHONE("phone", "Handy-Mikrofon"),
    }

    /**
     * What the glasses ask for. [from] is a language code or "auto"; [speak]: read finished translations
     * aloud on the phone; [partials]: send sentences while they are spoken, not only finished ones.
     */
    data class Request(val source: Source, val from: String, val to: String, val speak: Boolean, val partials: Boolean)

    /** A message from the glasses. [n] counts their requests (start, config, stop) up. */
    sealed interface Incoming {
        data class Hello(val proto: String, val app: String) : Incoming
        data class Start(val n: Int, val request: Request) : Incoming
        data class Config(val n: Int, val request: Request) : Incoming
        data class Stop(val n: Int) : Incoming

        /** A known type with a field missing or wrong, answered with [Code.BAD_REQUEST]; [n] if it had one. */
        data class Bad(val n: Int?, val why: String) : Incoming
    }

    /** The codes of an "error" message. */
    object Code {
        /** no model for the language pair */
        const val NO_MODEL = "no_model"

        /** the phone's microphone is taken or not allowed */
        const val MIC = "mic"

        /** no sound from the watch */
        const val AUDIO = "audio"
        const val BUSY = "busy"
        const val BAD_REQUEST = "bad_request"
    }

    /** [text] as a message from the glasses, or null for anything Fluency does not know (another type, no JSON). */
    fun parse(text: String): Incoming? {
        val o = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return null
        }
        val type = o.optString("t")
        return when (type) {
            "hello" -> Incoming.Hello(o.optString("proto"), o.optString("app"))
            "start", "config" -> {
                val n = number(o, "n") ?: return Incoming.Bad(null, "„n“ fehlt")
                val source = Source.entries.firstOrNull { it.json == o.optString("source") }
                    ?: return Incoming.Bad(n, "Unbekannte Tonquelle „${o.optString("source")}“")
                val to = o.optString("to").ifBlank { return Incoming.Bad(n, "Zielsprache fehlt") }
                val request = Request(
                    source = source,
                    from = o.optString("from").ifBlank { AUTO },
                    to = to,
                    speak = o.optBoolean("speak", false),
                    partials = o.optBoolean("partials", true),
                )
                if (type == "start") Incoming.Start(n, request) else Incoming.Config(n, request)
            }
            "stop" -> number(o, "n")?.let { Incoming.Stop(it) } ?: Incoming.Bad(null, "„n“ fehlt")
            else -> null
        }
    }

    private fun number(o: JSONObject, key: String): Int? = (o.opt(key) as? Number)?.toInt()

    /** The answer to "hello": Fluency's protocol and version. */
    fun welcome(app: String): String = message("welcome") {
        put("proto", NAME)
        put("app", app)
    }

    /**
     * Whether Fluency listens (from "start" on, also while it loads its models). [note]: what it does or why
     * it stopped (German); [re]: the request this answers, null when Fluency changed by itself.
     */
    fun state(running: Boolean, note: String? = null, re: Int? = null): String = message("state") {
        put("running", running)
        if (note != null) put("note", note)
        if (re != null) put("re", re)
    }

    /** An utterance as it grows: the recognised text [src] and its translation [text]; [from]: the language of [src], if known. */
    fun utterance(id: Long, src: String, text: String, final: Boolean, from: String?): String = message("utt") {
        put("id", id)
        put("src", src)
        put("text", text)
        put("final", final)
        if (from != null) put("from", from)
    }

    /** Fluency dropped the utterance (it was a noise, or listening ended before it was finished). */
    fun gone(id: Long): String = message("utt") {
        put("id", id)
        put("gone", true)
    }

    fun error(code: String, message: String): String = message("error") {
        put("code", code)
        put("message", message)
    }

    private inline fun message(type: String, fill: JSONObject.() -> Unit): String = JSONObject().put("t", type).apply(fill).toString()
}
