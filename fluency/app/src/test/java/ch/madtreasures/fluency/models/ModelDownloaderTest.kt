package ch.madtreasures.fluency.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

class ModelDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val big = Random(1).nextBytes(20 * 1024 * 1024 + 123) // > SMALL_FILE: sequential path
    private val small = Random(2).nextBytes(4096)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()

    private fun model(files: List<Pair<String, ByteArray>>, badHash: Boolean = false) = ModelInfo(
        id = "m", kind = ModelKind.TRANSLATION, name = "M", description = "", license = "", source = "",
        files = files.map { (p, b) -> RemoteFile("mem://$p", p, b.size.toLong(), if (badHash) "0".repeat(64) else sha(b)) },
    )

    /** Serves files from memory; can fail once after [failAfter] bytes or ignore Range requests. */
    private class FakeServer(
        val files: Map<String, ByteArray>,
        var failAfter: Long = -1,
        val ignoreRange: Boolean = false,
    ) : RangeFetcher {
        val requests = mutableListOf<Pair<String, Long>>()
        override fun open(url: String, offset: Long): FetchResponse {
            requests += url to offset
            val data = files.getValue(url.removePrefix("mem://"))
            if (offset >= data.size && !ignoreRange) return FetchResponse(416, null, 0)
            val start = if (ignoreRange) 0 else offset.toInt()
            val fail = failAfter
            failAfter = -1
            val body = object : InputStream() {
                var pos = start
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos >= data.size) return -1
                    if (fail >= 0 && pos - start >= fail) throw IOException("connection reset")
                    val n = minOf(len, data.size - pos, 64 * 1024)
                    System.arraycopy(data, pos, b, off, n)
                    pos += n
                    return n
                }
            }
            return FetchResponse(if (ignoreRange || offset == 0L) 200 else 206, body, (data.size - start).toLong())
        }
    }

    @Test fun downloadsAndVerifies() = runBlocking {
        val store = ModelStore(tmp.root)
        val m = model(listOf("a.bin" to big, "dir/b.txt" to small))
        val server = FakeServer(mapOf("a.bin" to big, "dir/b.txt" to small))
        val progress = mutableListOf<ModelDownloader.Progress>()
        ModelDownloader(store, server).download(m) { progress += it }
        assertTrue(store.isInstalled(m))
        assertArrayEquals(big, store.file("m", "a.bin").readBytes())
        assertArrayEquals(small, store.file("m", "dir/b.txt").readBytes())
        assertEquals(m.totalBytes, progress.last().bytesDone)
        assertFalse(store.partFile("m", "a.bin").exists())
    }

    @Test fun resumesWithRangeAfterConnectionLoss() = runBlocking {
        val store = ModelStore(tmp.root)
        val m = model(listOf("a.bin" to big))
        val server = FakeServer(mapOf("a.bin" to big), failAfter = 5_000_000)
        ModelDownloader(store, server, maxRetries = 3).download(m) {}
        assertTrue(store.isInstalled(m))
        assertArrayEquals(big, store.file("m", "a.bin").readBytes())
        assertEquals(2, server.requests.size)
        assertEquals(0L, server.requests[0].second)
        assertTrue("second request resumes", server.requests[1].second >= 5_000_000L)
    }

    @Test fun resumesPartialFileFromEarlierRun() = runBlocking {
        val store = ModelStore(tmp.root)
        val m = model(listOf("a.bin" to big))
        store.dir("m").mkdirs()
        store.partFile("m", "a.bin").writeBytes(big.copyOf(7_000_000))
        assertTrue(store.hasPartialData(m))
        val server = FakeServer(mapOf("a.bin" to big))
        ModelDownloader(store, server).download(m) {}
        assertEquals(7_000_000L, server.requests.single().second)
        assertArrayEquals(big, store.file("m", "a.bin").readBytes())
    }

    @Test fun restartsWhenServerIgnoresRange() = runBlocking {
        val store = ModelStore(tmp.root)
        val m = model(listOf("a.bin" to big))
        store.dir("m").mkdirs()
        store.partFile("m", "a.bin").writeBytes(big.copyOf(1_000_000))
        ModelDownloader(store, FakeServer(mapOf("a.bin" to big), ignoreRange = true)).download(m) {}
        assertArrayEquals(big, store.file("m", "a.bin").readBytes())
    }

    @Test fun rejectsChecksumMismatchAndDeletesData() = runBlocking {
        val store = ModelStore(tmp.root)
        val m = model(listOf("b.txt" to small), badHash = true)
        try {
            ModelDownloader(store, FakeServer(mapOf("b.txt" to small))).download(m) {}
            fail("expected checksum error")
        } catch (e: ChecksumMismatchException) {
            assertEquals("b.txt", e.file)
        }
        assertFalse(store.isInstalled(m))
        assertFalse(store.partFile("m", "b.txt").exists())
        assertFalse(store.file("m", "b.txt").exists())
    }

    @Test fun completedPartWithout416IsAccepted() = runBlocking {
        val store = ModelStore(tmp.root)
        val m = model(listOf("b.txt" to small))
        store.dir("m").mkdirs()
        store.partFile("m", "b.txt").writeBytes(small) // complete but not yet renamed
        val server = FakeServer(mapOf("b.txt" to small))
        ModelDownloader(store, server).download(m) {}
        assertTrue(store.isInstalled(m))
        assertTrue("no request needed", server.requests.isEmpty())
    }

    @Test fun deleteRemovesEverything() = runBlocking {
        val store = ModelStore(tmp.root)
        val m = model(listOf("b.txt" to small))
        ModelDownloader(store, FakeServer(mapOf("b.txt" to small))).download(m) {}
        assertTrue(store.isInstalled(m))
        store.delete("m")
        assertFalse(store.isInstalled(m))
        assertEquals(0L, store.bytesOnDisk(m))
    }
}
