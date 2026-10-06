package ch.madtreasures.fluency.engine

import ch.madtreasures.fluency.core.Languages
import ch.madtreasures.fluency.engine.llm.PromptFormat
import ch.madtreasures.fluency.engine.llm.TranslationEngine
import ch.madtreasures.fluency.engine.llm.TranslationEngine.Role
import ch.madtreasures.fluency.engine.llm.TranslationModelSource
import ch.madtreasures.fluency.models.ModelCatalog
import ch.madtreasures.fluency.models.ModelInfo
import ch.madtreasures.fluency.models.PromptStyle
import ch.madtreasures.fluency.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptFormatTest {
    private val de = Languages.require("de")
    private val en = Languages.require("en")
    private val zh = Languages.require("zh")
    private val ch = Languages.require("de-CH")

    @Test fun hyMtUsesHunyuanTemplateWithEnglishInstruction() {
        val p = PromptFormat.build(PromptStyle.HY_MT, null, de, "Good morning")
        assertEquals(
            "<｜hy_begin▁of▁sentence｜><｜hy_User｜>Translate the following text into German. Note that you should only " +
                "output the translated result without any additional explanation:\n\nGood morning<｜hy_Assistant｜>",
            p.text,
        )
        assertFalse(p.addSpecial)
        assertTrue(p.stopAtNewline)
    }

    @Test fun hyMtUsesChinesePromptWhenChineseIsInvolved() {
        val p = PromptFormat.build(PromptStyle.HY_MT, en, zh, "Hello")
        assertTrue(p.text.contains("将以下文本翻译为中文"))
        val q = PromptFormat.build(PromptStyle.HY_MT, zh, de, "你好")
        assertTrue(q.text.contains("翻译为德语"))
    }

    @Test fun swissGermanIsPromptedAsGerman() {
        assertTrue(PromptFormat.build(PromptStyle.HY_MT, en, ch, "x").text.contains("into German."))
        assertTrue(PromptFormat.build(PromptStyle.MILMMT, en, ch, "x").text.endsWith("\nGerman:"))
    }

    @Test fun milmmtUsesCompletionFormat() {
        val p = PromptFormat.build(PromptStyle.MILMMT, en, zh, "I love\nmachine translation")
        assertEquals(
            "Translate this from English to Chinese (Simplified):\nEnglish: I love machine translation\nChinese (Simplified):",
            p.text,
        )
        assertTrue(p.stopAtNewline)
        assertFalse(p.addSpecial)
    }

    @Test fun multiLineTextDoesNotStopAtNewline() {
        assertFalse(PromptFormat.build(PromptStyle.HY_MT, null, de, "a\nb").stopAtNewline)
    }

    @Test fun genericUsesChatTemplateWhenAvailable() {
        val p = PromptFormat.build(PromptStyle.CHAT_TEMPLATE, en, de, "Hi") { user -> "<u>$user</u><a>" }
        assertTrue(p.text.startsWith("<u>Translate the following text from English into German."))
        assertTrue(p.addSpecial)
        val raw = PromptFormat.build(PromptStyle.CHAT_TEMPLATE, null, de, "Hi") { null }
        assertTrue(raw.text.startsWith("Translate the following text into German."))
    }

    @Test fun maxTokensIsBounded() {
        assertEquals(48, PromptFormat.maxTokens("Hi"))
        assertEquals(1024, PromptFormat.maxTokens("x".repeat(5000)))
    }
}

class RoutingTest {
    private fun engine(installed: List<String>, settings: AppSettings = AppSettings()) = TranslationEngine(
        source = object : TranslationModelSource {
            override fun installedTranslationModels(): List<ModelInfo> = installed.map { ModelCatalog.byId(it)!! }
            override fun modelPath(info: ModelInfo) = "/nonexistent"
        },
        settings = { settings },
        nativeLibDir = null,
    )

    private val de = Languages.require("de")
    private val en = Languages.require("en")
    private val sv = Languages.require("sv")
    private val uk = Languages.require("uk")

    @Test fun defaultUsesHyMtForLiveAndFinal() {
        val e = engine(listOf(ModelCatalog.HY_MT2, ModelCatalog.MILMMT_4B))
        assertEquals(ModelCatalog.HY_MT2, e.pickModel(Role.LIVE, de, en)?.id)
        assertEquals(ModelCatalog.HY_MT2, e.pickModel(Role.FINAL, de, en)?.id)
        // text mode: best quality
        assertEquals(ModelCatalog.MILMMT_4B, e.pickModel(Role.TEXT, de, en)?.id)
    }

    @Test fun qualityModelForFinalsWhenConfigured() {
        val e = engine(listOf(ModelCatalog.HY_MT2, ModelCatalog.MILMMT_4B), AppSettings(finalModelId = ModelCatalog.MILMMT_4B))
        assertEquals(ModelCatalog.HY_MT2, e.pickModel(Role.LIVE, de, en)?.id)
        assertEquals(ModelCatalog.MILMMT_4B, e.pickModel(Role.FINAL, de, en)?.id)
    }

    @Test fun fallsBackToModelThatSupportsTheLanguage() {
        val e = engine(listOf(ModelCatalog.HY_MT2, ModelCatalog.MILMMT_1B))
        // Hy-MT2 cannot do Swedish -> MiLMMT
        assertEquals(ModelCatalog.MILMMT_1B, e.pickModel(Role.LIVE, sv, de)?.id)
        // MiLMMT cannot do Ukrainian -> Hy-MT2
        assertEquals(ModelCatalog.HY_MT2, e.pickModel(Role.FINAL, uk, de)?.id)
    }

    @Test fun autoSourcePrefersModelsThatDoNotNeedIt() {
        val e = engine(listOf(ModelCatalog.HY_MT2, ModelCatalog.MILMMT_4B), AppSettings(liveModelId = ModelCatalog.MILMMT_4B))
        assertEquals(ModelCatalog.HY_MT2, e.pickModel(Role.LIVE, null, en)?.id)
        // only MiLMMT installed: allowed (source is then guessed from the text)
        assertEquals(ModelCatalog.MILMMT_4B, engine(listOf(ModelCatalog.MILMMT_4B)).pickModel(Role.LIVE, null, en)?.id)
    }

    @Test fun nothingInstalled() {
        assertNull(engine(emptyList()).pickModel(Role.LIVE, de, en))
    }
}
