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

    /**
     * Earlier sentences ([source]) and their translation, see [TranslationContext]: the sentences
     * are put in front of the text, the translation is the beginning of the model's answer.
     */
    data class Context(val source: String, val translation: String)

    private const val HY_BOS = "<｜hy_begin▁of▁sentence｜>"
    private const val HY_USER = "<｜hy_User｜>"
    private const val HY_ASSISTANT = "<｜hy_Assistant｜>"

    /** True if [style] needs to know the source language. */
    fun needsSource(style: PromptStyle): Boolean = style == PromptStyle.MILMMT

    /** True if [style] takes earlier sentences as [Context] (measured for Hy-MT2 and MiLMMT-46). */
    fun supportsContext(style: PromptStyle): Boolean = style == PromptStyle.HY_MT || style == PromptStyle.MILMMT

    fun build(
        style: PromptStyle,
        source: Language?,
        target: Language,
        text: String,
        context: Context? = null,
        chatTemplate: ((String) -> String?)? = null,
    ): Prompt = when (style) {
        PromptStyle.HY_MT -> hyMt(source, target, text, context)
        PromptStyle.MILMMT -> milmmt(requireNotNull(source) { "MiLMMT needs the source language" }, target, text, context)
        PromptStyle.CHAT_TEMPLATE -> generic(source, target, text, chatTemplate)
    }

    /**
     * The part of the prompt in front of the text to translate (instruction and earlier
     * sentences): it is the same for every partial translation of the next utterance and can be
     * evaluated before the utterance starts. Null if [style] takes no context.
     */
    fun warmUpPrompt(style: PromptStyle, source: Language?, target: Language, context: Context): Prompt? {
        if (!supportsContext(style) || (source == null && needsSource(style))) return null
        val full = build(style, source, target, TEXT_MARKER, context)
        val at = full.text.indexOf(TEXT_MARKER)
        if (at <= 0) return null
        return full.copy(text = full.text.substring(0, at).trimEnd())
    }

    private const val TEXT_MARKER = "\u0001"

    /**
     * Hy-MT2 (and HY-MT1.5): Chinese instruction when Chinese is involved, English otherwise.
     * The Hunyuan chat template is written out directly (BOS included), no system prompt.
     */
    fun hyMt(source: Language?, target: Language, text: String, context: Context? = null): Prompt {
        val input = if (context == null) text else TranslationContext.joinSentences(context.source, text)
        val instruction = if (target.isChinese || source?.isChinese == true) {
            "将以下文本翻译为${target.zhName ?: target.promptName}，注意只需要输出翻译后的结果，不要额外解释：\n\n$input"
        } else {
            "Translate the following text into ${target.promptName}. Note that you should only output the " +
                "translated result without any additional explanation:\n\n$input"
        }
        val answer = context?.translation.orEmpty()
        return Prompt("$HY_BOS$HY_USER$instruction$HY_ASSISTANT$answer", addSpecial = false, stopAtNewline = '\n' !in input)
    }

    /** MiLMMT-46 (GemmaX lineage): plain completion prompt, no chat template, no BOS. */
    fun milmmt(source: Language, target: Language, text: String, context: Context? = null): Prompt {
        val src = source.milmmtName ?: source.promptName
        val tgt = target.milmmtName ?: target.promptName
        val oneLine = text.replace('\n', ' ').trim()
        val input = if (context == null) oneLine else TranslationContext.joinSentences(context.source, oneLine)
        val answer = context?.let { " ${it.translation}" }.orEmpty()
        return Prompt("Translate this from $src to $tgt:\n$src: $input\n$tgt:$answer", addSpecial = false, stopAtNewline = true)
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
