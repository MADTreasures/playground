package ch.madtreasures.fluency.models

import ch.madtreasures.fluency.core.Languages

data class RemoteFile(val url: String, val path: String, val size: Long, val sha256: String?)

enum class ModelKind(val title: String) {
    TRANSLATION("Übersetzung"),
    ASR("Spracherkennung"),
    VAD("Sprachaktivität"),
    TTS("Sprachausgabe (Piper)"),
    SUPPORT("Zusatzdaten"),
}

/** Prompt family of a translation model. */
enum class PromptStyle { HY_MT, MILMMT, CHAT_TEMPLATE }

enum class AsrType { PARAKEET, WHISPER_SHERPA, WHISPER_CPP }

data class ModelInfo(
    val id: String,
    val kind: ModelKind,
    val name: String,
    val description: String,
    val license: String,
    val source: String,
    val files: List<RemoteFile>,
    val recommended: Boolean = false,
    val promptStyle: PromptStyle? = null,
    val asrType: AsrType? = null,
    /** language codes (translation/ASR); empty = all */
    val languages: Set<String> = emptySet(),
    val ttsLanguage: String? = null,
    val dependsOn: List<String> = emptyList(),
    /** 1 = slowest .. 3 = fastest; used for routing live partials */
    val speedRank: Int = 0,
    /** 1 = lowest .. 3 = best; used for routing final translations */
    val qualityRank: Int = 0,
    /** imported by the user (not downloadable) */
    val custom: Boolean = false,
) {
    val totalBytes: Long get() = files.sumOf { it.size }

    fun supports(code: String): Boolean = languages.isEmpty() || code in languages
}

object ModelCatalog {
    const val HY_MT2 = "hy-mt2-1.8b"
    const val MILMMT_4B = "milmmt-46-4b"
    const val MILMMT_1B = "milmmt-46-1b"
    const val PARAKEET = "parakeet-tdt-0.6b-v3"
    const val WHISPER_TURBO = "whisper-turbo"
    const val SWISS_WHISPER = "swiss-whisper-turbo"
    const val SILERO_VAD = "silero-vad"
    const val ESPEAK_DATA = "espeak-ng-data"

    private val hyMtLangs = Languages.all.filter { it.hyMt }.map { it.code }.toSet()
    private val milmmtLangs = Languages.all.filter { it.milmmtName != null }.map { it.code }.toSet()
    private val parakeetLangs = Languages.all.filter { it.parakeet }.map { it.code }.toSet()
    private val whisperLangs = Languages.all.filter { it.whisper != null }.map { it.code }.toSet()

    private fun hf(repo: String) = "https://huggingface.co/$repo"

    val builtIn: List<ModelInfo> = listOf(
        ModelInfo(
            id = HY_MT2, kind = ModelKind.TRANSLATION, name = "Hy-MT2 1.8B",
            description = "Tencent, Mai 2026 · 33 Sprachen · sehr schnell bei guter Qualität. " +
                "Empfohlen für Live- und Gesprächsmodus.",
            license = "Apache-2.0", source = hf("tencent/Hy-MT2-1.8B-GGUF"),
            files = ModelFiles.forModel(HY_MT2), recommended = true, promptStyle = PromptStyle.HY_MT,
            languages = hyMtLangs, speedRank = 2, qualityRank = 2,
        ),
        ModelInfo(
            id = MILMMT_4B, kind = ModelKind.TRANSLATION, name = "MiLMMT-46 4B",
            description = "Xiaomi, Aug. 2026 · 46 Sprachen · beste Qualität (auf Niveau von Hy-MT2-7B), " +
                "etwa doppelt so langsam. Für finale Übersetzungen und Texte.",
            license = "Gemma Terms of Use", source = hf("mradermacher/MiLMMT-46-4B-v1.0-GGUF"),
            files = ModelFiles.forModel(MILMMT_4B), promptStyle = PromptStyle.MILMMT,
            languages = milmmtLangs, speedRank = 1, qualityRank = 3,
        ),
        ModelInfo(
            id = MILMMT_1B, kind = ModelKind.TRANSLATION, name = "MiLMMT-46 1B",
            description = "Xiaomi · 46 Sprachen · am schnellsten, etwas schwächere Qualität. " +
                "Gut für Live-Teilübersetzungen und Sprachen, die Hy-MT2 nicht kann (z. B. Schwedisch).",
            license = "Gemma Terms of Use", source = hf("mradermacher/MiLMMT-46-1B-v1.0-GGUF"),
            files = ModelFiles.forModel(MILMMT_1B), promptStyle = PromptStyle.MILMMT,
            languages = milmmtLangs, speedRank = 3, qualityRank = 1,
        ),
        ModelInfo(
            id = PARAKEET, kind = ModelKind.ASR, name = "Parakeet-TDT 0.6B v3",
            description = "NVIDIA · 25 europäische Sprachen mit automatischer Erkennung · sehr schnell, " +
                "liefert Live-Untertitel während des Sprechens.",
            license = "CC-BY-4.0", source = hf("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"),
            files = ModelFiles.forModel(PARAKEET), recommended = true, asrType = AsrType.PARAKEET,
            languages = parakeetLangs, speedRank = 3, qualityRank = 2,
        ),
        ModelInfo(
            id = WHISPER_TURBO, kind = ModelKind.ASR, name = "Whisper large-v3-turbo",
            description = "OpenAI · rund 100 Sprachen · Fallback für Sprachen, die Parakeet nicht kann " +
                "(z. B. Türkisch, Arabisch, Chinesisch). Langsamer, Teilergebnisse seltener.",
            license = "MIT", source = hf("csukuangfj/sherpa-onnx-whisper-turbo"),
            files = ModelFiles.forModel(WHISPER_TURBO), asrType = AsrType.WHISPER_SHERPA,
            languages = whisperLangs, speedRank = 1, qualityRank = 2,
        ),
        ModelInfo(
            id = SWISS_WHISPER, kind = ModelKind.ASR, name = "Schweizerdeutsch (Whisper-Turbo)",
            description = "Whisper large-v3-turbo, auf Schweizer Mundart feinabgestimmt (Flurin17), " +
                "Format whisper.cpp. Wird für „Deutsch (Schweiz)“ als Ausgangssprache verwendet und schreibt " +
                "Hochdeutsch mit ss. Langsamer als Parakeet, keine Teilergebnisse.",
            license = "CC-BY-NC-4.0 (privat)", source = hf("gariwat/swiss-german-whisper-ggml"),
            files = ModelFiles.forModel(SWISS_WHISPER), asrType = AsrType.WHISPER_CPP,
            languages = setOf("de-CH"), speedRank = 1, qualityRank = 3,
        ),
        ModelInfo(
            id = SILERO_VAD, kind = ModelKind.VAD, name = "Silero VAD v5",
            description = "Erkennt Sprechpausen und Satzenden (2 MB). Für Live- und Gesprächsmodus nötig.",
            license = "MIT", source = hf("csukuangfj/vad"),
            files = ModelFiles.forModel(SILERO_VAD), recommended = true,
        ),
        ModelInfo(
            id = ESPEAK_DATA, kind = ModelKind.SUPPORT, name = "eSpeak-NG-Aussprachedaten",
            description = "Wird von allen Piper-Stimmen benötigt (18 MB, 355 Dateien).",
            license = "GPL-3.0", source = hf("csukuangfj/vits-piper-de_DE-thorsten-medium"),
            files = ModelFiles.forModel(ESPEAK_DATA),
        ),
        piper("piper-de-thorsten", "Piper Thorsten", "de", "Deutsch, männlich", "CC0", "de_DE-thorsten-medium"),
        piper("piper-en-lessac", "Piper Lessac", "en", "Englisch (US), weiblich", "Lessac/Blizzard-Lizenz (privat)", "en_US-lessac-medium"),
        piper("piper-fr-siwis", "Piper Siwis", "fr", "Französisch, weiblich", "CC-BY-4.0", "fr_FR-siwis-medium"),
        piper("piper-it-paola", "Piper Paola", "it", "Italienisch, weiblich", "siehe Datensatz", "it_IT-paola-medium"),
        piper("piper-es-davefx", "Piper Davefx", "es", "Spanisch, männlich", "CC0", "es_ES-davefx-medium"),
        piper("piper-pt-tugao", "Piper Tugão", "pt", "Portugiesisch (PT), männlich", "CC0", "pt_PT-tugao-medium"),
    )

    private fun piper(id: String, name: String, lang: String, desc: String, license: String, voice: String) = ModelInfo(
        id = id, kind = ModelKind.TTS, name = name,
        description = "$desc · offline, natürlicher als viele Systemstimmen (ca. 63 MB).",
        license = license, source = hf("csukuangfj/vits-piper-$voice"),
        files = ModelFiles.forModel(id), ttsLanguage = lang, dependsOn = listOf(ESPEAK_DATA),
    )

    fun byId(id: String): ModelInfo? = builtIn.firstOrNull { it.id == id }

    /** Models downloaded by the "Empfohlen" button on first start. */
    val recommendedIds: List<String> = builtIn.filter { it.recommended }.map { it.id }
}
