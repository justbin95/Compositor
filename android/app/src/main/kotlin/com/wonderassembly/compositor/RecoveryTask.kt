package com.wonderassembly.compositor

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Owns a frozen revision and its source assets independently of the editor Activity. */
internal class RecoveryTask(
    private val context: Context,
    val project: CompProjectReader.Project,
    val revision: Long,
    val epoch: String,
    private val frozenAssets: Map<String, FrozenLayerExport>,
    private val store: RecoveryStore
) {
    data class Result(val saved: RecoveryStore.Copy?, val failure: String?, val cleanupFailed: Boolean)
    @Volatile var result: Result? = null
        private set
    private val releases = mutableListOf<() -> Unit>()

    fun releaseWhenFinished(action: () -> Unit) {
        val runNow = synchronized(this) {
            if (result != null) true else { releases.add(action); false }
        }
        if (runNow) action()
    }

    fun start() {
        synchronized(active) { active.add(this) }
        try { Thread({
            var outcome: Result
            try {
                val saved = store.save(project.documentId, { output ->
                    CompProjectArchiveWriter(context.contentResolver).write(output, project, emptyMap(), frozenAssets = frozenAssets)
                }, { candidate ->
                    val checked = CompProjectArchiveImporter(context, CompProjectReader(context)).read(Uri.fromFile(candidate)) { false }
                    try {
                        check(checked.documentId == project.documentId && checked.width == project.width && checked.height == project.height &&
                            checked.originalManifestBytes.contentEquals(project.originalManifestBytes)) {
                            "The recovery package does not match the captured document."
                        }
                    } finally { checked.releasePrivateAssets() }
                })
                outcome = Result(saved.copy, null, saved.cleanupFailed)
            } catch (error: Exception) {
                outcome = Result(null, error.message ?: "Internal recovery failed.", false)
            } catch (_: OutOfMemoryError) {
                outcome = Result(null, "There was not enough memory to save an internal recovery copy.", false)
            } finally {
                frozenAssets.values.forEach { runCatching { it.close() } }
            }
            finish(outcome)
        }, "compositor-recovery").start() }
        catch (error: Throwable) {
            frozenAssets.values.forEach { runCatching { it.close() } }
            finish(Result(null, error.message ?: "Could not start internal recovery.", false))
            throw error
        }
    }

    private fun finish(outcome: Result) {
        val callbacks = synchronized(this) {
            result = outcome
            releases.toList().also { releases.clear() }
        }
        synchronized(active) { active.remove(this) }
        callbacks.forEach { runCatching(it) }
    }

    companion object {
        private val active = mutableSetOf<RecoveryTask>()
        private val stores = mutableMapOf<String, RecoveryStore>()
        val isRunning: Boolean get() = synchronized(active) { active.isNotEmpty() }

        fun releaseProjectWhenFinished(project: CompProjectReader.Project) {
            val owners = synchronized(active) { active.filter { it.project.documentId == project.documentId } }
            fun release(index: Int) {
                if (index >= owners.size) project.releasePrivateAssets()
                else owners[index].releaseWhenFinished { release(index + 1) }
            }
            release(0)
        }

        fun store(context: Context): RecoveryStore = synchronized(stores) {
            val directory = File(context.filesDir, "recovery")
            stores.getOrPut(directory.absolutePath) { RecoveryStore(directory, ::syncDirectory) }
        }

        internal fun syncDirectory(directory: File) {
            check(directory.isDirectory) { "The recovery sync target is not a directory." }
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
    }
}
