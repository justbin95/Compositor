package com.wonderassembly.compositor

import android.content.Context
import android.net.Uri
import com.wonderassembly.compositor.core.ReferencePngWriter
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectImportRegressionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val layerId = "10000000-0000-4000-8000-000000000001"

    @Test fun generatedImageManifestLoadsThroughProductionReaderAndArraysStayInvalid() {
        val cache = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val images = File(cache, "images").apply { mkdirs() }
        File(images, "$layerId.png").outputStream().use { output ->
            ReferencePngWriter.writeRgba8PremultipliedRows(output, 320, 240) { _, _ -> }
        }
        val manifest = ProjectImageImporter.importedImageManifest(layerId, 320, 240)
        val file = File(cache, "manifest.json")
        file.writeText(manifest.toString())
        val reader = CompProjectReader(context)
        val project = reader.readExtractedPackage(Uri.fromFile(cache), cache, "test.comp", cache)
        assertEquals(320, project.width)
        assertEquals(240, project.height)
        assertEquals(0.0, project.layers.single().transform.x, 0.0)
        assertEquals(320.0, project.layers.single().transform.width, 0.0)
        manifest.getJSONArray("layers").getJSONObject(0).getJSONObject("transform")
            .put("origin", JSONArray().put(0).put(0))
        file.writeText(manifest.toString())
        expectRejected("transform position") { reader.readExtractedPackage(Uri.fromFile(cache), cache, "test.comp", cache) }
        cache.deleteRecursively()
    }

    @Test fun originalBlendArchiveWithReadmeLoadsUnchanged() {
        val sample = File("../samples/Compositor-Android-Blend-Demo.comp.zip")
        assertTrue(sample.isFile)
        val project = importer().read(Uri.fromFile(sample)) { false }
        assertEquals(8, project.layers.size)
        ZipFile(sample).use { zip ->
            assertArrayEquals(zip.getInputStream(zip.getEntry("Compositor-Android-Blend-Demo.comp/manifest.json")).readBytes(), project.originalManifestBytes)
            for (asset in project.packageAssets) {
                assertArrayEquals(zip.getInputStream(zip.getEntry("Compositor-Android-Blend-Demo.comp/images/${asset.name}")).readBytes(), File(asset.uri.path!!).readBytes())
            }
        }
        project.archiveCacheDirectory!!.deleteRecursively()
    }

    @Test fun readmeAllowedOnlyAtProjectRootInBothLayouts() {
        for (wrapped in listOf(false, true)) {
            val entries = sampleEntries(wrapped)
            entries[if (wrapped) "test.comp/README.md" else "README.md"] = ByteArray(65536)
            val project = importer().read(Uri.fromFile(archive(entries))) { false }
            assertEquals(8, project.layers.size)
            project.archiveCacheDirectory!!.deleteRecursively()
        }
    }

    @Test fun unknownOversizedDirectoryAndTraversalReadmesAreRejected() {
        for ((name, bytes) in listOf(
            "README.md" to ByteArray(65537), "README.txt" to byteArrayOf(1),
            "README.md/" to byteArrayOf(), "../README.md" to byteArrayOf(),
            "images/nested/README.md" to byteArrayOf(), "other.comp/README.md" to byteArrayOf()
        )) {
            val entries = sampleEntries(false).apply { put(name, bytes) }
            expectRejected { importer().read(Uri.fromFile(archive(entries))) { false } }
        }
    }

    @Test fun readmeCrcCorruptionAndSymlinkRemainRejected() {
        for (symlink in listOf(false, true)) {
            val file = archive(sampleEntries(false).apply { put("README.md", "read me".toByteArray()) })
            val bytes = file.readBytes()
            var index = 0
            while (index < bytes.size - 46) {
                if ((bytes[index].toInt() and 255) == 0x50 && (bytes[index + 1].toInt() and 255) == 0x4b && bytes[index + 2] == 1.toByte() && bytes[index + 3] == 2.toByte()) {
                    val nameSize = (bytes[index + 28].toInt() and 255) or ((bytes[index + 29].toInt() and 255) shl 8)
                    if (String(bytes, index + 46, nameSize) == "README.md") {
                        if (symlink) {
                            bytes[index + 5] = 3 // Unix host
                            bytes[index + 41] = 0xa0.toByte() // S_IFLNK in upper external attributes
                        } else bytes[index + 16] = (bytes[index + 16].toInt() xor 1).toByte()
                        break
                    }
                }
                index++
            }
            check(index < bytes.size - 46)
            file.writeBytes(bytes)
            expectRejected { importer().read(Uri.fromFile(file)) { false } }
        }
    }

    @Test fun duplicateReadmeAndMultipleWrappedRootsRemainRejected() {
        val duplicate = archive(sampleEntries(false).apply {
            put("README.md", byteArrayOf(1)); put("READMF.md", byteArrayOf(1))
        })
        val bytes = duplicate.readBytes()
        val from = "READMF.md".toByteArray()
        val to = "README.md".toByteArray()
        for (offset in 0..bytes.size - from.size) {
            if (from.indices.all { bytes[offset + it] == from[it] }) to.copyInto(bytes, offset)
        }
        duplicate.writeBytes(bytes)
        expectRejected("duplicate") { importer().read(Uri.fromFile(duplicate)) { false } }
        val multiple = archive(sampleEntries(true).apply { put("other.comp/README.md", byteArrayOf()) })
        expectRejected("more than one") { importer().read(Uri.fromFile(multiple)) { false } }
    }

    private fun importer() = CompProjectArchiveImporter(context, CompProjectReader(context))
    private fun sampleEntries(wrapped: Boolean): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipFile(File("../samples/Compositor-Android-Blend-Demo.comp.zip")).use { zip ->
            for (entry in zip.entries()) {
                val relative = entry.name.substringAfter('/')
                if (relative.isEmpty() || relative.startsWith("README")) continue
                result[(if (wrapped) "test.comp/" else "") + relative] = zip.getInputStream(entry).readBytes()
            }
        }
        return result
    }
    private fun archive(entries: Map<String, ByteArray>): File {
        val file = File.createTempFile("regression-", ".zip", context.cacheDir)
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
            }
        }
        return file
    }
    private fun expectRejected(message: String? = null, read: () -> Unit) {
        try { read(); fail("Expected the production reader to reject the input") }
        catch (error: IllegalArgumentException) {
            if (message != null) assertTrue(error.message.orEmpty().contains(message))
        }
    }
}
