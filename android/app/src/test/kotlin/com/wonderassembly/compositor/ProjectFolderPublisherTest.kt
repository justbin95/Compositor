package com.wonderassembly.compositor

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProjectFolderPublisherTest {
    private class DiskStorage(val fail: String? = null, val rejectCleanup: Boolean = false) : ProjectFolderPublisher.Storage<File> {
        val created = mutableSetOf<File>()
        override fun createDirectory(parent: File, name: String) = File(parent, name).also {
            if (fail == "create") throw IOException("create")
            check(it.mkdir()); created.add(it)
        }
        override fun createFile(parent: File, name: String) = File(parent, name).also { check(it.createNewFile()) }
        override fun write(file: File): OutputStream {
            if (fail == "open") throw IOException("open")
            val stream = file.outputStream()
            return object : OutputStream() {
                override fun write(value: Int) { if (fail == "write") throw IOException("write"); stream.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (fail == "write") throw IOException("write")
                    if (fail == "truncate") stream.write(bytes, offset, (length - 1).coerceAtLeast(0))
                    else stream.write(bytes, offset, length)
                }
                override fun close() { stream.close(); if (fail == "close") throw IOException("close") }
            }
        }
        override fun read(file: File) = if (fail == "read") throw IOException("read") else file.inputStream()
        override fun deleteCreatedDirectory(directory: File) = !rejectCleanup && directory in created && directory.deleteRecursively()
    }

    @Test fun independentVersionsVerifyBytesAndPublishManifestLast() = fixture { source, parent, old ->
        val storage = DiskStorage()
        val publisher = ProjectFolderPublisher(storage)
        repeat(2) {
            val saved = publisher.publish(parent, source, { folder ->
                assertArrayEquals(File(source, "images/a.png").readBytes(), File(folder, "images/a.png").readBytes())
                assertArrayEquals(File(source, "manifest.json").readBytes(), File(folder, "manifest.json").readBytes())
            }, { false })
            assertTrue(saved.name.endsWith(".comp"))
            assertNotEquals(old, saved)
        }
        assertEquals(3, parent.listFiles()!!.size)
        assertEquals("User work", File(old, "manifest.json").readText())
    }

    @Test fun faultsAndTruncatedReadbackNeverChangeOldVersion() = fixture { source, parent, old ->
        for (stage in listOf("create", "open", "write", "close", "read", "truncate", "validate")) {
            val storage = DiskStorage(stage)
            try {
                ProjectFolderPublisher(storage).publish(parent, source, { if (stage == "validate") error("invalid") }, { false })
                fail("$stage must fail")
            } catch (error: ProjectFolderPublisher.PublicationFailure) { assertFalse(error.cleanupFailed) }
            assertEquals(listOf(old), parent.listFiles()!!.toList())
            assertEquals("User work", File(old, "manifest.json").readText())
        }
    }

    @Test fun cancellationAndCleanupRefusalAreExplicit() = fixture { source, parent, old ->
        var calls = 0
        try {
            ProjectFolderPublisher(DiskStorage(rejectCleanup = true)).publish(parent, source, {}, { ++calls > 3 })
            fail()
        } catch (error: ProjectFolderPublisher.PublicationFailure) {
            assertTrue(error.cleanupFailed)
            assertTrue(error.cause is java.util.concurrent.CancellationException)
        }
        assertEquals("User work", File(old, "manifest.json").readText())
        assertFalse(parent.listFiles()!!.filter { it != old }.any { File(it, "manifest.json").exists() })
    }

    @Test fun exportPipelineDoesNotDeleteParentOnPublicationFailure() = fixture { source, parent, old ->
        val task = ExportTask(ExportTask.Kind.FOLDER)
        task.executeWithPublication(requireNotNull(source.parentFile), {}, { error("unused") }, { fail("must not delete parent"); false },
            { _, _ -> task.publicationCleanupFailed = true; throw IOException("provider failed") },
            { output, _, _ -> output.write(byteArrayOf(1)) })
        assertFalse(task.result!!.succeeded)
        assertTrue(task.result!!.cleanupFailed)
        assertTrue(old.exists()); assertTrue(parent.exists())
    }

    private fun fixture(block: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("folder-save").toFile()
        val source = File(root, "Source.comp").apply { mkdir() }
        File(source, "images").mkdir()
        File(source, "images/a.png").writeBytes(ByteArray(150_000) { it.toByte() })
        File(source, "manifest.json").writeText("Manifest")
        val parent = File(root, "destination").apply { mkdir() }
        val old = File(parent, "Old.comp").apply { mkdir() }
        File(old, "manifest.json").writeText("User work")
        try { block(source, parent, old) } finally { root.deleteRecursively() }
    }
}
