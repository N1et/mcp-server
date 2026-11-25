package net.portswigger.mcp.capture

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.RequestToBeSentAction.continueWith
import burp.api.montoya.http.handler.ResponseReceivedAction
import burp.api.montoya.http.handler.ResponseReceivedAction.continueWith
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private val captureJson = Json { ignoreUnknownKeys = true; prettyPrint = false }

@Serializable
private data class LegacySerializableSession(
    val id: String,
    val active: Boolean,
    val entries: List<CapturedEntry>,
    val startedAt: Long,
    val stoppedAt: Long?
)

class CaptureManager(
    private val api: MontoyaApi,
    captureBaseDirectory: Path? = null
) : HttpHandler, AutoCloseable {
    // Serialize session mutations, index changes, and manifest snapshots together.
    private val managerLock = Any()
    private val sessions = linkedMapOf<String, CaptureSession>()
    private var closed = false
    private var registered = false
    private var handlerRegistration: Registration? = null
    private val listeners = mutableListOf<() -> Unit>()
    private val persistence by lazy { api.persistence().extensionData() }
    private val storeId by lazy { loadOrCreateStoreId() }
    private val storeRoot by lazy {
        val configuredRoot = System.getProperty("burp.mcp.capture.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
        val base = captureBaseDirectory
            ?: configuredRoot
            ?: Path.of(System.getProperty("user.home"), ".BurpSuite", "mcp-captures")
        base.resolve(storeId)
    }
    private val storeDelegate = lazy {
        CaptureStore(storeRoot) { api.logging().logToError(it) }
    }
    private val store by storeDelegate

    fun addChangeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    private fun notifyListeners() {
        synchronized(listeners) { listeners.toList() }.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                api.logging().logToError("CaptureManager listener failed: ${e.message}")
            }
        }
    }

    fun register() {
        synchronized(managerLock) {
            check(!closed) { "Capture manager is closed" }
            if (registered) return
            try {
                // Validate identity and existing storage before any directory is created.
                val resolvedStoreId = storeId
                if (!persistence.getString(MANIFEST_KEY).isNullOrBlank()) {
                    check(Files.isDirectory(storeRoot)) { "Capture store directory is missing: $storeRoot" }
                }
                store.claimOwnership()
                loadFromPersistence()
                persistence.setString(STORE_ID_KEY, resolvedStoreId)
                handlerRegistration = api.http().registerHttpHandler(this)
                registered = true
                api.logging().logToOutput(
                    "CaptureManager: registered with lightweight store $storeRoot; loaded ${sessions.size} sessions"
                )
            } catch (e: Exception) {
                closed = true
                registered = false
                sessions.clear()
                try {
                    handlerRegistration?.deregister()
                } catch (cleanupError: Exception) {
                    e.addSuppressed(cleanupError)
                } finally {
                    handlerRegistration = null
                    if (storeDelegate.isInitialized()) {
                        try {
                            store.close()
                        } catch (cleanupError: Exception) {
                            e.addSuppressed(cleanupError)
                        }
                    }
                }
                throw IllegalStateException("Failed to register capture manager: ${e.message}", e)
            }
        }
    }

    override fun close() {
        var failure: Exception? = null
        synchronized(managerLock) {
            if (closed) return
            closed = true
            try {
                handlerRegistration?.deregister()
            } catch (e: Exception) {
                failure = e
            } finally {
                handlerRegistration = null
            }
            try {
                if (registered) {
                    val stoppedAt = System.currentTimeMillis()
                    sessions.values.forEach { session ->
                        if (session.active) {
                            session.active = false
                            session.stoppedAt = stoppedAt
                        }
                    }
                    check(saveManifest()) { "Failed to save capture sessions while closing manager" }
                }
            } catch (e: Exception) {
                if (failure == null) failure = e else failure!!.addSuppressed(e)
            } finally {
                registered = false
                if (storeDelegate.isInitialized()) {
                    try {
                        store.close()
                    } catch (e: Exception) {
                        if (failure == null) failure = e else failure!!.addSuppressed(e)
                    }
                }
            }
        }
        notifyListeners()
        failure?.let { throw IllegalStateException("Failed to close capture manager: ${it.message}", it) }
    }

    private fun requireRegistered() {
        check(!closed) { "Capture manager is closed" }
        check(registered) { "Capture manager is not registered" }
    }

    fun startCapture(sessionId: String, policy: CapturePolicy? = null): String {
        val result = synchronized(managerLock) {
            requireRegistered()
            requireValidSessionId(sessionId)
            val normalizedPolicy = policy?.normalized()
            val existing = sessions[sessionId]
            if (existing != null) {
                if (existing.active) return "Capture session '$sessionId' is already active"
                val previousStoppedAt = existing.stoppedAt
                val previousPolicy = existing.policy
                existing.active = true
                existing.stoppedAt = null
                if (normalizedPolicy != null) existing.policy = normalizedPolicy
                if (!saveManifest()) {
                    existing.active = false
                    existing.stoppedAt = previousStoppedAt
                    existing.policy = previousPolicy
                    throw IllegalStateException("Failed to reactivate capture session '$sessionId': project manifest could not be saved")
                }
                "Capture session '$sessionId' reactivated with ${existing.entryCount} existing entries"
            } else {
                // A real empty index distinguishes a new session from missing external data.
                store.replaceRefs(sessionId, emptyList())
                sessions[sessionId] = CaptureSession(
                    id = sessionId,
                    active = true,
                    policy = normalizedPolicy ?: CapturePolicy()
                )
                if (!saveManifest()) {
                    sessions.remove(sessionId)
                    throw IllegalStateException("Failed to start capture session '$sessionId': project manifest could not be saved")
                }
                "Capture session '$sessionId' started"
            }
        }
        notifyListeners()
        return result
    }

    fun stopCapture(sessionId: String): String {
        val result = synchronized(managerLock) {
            requireRegistered()
            val session = sessions[sessionId] ?: return "Capture session '$sessionId' not found"
            if (!session.active) return "Capture session '$sessionId' is already stopped"
            val previousStoppedAt = session.stoppedAt
            session.active = false
            session.stoppedAt = System.currentTimeMillis()
            if (!saveManifest()) {
                session.active = true
                session.stoppedAt = previousStoppedAt
                throw IllegalStateException("Failed to stop capture session '$sessionId': project manifest could not be saved")
            }
            "Capture session '$sessionId' stopped with ${session.entryCount} entries captured"
        }
        notifyListeners()
        return result
    }

    fun getCapture(sessionId: String): CaptureSession? = synchronized(managerLock) { sessions[sessionId] }

    fun listSessions(): Map<String, CaptureSession> = synchronized(managerLock) { sessions.toMap() }

    fun getEntryRefs(sessionId: String, excludeStaticAssets: Boolean = false): List<CapturedEntryRef> =
        synchronized(managerLock) {
            val refs = sessions[sessionId]?.entryRefs() ?: return@synchronized emptyList()
            if (excludeStaticAssets) refs.filterNot(::isStaticAsset) else refs
        }

    fun getCaptureEntry(sessionId: String, index: Int): CapturedEntry? = synchronized(managerLock) {
        requireRegistered()
        val ref = sessions[sessionId]?.entryRef(index) ?: return@synchronized null
        getCaptureEntry(ref)
    }

    fun getCaptureEntry(ref: CapturedEntryRef): CapturedEntry = synchronized(managerLock) {
        requireRegistered()
        try {
            store.read(ref)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to read capture payload", e)
        }
    }

    fun deleteCaptureEntry(sessionId: String, index: Int): Boolean {
        synchronized(managerLock) {
            requireRegistered()
            val session = sessions[sessionId] ?: return false
            val replacement = session.entryRefs().toMutableList()
            if (index !in replacement.indices) return false
            replacement.removeAt(index)
            try {
                // The store replaces atomically; memory changes only after the index commits.
                store.replaceRefs(sessionId, replacement)
            } catch (e: Exception) {
                api.logging().logToError("CaptureManager: failed to delete '$sessionId' entry $index: ${e.message}")
                throw IllegalStateException("Failed to delete capture '$sessionId' entry $index", e)
            }
            session.replaceEntries(replacement)
        }
        notifyListeners()
        return true
    }

    fun deleteCapture(sessionId: String): String {
        var cleanupFailure: Exception? = null
        val result = synchronized(managerLock) {
            requireRegistered()
            val removed = sessions.remove(sessionId) ?: return "Capture session '$sessionId' not found"
            if (!saveManifest()) {
                sessions[sessionId] = removed
                throw IllegalStateException("Failed to delete capture session '$sessionId': project manifest could not be saved")
            }
            try {
                store.deleteSession(sessionId)
                "Capture session '$sessionId' deleted; unreferenced payloads can be reclaimed with capture_storage_cleanup"
            } catch (e: Exception) {
                cleanupFailure = e
                api.logging().logToError(
                    "CaptureManager: session '$sessionId' was removed, but its external index could not be deleted: ${e.message}"
                )
                "Capture session '$sessionId' deleted; external cleanup warning: ${e.message}"
            }
        }
        notifyListeners()
        cleanupFailure?.let { throw IllegalStateException(result, it) }
        return result
    }

    fun getCaptureStats(sessionId: String): String? = synchronized(managerLock) {
        val session = sessions[sessionId] ?: return@synchronized null
        buildString { appendSessionStats(session, session.entryRefs()) }.trimEnd()
    }

    fun getCaptureSummary(sessionId: String, excludeStaticAssets: Boolean): String? = synchronized(managerLock) {
        val session = sessions[sessionId] ?: return@synchronized null
        val allEntries = session.entryRefs()
        val filtered = if (excludeStaticAssets) allEntries.filterNot(::isStaticAsset) else allEntries
        buildString {
            appendSessionStats(session, allEntries, filtered.size)
            appendLine()
            appendLine("Endpoints:")
            allEntries.asSequence().withIndex()
                .filter { !excludeStaticAssets || !isStaticAsset(it.value) }
                .take(MAX_SUMMARY_ENDPOINTS)
                .forEach { (index, entry) ->
                    val bodyIndicator = if (entry.hasRequestBody) " (has body)" else ""
                    val staticIndicator = if (!excludeStaticAssets && entry.staticAsset) " [static]" else ""
                    val truncatedIndicator = if (entry.truncated) " [truncated]" else ""
                    appendLine(
                        "  #$index ${entry.method} ${entry.path} → ${entry.statusCode ?: "-"}" +
                            "$bodyIndicator$staticIndicator$truncatedIndicator"
                    )
                }
            if (filtered.size > MAX_SUMMARY_ENDPOINTS) {
                appendLine("  ... ${filtered.size - MAX_SUMMARY_ENDPOINTS} additional endpoints omitted")
            }
            appendLine()
            val hostCounts = filtered.groupingBy { it.host + ":" + it.port }.eachCount()
            appendLine("Hosts: ${hostCounts.entries.joinToString(", ") { "${it.key} (${it.value})" }}")
            val methodCounts = filtered.groupingBy { it.method }.eachCount()
            appendLine("Methods: ${methodCounts.entries.joinToString(", ") { "${it.key} (${it.value})" }}")
            val statusCounts = filtered.groupingBy { it.statusCode?.toString() ?: "-" }.eachCount()
            append("Status codes: ${statusCounts.entries.joinToString(", ") { "${it.key} (${it.value})" }}")
        }
    }

    private fun StringBuilder.appendSessionStats(
        session: CaptureSession,
        refs: List<CapturedEntryRef>,
        relevantEntries: Int? = null
    ) {
        val relevant = relevantEntries?.let { ", $it relevant" } ?: ""
        appendLine(
            "Session: ${session.id} (${if (session.active) "active" else "stopped"}, ${refs.size} entries$relevant)"
        )
        appendLine(
            "Storage: raw=${formatBytes(refs.sumOf { it.rawBytes })}, " +
                "stored=${formatBytes(refs.sumOf { it.storedBytes })}, " +
                "truncated=${refs.count { it.truncated }}, filtered=${session.filteredEntries}, " +
                "dropped=${session.droppedEntries}"
        )
        appendLine(
            "Policy: static=${if (session.policy.excludeStaticAssets) "exclude" else "include"}, " +
                "scope=${if (session.policy.inScopeOnly) "in-scope" else "all"}, " +
                "requestLimit=${formatBytes(session.policy.maxRequestBytes.toLong())}, " +
                "responseLimit=${formatBytes(session.policy.maxResponseBytes.toLong())}, " +
                "entryLimit=${session.policy.maxEntries}"
        )
    }

    fun storageStats(): CaptureStorageStats = synchronized(managerLock) {
        requireRegistered()
        store.stats()
    }

    fun cleanupStorage(dryRun: Boolean): CaptureCleanupResult = synchronized(managerLock) {
        requireRegistered()
        try {
            store.cleanup(dryRun, sessions.keys)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to clean capture storage: ${e.message}", e)
        }
    }

    private fun saveManifest(): Boolean {
        return try {
            val manifest = CaptureManifest(
                storeId = storeId,
                sessions = sessions.values.sortedBy { it.id }.map { it.toManifest() }
            )
            persistence.setString(STORE_ID_KEY, storeId)
            persistence.setString(MANIFEST_KEY, captureJson.encodeToString(manifest))
            true
        } catch (e: Exception) {
            api.logging().logToError("CaptureManager: failed to save lightweight manifest: ${e.message}")
            false
        }
    }

    private fun loadFromPersistence() {
        try {
            val persistedStoreId = storeId
            val manifestData = persistence.getString(MANIFEST_KEY)
            if (!manifestData.isNullOrBlank()) {
                val manifest = decodeManifest(manifestData)
                // Resolve and validate both IDs before opening or creating any directories.
                check(persistedStoreId == manifest.storeId.lowercase()) { "Capture store ID conflicts with manifest" }
                check(Files.isDirectory(storeRoot)) { "Capture store directory is missing: $storeRoot" }
                requireUniqueSessionIds(manifest.sessions.map { it.id })
                val loaded = manifest.sessions.associate { saved ->
                    saved.id to CaptureSession(
                        id = saved.id,
                        active = false,
                        startedAt = saved.startedAt,
                        stoppedAt = saved.stoppedAt,
                        policy = saved.policy.normalized(),
                        filteredEntries = saved.filteredEntries,
                        droppedEntries = saved.droppedEntries,
                        refs = store.loadRefs(saved.id)
                    )
                }
                persistence.setString(STORE_ID_KEY, storeId)
                sessions.clear()
                sessions.putAll(loaded)
                return
            }
            val legacyData = persistence.getString(LEGACY_STORAGE_KEY)
            if (!legacyData.isNullOrBlank()) migrateLegacy(legacyData)
        } catch (e: Exception) {
            api.logging().logToError("CaptureManager: failed to load captures; persisted data retained: ${e.message}")
            throw IllegalStateException("Failed to load captures: ${e.message}", e)
        }
    }

    private fun migrateLegacy(data: String) {
        val legacySessions = captureJson.decodeFromString<List<LegacySerializableSession>>(data)
        requireUniqueSessionIds(legacySessions.map { it.id })
        val migrationStoragePolicy = CapturePolicy(
            excludeStaticAssets = false,
            inScopeOnly = false,
            maxRequestBytes = Int.MAX_VALUE,
            maxResponseBytes = Int.MAX_VALUE,
            maxEntries = Int.MAX_VALUE
        )
        val migrated = legacySessions.associate { legacy ->
            val refs = store.replaceSession(legacy.id, legacy.entries, migrationStoragePolicy)
            legacy.id to CaptureSession(
                id = legacy.id,
                active = false,
                startedAt = legacy.startedAt,
                stoppedAt = legacy.stoppedAt,
                policy = CapturePolicy(excludeStaticAssets = false),
                refs = refs
            )
        }
        val previous = sessions.toMap()
        sessions.clear()
        sessions.putAll(migrated)
        if (!saveManifest()) {
            sessions.clear()
            sessions.putAll(previous)
            error("Legacy migration failed: lightweight manifest could not be persisted; original data retained")
        }
        persistence.deleteString(LEGACY_STORAGE_KEY)
        api.logging().logToOutput(
            "CaptureManager: migrated ${migrated.size} legacy sessions / " +
                "${migrated.values.sumOf { it.entryCount }} entries to external lightweight storage. " +
                "Use Project > Save copy to reclaim existing .burp space."
        )
    }

    private fun decodeManifest(data: String): CaptureManifest {
        val manifest = captureJson.decodeFromString<CaptureManifest>(data)
        require(manifest.formatVersion == 2) { "Unsupported capture manifest version: ${manifest.formatVersion}" }
        validStoreId(manifest.storeId)
        return manifest
    }

    private fun loadOrCreateStoreId(): String {
        val auxiliary = persistence.getString(STORE_ID_KEY)?.takeIf { it.isNotBlank() }?.let(::validStoreId)
        val manifest = persistence.getString(MANIFEST_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeManifest)
        val manifestStoreId = manifest?.storeId?.let(::validStoreId)
        require(auxiliary == null || manifestStoreId == null || auxiliary == manifestStoreId) {
            "Capture store ID conflicts with manifest"
        }
        return manifestStoreId ?: auxiliary ?: UUID.randomUUID().toString()
    }

    private fun validStoreId(value: String): String {
        val parsed = try {
            UUID.fromString(value).toString()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid capture store ID: expected a UUID", e)
        }
        require(parsed == value.lowercase()) { "Invalid capture store ID: expected a canonical UUID" }
        return parsed
    }

    private fun requireUniqueSessionIds(ids: List<String>) {
        require(ids.distinct().size == ids.size) { "Duplicate capture session IDs in persistence" }
    }

    override fun handleHttpRequestToBeSent(requestToBeSent: HttpRequestToBeSent): RequestToBeSentAction =
        continueWith(requestToBeSent)

    override fun handleHttpResponseReceived(responseReceived: HttpResponseReceived): ResponseReceivedAction {
        captureResponse(responseReceived)
        return continueWith(responseReceived)
    }

    internal fun captureResponse(responseReceived: HttpResponseReceived) {
        synchronized(managerLock) {
            if (closed || !registered) return
            val activeSessions = sessions.values.filter { it.active }
            if (activeSessions.isEmpty()) return
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
                // Also defend against reentrant callbacks while reading the response.
                if (sessions[session.id] === session && session.active) {
                    captureForSession(session, entry, request?.url())
                }
            }
        }
    }

    private fun captureForSession(session: CaptureSession, entry: CapturedEntry, url: String?) {
        if (session.entryCount >= session.policy.maxEntries) {
            session.droppedEntries++
            return
        }
        if (session.policy.excludeStaticAssets && isStaticAsset(entry)) {
            session.filteredEntries++
            return
        }
        val inScope = if (session.policy.inScopeOnly) {
            try {
                url != null && api.scope().isInScope(url)
            } catch (e: Exception) {
                session.droppedEntries++
                api.logging().logToError("CaptureManager: failed to check scope for '${session.id}': ${e.message}")
                return
            }
        } else {
            true
        }
        if (sessions[session.id] !== session || !session.active) return
        if (!inScope) {
            session.filteredEntries++
            return
        }
        try {
            session.addEntry(store.append(session.id, entry, session.policy))
        } catch (e: Exception) {
            session.droppedEntries++
            api.logging().logToError("CaptureManager: failed to store entry for '${session.id}': ${e.message}")
        }
    }

    private fun requireValidSessionId(sessionId: String) {
        require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
        require(sessionId.length <= 200) { "Session ID cannot exceed 200 characters" }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.2f MiB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.2f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val MAX_SUMMARY_ENDPOINTS = 1000
        private const val LEGACY_STORAGE_KEY = "capture_sessions"
        private const val MANIFEST_KEY = "capture_sessions_v2"
        private const val STORE_ID_KEY = "capture_store_id"
    }
}
