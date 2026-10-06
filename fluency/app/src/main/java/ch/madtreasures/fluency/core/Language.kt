package ch.madtreasures.fluency.core

import java.util.Locale

/**
 * A language the app can translate from/to.
 *
 * @param code       BCP-47 tag used inside the app ("de", "de-CH", "zh-Hant", ...)
 * @param nameDe     display name (Swiss spelling: no "ß")
 * @param promptName English name used in Hy-MT2 prompts
 * @param zhName     Chinese name used in Hy-MT2's Chinese prompt (null: not supported by Hy-MT2)
 * @param milmmtName exact language name expected by MiLMMT-46 (null: not supported)
 * @param parakeet   recognised by Parakeet-TDT-0.6B-v3
 * @param whisper    Whisper language code (null: not recognised by Whisper)
 */
data class Language(
    val code: String,
    val nameDe: String,
    val promptName: String,
    val zhName: String?,
    val milmmtName: String?,
    val parakeet: Boolean,
    val whisper: String?,
) {
    val hyMt: Boolean get() = zhName != null
    val isChinese: Boolean get() = code == "zh" || code == "zh-Hant" || code == "yue"

    /** Swiss Standard German: always "ss" instead of "ß". */
    val usesSwissSpelling: Boolean get() = code == "de-CH"

    val locale: Locale get() = Locale.forLanguageTag(code)

    override fun toString(): String = nameDe
}

object Languages {
    val all: List<Language> = listOf(
        Language("de", "Deutsch", "German", "德语", "German", parakeet = true, whisper = "de"),
        Language("de-CH", "Deutsch (Schweiz)", "German", "德语", "German", parakeet = true, whisper = "de"),
        Language("en", "Englisch", "English", "英语", "English", parakeet = true, whisper = "en"),
        Language("fr", "Französisch", "French", "法语", "French", parakeet = true, whisper = "fr"),
        Language("it", "Italienisch", "Italian", "意大利语", "Italian", parakeet = true, whisper = "it"),
        Language("es", "Spanisch", "Spanish", "西班牙语", "Spanish", parakeet = true, whisper = "es"),
        Language("pt", "Portugiesisch", "Portuguese", "葡萄牙语", "Portuguese", parakeet = true, whisper = "pt"),
        Language("nl", "Niederländisch", "Dutch", "荷兰语", "Dutch", parakeet = true, whisper = "nl"),
        Language("pl", "Polnisch", "Polish", "波兰语", "Polish", parakeet = true, whisper = "pl"),
        Language("cs", "Tschechisch", "Czech", "捷克语", "Czech", parakeet = true, whisper = "cs"),
        Language("sk", "Slowakisch", "Slovak", null, "Slovak", parakeet = true, whisper = "sk"),
        Language("sl", "Slowenisch", "Slovenian", null, "Slovenian", parakeet = true, whisper = "sl"),
        Language("hr", "Kroatisch", "Croatian", null, "Croatian", parakeet = true, whisper = "hr"),
        Language("bg", "Bulgarisch", "Bulgarian", null, "Bulgarian", parakeet = true, whisper = "bg"),
        Language("ro", "Rumänisch", "Romanian", null, "Romanian", parakeet = true, whisper = "ro"),
        Language("hu", "Ungarisch", "Hungarian", null, "Hungarian", parakeet = true, whisper = "hu"),
        Language("el", "Griechisch", "Greek", null, "Greek", parakeet = true, whisper = "el"),
        Language("da", "Dänisch", "Danish", null, "Danish", parakeet = true, whisper = "da"),
        Language("sv", "Schwedisch", "Swedish", null, "Swedish", parakeet = true, whisper = "sv"),
        Language("no", "Norwegisch", "Norwegian", null, "Norwegian", parakeet = false, whisper = "no"),
        Language("fi", "Finnisch", "Finnish", null, "Finnish", parakeet = true, whisper = "fi"),
        Language("ca", "Katalanisch", "Catalan", null, "Catalan", parakeet = false, whisper = "ca"),
        Language("ru", "Russisch", "Russian", "俄语", "Russian", parakeet = true, whisper = "ru"),
        Language("uk", "Ukrainisch", "Ukrainian", "乌克兰语", null, parakeet = true, whisper = "uk"),
        Language("tr", "Türkisch", "Turkish", "土耳其语", "Turkish", parakeet = false, whisper = "tr"),
        Language("ar", "Arabisch", "Arabic", "阿拉伯语", "Arabic", parakeet = false, whisper = "ar"),
        Language("he", "Hebräisch", "Hebrew", "希伯来语", "Hebrew", parakeet = false, whisper = "he"),
        Language("fa", "Persisch", "Persian", "波斯语", "Persian", parakeet = false, whisper = "fa"),
        Language("ur", "Urdu", "Urdu", "乌尔都语", "Urdu", parakeet = false, whisper = "ur"),
        Language("hi", "Hindi", "Hindi", "印地语", "Hindi", parakeet = false, whisper = "hi"),
        Language("bn", "Bengalisch", "Bengali", "孟加拉语", "Bengali", parakeet = false, whisper = "bn"),
        Language("ta", "Tamil", "Tamil", "泰米尔语", "Tamil", parakeet = false, whisper = "ta"),
        Language("te", "Telugu", "Telugu", "泰卢固语", null, parakeet = false, whisper = "te"),
        Language("mr", "Marathi", "Marathi", "马拉地语", null, parakeet = false, whisper = "mr"),
        Language("gu", "Gujarati", "Gujarati", "古吉拉特语", null, parakeet = false, whisper = "gu"),
        Language("zh", "Chinesisch (vereinfacht)", "Chinese", "中文", "Chinese (Simplified)", parakeet = false, whisper = "zh"),
        Language("zh-Hant", "Chinesisch (traditionell)", "Traditional Chinese", "繁体中文", "Chinese (Traditional)", parakeet = false, whisper = "zh"),
        Language("yue", "Kantonesisch", "Cantonese", "粤语", "Cantonese", parakeet = false, whisper = "yue"),
        Language("ja", "Japanisch", "Japanese", "日语", "Japanese", parakeet = false, whisper = "ja"),
        Language("ko", "Koreanisch", "Korean", "韩语", "Korean", parakeet = false, whisper = "ko"),
        Language("th", "Thailändisch", "Thai", "泰语", "Thai", parakeet = false, whisper = "th"),
        Language("vi", "Vietnamesisch", "Vietnamese", "越南语", "Vietnamese", parakeet = false, whisper = "vi"),
        Language("id", "Indonesisch", "Indonesian", "印尼语", "Indonesian", parakeet = false, whisper = "id"),
        Language("ms", "Malaiisch", "Malay", "马来语", "Malay", parakeet = false, whisper = "ms"),
        Language("tl", "Filipino", "Filipino", "菲律宾语", "Tagalog", parakeet = false, whisper = "tl"),
        Language("km", "Khmer", "Khmer", "高棉语", "Khmer", parakeet = false, whisper = "km"),
        Language("lo", "Laotisch", "Lao", null, "Lao", parakeet = false, whisper = "lo"),
        Language("my", "Burmesisch", "Burmese", "缅甸语", "Burmese", parakeet = false, whisper = "my"),
        Language("kk", "Kasachisch", "Kazakh", "哈萨克语", "Kazakh", parakeet = false, whisper = "kk"),
        Language("az", "Aserbaidschanisch", "Azerbaijani", null, "Azerbaijani", parakeet = false, whisper = "az"),
        Language("uz", "Usbekisch", "Uzbek", null, "Uzbek", parakeet = false, whisper = "uz"),
        Language("bo", "Tibetisch", "Tibetan", "藏语", null, parakeet = false, whisper = "bo"),
        Language("mn", "Mongolisch", "Mongolian", "蒙古语", null, parakeet = false, whisper = "mn"),
        Language("ug", "Uigurisch", "Uyghur", "维吾尔语", null, parakeet = false, whisper = null),
    )

    private val byCode = all.associateBy { it.code }

    /** The six core languages shown first in pickers. */
    val core: List<Language> = listOf("de", "de-CH", "en", "fr", "it", "es", "pt").map { byCode.getValue(it) }

    fun byCode(code: String): Language? = byCode[code]

    fun require(code: String): Language = byCode[code] ?: error("unknown language $code")

    /** Pickers: core languages first, then the rest alphabetically. */
    val sortedForPicker: List<Language> = core + (all - core.toSet()).sortedBy { it.nameDe }
}
