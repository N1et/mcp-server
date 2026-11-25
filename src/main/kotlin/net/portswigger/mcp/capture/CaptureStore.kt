package net.portswigger.mcp.capture

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

private val storeJson = Json { ignoreUnknownKeys = true }

class CaptureStore(
    val root: Path,
    private val logError: (String) -> Unit = {}
) : AutoCloseable {
    private val blobsDir = root.resolve("blobs")
    private val sessionsDir = root.resolve("sessions")
    private val privateDirectories = permissions("rwx------")
    private val privateFiles = permissions("rw-------")
    private val unwritableIndexes = mutableSetOf<Path>()
    private var ownerChannel: FileChannel? = null
    private var ownerLock: FileLock? = null
    private val ownershipKey: Path

    init {
        Files.createDirectories(root, *privateDirectories)
        Files.createDirectories(blobsDir, *privateDirectories)
        Files.createDirectories(sessionsDir, *privateDirectories)
        ownershipKey = root.toRealPath()
    }

    fun claimOwnership() = synchronized(FILE_LOCK_MONITOR) {
        if (ownerChannel != null) return@synchronized
        check(ownershipKey !in OWNED_ROOTS) { OWNERSHIP_CONFLICT_MESSAGE }
        val channel = openFile(root.resolve(".owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            checkNotNull(lock) { OWNERSHIP_CONFLICT_MESSAGE }
            ownerChannel = channel
            ownerLock = lock
            OWNED_ROOTS.add(ownershipKey)
        } catch (failure: Exception) {
            channel.close()
            throw failure
        }
    }

    override fun close(): Unit = synchronized(FILE_LOCK_MONITOR) {
        val lock = ownerLock
        val channel = ownerChannel
        ownerLock = null
        ownerChannel = null
        try {
            lock?.release()
        } finally {
            try {
                channel?.close()
            } finally {
                if (channel != null && !channel.isOpen) OWNED_ROOTS.remove(ownershipKey)
            }
        }
    }

    fun append(sessionId: String, entry: CapturedEntry, policy: CapturePolicy): CapturedEntryRef = withStoreLock {
        val path = sessionPath(sessionId)
        if (path in unwritableIndexes) throw IOException("Capture index requires recovery before further writes")
        // Opening without CREATE detects missing external data before publishing a payload.
        openFile(path, StandardOpenOption.WRITE).use { channel ->
            val ref = storePayload(entry, policy)
            val record = (storeJson.encodeToString(ref) + "\n").toByteArray(UTF_8)
            appendRecord(path, channel, record)
            ref
        }
    }

    internal fun appendRecord(path: Path, channel: FileChannel, record: ByteArray) {
        val previousSize = channel.size()
        channel.position(previousSize)
        try {
            val buffer = ByteBuffer.wrap(record)
            while (buffer.hasRemaining()) channel.write(buffer)
        } catch (failure: IOException) {
            try {
                channel.truncate(previousSize)
            } catch (rollbackFailure: IOException) {
                unwritableIndexes.add(path)
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    fun read(ref: CapturedEntryRef): CapturedEntry = withStoreLock {
        val payload = Files.newInputStream(blobPath(ref.blobId)).use { input ->
            GZIPInputStream(input).bufferedReader(UTF_8).use { reader ->
                storeJson.decodeFromString<CapturePayload>(reader.readText())
            }
        }
        CapturedEntry(
            payload.request, payload.response, ref.host, ref.port,
            ref.method, ref.path, ref.statusCode, ref.timestampMs
        )
    }

    fun loadRefs(sessionId: String): List<CapturedEntryRef> = withStoreLock {
        readIndex(sessionPath(sessionId))
    }

    fun replaceSession(
        sessionId: String,
        entries: List<CapturedEntry>,
        policy: CapturePolicy
    ): List<CapturedEntryRef> = withStoreLock {
        val refs = entries.map { storePayload(it, policy) }
        writeIndex(sessionId, refs)
        refs
    }

    fun replaceRefs(sessionId: String, refs: List<CapturedEntryRef>) = withStoreLock {
        refs.forEach { validateBlobId(it.blobId) }
        writeIndex(sessionId, refs)
    }

    fun deleteSession(sessionId: String) = withStoreLock {
        Files.deleteIfExists(sessionPath(sessionId))
        Unit
    }

    fun stats(): CaptureStorageStats = withStoreLock {
        val indexes = listFiles(sessionsDir, "jsonl")
        val blobs = listFiles(blobsDir, "gz")
        val referenced = referencedBlobIds(indexes)
        val orphans = blobs.filter { blobIdFromPath(it) !in referenced }
        CaptureStorageStats(
            root = root.toAbsolutePath().toString(),
            sessionFiles = indexes.size,
            blobFiles = blobs.size,
            referencedBlobs = referenced.size,
            orphanBlobs = orphans.size,
            indexBytes = indexes.sumOf { Files.size(it) },
            blobBytes = blobs.sumOf { Files.size(it) },
            orphanBytes = orphans.sumOf { Files.size(it) }
        )
    }

    fun cleanup(
        dryRun: Boolean,
        expectedSessionIds: Collection<String> = emptyList()
    ): CaptureCleanupResult = withStoreLock {
        // Missing expected indexes are as unsafe as corrupt ones: neither proves a blob is orphaned.
        val indexes = (listFiles(sessionsDir, "jsonl") + expectedSessionIds.map(::sessionPath)).distinct()
        val referenced = referencedBlobIds(indexes)
        val orphans = listFiles(blobsDir, "gz").filter { blobIdFromPath(it) !in referenced }
        val bytes = orphans.sumOf { Files.size(it) }
        val deleted = if (dryRun) 0 else orphans.count { Files.deleteIfExists(it) }
        CaptureCleanupResult(dryRun, orphans.size, bytes, deleted)
    }

    private fun storePayload(entry: CapturedEntry, policy: CapturePolicy): CapturedEntryRef {
        require(policy.maxRequestBytes > 0 && policy.maxResponseBytes > 0) { "Message limits must be positive" }
        val requestBytes = entry.request.toByteArray(UTF_8)
        val responseBytes = entry.response?.toByteArray(UTF_8)
        val request = truncateUtf8(requestBytes, policy.maxRequestBytes)
        val response = responseBytes?.let { truncateUtf8(it, policy.maxResponseBytes) }
        val payloadBytes = storeJson.encodeToString(CapturePayload(request.text, response?.text)).toByteArray(UTF_8)
        val blobId = sha256(payloadBytes)
        writeBlobIfAbsent(blobId, payloadBytes)
        return CapturedEntryRef(
            blobId = blobId,
            host = entry.host,
            port = entry.port,
            method = entry.method,
            path = entry.path,
            statusCode = entry.statusCode,
            timestampMs = entry.timestampMs,
            requestBytes = requestBytes.size,
            responseBytes = responseBytes?.size ?: 0,
            storedRequestBytes = request.bytes,
            storedResponseBytes = response?.bytes ?: 0,
            requestTruncated = request.truncated,
            responseTruncated = response?.truncated ?: false,
            hasRequestBody = hasBody(entry.request),
            staticAsset = isStaticAsset(entry)
        )
    }

    private fun readIndex(path: Path): List<CapturedEntryRef> {
        val refs = mutableListOf<CapturedEntryRef>()
        Files.newBufferedReader(path, UTF_8).useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                try {
                    val ref = storeJson.decodeFromString<CapturedEntryRef>(line)
                    validateBlobId(ref.blobId)
                    refs.add(ref)
                } catch (e: SerializationException) {
                    val message = "Invalid capture index '${path.fileName}' at line ${index + 1}"
                    logError(message)
                    throw IOException(message, e)
                }
            }
        }
        return refs
    }

    private fun writeIndex(sessionId: String, refs: List<CapturedEntryRef>) {
        val destination = sessionPath(sessionId)
        val temp = Files.createTempFile(sessionsDir, destination.fileName.toString(), ".tmp", *privateFiles)
        try {
            Files.newBufferedWriter(temp, UTF_8).use { writer ->
                refs.forEach { writer.append(storeJson.encodeToString(it)).append('\n') }
            }
            atomicMove(temp, destination)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun writeBlobIfAbsent(blobId: String, payloadBytes: ByteArray) {
        val destination = blobPath(blobId)
        if (Files.exists(destination)) return
        Files.createDirectories(destination.parent, *privateDirectories)
        val temp = Files.createTempFile(destination.parent, blobId, ".tmp", *privateFiles)
        try {
            Files.newOutputStream(temp).use { output ->
                GZIPOutputStream(output).use { it.write(payloadBytes) }
            }
            atomicMove(temp, destination)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun referencedBlobIds(indexes: List<Path>): Set<String> = buildSet {
        indexes.forEach { path -> readIndex(path).forEach { add(it.blobId) } }
    }

    private fun sessionPath(sessionId: String): Path =
        sessionsDir.resolve("${sha256(sessionId.toByteArray(UTF_8))}.jsonl")

    private fun blobPath(blobId: String): Path {
        validateBlobId(blobId)
        return blobsDir.resolve(blobId.take(2)).resolve("$blobId.json.gz")
    }

    private fun validateBlobId(blobId: String) {
        if (blobId.length != 64 || blobId.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw IOException("Invalid capture payload identifier")
        }
    }

    private fun blobIdFromPath(path: Path): String = path.fileName.toString().removeSuffix(".json.gz")

    private fun listFiles(directory: Path, extension: String): List<Path> = Files.walk(directory).use { paths ->
        paths.filter { it.isRegularFile() && it.extension == extension }.toList()
    }

    private fun <T> withStoreLock(action: () -> T): T = synchronized(FILE_LOCK_MONITOR) {
        // File locks exclude other Burp processes; this monitor also prevents overlapping
        // locks from separate CaptureStore instances in this JVM. Never publish a blob
        // outside this transaction: cleanup must see its index reference atomically.
        openFile(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { action() }
        }
    }

    private fun openFile(path: Path, vararg options: StandardOpenOption): FileChannel =
        FileChannel.open(path, options.toSet(), *privateFiles)

    private fun permissions(mode: String): Array<FileAttribute<*>> =
        if ("posix" in root.fileSystem.supportedFileAttributeViews()) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode)))
        } else {
            emptyArray()
        }

    private fun atomicMove(source: Path, destination: Path) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class TruncatedText(val text: String, val bytes: Int, val truncated: Boolean)

    private fun truncateUtf8(bytes: ByteArray, maxBytes: Int): TruncatedText {
        if (bytes.size <= maxBytes) return TruncatedText(String(bytes, UTF_8), bytes.size, false)
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return TruncatedText(String(bytes, 0, end, UTF_8), end, true)
    }

    private fun hasBody(request: String): Boolean {
        val separator = when {
            "\r\n\r\n" in request -> "\r\n\r\n"
            "\n\n" in request -> "\n\n"
            else -> return false
        }
        return request.substringAfter(separator).isNotBlank()
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    companion object {
        private val FILE_LOCK_MONITOR = Any()
        private val OWNED_ROOTS = mutableSetOf<Path>()
        private const val OWNERSHIP_CONFLICT_MESSAGE =
            "Capture storage is already open in another project. Close it before reopening this store."
    }
}
