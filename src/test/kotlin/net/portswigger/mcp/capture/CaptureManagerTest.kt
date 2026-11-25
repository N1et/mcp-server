package net.portswigger.mcp.capture

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CaptureManagerTest {
    @TempDir
    lateinit var tempDir: Path
    private val managers = mutableListOf<CaptureManager>()

    @AfterEach
    fun closeManagers() {
        managers.asReversed().forEach { it.close() }
    }

    @Test
    fun `capturing writes payloads externally without rewriting the manifest`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session")

        manager.captureResponse(response("/api/users"))

        assertEquals(1, manager.getCapture("session")?.entryCount)
        assertEquals("/api/users", manager.getCaptureEntry("session", 0)?.path)
        verify(exactly = 1) { fixture.persisted.setString("capture_sessions_v2", any()) }
        assertFalse(fixture.storage.getValue("capture_sessions_v2").contains("synthetic-body"))
        assertEquals(1, manager.storageStats().blobFiles)
    }

    @Test
    fun `reopening v2 preserves payloads and restores sessions stopped`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session")
        manager.captureResponse(response("/api/users"))
        val captured = manager.getCaptureEntry("session", 0)
        manager.close()

        val reopened = fixture.manager()

        assertEquals(captured, reopened.getCaptureEntry("session", 0))
        assertFalse(reopened.getCapture("session")!!.active)
        assertEquals(1, reopened.getCapture("session")!!.entryCount)
    }

    @Test
    fun `new empty sessions have an index and reopen successfully`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("empty")

        assertEquals(1, manager.storageStats().sessionFiles)
        manager.close()
        assertEquals(0, fixture.manager().getCapture("empty")!!.entryCount)
    }

    @Test
    fun `missing auxiliary store ID is recovered from the manifest`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session")
        manager.captureResponse(response("/api/users"))
        manager.close()
        val storeId = fixture.storage.remove("capture_store_id")

        val reopened = fixture.manager()

        assertEquals(storeId, fixture.storage["capture_store_id"])
        assertEquals("/api/users", reopened.getCaptureEntry("session", 0)?.path)
    }

    @Test
    fun `conflicting IDs reject loading without changing legacy or manifest data`() {
        val fixture = fixture()
        fixture.manager().apply { startCapture("session"); close() }
        fixture.storage["capture_store_id"] = UUID.randomUUID().toString()
        fixture.storage["capture_sessions"] = legacyData()
        val persistedBefore = fixture.storage.toMap()
        val manager = CaptureManager(fixture.api, tempDir)

        val failure = assertThrows(IllegalStateException::class.java) { manager.register() }

        assertTrue(failure.message!!.contains("conflicts"))
        assertEquals(persistedBefore, fixture.storage)
        assertTrue(manager.listSessions().isEmpty())
    }

    @Test
    fun `invalid store IDs never resolve a storage directory`() {
        val fixture = fixture(mapOf("capture_store_id" to "../outside"))
        val manager = CaptureManager(fixture.api, tempDir)

        val failure = assertThrows(IllegalStateException::class.java) { manager.register() }

        assertTrue(failure.message!!.contains("UUID"))
        Files.list(tempDir).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `missing store fails without creating a replacement or falling back to legacy`() {
        val fixture = fixture()
        fixture.manager().apply { startCapture("session"); close() }
        fixture.storage["capture_sessions"] = legacyData()
        val persistedBefore = fixture.storage.toMap()
        val differentBase = tempDir.resolve("missing-base")
        val manager = CaptureManager(fixture.api, differentBase)

        val failure = assertThrows(IllegalStateException::class.java) { manager.register() }

        assertTrue(failure.message!!.contains("directory is missing"))
        assertFalse(Files.exists(differentBase))
        assertEquals(persistedBefore, fixture.storage)
        assertTrue(manager.listSessions().isEmpty())
    }

    @Test
    fun `missing persisted index is an error rather than an empty session`() {
        val fixture = fixture()
        fixture.manager().apply { startCapture("session"); close() }
        val index = sessionIndex(fixture)
        Files.delete(index)
        val persistedBefore = fixture.storage.toMap()
        val manager = CaptureManager(fixture.api, tempDir)

        val failure = assertThrows(IllegalStateException::class.java) { manager.register() }

        assertNotNull(failure.cause)
        assertFalse(Files.exists(index))
        assertEquals(persistedBefore, fixture.storage)
        assertTrue(manager.listSessions().isEmpty())
        Files.writeString(index, "")
        assertEquals(0, fixture.manager().getCapture("session")!!.entryCount)
    }

    @Test
    fun `invalid manifest does not migrate or delete legacy data`() {
        val fixture = fixture(mapOf("capture_sessions_v2" to "invalid-json", "capture_sessions" to legacyData()))
        val before = fixture.storage.toMap()

        assertThrows(IllegalStateException::class.java) { fixture.manager() }

        assertEquals(before, fixture.storage)
        Files.list(tempDir).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `migration preserves payloads larger than current defaults across repeated loads`() {
        val request = "POST /legacy HTTP/1.1\r\nHost: example.com\r\n\r\n" +
            "x".repeat(CapturePolicy.DEFAULT_MAX_REQUEST_BYTES + 1)
        val response = "HTTP/1.1 200 OK\r\n\r\n" + "y".repeat(CapturePolicy.DEFAULT_MAX_RESPONSE_BYTES + 1)
        val fixture = fixture(mapOf("capture_sessions" to legacyData(request, response)))

        repeat(3) {
            val manager = fixture.manager()
            assertNull(fixture.storage["capture_sessions"])
            assertEquals(request, manager.getCaptureEntry("legacy", 0)?.request)
            assertEquals(response, manager.getCaptureEntry("legacy", 0)?.response)
            assertFalse(manager.getCapture("legacy")!!.active)
            assertFalse(manager.getEntryRefs("legacy").single().truncated)
            manager.close()
        }
        verify(exactly = 1) { fixture.persisted.deleteString("capture_sessions") }
    }

    @Test
    fun `failed migration manifest retains legacy and can retry without duplicates`() {
        val legacy = legacyData()
        val fixture = fixture(mapOf("capture_sessions" to legacy))
        fixture.failManifestWrites = true
        val failed = CaptureManager(fixture.api, tempDir)

        assertThrows(IllegalStateException::class.java) { failed.register() }
        assertEquals(legacy, fixture.storage["capture_sessions"])
        assertNull(fixture.storage["capture_sessions_v2"])
        assertTrue(failed.listSessions().isEmpty())
        verify(exactly = 0) { fixture.persisted.deleteString("capture_sessions") }

        fixture.failManifestWrites = false
        val retried = fixture.manager()
        assertEquals(1, retried.getCapture("legacy")!!.entryCount)
        assertEquals("HTTP/1.1 200 OK\r\n\r\nsynthetic-body", retried.getCaptureEntry("legacy", 0)?.response)
        retried.close()
        assertEquals(1, fixture.manager().getCapture("legacy")!!.entryCount)
    }

    @Test
    fun `failed manifest mutations preserve the live session state`() {
        val fixture = fixture()
        val manager = fixture.manager()
        fixture.failManifestWrites = true
        assertThrows(IllegalStateException::class.java) { manager.startCapture("failed") }
        assertNull(manager.getCapture("failed"))

        fixture.failManifestWrites = false
        manager.startCapture("session")
        manager.captureResponse(response("/one"))
        fixture.failManifestWrites = true
        assertThrows(IllegalStateException::class.java) { manager.stopCapture("session") }
        assertTrue(manager.getCapture("session")!!.active)
        assertThrows(IllegalStateException::class.java) { manager.deleteCapture("session") }
        assertEquals(1, manager.getCapture("session")!!.entryCount)
        manager.captureResponse(response("/two"))
        assertEquals(2, manager.getCapture("session")!!.entryCount)

        fixture.failManifestWrites = false
        manager.stopCapture("session")
        val stoppedAt = manager.getCapture("session")!!.stoppedAt
        fixture.failManifestWrites = true
        assertThrows(IllegalStateException::class.java) {
            manager.startCapture("session", CapturePolicy(excludeStaticAssets = false))
        }
        assertFalse(manager.getCapture("session")!!.active)
        assertEquals(stoppedAt, manager.getCapture("session")!!.stoppedAt)
        assertTrue(manager.getCapture("session")!!.policy.excludeStaticAssets)
        fixture.failManifestWrites = false
    }

    @Test
    fun `entry deletion persists the remaining entries and subsequent captures`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session")
        manager.captureResponse(response("/one"))
        manager.captureResponse(response("/two"))

        assertTrue(manager.deleteCaptureEntry("session", 0))
        manager.captureResponse(response("/three"))

        assertEquals(listOf("/two", "/three"), manager.getEntryRefs("session").map { it.path })
        assertFalse(manager.deleteCaptureEntry("session", -1))
        assertFalse(manager.deleteCaptureEntry("session", 2))
        manager.close()
        assertEquals(listOf("/two", "/three"), fixture.manager().getEntryRefs("session").map { it.path })
    }

    @Test
    fun `failed index replacement leaves live entries untouched`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session")
        manager.captureResponse(response("/one"))
        val index = sessionIndex(fixture)
        val bytes = Files.readAllBytes(index)
        Files.delete(index)
        Files.createDirectory(index)
        Files.writeString(index.resolve("prevent-replacement"), "synthetic")

        assertThrows(IllegalStateException::class.java) { manager.deleteCaptureEntry("session", 0) }
        assertEquals("/one", manager.getEntryRefs("session").single().path)

        Files.delete(index.resolve("prevent-replacement"))
        Files.delete(index)
        Files.write(index, bytes)
        manager.close()
        assertEquals("/one", fixture.manager().getEntryRefs("session").single().path)
    }

    @Test
    fun `failed external deletion reports partial failure and notifies listeners`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session")
        val index = sessionIndex(fixture)
        Files.delete(index)
        Files.createDirectory(index)
        Files.writeString(index.resolve("prevent-deletion"), "synthetic")
        var notifications = 0
        manager.addChangeListener { notifications++ }

        val failure = assertThrows(IllegalStateException::class.java) { manager.deleteCapture("session") }

        assertTrue(failure.message!!.contains("external cleanup warning"))
        assertNull(manager.getCapture("session"))
        assertEquals(1, notifications)
        manager.close()
        assertTrue(fixture.manager().listSessions().isEmpty())
    }

    @Test
    fun `snapshot reference never resolves to a replacement entry`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session")
        manager.captureResponse(response("/original"))
        manager.captureResponse(response("/replacement"))
        val originalRef = manager.getEntryRefs("session").first()

        manager.deleteCaptureEntry("session", 0)

        assertEquals("/replacement", manager.getCaptureEntry("session", 0)?.path)
        assertEquals("/original", manager.getCaptureEntry(originalRef).path)

        manager.deleteCapture("session")
        manager.startCapture("session")
        manager.captureResponse(response("/new-session"))

        assertEquals("/new-session", manager.getCaptureEntry("session", 0)?.path)
        assertEquals("/original", manager.getCaptureEntry(originalRef).path)

        manager.cleanupStorage(dryRun = false)

        assertThrows(IllegalStateException::class.java) { manager.getCaptureEntry(originalRef) }
        assertEquals("/new-session", manager.getCaptureEntry("session", 0)?.path)
    }

    @Test
    fun `large summaries bound endpoint output without changing total statistics`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session")
        manager.captureResponse(response("/first"))
        val session = manager.getCapture("session")!!
        val reference = session.entryRefs().single()
        repeat(1000) { session.addEntry(reference.copy(path = "/endpoint/$it")) }

        val summary = manager.getCaptureSummary("session", true)!!

        assertTrue(summary.contains("1001 entries, 1001 relevant"))
        assertTrue(summary.contains("#999 GET /endpoint/998"))
        assertFalse(summary.contains("#1000 "))
        assertTrue(summary.contains("1 additional endpoints omitted"))
        assertTrue(summary.contains("Hosts: example.com:443 (1001)"))
        assertTrue(summary.contains("Methods: GET (1001)"))
    }

    @Test
    fun `reentrant deletion during response reading cannot resurrect an old session`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session")
        val response = response("/late")
        every { response.toString() } answers {
            manager.deleteCapture("session")
            manager.startCapture("session")
            "HTTP/1.1 200 OK\r\n\r\nlate-body"
        }

        manager.captureResponse(response)

        assertEquals(0, manager.getCapture("session")!!.entryCount)
        manager.close()
        assertEquals(0, fixture.manager().getCapture("session")!!.entryCount)
    }

    @Test
    fun `capture and delete serialize without recreating a deleted index`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session")
        val response = response("/late")
        val readingResponse = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val deleteStarted = CountDownLatch(1)
        every { response.toString() } answers {
            readingResponse.countDown()
            check(releaseResponse.await(5, TimeUnit.SECONDS))
            "HTTP/1.1 200 OK\r\n\r\nlate-body"
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val captured = executor.submit { manager.captureResponse(response) }
            assertTrue(readingResponse.await(5, TimeUnit.SECONDS))
            val deleted = executor.submit<String> {
                deleteStarted.countDown()
                manager.deleteCapture("session")
            }
            assertTrue(deleteStarted.await(5, TimeUnit.SECONDS))
            releaseResponse.countDown()
            captured.get(5, TimeUnit.SECONDS)
            assertTrue(deleted.get(5, TimeUnit.SECONDS).contains("deleted"))
            assertNull(manager.getCapture("session"))
            assertEquals(0, manager.storageStats().sessionFiles)
            manager.close()
            assertTrue(fixture.manager().listSessions().isEmpty())
        } finally {
            releaseResponse.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `filters limits and stats use refs without reading payloads`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session", CapturePolicy(maxEntries = 1, inScopeOnly = true))
        every { fixture.api.scope().isInScope(any()) } returns false
        manager.captureResponse(response("/outside"))
        every { fixture.api.scope().isInScope(any()) } returns true
        manager.captureResponse(response("/assets/app.js"))
        manager.captureResponse(response("/inside"))
        manager.captureResponse(response("/dropped"))
        assertEquals(1, manager.getCapture("session")!!.entryCount)
        assertEquals(2L, manager.getCapture("session")!!.filteredEntries)
        assertEquals(1L, manager.getCapture("session")!!.droppedEntries)
        val refs = manager.getEntryRefs("session")
        val root = tempDir.resolve(fixture.storage.getValue("capture_store_id"))
        val blob = refs.single().blobId
        Files.delete(root.resolve("blobs").resolve(blob.take(2)).resolve("$blob.json.gz"))

        val stats = manager.getCaptureStats("session")!!
        assertTrue(stats.contains("1 entries"))
        assertTrue(stats.contains("filtered=2, dropped=1"))
        assertFalse(stats.contains("Endpoints:"))
        assertFalse(stats.contains("/inside"))
        assertTrue(manager.getCaptureSummary("session", true)!!.contains("#0 GET /inside"))
        assertThrows(IllegalStateException::class.java) { manager.getCaptureEntry("session", 0) }
    }

    @Test
    fun `capture payload limits produce honest truncation stats`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture(
            "session",
            CapturePolicy(maxRequestBytes = 1024, maxResponseBytes = 1024)
        )
        val response = response("/large")
        val request = response.initiatingRequest()
        every { request.toString() } returns "POST /large HTTP/1.1\r\n\r\n" + "x".repeat(2048)
        every { response.toString() } returns "HTTP/1.1 200 OK\r\n\r\n" + "y".repeat(2048)

        manager.captureResponse(response)

        val ref = manager.getEntryRefs("session").single()
        assertTrue(ref.requestTruncated)
        assertTrue(ref.responseTruncated)
        assertEquals(1024, ref.storedRequestBytes)
        assertEquals(1024, ref.storedResponseBytes)
        assertTrue(manager.getCaptureStats("session")!!.contains("truncated=1"))
        assertTrue(manager.getCaptureSummary("session", false)!!.contains("[truncated]"))
    }

    @Test
    fun `scope lookup failure drops the capture without storing a payload`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session", CapturePolicy(inScopeOnly = true))
        every { fixture.api.scope().isInScope(any()) } throws IllegalStateException("Synthetic scope failure")

        manager.captureResponse(response("/scope-error"))

        assertEquals(0, manager.getCapture("session")!!.entryCount)
        assertEquals(1L, manager.getCapture("session")!!.droppedEntries)
        assertEquals(0, manager.storageStats().blobFiles)
    }

    @Test
    fun `reentrant stop during scope lookup cannot append after stop`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session", CapturePolicy(inScopeOnly = true))
        every { fixture.api.scope().isInScope(any()) } answers {
            manager.stopCapture("session")
            true
        }

        manager.captureResponse(response("/late"))

        assertFalse(manager.getCapture("session")!!.active)
        assertEquals(0, manager.getCapture("session")!!.entryCount)
        manager.close()
        assertEquals(0, fixture.manager().getCapture("session")!!.entryCount)
    }

    @Test
    fun `summary filtering preserves original entry indices`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session", CapturePolicy(excludeStaticAssets = false))
        manager.captureResponse(response("/assets/app.js"))
        manager.captureResponse(response("/api/users"))

        assertEquals(listOf("/api/users"), manager.getEntryRefs("session", true).map { it.path })
        val filtered = manager.getCaptureSummary("session", true)!!
        assertTrue(filtered.contains("2 entries, 1 relevant"))
        assertTrue(filtered.contains("#1 GET /api/users"))
        assertFalse(filtered.contains("app.js"))
        assertTrue(manager.getCaptureSummary("session", false)!!.contains("[static]"))
    }

    @Test
    fun `second manager is refused without modifying data until the owner closes`() {
        val fixture = fixture()
        val owner = fixture.manager()
        owner.startCapture("session")
        owner.captureResponse(response("/original"))
        val persistedBefore = fixture.storage.toMap()
        val second = CaptureManager(fixture.api, tempDir)

        assertThrows(IllegalStateException::class.java) { second.register() }

        assertEquals(persistedBefore, fixture.storage)
        owner.captureResponse(response("/still-owned"))
        assertEquals(2, owner.getCapture("session")!!.entryCount)
        owner.close()

        val reopened = fixture.manager()
        assertEquals(listOf("/original", "/still-owned"), reopened.getEntryRefs("session").map { it.path })
    }

    @Test
    fun `close stops sessions persists counters deregisters once and rejects further writes`() {
        val fixture = fixture()
        val registration = mockk<Registration>(relaxed = true)
        every { fixture.api.http().registerHttpHandler(any()) } returns registration
        val manager = fixture.manager()
        manager.startCapture("session", CapturePolicy(maxEntries = 1))
        manager.captureResponse(response("/assets/app.js"))
        manager.captureResponse(response("/captured"))
        manager.captureResponse(response("/dropped"))

        manager.close()
        manager.close()
        val lateResponse = mockk<HttpResponseReceived>()
        manager.captureResponse(lateResponse)

        verify(exactly = 0) { lateResponse.initiatingRequest() }
        verify(exactly = 1) { registration.deregister() }
        assertFalse(manager.getCapture("session")!!.active)
        assertNotNull(manager.getCapture("session")!!.stoppedAt)
        assertThrows(IllegalStateException::class.java) { manager.startCapture("late") }
        assertThrows(IllegalStateException::class.java) { manager.deleteCapture("session") }
        assertThrows(IllegalStateException::class.java) { manager.cleanupStorage(dryRun = false) }
        assertThrows(IllegalStateException::class.java) { manager.getCaptureEntry("session", 0) }

        val reopened = fixture.manager()
        assertEquals(1, reopened.getCapture("session")!!.entryCount)
        assertEquals(1L, reopened.getCapture("session")!!.filteredEntries)
        assertEquals(1L, reopened.getCapture("session")!!.droppedEntries)
        assertFalse(reopened.getCapture("session")!!.active)
    }

    @Test
    fun `failed close still releases ownership and permanently disables the old manager`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session")
        manager.captureResponse(response("/retained"))
        fixture.failManifestWrites = true

        val failure = assertThrows(IllegalStateException::class.java) { manager.close() }

        assertTrue(failure.message!!.contains("Failed to close"))
        assertThrows(IllegalStateException::class.java) { manager.startCapture("late") }
        fixture.failManifestWrites = false
        assertEquals("/retained", fixture.manager().getCaptureEntry("session", 0)?.path)
    }

    @Test
    fun `failed handler registration releases the store ownership`() {
        val fixture = fixture()
        every { fixture.api.http().registerHttpHandler(any()) } throws IllegalStateException("Synthetic registration failure")
        assertThrows(IllegalStateException::class.java) { fixture.manager() }

        every { fixture.api.http().registerHttpHandler(any()) } returns mockk<Registration>(relaxed = true)

        assertTrue(fixture.manager().startCapture("session").contains("started"))
    }

    @Test
    fun `cleanup fails closed when a known session index disappears`() {
        val fixture = fixture()
        val manager = fixture.manager()
        manager.startCapture("session")
        manager.captureResponse(response("/retained"))
        val index = sessionIndex(fixture)
        val ref = manager.getEntryRefs("session").single()
        Files.delete(index)

        assertThrows(IllegalStateException::class.java) { manager.cleanupStorage(dryRun = false) }

        assertEquals("/retained", manager.getCaptureEntry(ref).path)
        assertFalse(Files.exists(index))
    }

    private fun Fixture.manager(): CaptureManager = CaptureManager(api, tempDir).also {
        managers.add(it)
        it.register()
    }

    private fun sessionIndex(fixture: Fixture): Path {
        val directory = tempDir.resolve(fixture.storage.getValue("capture_store_id")).resolve("sessions")
        return Files.list(directory).use { paths -> paths.filter { it.fileName.toString().endsWith(".jsonl") }.findFirst().orElseThrow() }
    }

    private fun response(path: String): HttpResponseReceived {
        val service = mockk<HttpService>()
        every { service.host() } returns "example.com"
        every { service.port() } returns 443
        val request = mockk<HttpRequest>()
        every { request.toString() } returns "GET $path HTTP/1.1\r\nHost: example.com\r\n\r\n"
        every { request.httpService() } returns service
        every { request.method() } returns "GET"
        every { request.path() } returns path
        every { request.url() } returns "https://example.com$path"
        return mockk<HttpResponseReceived>().also { response ->
            every { response.initiatingRequest() } returns request
            every { response.toString() } returns "HTTP/1.1 200 OK\r\n\r\nsynthetic-body"
            every { response.statusCode() } returns 200
        }
    }

    private fun legacyData(
        request: String = "GET /legacy HTTP/1.1\r\nHost: example.com\r\n\r\n",
        response: String = "HTTP/1.1 200 OK\r\n\r\nsynthetic-body"
    ): String = Json.encodeToString(
        listOf(
            LegacySessionFixture(
                id = "legacy",
                active = true,
                entries = listOf(CapturedEntry(request, response, "example.com", 443, "GET", "/legacy", 200, 42)),
                startedAt = 1,
                stoppedAt = 2
            )
        )
    )

    @Serializable
    private data class LegacySessionFixture(
        val id: String,
        val active: Boolean,
        val entries: List<CapturedEntry>,
        val startedAt: Long,
        val stoppedAt: Long?
    )

    private fun fixture(initial: Map<String, String> = emptyMap()): Fixture {
        val persisted = mockk<PersistedObject>()
        val api = mockk<MontoyaApi>(relaxed = true)
        val fixture = Fixture(api, persisted, initial.toMutableMap())
        every { api.persistence().extensionData() } returns persisted
        every { api.logging() } returns mockk<Logging>(relaxed = true)
        every { persisted.getString(any()) } answers { fixture.storage[firstArg()] }
        every { persisted.setString(any(), any()) } answers {
            val key = firstArg<String>()
            check(key != "capture_sessions_v2" || !fixture.failManifestWrites) { "Synthetic manifest failure" }
            fixture.storage[key] = secondArg()
            Unit
        }
        every { persisted.deleteString(any()) } answers {
            fixture.storage.remove(firstArg<String>())
            Unit
        }
        return fixture
    }

    private data class Fixture(
        val api: MontoyaApi,
        val persisted: PersistedObject,
        val storage: MutableMap<String, String>,
        var failManifestWrites: Boolean = false
    )
}
