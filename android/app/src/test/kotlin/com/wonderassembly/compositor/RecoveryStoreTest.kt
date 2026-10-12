package com.wonderassembly.compositor

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun twoHundredInjectedFailuresKeepOldOrCompleteNewProductionPackages() {
        val creator = BlankProjectCreator(context) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }
        val old = creator.create(16, 16, 72.0) { false }
        val imported = creator.create(8, 8, 72.0, BlankProjectCreator.Background.WHITE) { false }
        val newer = ProjectLayerAppender(context).append(old, imported)
        val source = File(old.packageAssets.single().uri.path!!)
        val sourceHash = digest(source)
        val root = File(context.filesDir, "recovery-fault-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            for (index in 0 until 200) {
                val folder = File(root, index.toString())
                var failAt: RecoveryStore.Stage? = null
                var injected = false
                var writeCalls = 0
                val interruptWrite = index / 7 % 20 + 1
                val store = RecoveryStore(folder, {}, { stage ->
                    if (stage == RecoveryStore.Stage.WRITING) writeCalls++
                    if (!injected && stage == failAt && (stage != RecoveryStore.Stage.WRITING || writeCalls >= interruptWrite)) {
                        injected = true; throw IOException("Injected $stage at write $writeCalls")
                    }
                })
                val first = store.save(old.documentId, { CompProjectArchiveWriter(context.contentResolver).write(it, old, emptyMap()) }, ::validate)
                val originalHash = digest(first.copy.file)
                failAt = RecoveryStore.Stage.entries[index % RecoveryStore.Stage.entries.size]
                try {
                    store.save(old.documentId, { CompProjectArchiveWriter(context.contentResolver).write(it, newer, emptyMap()) }, ::validate)
                    fail("The injected failure was not reached: $failAt")
                } catch (_: IOException) {}
                assertTrue(injected)
                assertTrue(first.copy.file.exists())
                assertArrayEquals(originalHash, digest(first.copy.file))
                val copies = store.copies()
                assertTrue(copies.size in 1..2)
                for (copy in copies) {
                    val checked = read(copy.file)
                    try {
                        assertEquals(old.documentId, checked.documentId)
                        assertTrue(checked.originalManifestBytes.contentEquals(old.originalManifestBytes) ||
                            checked.originalManifestBytes.contentEquals(newer.originalManifestBytes))
                    } finally { checked.releasePrivateAssets() }
                }
                assertFalse(folder.walkTopDown().any { it.extension == "pending" })
            }
            assertArrayEquals(sourceHash, digest(source))
            println("RECOVERY06: 200/200 injected failures; prior hashes unchanged; all published copies reopen through the production importer.")
        } finally { root.deleteRecursively(); newer.releasePrivateAssets() }
    }

    @Test fun rotationKeepsTwoVersionsForThreeProjectsAndCleanupIsPrivate() {
        val root = File(context.filesDir, "recovery-rotation-${UUID.randomUUID()}")
        val external = File(context.filesDir, "user-work-${UUID.randomUUID()}.zip").apply { writeText("User work") }
        val store = RecoveryStore(root, {})
        try {
            for (project in 1..4) for (version in 1..3) {
                store.save("project-$project", { it.write("$project/$version".toByteArray()) }, { assertTrue(it.length() > 0) })
            }
            val copies = store.copies()
            assertEquals(6, copies.size)
            assertEquals(3, copies.map { it.projectKey }.distinct().size)
            assertTrue(copies.groupBy { it.projectKey }.values.all { it.size == 2 })
            val pending = File(copies.first().file.parentFile, "123-${UUID.randomUUID()}.pending").apply { writeText("interrupted") }
            assertEquals(6, store.copies().size)
            assertTrue(store.cleanupPending()); assertFalse(pending.exists())
            assertFalse(store.delete(RecoveryStore.Copy(external, 1, 9, "unknown")))
            assertTrue(external.exists())
            assertTrue(store.delete(copies.first()))
            assertEquals(5, store.copies().size)
        } finally { root.deleteRecursively(); external.delete() }
    }

    @Test fun validationOrDirectorySyncFailureNeverPrunesPriorCopy() {
        val root = File(context.filesDir, "recovery-validation-${UUID.randomUUID()}")
        var reject = false
        val store = RecoveryStore(root, { if (reject) throw IOException("No storage sync") })
        try {
            val first = store.save("one", { it.write(byteArrayOf(1)) }, {})
            try { store.save("one", { it.write(byteArrayOf(2)) }, { throw IOException("Invalid candidate") }); fail() }
            catch (_: IOException) {}
            assertEquals(1, store.copies().size)
            reject = true
            try { store.save("one", { it.write(byteArrayOf(3)) }, {}); fail() } catch (_: IOException) {}
            assertArrayEquals(byteArrayOf(1), first.copy.file.readBytes())
        } finally { root.deleteRecursively() }
    }

    private fun read(file: File) = CompProjectArchiveImporter(context, CompProjectReader(context)).read(Uri.fromFile(file)) { false }
    private fun validate(file: File) { read(file).releasePrivateAssets() }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
