package ch.madtreasures.fluency.engine.llm

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.models.PromptStyle

/** Builds the shortest prompt each model family was trained on. */
object PromptFormat {

    data class Prompt(
        val text: String,
        /** let llama.cpp add the model's default special tokens (BOS) */
        val addSpecial: Boolean,
        val stopAtNewline: Boolean,
    )

    private const val HY_BOS = "<｜hy_begin▁of▁sentence｜>"
    private const val HY_USER = "<｜hy_User｜>"
    private const val HY_ASSISTANT = "<｜hy_Assistant｜>"

    /** ends the system message in the Hunyuan chat template */
    private const val HY_SYSTEM_END = "<｜hy_place▁holder▁no▁3｜>"

    /** True if [style] needs to know the source language. */
    fun needsSource(style: PromptStyle): Boolean = style == PromptStyle.MILMMT

    /** True if [style] uses the user's [ContextDocument] (Hy-MT2: system message and terminology). */
    fun supportsDocument(style: PromptStyle): Boolean = style == PromptStyle.HY_MT

    fun build(
        style: PromptStyle,
        source: Language?,
        target: Language,
        text: String,
        document: ContextDocument.Applied? = null,
        chatTemplate: ((String) -> String?)? = null,
    ): Prompt = when (style) {
        PromptStyle.HY_MT -> hyMt(source, target, text, document)
        PromptStyle.MILMMT -> milmmt(requireNotNull(source) { "MiLMMT needs the source language" }, target, text)
        PromptStyle.CHAT_TEMPLATE -> generic(source, target, text, chatTemplate)
    }

    /**
     * Hy-MT2 (and HY-MT1.5): Chinese instruction when Chinese is involved, English otherwise, both as
     * in the model card. The Hunyuan chat template is written out directly (BOS included). The
     * user's [document]: its sentences as the system message, its fixed translations through the
     * terminology template.
     */
    fun hyMt(source: Language?, target: Language, text: String, document: ContextDocument.Applied? = null): Prompt {
        val chinese = target.isChinese || source?.isChinese == true
        val terms = document?.terms.orEmpty()
        val user = if (chinese) {
            val tgt = target.zhName ?: target.promptName
            val reference = if (terms.isEmpty()) "" else
                "参考下面的翻译：\n" + terms.joinToString("") { "${it.source} 翻译成 ${it.target}\n" }
            "${reference}将以下文本翻译为$tgt，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"
        } else if (terms.isNotEmpty()) {
            "Reference the following translations:\n" + terms.joinToString("\n") { "${it.source} translates to ${it.target}" } +
                "\n\nTranslate the following text into ${target.promptName}. Note that you must ONLY output the " +
                "translated result without any additional explanation:\n\n$text"
        } else {
            "Translate the following text into ${target.promptName}. Note that you should only output the " +
                "translated result without any additional explanation:\n\n$text"
        }
        val system = document?.sentences?.let { "$it$HY_SYSTEM_END" }.orEmpty()
        return Prompt("$HY_BOS$system$HY_USER$user$HY_ASSISTANT", addSpecial = false, stopAtNewline = '\n' !in text)
    }

    /**
     * The beginning that every Hy-MT2 prompt with [sentences] as system message shares (up to the
     * user turn): evaluated once after loading, the first translation then gets it from the KV cache.
     */
    fun hyMtDocumentPrefix(sentences: String): Prompt =
        Prompt("$HY_BOS$sentences$HY_SYSTEM_END$HY_USER", addSpecial = false, stopAtNewline = false)

    /** MiLMMT-46 (GemmaX lineage): plain completion prompt, no chat template, no BOS. */
    fun milmmt(source: Language, target: Language, text: String): Prompt {
        val src = source.milmmtName ?: source.promptName
        val tgt = target.milmmtName ?: target.promptName
        val oneLine = text.replace('\n', ' ').trim()
        return Prompt("Translate this from $src to $tgt:\n$src: $oneLine\n$tgt:", addSpecial = false, stopAtNewline = true)
    }

    /** Any other instruction model: its own chat template with a generic instruction. */
    fun generic(source: Language?, target: Language, text: String, chatTemplate: ((String) -> String?)?): Prompt {
        val from = source?.let { " from ${it.promptName}" } ?: ""
        val user = "Translate the following text$from into ${target.promptName}. " +
            "Output only the translation, nothing else.\n\n$text"
        val formatted = chatTemplate?.invoke(user)
        return Prompt(formatted ?: user, addSpecial = true, stopAtNewline = '\n' !in text)
    }

    /** Upper bound for generated tokens: generous for CJK, but stops runaway repetitions. */
    fun maxTokens(text: String): Int = (32 + text.length * 2).coerceIn(48, 1024)

    /** Names a model may echo in front of its answer ("German: ..."). */
    fun echoNames(target: Language): List<String> = listOfNotNull(target.milmmtName, target.promptName).distinct()
}
