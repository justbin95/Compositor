package com.wonderassembly.compositor

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

/** Publishes an independent generation. No existing document is opened for writing or deleted. */
internal class ProjectFolderPublisher<T>(private val storage: Storage<T>) {
    interface Storage<T> {
        fun createDirectory(parent: T, name: String): T
        fun createFile(parent: T, name: String): T
        fun write(file: T): OutputStream
        fun read(file: T): InputStream
        fun deleteCreatedDirectory(directory: T): Boolean
    }
    class PublicationFailure(cause: Throwable, val cleanupFailed: Boolean) : Exception(cause.message, cause)

    fun publish(parent: T, packageRoot: File, validate: (T) -> Unit, cancelled: () -> Boolean,
        packageName: String = packageRoot.name): T {
        var created: T? = null
        try {
            checkCancelled(cancelled)
            require(packageRoot.isDirectory)
            val assets = requireNotNull(File(packageRoot, "images").listFiles()).sortedBy { it.name }
            require(assets.all { it.isFile && it.name != "." && it.name != ".." && '/' !in it.name && '\\' !in it.name })
            val base = packageName.removeSuffix(".comp").map {
                if (it in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') || Character.isISOControl(it)) '_' else it
            }.joinToString("").take(64).trim().ifBlank { "Project" }
            val name = base + "-${UUID.randomUUID()}.comp"
            val folder = storage.createDirectory(parent, name)
            created = folder
            val images = storage.createDirectory(folder, "images")
            for (asset in assets) copyChecked(asset, storage.createFile(images, asset.name), cancelled)
            // Until all images have been read back, there is no manifest that can open as a project.
            copyChecked(File(packageRoot, "manifest.json"), storage.createFile(folder, "manifest.json"), cancelled)
            checkCancelled(cancelled)
            validate(folder)
            checkCancelled(cancelled)
            return folder
        } catch (error: Exception) {
            val cleanupFailed = created?.let { !runCatching { storage.deleteCreatedDirectory(it) }.getOrDefault(false) } ?: false
            throw PublicationFailure(error, cleanupFailed)
        } catch (error: OutOfMemoryError) {
            val cleanupFailed = created?.let { !runCatching { storage.deleteCreatedDirectory(it) }.getOrDefault(false) } ?: false
            throw PublicationFailure(error, cleanupFailed)
        }
    }

    private fun copyChecked(source: File, destination: T, cancelled: () -> Boolean) {
        val expected = MessageDigest.getInstance("SHA-256")
        var expectedSize = 0L
        storage.write(destination).use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled(cancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    expected.update(buffer, 0, count)
                    expectedSize += count
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
        }
        val actual = MessageDigest.getInstance("SHA-256")
        var actualSize = 0L
        storage.read(destination).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled(cancelled)
                val count = input.read(buffer)
                if (count < 0) break
                actualSize += count
                require(actualSize <= expectedSize) { "The saved file has unexpected extra bytes." }
                actual.update(buffer, 0, count)
            }
        }
        require(actualSize == expectedSize && actual.digest().contentEquals(expected.digest())) {
            "The selected provider did not preserve the complete project bytes."
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Folder saving was cancelled.")
    }
}
