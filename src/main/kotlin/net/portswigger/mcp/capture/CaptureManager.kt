package net.portswigger.mcp.capture

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.handler.*
import burp.api.montoya.http.handler.RequestToBeSentAction.continueWith
import burp.api.montoya.http.handler.ResponseReceivedAction.continueWith
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

@Serializable
data class CapturedEntry(
    val request: String,
    val response: String?,
    val host: String,
    val port: Int,
    val method: String,
    val path: String,
    val statusCode: Int?,
    val timestampMs: Long
)

@Serializable
data class SerializableSession(
    val id: String,
    val active: Boolean,
    val entries: List<CapturedEntry>,
    val startedAt: Long,
    val stoppedAt: Long?
)

data class CaptureSession(
    val id: String,
    val active: Boolean,
    val entries: MutableList<CapturedEntry> = mutableListOf(),
    val startedAt: Long = System.currentTimeMillis(),
    var stoppedAt: Long? = null
) {
    fun toSerializable() = SerializableSession(id, active, entries.toList(), startedAt, stoppedAt)

    companion object {
        fun fromSerializable(s: SerializableSession) = CaptureSession(
            id = s.id,
            active = false, // always stopped on load
            entries = s.entries.toMutableList(),
            startedAt = s.startedAt,
            stoppedAt = s.stoppedAt
        )
    }
}

private val STATIC_EXTENSIONS = setOf(
    ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
    ".woff", ".woff2", ".ttf", ".eot", ".webp", ".map"
)

private val STATIC_PATHS = setOf(
    "/site.webmanifest", "/favicon.ico", "/robots.txt"
)

fun isStaticAsset(entry: CapturedEntry): Boolean {
    val pathLower = entry.path.lowercase().split("?").first()
    if (pathLower in STATIC_PATHS) return true
    return STATIC_EXTENSIONS.any { pathLower.endsWith(it) }
}

class CaptureManager(private val api: MontoyaApi) : HttpHandler {

    private val sessions = ConcurrentHashMap<String, CaptureSession>()
    private val STORAGE_KEY = "capture_sessions"
    private val listeners = mutableListOf<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    fun register() {
        api.http().registerHttpHandler(this)
        loadFromPersistence()
        api.logging().logToOutput("CaptureManager: HTTP handler registered, loaded ${sessions.size} sessions")
    }

    fun startCapture(sessionId: String): String {
        val existing = sessions[sessionId]
        if (existing != null && existing.active) {
            return "Capture session '$sessionId' is already active"
        }
        if (existing != null && !existing.active) {
            // Reuse: reactivate stopped session, keep existing entries
            sessions[sessionId] = existing.copy(active = true, stoppedAt = null)
            api.logging().logToOutput("CaptureManager: Reactivated capture session '$sessionId' (${existing.entries.size} existing entries)")
            saveToPersistence()
            notifyListeners()
            return "Capture session '$sessionId' reactivated with ${existing.entries.size} existing entries"
        }
        sessions[sessionId] = CaptureSession(id = sessionId, active = true)
        api.logging().logToOutput("CaptureManager: Started capture session '$sessionId'")
        saveToPersistence()
        notifyListeners()
        return "Capture session '$sessionId' started"
    }

    fun stopCapture(sessionId: String): String {
        val session = sessions[sessionId]
            ?: return "Capture session '$sessionId' not found"
        if (!session.active) {
            return "Capture session '$sessionId' is already stopped"
        }
        sessions[sessionId] = session.copy(active = false, stoppedAt = System.currentTimeMillis())
        api.logging().logToOutput("CaptureManager: Stopped capture session '$sessionId' (${session.entries.size} entries)")
        saveToPersistence()
        notifyListeners()
        return "Capture session '$sessionId' stopped with ${session.entries.size} entries captured"
    }

    fun getCapture(sessionId: String): CaptureSession? {
        return sessions[sessionId]
    }

    fun listSessions(): Map<String, CaptureSession> {
        return sessions.toMap()
    }

    fun deleteCapture(sessionId: String): String {
        val removed = sessions.remove(sessionId)
        return if (removed != null) {
            saveToPersistence()
            notifyListeners()
            "Capture session '$sessionId' deleted"
        } else {
            "Capture session '$sessionId' not found"
        }
    }

    fun getCaptureSummary(sessionId: String, excludeStaticAssets: Boolean): String? {
        val session = sessions[sessionId] ?: return null

        val status = if (session.active) "active" else "stopped"
        val totalEntries = session.entries.size

        val indexedEntries = session.entries.mapIndexed { index, entry -> index to entry }
        val filtered = if (excludeStaticAssets) indexedEntries.filter { !isStaticAsset(it.second) } else indexedEntries
        val relevantCount = filtered.size

        val sb = StringBuilder()
        sb.appendLine("Session: $sessionId ($status, $totalEntries entries, $relevantCount relevant)")
        sb.appendLine()

        sb.appendLine("Endpoints:")
        for ((index, entry) in filtered) {
            val hasBody = entry.request.contains("\r\n\r\n") &&
                entry.request.substringAfter("\r\n\r\n").isNotBlank()
            val bodyIndicator = if (hasBody) " (has body)" else ""
            val staticIndicator = if (!excludeStaticAssets && isStaticAsset(entry)) " [static]" else ""
            sb.appendLine("  #$index ${entry.method} ${entry.path} → ${entry.statusCode ?: "-"}$bodyIndicator$staticIndicator")
        }
        sb.appendLine()

        val hostCounts = filtered.groupingBy { it.second.host + ":" + it.second.port }.eachCount()
        sb.appendLine("Hosts: ${hostCounts.entries.joinToString(", ") { "${it.key} (${it.value})" }}")

        val methodCounts = filtered.groupingBy { it.second.method }.eachCount()
        sb.appendLine("Methods: ${methodCounts.entries.joinToString(", ") { "${it.key} (${it.value})" }}")

        val statusCounts = filtered.groupingBy { it.second.statusCode?.toString() ?: "-" }.eachCount()
        sb.appendLine("Status codes: ${statusCounts.entries.joinToString(", ") { "${it.key} (${it.value})" }}")

        return sb.toString().trimEnd()
    }

    private fun saveToPersistence() {
        try {
            val serializable = sessions.values.map { it.toSerializable() }
            val data = json.encodeToString(serializable)
            api.persistence().extensionData().setString(STORAGE_KEY, data)
        } catch (e: Exception) {
            api.logging().logToError("CaptureManager: Failed to save sessions: ${e.message}")
        }
    }

    private fun loadFromPersistence() {
        try {
            val data = api.persistence().extensionData().getString(STORAGE_KEY) ?: return
            val serializable = json.decodeFromString<List<SerializableSession>>(data)
            serializable.forEach { s ->
                sessions[s.id] = CaptureSession.fromSerializable(s)
            }
        } catch (e: Exception) {
            api.logging().logToError("CaptureManager: Failed to load sessions: ${e.message}")
        }
    }

    override fun handleHttpRequestToBeSent(requestToBeSent: HttpRequestToBeSent): RequestToBeSentAction {
        return continueWith(requestToBeSent)
    }

    override fun handleHttpResponseReceived(responseReceived: HttpResponseReceived): ResponseReceivedAction {
        val activeSessions = sessions.values.filter { it.active }
        if (activeSessions.isEmpty()) {
            return continueWith(responseReceived)
        }

        val request = responseReceived.initiatingRequest()
        val entry = CapturedEntry(
            request = request?.toString() ?: "<no request>",
            response = responseReceived.toString(),
            host = request?.httpService()?.host() ?: "unknown",
            port = request?.httpService()?.port() ?: 0,
            method = request?.method() ?: "unknown",
            path = request?.path() ?: "unknown",
            statusCode = responseReceived.statusCode().toInt(),
            timestampMs = System.currentTimeMillis()
        )

        activeSessions.forEach { session ->
            session.entries.add(entry)
        }

        return continueWith(responseReceived)
    }
}
