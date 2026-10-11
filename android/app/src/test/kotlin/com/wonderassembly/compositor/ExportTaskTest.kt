package com.wonderassembly.compositor

import com.wonderassembly.compositor.core.ReferencePngWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class ExportTaskTest {
    @Test fun completedPngIsPublishedAfterEncodingAndFullyDecodes() {
        val cache = Files.createTempDirectory("export-test").toFile()
        val destination = ByteArrayOutputStream()
        var encoded = false
        val task = ExportTask(ExportTask.Kind.LAYER)
        task.execute(cache, {}, { assertTrue(encoded); destination }, { false }) { output, _, _ ->
            writePng(output)
            encoded = true
        }
        assertTrue(task.result!!.succeeded)
        assertCompletePng(destination.toByteArray())
        val image = ImageIO.read(destination.toByteArray().inputStream())
        assertEquals(1800, image.height)
        assertEquals(1800, image.width)
        assertEquals(0x80ff0000.toInt(), image.getRGB(0, 1799))
        assertEquals(0, cache.listFiles()!!.size)
        cache.delete()
    }

    @Test fun interruptionDuringEncodingNeverPublishesAndReportsDeleteRefusal() {
        val task = ExportTask(ExportTask.Kind.LAYER)
        withCache { cache ->
            task.execute(cache, {}, { fail("Incomplete PNG must not reach the provider"); ByteArrayOutputStream() }, { false }) { output, _, _ ->
                output.write(byteArrayOf(1, 2))
                throw IOException("encoding interrupted")
            }
            assertFalse(task.result!!.succeeded)
            assertFalse(task.result!!.cancelled)
            assertTrue(task.result!!.cleanupFailed)
            assertEquals("encoding interrupted", task.result!!.failure)
        }
    }

    @Test fun writeCloseAndOpenFailuresDeleteTargetAndNeverSucceed() {
        for (stage in listOf("open", "write", "close")) withCache { cache ->
            var deleted = false
            val task = ExportTask(ExportTask.Kind.LAYER)
            task.execute(cache, {}, {
                if (stage == "open") throw IOException("open")
                object : OutputStream() {
                    override fun write(value: Int) { if (stage == "write") throw IOException("write") }
                    override fun close() { if (stage == "close") throw IOException("close") }
                }
            }, { deleted = true; true }) { output, _, _ -> output.write(byteArrayOf(1, 2, 3)) }
            assertFalse(task.result!!.succeeded)
            assertEquals(stage, task.result!!.failure)
            assertTrue(deleted)
            assertFalse(task.result!!.cleanupFailed)
        }
    }

    @Test fun protectedSourceIsNeverOpenedOrDeleted() = withCache { cache ->
        val task = ExportTask(ExportTask.Kind.LAYER)
        task.execute(cache, { throw IllegalArgumentException("source") },
            { fail("source was opened"); ByteArrayOutputStream() }, { fail("source was deleted"); false }) { _, _, _ -> fail() }
        assertFalse(task.result!!.succeeded)
        assertFalse(task.result!!.cleanupFailed)
    }

    @Test fun actualRunningCancellationRetainsResultAndDefersResourceRelease() = withCache { cache ->
        val encoding = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val released = CountDownLatch(1)
        val task = ExportTask(ExportTask.Kind.LAYER)
        task.start(cache, {}, { fail("Cancelled encoding was published"); ByteArrayOutputStream() }, { true }) { output, _, _ ->
            encoding.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            output.write(1)
        }
        assertTrue(encoding.await(5, TimeUnit.SECONDS))
        task.releaseWhenFinished { released.countDown() }
        assertEquals(1L, released.count)
        task.cancel()
        resume.countDown()
        assertTrue(released.await(5, TimeUnit.SECONDS))
        assertTrue(task.result!!.cancelled)
        assertFalse(task.result!!.succeeded)
        assertFalse(task.result!!.cleanupFailed)
    }

    @Test fun cancellationDuringPublicationAndThrowingDeleteReportResidualFile() = withCache { cache ->
        val task = ExportTask(ExportTask.Kind.LAYER)
        task.execute(cache, {}, {
            object : OutputStream() {
                override fun write(value: Int) { task.cancel() }
            }
        }, { throw IOException("provider refuses deletion") }) { output, _, _ -> output.write(ByteArray(70000)) }
        assertFalse(task.result!!.succeeded)
        assertTrue(task.result!!.cancelled)
        assertTrue(task.result!!.cleanupFailed)
    }

    private fun assertCompletePng(bytes: ByteArray) {
        val input = java.io.DataInputStream(bytes.inputStream())
        assertEquals(0x89504e470d0a1a0aUL.toLong(), input.readLong())
        val compressed = ByteArrayOutputStream()
        var sawEnd = false
        while (input.available() > 0) {
            val length = input.readInt()
            assertTrue(length >= 0 && length <= input.available() - 8)
            val type = ByteArray(4).also(input::readFully)
            val data = ByteArray(length).also(input::readFully)
            val crc = java.util.zip.CRC32().apply { update(type); update(data) }.value
            assertEquals(crc, input.readInt().toLong() and 0xffffffffL)
            when (String(type, Charsets.US_ASCII)) {
                "IDAT" -> compressed.write(data)
                "IEND" -> { assertEquals(0, length); assertEquals(0, input.available()); sawEnd = true }
            }
        }
        assertTrue(sawEnd)
        val inflater = java.util.zip.Inflater()
        try {
            inflater.setInput(compressed.toByteArray())
            val buffer = ByteArray(65536)
            var total = 0L
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                assertTrue(count > 0 || inflater.finished())
                total += count
            }
            assertEquals(1800L * (1800L * 4 + 1), total)
            assertEquals(0, inflater.remaining)
        } finally { inflater.end() }
    }

    private fun withCache(block: (File) -> Unit) {
        val cache = Files.createTempDirectory("export-test").toFile()
        try { block(cache); assertEquals(0, cache.listFiles()!!.size) } finally { cache.deleteRecursively() }
    }
    private fun writePng(output: OutputStream) {
        ReferencePngWriter.writeRgba8PremultipliedRows(output, 1800, 1800) { _, row ->
            for (x in row.indices step 4) { row[x] = 128.toByte(); row[x + 3] = 128.toByte() }
        }
    }
}
