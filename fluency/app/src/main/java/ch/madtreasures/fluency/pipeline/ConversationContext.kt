package ch.madtreasures.fluency.pipeline

import ch.madtreasures.fluency.core.Language
import ch.madtreasures.fluency.engine.llm.ContextPair
import ch.madtreasures.fluency.engine.llm.TranslationContext

/**
 * Earlier finished utterances as translation context (pure, unit tested). In conversation mode
 * the other person speaks the target language: their utterance is used the other way round
 * (their translation is the sentence in our source language, their words the translation).
 */
object ConversationContext {

    /** Context for utterance [currentId], translated [source] (null: detected) → [target], oldest first. */
    fun pairsFor(utterances: List<Utterance>, currentId: Long, source: Language?, target: Language): List<ContextPair> {
        val pairs = utterances
            .filter { it.id < currentId && it.final && it.error == null && it.sourceText.isNotBlank() && it.translation.isNotBlank() }
            .sortedBy { it.id }
            .mapNotNull { u ->
                when {
                    // same direction (or a detected source language)
                    TranslationContext.sameLanguage(u.target, target) &&
                        (source == null || u.source == null || TranslationContext.sameLanguage(u.source, source)) ->
                        ContextPair(u.sourceText, u.translation)
                    // the other person: spoken in our target language, translated into our source language
                    TranslationContext.sameLanguage(u.source, target) &&
                        (source == null || TranslationContext.sameLanguage(u.target, source)) ->
                        ContextPair(u.translation, u.sourceText)
                    else -> null
                }
            }
        return TranslationContext.select(pairs)
    }
}
