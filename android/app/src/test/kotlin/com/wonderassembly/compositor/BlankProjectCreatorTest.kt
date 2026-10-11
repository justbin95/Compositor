package com.wonderassembly.compositor

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BlankProjectCreatorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun creator(bytes: Long = 64L * 1024 * 1024) = BlankProjectCreator(context) {
        AndroidRasterBudget(bytes, bytes, bytes, 2048)
    }

    @Test fun transparent2048ProjectKeepsDimensionsResolutionAndCanSaveAndReopen() {
        val project = creator().create(2048, 2048, 144.0) { false }
        assertEquals(2048, project.width)
        assertEquals(144.0, project.resolution, 0.0)
        assertEquals(project.layers.single().id, project.activeLayerId)
        val imageFile = File(project.packageAssets.single().uri.path!!)
        val image = ImageIO.read(imageFile)
        for (y in 0 until image.height) for (x in 0 until image.width) assertEquals(0, image.getRGB(x, y))
        val archive = File.createTempFile("blank-project-", ".zip", context.cacheDir)
        archive.outputStream().use { CompProjectArchiveWriter(context.contentResolver).write(it, project, emptyMap()) }
        ZipFile(archive).use { zip -> assertNull(zip.getEntry("Untitled.comp/checkerboard.png")) }
        val reopened = CompProjectArchiveImporter(context, CompProjectReader(context)).read(Uri.fromFile(archive)) { false }
        assertArrayEquals(project.originalManifestBytes, reopened.originalManifestBytes)
        assertArrayEquals(imageFile.readBytes(), File(reopened.packageAssets.single().uri.path!!).readBytes())
        reopened.archiveCacheDirectory!!.deleteRecursively()
        project.archiveCacheDirectory!!.deleteRecursively()
        archive.delete()
    }

    @Test fun invalidDimensionsBudgetAndCancellationLeaveNoCandidate() {
        val before = context.cacheDir.listFiles()!!.filter { it.name.startsWith("compositor-import-") }.toSet()
        for (dimensions in listOf(0 to 10, -1 to 10, 30001 to 1, 30000 to 30000)) {
            try { creator().create(dimensions.first, dimensions.second, 72.0) { false }; fail() }
            catch (_: IllegalArgumentException) { }
        }
        try { creator(1024).create(2048, 2048, 72.0) { false }; fail() }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("memory budget")) }
        var rows = 0
        try { creator().create(320, 240, 72.0) { ++rows > 10 }; fail() }
        catch (_: CancellationException) { }
        assertEquals(before, context.cacheDir.listFiles()!!.filter { it.name.startsWith("compositor-import-") }.toSet())
    }
}
