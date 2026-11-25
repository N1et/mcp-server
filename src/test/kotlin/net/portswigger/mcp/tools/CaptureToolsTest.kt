package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.persistence.PersistedObject
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.TestSseMcpClient
import net.portswigger.mcp.capture.CaptureManager
import net.portswigger.mcp.capture.CapturePolicy
import net.portswigger.mcp.capture.CapturedEntry
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

@Timeout(15)
class CaptureToolsTest {
    @TempDir
    lateinit var captureDirectory: Path

    private val api = mockk<MontoyaApi>(relaxed = true)
    private val client = TestSseMcpClient()
    private lateinit var captureManager: CaptureManager
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var serverUrl: String

    @BeforeEach
    @Timeout(15)
    fun startServer() {
        val values = mutableMapOf<String, String>()
        val persistence = mockk<PersistedObject>(relaxed = true)
        every { persistence.getString(any()) } answers { values[firstArg<String>()] }
        every { persistence.setString(any(), any()) } answers {
            values[firstArg<String>()] = secondArg<String>()
        }
        every { persistence.deleteString(any()) } answers { values.remove(firstArg<String>()); Unit }
        every { api.persistence().extensionData() } returns persistence
        every { api.burpSuite().version().edition() } returns BurpSuiteEdition.COMMUNITY_EDITION

        val configStorage = mockk<PersistedObject>(relaxed = true)
        every { configStorage.getString(any()) } returns null
        every { configStorage.getBoolean(any()) } returns null
        every { configStorage.getInteger(any()) } returns null
        val config = McpConfig(configStorage, api.logging())
        captureManager = CaptureManager(api, captureDirectory).also { it.register() }
        val mcpServer = Server(
            serverInfo = Implementation("capture-tools-test", "1.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            )
        )
        mcpServer.registerTools(api, config, captureManager)
        val port = ServerSocket(0).use { it.localPort }
        serverUrl = "http://127.0.0.1:$port"
        server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            mcp { mcpServer }
        }.start(wait = false)
        runBlocking { client.connectToServer(serverUrl) }
    }

    @AfterEach
    @Timeout(15)
    fun stopServer() = runBlocking {
        try {
            if (client.isConnected()) client.close()
        } finally {
            try {
                if (::server.isInitialized) server.stop(0, 1000)
            } finally {
                if (::captureManager.isInitialized) captureManager.close()
            }
        }
    }

    @Test
    fun `capture tools expose optional policy parameters and effective defaults`(): Unit = runBlocking {
        val tools = client.listTools().associateBy { it.name }
        assertTrue(tools.keys.containsAll(setOf(
            "capture_start", "capture_stop", "capture_list", "capture_get", "capture_delete",
            "capture_count", "capture_summary", "capture_stats", "capture_storage_cleanup"
        )))
        val startSchema = tools.getValue("capture_start").inputSchema
        assertEquals(setOf("sessionId"), startSchema.required.orEmpty().toSet())
        val expectedTypes = mapOf(
            "sessionId" to "string", "excludeStaticAssets" to "boolean", "inScopeOnly" to "boolean",
            "maxRequestBytes" to "integer", "maxResponseBytes" to "integer", "maxEntries" to "integer"
        )
        expectedTypes.forEach { (name, type) ->
            assertEquals(type, startSchema.properties[name]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        }
        assertEquals(setOf("sessionId"), tools.getValue("capture_get").inputSchema.required.orEmpty().toSet())
        assertTrue(tools.getValue("capture_stats").inputSchema.required.orEmpty().isEmpty())
        val cleanupSchema = tools.getValue("capture_storage_cleanup").inputSchema
        assertTrue(cleanupSchema.required.orEmpty().isEmpty())
        assertEquals("boolean", cleanupSchema.properties["dryRun"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

        call("capture_start", mapOf("sessionId" to "defaults"))
            .text("Capture session 'defaults' started")
        assertEquals(CapturePolicy(), captureManager.getCapture("defaults")!!.policy)
        capture("/bundle.js")
        capture("/api/items")
        assertEquals(1, captureManager.getCapture("defaults")!!.entryCount)
        assertEquals(1L, captureManager.getCapture("defaults")!!.filteredEntries)
        val stats = call("capture_stats", mapOf("sessionId" to "defaults")).text()
        assertTrue(stats.contains("static=exclude"))
        assertTrue(stats.contains("scope=all"))
        assertTrue(stats.contains("entryLimit=100000"))
        assertFalse(stats.contains("Endpoints:"))
        assertFalse(stats.contains(REQUEST_MARKER))
        assertFalse(stats.contains(RESPONSE_MARKER))
    }

    @Test
    fun `capture round trip retains duplicate entries and supports indexed and paginated reads`(): Unit = runBlocking {
        call("capture_start", mapOf("sessionId" to "roundtrip", "excludeStaticAssets" to false))
        val expected = capture("/api/items")
        capture("/api/items")
        capture("/bundle.js")
        call("capture_stop", mapOf("sessionId" to "roundtrip"))
            .text("Capture session 'roundtrip' stopped with 3 entries captured")
        call("capture_count", mapOf("sessionId" to "roundtrip"))
            .text("Session 'roundtrip' (stopped): 3 entries")
        call("capture_count", mapOf("sessionId" to "roundtrip", "excludeStaticAssets" to true))
            .text("Session 'roundtrip' (stopped): 2 entries (filtered)")
        assertEquals(2, captureManager.storageStats().blobFiles)
        assertTrue(call("capture_list").text().contains("roundtrip: stopped, 3 entries"))
        val summary = call("capture_summary", mapOf("sessionId" to "roundtrip")).text()
        assertTrue(summary.contains("#2 GET /bundle.js"))
        assertFalse(summary.contains(REQUEST_MARKER))
        val indexed = Json.decodeFromString<CapturedEntry>(call("capture_get", mapOf(
            "sessionId" to "roundtrip", "entryIndex" to 0
        )).text())
        assertEquals(expected.request, indexed.request)
        assertEquals(expected.response, indexed.response)
        assertEquals(expected.host, indexed.host)
        assertEquals(expected.port, indexed.port)
        assertEquals(expected.method, indexed.method)
        assertEquals(expected.path, indexed.path)
        assertEquals(expected.statusCode, indexed.statusCode)
        val paged = Json.decodeFromString<CapturedEntry>(call("capture_get", mapOf(
            "sessionId" to "roundtrip", "offset" to 1, "count" to 1, "excludeStaticAssets" to true
        )).text())
        assertEquals(expected.request, paged.request)
        call("capture_get", mapOf("sessionId" to "roundtrip", "offset" to 3)).text("Reached end of items")
        val stats = call("capture_stats").text()
        assertTrue(stats.contains("Payload blobs: 2 (2 referenced, 0 orphaned)"))
        assertFalse(stats.contains(RESPONSE_MARKER))
    }

    @Test
    fun `explicit scope size and entry policy parameters apply`(): Unit = runBlocking {
        every { api.scope().isInScope(any<String>()) } answers { firstArg<String>().contains("/allowed") }
        call("capture_start", mapOf(
            "sessionId" to "limited", "excludeStaticAssets" to false, "inScopeOnly" to true,
            "maxRequestBytes" to 1024, "maxResponseBytes" to 1024, "maxEntries" to 1
        ))
        assertEquals(CapturePolicy(false, true, 1024, 1024, 1), captureManager.getCapture("limited")!!.policy)
        capture("/outside")
        capture("/allowed", "x".repeat(2048))
        capture("/allowed-again")
        val session = captureManager.getCapture("limited")!!
        assertEquals(1, session.entryCount)
        assertEquals(1L, session.filteredEntries)
        assertEquals(1L, session.droppedEntries)
        assertTrue(captureManager.getEntryRefs("limited").single().responseTruncated)
        val stats = call("capture_stats", mapOf("sessionId" to "limited")).text()
        assertTrue(stats.contains("truncated=1"))
        assertTrue(stats.contains("scope=in-scope"))
    }

    @Test
    fun `cleanup defaults to dry run and only explicit cleanup deletes orphaned blobs`(): Unit = runBlocking {
        call("capture_start", mapOf("sessionId" to "cleanup"))
        capture("/api/items")
        call("capture_delete", mapOf("sessionId" to "cleanup"))
        assertEquals(1, captureManager.storageStats().orphanBlobs)
        assertTrue(call("capture_storage_cleanup").text().startsWith("Dry run: 1 orphaned payloads"))
        assertEquals(1, captureManager.storageStats().blobFiles)
        assertTrue(call("capture_storage_cleanup", mapOf("dryRun" to false)).text().startsWith("Deleted 1/1"))
        assertEquals(0, captureManager.storageStats().blobFiles)
    }

    @Test
    fun `missing payload is an actionable tool error for indexed and paginated reads`(): Unit = runBlocking {
        call("capture_start", mapOf("sessionId" to "missing"))
        capture("/api/items")
        Files.walk(captureDirectory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".gz") }.forEach { Files.delete(it) }
        }
        listOf(
            mapOf<String, Any>("sessionId" to "missing", "entryIndex" to 0),
            mapOf<String, Any>("sessionId" to "missing", "count" to 1)
        ).forEach { arguments ->
            val result = call("capture_get", arguments)
            assertEquals(true, result.isError)
            val text = result.text()
            assertTrue(text.contains("Payload unavailable for entry 0"))
            assertTrue(text.contains("Restore the external capture storage from backup"))
            assertFalse(text.contains("Reached end of items"))
            assertFalse(text.contains(REQUEST_MARKER))
            assertFalse(text.contains(RESPONSE_MARKER))
        }
    }

    @Test
    fun `invalid capture limits and pagination return tool errors`(): Unit = runBlocking {
        listOf(
            mapOf<String, Any>("sessionId" to "invalid", "maxRequestBytes" to 0),
            mapOf<String, Any>("sessionId" to "invalid", "maxResponseBytes" to Int.MAX_VALUE),
            mapOf<String, Any>("sessionId" to "invalid", "maxEntries" to 0)
        ).forEach { assertEquals(true, call("capture_start", it).isError) }
        assertNull(captureManager.getCapture("invalid"))
        call("capture_start", mapOf("sessionId" to "valid"))
        listOf("count" to 0, "count" to 101, "offset" to -1, "entryIndex" to -1).forEach { (name, value) ->
            assertEquals(true, call("capture_get", mapOf("sessionId" to "valid", name to value)).isError)
        }
        call("capture_get", mapOf("sessionId" to "valid", "count" to 100)).text("Reached end of items")
    }

    @Test
    fun `SDK negotiation advertises tools without unsupported capabilities`(): Unit = runBlocking {
        client.close()
        val sdkClient = Client(Implementation("capture-capabilities-test", "1.0"))
        val httpClient = HttpClient { install(SSE) }
        try {
            sdkClient.connect(SseClientTransport(httpClient, urlString = serverUrl))
            val capabilities = requireNotNull(sdkClient.serverCapabilities)
            assertNotNull(capabilities.tools)
            assertEquals(false, capabilities.tools?.listChanged)
            assertNull(capabilities.resources)
            assertNull(capabilities.prompts)
            assertNotNull(sdkClient.ping())
        } finally {
            sdkClient.close()
            httpClient.close()
        }
    }

    private suspend fun call(name: String, arguments: Map<String, Any> = emptyMap()): CallToolResultBase =
        requireNotNull(client.callTool(name, arguments))

    private fun CallToolResultBase.text(expected: String? = null): String {
        val text = (content.single() as TextContent).text.orEmpty()
        if (expected != null) assertEquals(expected, text)
        return text
    }

    private fun capture(path: String, responseBody: String = RESPONSE_MARKER): CapturedEntry {
        val requestText = "GET $path HTTP/1.1\r\nHost: example.test\r\nX-Test: $REQUEST_MARKER\r\n\r\n"
        val responseText = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n$responseBody"
        val request = mockk<HttpRequest>(relaxed = true)
        every { request.toString() } returns requestText
        every { request.method() } returns "GET"
        every { request.path() } returns path
        every { request.url() } returns "https://example.test$path"
        every { request.httpService().host() } returns "example.test"
        every { request.httpService().port() } returns 443
        val response = mockk<HttpResponseReceived>(relaxed = true)
        every { response.initiatingRequest() } returns request
        every { response.toString() } returns responseText
        every { response.statusCode() } returns 200
        captureManager.captureResponse(response)
        return CapturedEntry(requestText, responseText, "example.test", 443, "GET", path, 200, 0)
    }

    companion object {
        private const val REQUEST_MARKER = "synthetic-request-marker"
        private const val RESPONSE_MARKER = "synthetic-response-marker"
    }
}
