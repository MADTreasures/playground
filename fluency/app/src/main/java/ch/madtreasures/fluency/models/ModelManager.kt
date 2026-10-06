package ch.madtreasures.fluency.models

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

sealed interface ModelState {
    data object NotInstalled : ModelState
    data object Queued : ModelState
    data class Downloading(val bytesDone: Long, val bytesTotal: Long, val bytesPerSecond: Long, val verifying: Boolean) : ModelState
    data class Paused(val bytesDone: Long, val bytesTotal: Long) : ModelState
    data class Importing(val bytesDone: Long, val bytesTotal: Long) : ModelState
    data object Installed : ModelState
    data class Failed(val message: String, val bytesDone: Long, val bytesTotal: Long) : ModelState

    val isBusy: Boolean get() = this is Queued || this is Downloading || this is Importing
}

/**
 * Owns the model files: state of every catalog/custom model, queued downloads, import, delete.
 * Downloads run one model at a time in [scope] (an application scope; a foreground service keeps
 * the process alive while [activeCount] > 0).
 */
class ModelManager(
    val store: ModelStore,
    private val downloader: ModelDownloader,
    private val scope: CoroutineScope,
    private val onActiveChanged: (Boolean) -> Unit = {},
) {
    private val _models = MutableStateFlow(ModelCatalog.builtIn + store.customModels())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val downloadMutex = Mutex()

    init {
        refresh()
    }

    fun model(id: String): ModelInfo? = _models.value.firstOrNull { it.id == id }

    fun state(id: String): ModelState = _states.value[id] ?: ModelState.NotInstalled

    fun isInstalled(id: String): Boolean = state(id) == ModelState.Installed

    fun installed(kind: ModelKind): List<ModelInfo> = _models.value.filter { it.kind == kind && isInstalled(it.id) }

    val activeCount: Int get() = _states.value.values.count { it.isBusy }

    /** Re-reads the disk state of all models that are not busy. */
    fun refresh() {
        _models.value = ModelCatalog.builtIn + store.customModels()
        _states.update { current ->
            _models.value.associate { m ->
                val busy = current[m.id]?.takeIf { it.isBusy }
                m.id to (busy ?: diskState(m))
            }
        }
    }

    private fun diskState(m: ModelInfo): ModelState = when {
        store.isInstalled(m) -> ModelState.Installed
        m.custom -> ModelState.Failed("Datei fehlt", 0, m.totalBytes)
        store.hasPartialData(m) -> ModelState.Paused(store.bytesOnDisk(m), m.totalBytes)
        else -> ModelState.NotInstalled
    }

    @Volatile private var wasActive = false

    private fun setState(id: String, s: ModelState) {
        _states.update { it + (id to s) }
        notifyActive()
    }

    /** Tells the app only about changes (start/end of downloading), not every progress tick. */
    @Synchronized
    private fun notifyActive() {
        val active = activeCount > 0
        if (active != wasActive) {
            wasActive = active
            onActiveChanged(active)
        }
    }

    /** Queues [id] and its dependencies (e.g. eSpeak data for Piper voices). */
    fun download(id: String) {
        val m = model(id) ?: return
        m.dependsOn.forEach { dep -> if (!isInstalled(dep) && !state(dep).isBusy) download(dep) }
        if (m.custom || isInstalled(id) || state(id).isBusy) return
        setState(id, ModelState.Queued)
        jobs[id] = scope.launch(Dispatchers.IO) {
            downloadMutex.withLock {
                var lastBytes = -1L
                var lastTime = System.nanoTime()
                var speed = 0L
                try {
                    downloader.download(m) { p ->
                        val now = System.nanoTime()
                        if (lastBytes >= 0 && now > lastTime) {
                            val inst = ((p.bytesDone - lastBytes) * 1_000_000_000L / (now - lastTime)).coerceAtLeast(0)
                            speed = if (speed == 0L) inst else (speed * 7 + inst * 3) / 10
                        }
                        lastBytes = p.bytesDone
                        lastTime = now
                        setState(id, ModelState.Downloading(p.bytesDone, p.bytesTotal, speed, p.verifying))
                    }
                    setState(id, ModelState.Installed)
                } catch (e: CancellationException) {
                    setState(id, diskState(m).let { if (it is ModelState.NotInstalled) it else ModelState.Paused(store.bytesOnDisk(m), m.totalBytes) })
                    throw e
                } catch (e: Exception) {
                    setState(id, ModelState.Failed(e.message ?: e.javaClass.simpleName, store.bytesOnDisk(m), m.totalBytes))
                } finally {
                    jobs.remove(id)
                }
            }
        }
    }

    fun downloadRecommended() = ModelCatalog.recommendedIds.forEach { download(it) }

    /** Stops a running/queued download; downloaded bytes are kept for resuming. */
    fun pause(id: String) {
        jobs.remove(id)?.cancel()
        model(id)?.let { setState(id, diskState(it)) }
    }

    fun delete(id: String) {
        jobs.remove(id)?.cancel()
        scope.launch(Dispatchers.IO) {
            store.delete(id)
            refresh()
            notifyActive()
        }
    }

    // ------------------------------------------------------------------------------------- import

    /**
     * Imports files picked by the user for a catalog model (e.g. downloaded on a computer).
     * Files are matched by name; a model with a single file accepts any name.
     * The SHA-256 must match the catalog.
     */
    suspend fun importFiles(id: String, uris: List<Uri>, resolver: ContentResolver): Result<Unit> = withContext(Dispatchers.IO) {
        val m = model(id) ?: return@withContext Result.failure(IOException("Unbekanntes Modell"))
        runCatching {
            val total = uris.sumOf { querySize(resolver, it) }
            var done = 0L
            setState(id, ModelState.Importing(0, total))
            for (uri in uris) {
                val name = queryName(resolver, uri) ?: "datei"
                val target = m.files.firstOrNull { it.path.substringAfterLast('/') == name }
                    ?: m.files.singleOrNull()
                    ?: throw IOException("„$name“ gehört nicht zu ${m.name}")
                val dest = store.file(id, target.path)
                dest.parentFile?.mkdirs()
                val tmp = File(dest.path + ".import")
                val sha = copyWithHash(resolver, uri, tmp) { n ->
                    done += n
                    setState(id, ModelState.Importing(done, total))
                }
                if (target.sha256 != null && !sha.equals(target.sha256, ignoreCase = true)) {
                    tmp.delete()
                    throw IOException("„$name“: SHA-256 passt nicht zu ${m.name}")
                }
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("Speichern fehlgeschlagen")
            }
            if (m.files.all { store.file(id, it.path).isFile }) store.markVerified(id)
            Unit
        }.also { r ->
            r.exceptionOrNull()?.let { setState(id, ModelState.Failed(it.message ?: "Import fehlgeschlagen", 0, m.totalBytes)) }
            refresh()
        }
    }

    /** Imports an arbitrary GGUF translation model or a whisper.cpp model. */
    suspend fun importCustom(uri: Uri, resolver: ContentResolver, kind: ModelKind, asrType: AsrType? = null): Result<ModelInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = queryName(resolver, uri) ?: "modell.gguf"
                val display = name.substringBeforeLast('.')
                val dir = store.createCustomDir(display)
                val size = querySize(resolver, uri)
                val pseudoId = dir.name
                setState(pseudoId, ModelState.Importing(0, size))
                var done = 0L
                val dest = File(dir, name)
                val sha = try {
                    copyWithHash(resolver, uri, dest) { n ->
                        done += n
                        setState(pseudoId, ModelState.Importing(done, size))
                    }
                } catch (e: Exception) {
                    dir.deleteRecursively()
                    _states.update { it - pseudoId }
                    throw e
                }
                val style = if (kind == ModelKind.TRANSLATION) guessPromptStyle(name) else null
                store.writeCustom(dir, ModelStore.CustomModelMeta(display, kind, name, dest.length(), sha, style, asrType))
                refresh()
                model(pseudoId) ?: throw IOException("Import fehlgeschlagen")
            }
        }

    private fun guessPromptStyle(fileName: String): PromptStyle {
        val n = fileName.lowercase()
        return when {
            "hy-mt" in n || "hunyuan-mt" in n || "hymt" in n -> PromptStyle.HY_MT
            "milmmt" in n || "gemmax" in n -> PromptStyle.MILMMT
            else -> PromptStyle.CHAT_TEMPLATE
        }
    }

    private fun copyWithHash(resolver: ContentResolver, uri: Uri, dest: File, onBytes: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = resolver.openInputStream(uri) ?: throw IOException("Datei kann nicht gelesen werden")
        input.use { inp ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    onBytes(n.toLong())
                }
            }
        }
        return digest.digest().toHex()
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun querySize(resolver: ContentResolver, uri: Uri): Long =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
}
