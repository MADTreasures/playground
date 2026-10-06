package ch.madtreasures.fluency.models

import java.io.File
import java.util.Properties

/**
 * On-disk layout:
 *   <root>/<modelId>/<relative file path>      downloaded/imported files
 *   <root>/<modelId>/<file>.part               incomplete download (resumable)
 *   <root>/<modelId>/.verified                 written after all files passed the SHA-256 check
 *   <root>/custom-<slug>/model.properties      metadata of a user-imported GGUF model
 */
class ModelStore(val root: File) {

    init {
        root.mkdirs()
    }

    fun dir(id: String) = File(root, id)

    fun file(id: String, path: String) = File(dir(id), path)

    fun partFile(id: String, path: String) = File(dir(id), "$path.part")

    private fun marker(id: String) = File(dir(id), VERIFIED_MARKER)

    fun isInstalled(model: ModelInfo): Boolean =
        model.files.isNotEmpty() && marker(model.id).exists() &&
            model.files.all { f -> file(model.id, f.path).let { it.isFile && (f.size <= 0 || it.length() == f.size) } }

    fun markVerified(id: String) {
        marker(id).apply { parentFile?.mkdirs(); writeText(System.currentTimeMillis().toString()) }
    }

    fun clearVerified(id: String) {
        marker(id).delete()
    }

    /** Bytes of completed + partial files of [model] currently on disk. */
    fun bytesOnDisk(model: ModelInfo): Long = model.files.sumOf { f ->
        val done = file(model.id, f.path)
        if (done.isFile) done.length() else partFile(model.id, f.path).takeIf { it.isFile }?.length() ?: 0L
    }

    fun hasPartialData(model: ModelInfo): Boolean = !isInstalled(model) && bytesOnDisk(model) > 0

    fun delete(id: String): Boolean = dir(id).deleteRecursively()

    /** Total size of everything stored under [root]. */
    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun freeBytes(): Long = root.usableSpace

    // -------------------------------------------------------------------------------- custom models

    fun customModels(): List<ModelInfo> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory && it.name.startsWith(CUSTOM_PREFIX) }
        .mapNotNull { readCustom(it) }
        .sortedBy { it.name }

    fun createCustomDir(displayName: String): File {
        val slug = displayName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "modell" }
        var dir = File(root, CUSTOM_PREFIX + slug)
        var i = 2
        while (dir.exists()) dir = File(root, CUSTOM_PREFIX + slug + "-" + i++)
        dir.mkdirs()
        return dir
    }

    fun writeCustom(dir: File, meta: CustomModelMeta) {
        val p = Properties()
        p["name"] = meta.name
        p["kind"] = meta.kind.name
        p["file"] = meta.fileName
        meta.promptStyle?.let { p["promptStyle"] = it.name }
        meta.asrType?.let { p["asrType"] = it.name }
        p["size"] = meta.size.toString()
        p["sha256"] = meta.sha256
        File(dir, CUSTOM_META).outputStream().use { p.store(it, "Fluency custom model") }
        markVerified(dir.name)
    }

    private fun readCustom(dir: File): ModelInfo? {
        val metaFile = File(dir, CUSTOM_META)
        if (!metaFile.isFile) return null
        val p = Properties().apply { metaFile.inputStream().use { load(it) } }
        val fileName = p.getProperty("file") ?: return null
        val kind = runCatching { ModelKind.valueOf(p.getProperty("kind", "TRANSLATION")) }.getOrDefault(ModelKind.TRANSLATION)
        val size = p.getProperty("size")?.toLongOrNull() ?: File(dir, fileName).length()
        return ModelInfo(
            id = dir.name,
            kind = kind,
            name = p.getProperty("name", fileName),
            description = "Eigenes Modell (importiert): $fileName",
            license = "–",
            source = "Import",
            files = listOf(RemoteFile(url = "", path = fileName, size = size, sha256 = p.getProperty("sha256"))),
            promptStyle = p.getProperty("promptStyle")?.let { runCatching { PromptStyle.valueOf(it) }.getOrNull() }
                ?: if (kind == ModelKind.TRANSLATION) PromptStyle.CHAT_TEMPLATE else null,
            asrType = p.getProperty("asrType")?.let { runCatching { AsrType.valueOf(it) }.getOrNull() },
            speedRank = 1,
            qualityRank = 1,
            custom = true,
        )
    }

    data class CustomModelMeta(
        val name: String,
        val kind: ModelKind,
        val fileName: String,
        val size: Long,
        val sha256: String,
        val promptStyle: PromptStyle? = null,
        val asrType: AsrType? = null,
    )

    companion object {
        const val VERIFIED_MARKER = ".verified"
        const val CUSTOM_PREFIX = "custom-"
        const val CUSTOM_META = "model.properties"
    }
}
