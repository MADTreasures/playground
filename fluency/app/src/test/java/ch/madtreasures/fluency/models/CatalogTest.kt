package ch.madtreasures.fluency.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private val pinned = Regex("^https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/.+")
    private val sha = Regex("^[0-9a-f]{64}$")

    @Test fun everyBuiltInModelHasPinnedVerifiedFiles() {
        for (m in ModelCatalog.builtIn) {
            assertTrue("${m.id} has files", m.files.isNotEmpty())
            for (f in m.files) {
                assertTrue("${m.id}/${f.path} pinned url: ${f.url}", pinned.matches(f.url))
                assertTrue("${m.id}/${f.path} sha256", f.sha256 != null && sha.matches(f.sha256))
                assertTrue("${m.id}/${f.path} size", f.size > 0)
            }
            assertEquals("${m.id} unique paths", m.files.size, m.files.map { it.path }.toSet().size)
        }
    }

    @Test fun idsMatchGeneratedFileLists() {
        assertEquals(ModelFiles.ids, ModelCatalog.builtIn.map { it.id }.toSet())
    }

    @Test fun recommendedSetIsLiveReady() {
        val rec = ModelCatalog.recommendedIds.map { ModelCatalog.byId(it)!! }
        assertTrue(rec.any { it.kind == ModelKind.TRANSLATION })
        assertTrue(rec.any { it.kind == ModelKind.ASR })
        assertTrue(rec.any { it.kind == ModelKind.VAD })
        // first download stays reasonable (< 2 GB)
        assertTrue(rec.sumOf { it.totalBytes } < 2_000_000_000L)
    }

    @Test fun translationModelsHavePromptStyleAndAsrModelsHaveType() {
        ModelCatalog.builtIn.filter { it.kind == ModelKind.TRANSLATION }.forEach { assertTrue(it.id, it.promptStyle != null) }
        ModelCatalog.builtIn.filter { it.kind == ModelKind.ASR }.forEach { assertTrue(it.id, it.asrType != null) }
    }

    @Test fun piperVoicesDependOnEspeakData() {
        ModelCatalog.builtIn.filter { it.kind == ModelKind.TTS }.forEach {
            assertEquals(listOf(ModelCatalog.ESPEAK_DATA), it.dependsOn)
            assertTrue(it.files.any { f -> f.path.endsWith(".onnx") })
            assertTrue(it.files.any { f -> f.path == "tokens.txt" })
        }
    }
}
