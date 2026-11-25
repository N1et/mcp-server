package net.portswigger.mcp.capture

import kotlinx.serialization.Serializable
import java.util.Collections

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
data class CapturePayload(val request: String, val response: String?)

@Serializable
data class CapturedEntryRef(
    val blobId: String,
    val host: String,
    val port: Int,
    val method: String,
    val path: String,
    val statusCode: Int?,
    val timestampMs: Long,
    val requestBytes: Int,
    val responseBytes: Int,
    val storedRequestBytes: Int,
    val storedResponseBytes: Int,
    val requestTruncated: Boolean,
    val responseTruncated: Boolean,
    val hasRequestBody: Boolean,
    val staticAsset: Boolean
) {
    val rawBytes: Long get() = requestBytes.toLong() + responseBytes
    val storedBytes: Long get() = storedRequestBytes.toLong() + storedResponseBytes
    val truncated: Boolean get() = requestTruncated || responseTruncated
}

@Serializable
data class CapturePolicy(
    val excludeStaticAssets: Boolean = true,
    val inScopeOnly: Boolean = false,
    val maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
    val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    fun normalized() = copy(
        maxRequestBytes = maxRequestBytes.coerceIn(MIN_MESSAGE_BYTES, MAX_MESSAGE_BYTES),
        maxResponseBytes = maxResponseBytes.coerceIn(MIN_MESSAGE_BYTES, MAX_MESSAGE_BYTES),
        maxEntries = maxEntries.coerceIn(1, MAX_ENTRIES)
    )

    companion object {
        const val DEFAULT_MAX_REQUEST_BYTES = 2 * 1024 * 1024
        const val DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES = 100_000
        const val MIN_MESSAGE_BYTES = 1024
        const val MAX_MESSAGE_BYTES = 100 * 1024 * 1024
        const val MAX_ENTRIES = 1_000_000
    }
}

@Serializable
data class CaptureSessionManifest(
    val id: String,
    val active: Boolean,
    val startedAt: Long,
    val stoppedAt: Long?,
    val policy: CapturePolicy = CapturePolicy(),
    val filteredEntries: Long = 0,
    val droppedEntries: Long = 0
)

@Serializable
data class CaptureManifest(
    val formatVersion: Int = 2,
    val storeId: String,
    val sessions: List<CaptureSessionManifest>
)

class CaptureSession(
    val id: String,
    @Volatile var active: Boolean,
    val startedAt: Long = System.currentTimeMillis(),
    @Volatile var stoppedAt: Long? = null,
    @Volatile var policy: CapturePolicy = CapturePolicy(),
    @Volatile var filteredEntries: Long = 0,
    @Volatile var droppedEntries: Long = 0,
    refs: List<CapturedEntryRef> = emptyList()
) {
    private val entries = Collections.synchronizedList(refs.toMutableList())

    val entryCount: Int get() = entries.size

    fun entryRefs(): List<CapturedEntryRef> = synchronized(entries) { entries.toList() }

    fun entryRef(index: Int): CapturedEntryRef? = synchronized(entries) { entries.getOrNull(index) }

    fun addEntry(ref: CapturedEntryRef) { entries.add(ref) }

    fun replaceEntries(refs: List<CapturedEntryRef>) = synchronized(entries) {
        entries.clear()
        entries.addAll(refs)
    }

    fun toManifest() = CaptureSessionManifest(
        id, active, startedAt, stoppedAt, policy, filteredEntries, droppedEntries
    )
}

data class CaptureStorageStats(
    val root: String,
    val sessionFiles: Int,
    val blobFiles: Int,
    val referencedBlobs: Int,
    val orphanBlobs: Int,
    val indexBytes: Long,
    val blobBytes: Long,
    val orphanBytes: Long
)

data class CaptureCleanupResult(
    val dryRun: Boolean,
    val orphanBlobs: Int,
    val reclaimableBytes: Long,
    val deletedBlobs: Int
)

private val STATIC_EXTENSIONS = setOf(
    ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
    ".woff", ".woff2", ".ttf", ".eot", ".webp", ".map", ".mp4", ".webm"
)
private val STATIC_PATHS = setOf("/site.webmanifest", "/favicon.ico", "/robots.txt")

fun isStaticAsset(path: String): Boolean {
    val normalizedPath = path.substringBefore('?').lowercase()
    return normalizedPath in STATIC_PATHS || STATIC_EXTENSIONS.any { normalizedPath.endsWith(it) }
}

fun isStaticAsset(entry: CapturedEntry): Boolean = isStaticAsset(entry.path)
fun isStaticAsset(entry: CapturedEntryRef): Boolean = entry.staticAsset
