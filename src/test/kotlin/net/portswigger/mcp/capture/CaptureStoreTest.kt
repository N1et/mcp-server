package net.portswigger.mcp.capture

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class CaptureStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `identical payloads share storage without removing capture events`() {
        val store = CaptureStore(tempDir.resolve("store"))
        val entry = entry()
        store.replaceRefs("one", emptyList())
        store.replaceRefs("two", emptyList())
        val first = store.append("one", entry, CapturePolicy())
        val duplicate = store.append("one", entry.copy(timestampMs = 5678L), CapturePolicy())
        val otherSession = store.append("two", entry, CapturePolicy())

        assertEquals(first.blobId, duplicate.blobId)
        assertEquals(first.blobId, otherSession.blobId)
        assertEquals(listOf(first, duplicate), store.loadRefs("one"))
        assertEquals(entry, store.read(first))
        assertEquals(5678L, store.read(duplicate).timestampMs)
        assertEquals(1, store.stats().blobFiles)
        assertEquals(2, store.stats().sessionFiles)
    }

    @Test
    fun `changed responses are not deduplicated with identical requests`() {
        val store = CaptureStore(tempDir.resolve("store"))
        store.replaceRefs("flow", emptyList())
        val first = store.append("flow", entry(response = "HTTP/1.1 200 OK\r\n\r\none"), CapturePolicy())
        val second = store.append("flow", entry(response = "HTTP/1.1 200 OK\r\n\r\ntwo"), CapturePolicy())

        assertEquals("HTTP/1.1 200 OK\r\n\r\none", store.read(first).response)
        assertEquals("HTTP/1.1 200 OK\r\n\r\ntwo", store.read(second).response)
        assertEquals(2, store.stats().blobFiles)
    }

    @Test
    fun `truncation preserves valid UTF-8 for two and four byte characters`() {
        val store = CaptureStore(tempDir.resolve("store"))
        val requestPrefix = "POST /api HTTP/1.1\r\nHost: example.test\r\n\r\n"
        val responsePrefix = "HTTP/1.1 200 OK\r\n\r\n"
        val policy = CapturePolicy(
            maxRequestBytes = requestPrefix.toByteArray().size + 1,
            maxResponseBytes = responsePrefix.toByteArray().size + 3
        )
        store.replaceRefs("limited", emptyList())
        val ref = store.append(
            "limited", entry(requestPrefix + "éé", responsePrefix + "😀😀"), policy
        )
        val restored = store.read(ref)

        assertEquals(requestPrefix, restored.request)
        assertEquals(responsePrefix, restored.response)
        assertTrue(ref.requestTruncated)
        assertTrue(ref.responseTruncated)
        assertTrue(ref.hasRequestBody)
        assertEquals(requestPrefix.toByteArray().size, ref.storedRequestBytes)
        assertEquals(responsePrefix.toByteArray().size, ref.storedResponseBytes)
    }

    @Test
    fun `reopened stores retain entries and null responses`() {
        val root = tempDir.resolve("store")
        val original = entry().copy(response = null)
        val store = CaptureStore(root)
        store.replaceRefs("session", emptyList())
        val ref = store.append("session", original, CapturePolicy())
        val reopened = CaptureStore(root)

        assertEquals(listOf(ref), reopened.loadRefs("session"))
        assertEquals(original, reopened.read(ref))
        assertFalse(ref.responseTruncated)
    }

    @Test
    fun `cleanup preserves shared payloads and only deletes unreferenced blobs`() {
        val store = CaptureStore(tempDir.resolve("store"))
        listOf("one", "two", "orphan").forEach { store.replaceRefs(it, emptyList()) }
        val shared = store.append("one", entry(), CapturePolicy())
        store.append("two", entry(), CapturePolicy())
        store.append("orphan", entry(response = "unreferenced response"), CapturePolicy())
        store.deleteSession("one")
        store.deleteSession("orphan")

        val preview = store.cleanup(dryRun = true)
        assertEquals(1, preview.orphanBlobs)
        assertEquals(0, preview.deletedBlobs)
        assertEquals(2, store.stats().blobFiles)

        val cleanup = store.cleanup(dryRun = false)
        assertEquals(1, cleanup.deletedBlobs)
        assertEquals(1, store.stats().blobFiles)
        assertEquals(entry(), store.read(shared))
        assertEquals(listOf(shared), store.loadRefs("two"))
    }

    @Test
    fun `cleanup refuses to delete anything when an index is corrupt`() {
        val root = tempDir.resolve("store")
        val store = CaptureStore(root)
        store.replaceRefs("kept", emptyList())
        val kept = store.append("kept", entry(), CapturePolicy())
        val index = Files.list(root.resolve("sessions")).use { it.findFirst().orElseThrow() }
        Files.writeString(index, "not-json\n", APPEND)
        store.replaceRefs("orphan", emptyList())
        val orphan = store.append("orphan", entry(response = "orphan"), CapturePolicy())
        store.deleteSession("orphan")

        assertThrows(IOException::class.java) { store.cleanup(dryRun = false) }
        assertEquals(entry(), store.read(kept))
        assertEquals("orphan", store.read(orphan).response)
        assertThrows(IOException::class.java) { store.loadRefs("kept") }
    }

    @Test
    fun `missing indexes are reported instead of becoming empty sessions`() {
        val store = CaptureStore(tempDir.resolve("store"))
        store.replaceRefs("empty", emptyList())
        assertEquals(emptyList<CapturedEntryRef>(), store.loadRefs("empty"))
        assertThrows(NoSuchFileException::class.java) { store.loadRefs("missing") }
    }

    @Test
    fun `payload identifiers cannot escape the store`() {
        val store = CaptureStore(tempDir.resolve("store"))
        store.replaceRefs("session", emptyList())
        val ref = store.append("session", entry(), CapturePolicy())
        assertThrows(IOException::class.java) { store.read(ref.copy(blobId = "../../outside")) }
        assertThrows(IOException::class.java) {
            store.replaceRefs("session", listOf(ref.copy(blobId = "../invalid")))
        }
        assertEquals(listOf(ref), store.loadRefs("session"))
    }

    @Test
    fun `session names are safe filenames even when they contain path separators`() {
        val store = CaptureStore(tempDir.resolve("store"))
        store.replaceRefs("../outside", emptyList())
        val ref = store.append("../outside", entry(), CapturePolicy())
        assertEquals(listOf(ref), store.loadRefs("../outside"))
        assertFalse(Files.exists(tempDir.resolve("outside")))
    }

    @Test
    fun `capture payloads and indexes are private on POSIX filesystems`() {
        val root = tempDir.resolve("store")
        assumeTrue("posix" in root.fileSystem.supportedFileAttributeViews())
        val store = CaptureStore(root)
        store.replaceRefs("session", emptyList())
        store.append("session", entry(), CapturePolicy())
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                val expected = if (Files.isDirectory(path)) "rwx------" else "rw-------"
                assertEquals(PosixFilePermissions.fromString(expected), Files.getPosixFilePermissions(path))
            }
        }
    }

    @Test
    fun `missing expected indexes block cleanup and are not recreated by append`() {
        val root = tempDir.resolve("store")
        val store = CaptureStore(root)
        store.replaceRefs("session", emptyList())
        val ref = store.append("session", entry(), CapturePolicy())
        val index = Files.list(root.resolve("sessions")).use { it.findFirst().orElseThrow() }
        Files.delete(index)

        assertThrows(IOException::class.java) { store.cleanup(false, listOf("session")) }
        assertThrows(NoSuchFileException::class.java) { store.append("session", entry(), CapturePolicy()) }
        assertFalse(Files.exists(index))
        assertEquals(entry(), store.read(ref))
    }

    @Test
    fun `a partial append is rolled back without losing earlier captures`() {
        val root = tempDir.resolve("store")
        val store = CaptureStore(root)
        store.replaceRefs("session", emptyList())
        val first = store.append("session", entry(), CapturePolicy())
        val index = Files.list(root.resolve("sessions")).use { it.findFirst().orElseThrow() }
        FileChannel.open(index, WRITE).use { real ->
            val failing = partialWriteChannel(real)
            assertThrows(IOException::class.java) {
                store.appendRecord(index, failing, "new record\n".toByteArray())
            }
        }
        assertEquals(listOf(first), store.loadRefs("session"))
        val next = store.append("session", entry(response = "next"), CapturePolicy())
        assertEquals(listOf(first, next), store.loadRefs("session"))
        assertEquals(entry(), store.read(first))
    }

    @Test
    fun `failed rollback prevents subsequent writes to the damaged index`() {
        val root = tempDir.resolve("store")
        val store = CaptureStore(root)
        store.replaceRefs("session", emptyList())
        store.append("session", entry(), CapturePolicy())
        val index = Files.list(root.resolve("sessions")).use { it.findFirst().orElseThrow() }
        FileChannel.open(index, WRITE).use { real ->
            val failing = partialWriteChannel(real, rollbackFails = true)
            val failure = assertThrows(IOException::class.java) {
                store.appendRecord(index, failing, "new record\n".toByteArray())
            }
            assertEquals("simulated rollback failure", failure.suppressed.single().message)
        }
        val damaged = Files.readAllBytes(index)
        assertThrows(IOException::class.java) { store.append("session", entry(), CapturePolicy()) }
        assertArrayEquals(damaged, Files.readAllBytes(index))
    }

    @Test
    fun `rejecting another owner preserves the native lease until the owner closes`() {
        val root = tempDir.resolve("store")
        val owner = CaptureStore(root)
        val rejected = CaptureStore(root)
        owner.claimOwnership()
        try {
            assertThrows(IllegalStateException::class.java) { rejected.claimOwnership() }
            rejected.close()
            CaptureStore(root).use { third ->
                assertThrows(IllegalStateException::class.java) { third.claimOwnership() }
            }
            assertNativeLeaseHeld(root)
        } finally {
            owner.close()
        }
        rejected.claimOwnership()
        try {
            assertNativeLeaseHeld(root)
        } finally {
            rejected.close()
        }
    }

    private fun assertNativeLeaseHeld(root: Path) {
        val probeClass = CaptureStoreLockProbe::class.java
        val classPath = Path.of(probeClass.protectionDomain.codeSource.location.toURI()).toString()
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classPath, probeClass.name, root.resolve(".owner.lock").toString()
        ).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Native lock probe timed out")
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
        } finally {
            process.destroyForcibly()
        }
    }

    private fun partialWriteChannel(real: FileChannel, rollbackFails: Boolean = false): FileChannel {
        val channel = mockk<FileChannel>()
        every { channel.size() } answers { real.size() }
        every { channel.position(any<Long>()) } answers {
            real.position(firstArg<Long>())
            channel
        }
        every { channel.write(any<ByteBuffer>()) } answers {
            real.write(ByteBuffer.wrap("{\"blob".toByteArray()))
            throw IOException("simulated write failure")
        }
        every { channel.truncate(any<Long>()) } answers {
            if (rollbackFails) throw IOException("simulated rollback failure")
            real.truncate(firstArg<Long>())
            channel
        }
        return channel
    }

    private fun entry(
        request: String = "GET /api HTTP/1.1\r\nHost: example.test\r\n\r\n",
        response: String = "HTTP/1.1 200 OK\r\n\r\nhello"
    ) = CapturedEntry(
        request = request,
        response = response,
        host = "example.test",
        port = 443,
        method = request.substringBefore(' '),
        path = request.substringAfter(' ').substringBefore(' '),
        statusCode = 200,
        timestampMs = 1234L
    )
}
