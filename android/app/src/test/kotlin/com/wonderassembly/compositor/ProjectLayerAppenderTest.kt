package com.wonderassembly.compositor

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectLayerAppenderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun blank(w: Int, h: Int, background: BlankProjectCreator.Background) =
        BlankProjectCreator(context) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }
            .create(w, h, 144.0, background) { false }

    @Test fun centeredLayerPreservesDocumentFieldsAndAssetsThroughSaveReopen() {
        val base = blank(40, 30, BlankProjectCreator.Background.TRANSPARENT)
        val imported = blank(11, 8, BlankProjectCreator.Background.WHITE)
        val before = base.originalManifestBytes.copyOf()
        val candidate = ProjectLayerAppender(context).append(base, imported)
        assertEquals(base.documentId, candidate.documentId)
        assertEquals(base.width, candidate.width)
        assertEquals(base.resolution, candidate.resolution, 0.0)
        assertEquals(base.layers.first(), candidate.layers.first())
        assertEquals(base.imageUris.values.first(), candidate.imageUris.values.first())
        assertArrayEquals(before, base.originalManifestBytes)
        assertEquals(14.5, candidate.layers.last().transform.x, 0.0)
        assertEquals(11.0, candidate.layers.last().transform.y, 0.0)
        assertEquals(imported.activeLayerId, candidate.activeLayerId)
        val archive = File.createTempFile("append-", ".zip", context.cacheDir)
        archive.outputStream().use { CompProjectArchiveWriter(context.contentResolver).write(it, candidate, emptyMap()) }
        val reopened = CompProjectArchiveImporter(context, CompProjectReader(context)).read(Uri.fromFile(archive)) { false }
        candidate.releasePrivateAssets()
        assertFalse(imported.archiveCacheDirectory!!.exists())
        assertEquals(2, reopened.layers.size)
        val pixels = ImageIO.read(File(reopened.imageUris[reopened.layers.last().imageFile]!!.path!!))
        assertEquals(-1, pixels.getRGB(10, 7))
        reopened.releasePrivateAssets()
        archive.delete()
    }

    @Test fun collisionAndLimitRejectWithoutMutatingOrReleasingExistingProject() {
        val base = blank(8, 8, BlankProjectCreator.Background.BLACK)
        val imported = blank(4, 4, BlankProjectCreator.Background.TRANSPARENT)
        val before = base.originalManifestBytes.copyOf()
        try { ProjectLayerAppender(context).append(base, base); fail() }
        catch (_: IllegalArgumentException) { }
        try { ProjectLayerAppender(context).append(base.copy(layers = List(10) { base.layers.single() }), imported); fail() }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, base.originalManifestBytes)
        assertTrue(base.archiveCacheDirectory!!.exists())
        assertTrue(imported.archiveCacheDirectory!!.exists())
        base.releasePrivateAssets()
        imported.releasePrivateAssets()
    }
}
