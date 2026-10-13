package com.wonderassembly.compositor

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/** Owns encoding, publication, cleanup and the result independently of any Activity instance. */
internal class ExportTask(val kind: Kind, val layerName: String = "") {
    enum class Kind { LAYER, CANVAS, ARCHIVE, FOLDER }
    data class Result(val succeeded: Boolean, val cancelled: Boolean, val failure: String?, val cleanupFailed: Boolean)

    val progress = AtomicInteger(0)
    @Volatile var cancellationRequested = false
        private set
    @Volatile var result: Result? = null
        private set
    private var release: (() -> Unit)? = null
    @Volatile var publishedFolder: android.net.Uri? = null
        internal set
    @Volatile var publishedFolderName: String? = null
        internal set
    @Volatile var publicationCleanupFailed = false
        internal set

    @Synchronized fun cancel() {
        if (result == null) cancellationRequested = true
    }

    @Synchronized fun releaseWhenFinished(action: () -> Unit) {
        if (result != null) action() else release = action
    }

    fun start(
        cacheDirectory: File,
        validateDestination: () -> Unit,
        openDestination: () -> OutputStream,
        deleteDestination: () -> Boolean,
        encode: (OutputStream, () -> Boolean, (Int) -> Unit) -> Unit
    ) = startWithPublication(cacheDirectory, validateDestination, openDestination, deleteDestination, null, encode)

    fun startWithPublication(
        cacheDirectory: File,
        validateDestination: () -> Unit,
        openDestination: () -> OutputStream,
        deleteDestination: () -> Boolean,
        publish: ((File, () -> Boolean) -> Unit)?,
        encode: (OutputStream, () -> Boolean, (Int) -> Unit) -> Unit
    ) {
        Thread({
            executeWithPublication(cacheDirectory, validateDestination, openDestination, deleteDestination, publish, encode)
        }, "compositor-export").start()
    }

    // Separate from the thread launcher so failure injection can exercise the production pipeline.
    internal fun execute(
        cacheDirectory: File,
        validateDestination: () -> Unit,
        openDestination: () -> OutputStream,
        deleteDestination: () -> Boolean,
        encode: (OutputStream, () -> Boolean, (Int) -> Unit) -> Unit
    ) = executeWithPublication(cacheDirectory, validateDestination, openDestination, deleteDestination, null, encode)

    internal fun executeWithPublication(
        cacheDirectory: File,
        validateDestination: () -> Unit,
        openDestination: () -> OutputStream,
        deleteDestination: () -> Boolean,
        publish: ((File, () -> Boolean) -> Unit)?,
        encode: (OutputStream, () -> Boolean, (Int) -> Unit) -> Unit
    ) {
        var temporary: File? = null
        var mayDeleteDestination = false
        var succeeded = false
        var cancelled = false
        var failure: String? = null
        var cleanupFailed = false
        try {
            validateDestination()
            mayDeleteDestination = publish == null
            checkCancelled()
            temporary = File.createTempFile("compositor-export-", ".tmp", cacheDirectory)
            temporary.outputStream().use { output ->
                encode(CheckedOutput(output, MAX_ENCODED_BYTES), { cancellationRequested }, { progress.set(it.coerceIn(0, 90)) })
            }
            checkCancelled()
            // Only complete encoded bytes reach SAF; provider publication is still not atomic.
            if (publish != null) publish(temporary) { cancellationRequested } else openDestination().use { output ->
                temporary.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        progress.set(90 + (copied * 9 / temporary.length().coerceAtLeast(1)).toInt())
                    }
                    output.flush()
                }
            }
            synchronized(this) {
                if (publish == null) checkCancelled()
                succeeded = true
                // The publication and its close both succeeded. Later cancellation cannot undo it.
                result = Result(true, false, null, false)
            }
            progress.set(100)
        } catch (error: CancellationException) {
            cancelled = true
            failure = error.message
        } catch (error: Exception) {
            failure = error.message ?: "The export could not be written."
        } catch (_: OutOfMemoryError) {
            failure = "There was not enough memory to complete the export."
        } finally {
            temporary?.delete()
            if (!succeeded && mayDeleteDestination) {
                cleanupFailed = !runCatching(deleteDestination).getOrDefault(false)
            }
            synchronized(this) {
                result = Result(succeeded, cancelled, failure, cleanupFailed || publicationCleanupFailed)
                release?.invoke()
                release = null
            }
        }
    }

    private fun checkCancelled() {
        if (cancellationRequested || Thread.currentThread().isInterrupted) {
            throw CancellationException("Export was cancelled.")
        }
    }

    private inner class CheckedOutput(output: OutputStream, private val maximum: Long) : FilterOutputStream(output) {
        private var written = 0L
        override fun write(value: Int) {
            checkCancelled()
            check(written < maximum) { "The encoded export exceeds the 1 GiB temporary storage limit." }
            out.write(value)
            written++
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            checkCancelled()
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            check(written <= maximum - length) { "The encoded export exceeds the 1 GiB temporary storage limit." }
            out.write(bytes, offset, length)
            written += length
        }
        override fun flush() { checkCancelled(); out.flush() }
    }

    private companion object { const val MAX_ENCODED_BYTES = 1024L * 1024L * 1024L }
}
