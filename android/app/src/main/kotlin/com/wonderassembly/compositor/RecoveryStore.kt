package com.wonderassembly.compositor

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Publishes immutable private ZIP generations. A failed candidate never replaces a good copy. */
internal class RecoveryStore(
    private val root: File,
    private val syncDirectory: (File) -> Unit,
    private val checkpoint: (Stage) -> Unit = {}
) {
    enum class Stage { CREATED, WRITING, ENCODED, SYNCED, VALIDATED, PUBLISHED, DIRECTORY_SYNCED }
    data class Copy(val file: File, val time: Long, val bytes: Long, val projectKey: String) {
        fun projectName(): String? = runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                zip.entries().asSequence().firstOrNull { it.name.endsWith(".comp/manifest.json") }
                    ?.name?.substringBefore('/')?.removeSuffix(".comp")
                    ?.filterNot(Character::isISOControl)?.take(128)
            }
        }.getOrNull()
    }
    data class Result(val copy: Copy, val cleanupFailed: Boolean)

    @Synchronized fun save(documentId: String, encode: (OutputStream) -> Unit, validate: (File) -> Unit): Result {
        require(documentId.isNotBlank())
        check(root.isDirectory || root.mkdirs()) { "Could not prepare internal recovery storage." }
        syncDirectory(root.parentFile!!)
        val key = MessageDigest.getInstance("SHA-256").digest(documentId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val directory = File(root, key)
        check(directory.isDirectory || directory.mkdir()) { "Could not prepare the recovery project folder." }
        syncDirectory(root)
        val time = maxOf(System.currentTimeMillis(), copiesIn(directory).maxOfOrNull { it.time + 1 } ?: 0)
        val candidate = File(directory, "$time-${UUID.randomUUID()}.pending")
        val published = File(directory, candidate.name.removeSuffix(".pending") + ".zip")
        try {
            FileOutputStream(candidate).use { stream ->
                checkpoint(Stage.CREATED)
                val bounded = object : FilterOutputStream(stream) {
                    var written = 0L
                    override fun write(value: Int) {
                        check(written < MAX_BYTES) { "The recovery copy exceeds the 1 GiB storage limit." }
                        checkpoint(Stage.WRITING); out.write(value); written++
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                        check(length <= MAX_BYTES - written) { "The recovery copy exceeds the 1 GiB storage limit." }
                        checkpoint(Stage.WRITING); out.write(bytes, offset, length); written += length
                    }
                    override fun close() = flush()
                }
                encode(bounded)
                bounded.flush()
                checkpoint(Stage.ENCODED)
                stream.fd.sync()
                checkpoint(Stage.SYNCED)
            }
            validate(candidate)
            checkpoint(Stage.VALIDATED)
            // Same-directory atomic rename: never fall back to overwriting a prior generation.
            Files.move(candidate.toPath(), published.toPath(), StandardCopyOption.ATOMIC_MOVE)
            checkpoint(Stage.PUBLISHED)
            syncDirectory(directory)
            checkpoint(Stage.DIRECTORY_SYNCED)
            val copy = Copy(published, time, published.length(), key)
            val cleanupFailed = runCatching { prune() }.getOrDefault(false).not()
            return Result(copy, cleanupFailed)
        } finally {
            candidate.delete()
        }
    }

    // Immutable published files can be listed without waiting for a large save to finish.
    fun copies(): List<Copy> = projectDirectories().flatMap(::copiesIn).sortedByDescending { it.time }

    @Synchronized fun delete(copy: Copy): Boolean {
        val owned = copies().firstOrNull { it.file.canonicalFile == copy.file.canonicalFile } ?: return false
        val deleted = owned.file.delete()
        if (deleted) syncDirectory(owned.file.parentFile!!)
        return deleted
    }

    @Synchronized fun cleanupPending(): Boolean {
        var succeeded = true
        for (directory in projectDirectories()) {
            for (file in directory.listFiles().orEmpty().filter { it.isFile && it.name.matches(PENDING_NAME) }) {
                if (!file.delete()) succeeded = false
            }
        }
        return succeeded
    }

    private fun prune(): Boolean {
        var succeeded = true
        val projects = projectDirectories().map { it to copiesIn(it) }.filter { it.second.isNotEmpty() }
            .sortedByDescending { it.second.maxOf { copy -> copy.time } }
        for ((index, pair) in projects.withIndex()) {
            val (directory, copies) = pair
            val obsolete = if (index < 3) copies.sortedByDescending { it.time }.drop(2) else copies
            for (copy in obsolete) if (!copy.file.delete()) succeeded = false
            if (obsolete.isNotEmpty()) syncDirectory(directory)
        }
        return succeeded
    }

    private fun projectDirectories(): List<File> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{64}")) && it.canonicalFile.parentFile == root.canonicalFile }

    private fun copiesIn(directory: File): List<Copy> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(COPY_NAME) && it.canonicalFile.parentFile == directory.canonicalFile }
        .mapNotNull { file -> file.name.substringBefore('-').toLongOrNull()?.let { Copy(file, it, file.length(), directory.name) } }

    companion object {
        private const val MAX_BYTES = 1024L * 1024L * 1024L
        private val COPY_NAME = Regex("[0-9]{1,19}-[0-9a-f-]{36}\\.zip")
        private val PENDING_NAME = Regex("[0-9]{1,19}-[0-9a-f-]{36}\\.pending")
    }
}
