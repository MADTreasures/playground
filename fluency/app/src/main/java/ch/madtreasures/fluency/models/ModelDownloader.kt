package ch.madtreasures.fluency.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Minimal HTTP abstraction so the resume logic can be unit-tested without a network. */
fun interface RangeFetcher {
    /** Opens [url] starting at byte [offset]. */
    fun open(url: String, offset: Long): FetchResponse
}

class FetchResponse(
    /** HTTP status: 200 (full body), 206 (partial body from offset), 416 (offset beyond end) ... */
    val status: Int,
    val body: InputStream?,
    val contentLength: Long,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        runCatching { body?.close() }
        onClose()
    }
}

class UrlConnectionFetcher : RangeFetcher {
    override fun open(url: String, offset: Long): FetchResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "Fluency/1.0 (Android; offline translator)")
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        val status = conn.responseCode
        val body = if (status in 200..299) conn.inputStream else null
        return FetchResponse(status, body, conn.contentLengthLong) { conn.disconnect() }
    }
}

class ChecksumMismatchException(val file: String) : IOException("Prüfsumme (SHA-256) stimmt nicht: $file")

/**
 * Resumable downloads with SHA-256 verification.
 *
 * Incomplete files are kept as `<name>.part`; a later call continues with an HTTP Range request.
 * The hash is computed while streaming (an existing `.part` is hashed first), so a fresh download
 * reads every byte only once.
 */
class ModelDownloader(
    private val store: ModelStore,
    private val fetcher: RangeFetcher = UrlConnectionFetcher(),
    private val maxRetries: Int = 6,
    private val parallelSmallFiles: Int = 6,
) {
    data class Progress(val bytesDone: Long, val bytesTotal: Long, val currentFile: String, val verifying: Boolean = false)

    suspend fun download(model: ModelInfo, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        require(!model.custom) { "custom models cannot be downloaded" }
        store.dir(model.id).mkdirs()
        store.clearVerified(model.id)
        val total = model.totalBytes
        val done = AtomicLong(model.files.sumOf { f -> completedBytes(model, f) })
        val reporter = ThrottledReporter(onProgress)
        reporter.report(Progress(done.get(), total, ""), force = true)

        val (big, small) = model.files.partition { it.size > SMALL_FILE }
        // big files one after another (full bandwidth each), small ones in parallel
        for (f in big) {
            downloadFile(model, f) { delta, verifying ->
                reporter.report(Progress(done.addAndGet(delta), total, f.path, verifying))
            }
        }
        coroutineScope {
            val sem = Semaphore(parallelSmallFiles)
            small.map { f ->
                async {
                    sem.withPermit {
                        downloadFile(model, f) { delta, _ ->
                            reporter.report(Progress(done.addAndGet(delta), total, f.path))
                        }
                    }
                }
            }.awaitAll()
        }
        store.markVerified(model.id)
        reporter.report(Progress(total, total, ""), force = true)
    }

    private fun completedBytes(model: ModelInfo, f: RemoteFile): Long {
        val target = store.file(model.id, f.path)
        if (target.isFile && target.length() == f.size) return f.size
        return store.partFile(model.id, f.path).takeIf { it.isFile }?.length()?.coerceAtMost(f.size) ?: 0L
    }

    /** @param progress called with the number of new bytes (may be negative after a restart) */
    private suspend fun downloadFile(model: ModelInfo, f: RemoteFile, progress: (Long, Boolean) -> Unit) {
        val target = store.file(model.id, f.path)
        if (target.isFile && target.length() == f.size) return // completed in an earlier run (hash was checked then)
        target.parentFile?.mkdirs()
        val part = store.partFile(model.id, f.path)
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                fetchInto(f, part, progress)
                if (!part.renameTo(target)) throw IOException("Umbenennen fehlgeschlagen: ${target.name}")
                return
            } catch (e: ChecksumMismatchException) {
                progress(-part.length(), false)
                part.delete()
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt > maxRetries) throw e
                delay(minOf(30_000L, 1_000L shl attempt))
            }
        }
    }

    private suspend fun fetchInto(f: RemoteFile, part: File, progress: (Long, Boolean) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = if (part.isFile) part.length() else 0L
        if (offset > f.size && f.size > 0) {
            progress(-offset, false)
            part.delete()
            offset = 0
        }
        // hash what is already there
        if (offset > 0) {
            progress(0, true)
            part.inputStream().use { input -> copyInto(input, null, digest) { } }
        }
        if (offset < f.size || f.size <= 0) {
            fetcher.open(f.url, offset).use { resp ->
                val append = when (resp.status) {
                    206 -> true
                    200 -> {
                        // server ignored the range: start over
                        if (offset > 0) {
                            progress(-offset, false)
                            digest.reset()
                            offset = 0
                        }
                        false
                    }
                    416 -> if (offset >= f.size) true else throw IOException("HTTP 416 bei ${f.path}")
                    else -> throw IOException("HTTP ${resp.status} bei ${f.path}")
                }
                val body = resp.body
                if (body != null && !(resp.status == 416)) {
                    FileOutputStream(part, append).use { out ->
                        copyInto(body, out, digest) { n -> progress(n, false) }
                    }
                }
            }
        }
        val length = part.length()
        if (f.size > 0 && length != f.size) throw IOException("Unvollständig: ${f.path} ($length von ${f.size} Bytes)")
        val expected = f.sha256
        if (expected != null) {
            val actual = digest.digest().toHex()
            if (!actual.equals(expected, ignoreCase = true)) throw ChecksumMismatchException(f.path)
        }
    }

    private suspend fun copyInto(input: InputStream, out: FileOutputStream?, digest: MessageDigest, onBytes: (Long) -> Unit) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
            out?.write(buf, 0, n)
            onBytes(n.toLong())
        }
    }

    private class ThrottledReporter(private val sink: (Progress) -> Unit) {
        @Volatile private var last = 0L

        fun report(p: Progress, force: Boolean = false) {
            val now = System.nanoTime()
            if (force || p.verifying || now - last > 200_000_000L) {
                last = now
                sink(p)
            }
        }
    }

    companion object {
        private const val SMALL_FILE = 8L * 1024 * 1024

        fun sha256(file: File): String {
            val d = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    d.update(buf, 0, n)
                }
            }
            return d.digest().toHex()
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
