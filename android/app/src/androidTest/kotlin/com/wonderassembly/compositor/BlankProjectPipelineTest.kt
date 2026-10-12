package com.wonderassembly.compositor

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wonderassembly.compositor.core.ReferencePngWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BlankProjectPipelineTest {
    @Test fun defaultCanvasStreamsThenPreviewsAndRoundtripsWithinMeasuredBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val budget = AndroidRasterBudget.capture(context)
        Log.i("CompositorBlankTest", "single=${budget.maxSingleBitmapBytes}, editable=${budget.maxEditableDocumentBytes}, previewSide=${budget.maxPreviewSide}, encoding=${ReferencePngWriter.rowEncodingWorkingBytes(2048)}")
        val project = BlankProjectCreator(context).create(2048, 2048, 72.0) { false }
        val archive = File.createTempFile("blank-device-", ".zip", context.cacheDir)
        try {
            val asset = File(project.packageAssets.single().uri.path!!)
            val options = BitmapFactory.Options().apply { inSampleSize = budget.sampleSizeFor(2048, 2048); inScaled = false }
            val preview = requireNotNull(BitmapFactory.decodeFile(asset.path, options))
            try {
                assertTrue(preview.allocationByteCount.toLong() <= budget.maxSingleBitmapBytes)
                assertEquals(0, preview.getPixel(preview.width / 2, preview.height / 2))
            } finally { preview.recycle() }
            asset.inputStream().use { input ->
                val decoder = requireNotNull(BitmapRegionDecoder.newInstance(input, false))
                try {
                    val tile = requireNotNull(decoder.decodeRegion(Rect(1792, 1792, 2048, 2048), BitmapFactory.Options()))
                    try { assertEquals(256, tile.width); assertEquals(0, tile.getPixel(255, 255)) }
                    finally { tile.recycle() }
                } finally { decoder.recycle() }
            }
            archive.outputStream().use { CompProjectArchiveWriter(context.contentResolver).write(it, project, emptyMap()) }
            val reopened = CompProjectArchiveImporter(context, CompProjectReader(context)).read(Uri.fromFile(archive)) { false }
            try {
                assertEquals(2048, reopened.width)
                assertEquals(2048, reopened.height)
                assertArrayEquals(project.originalManifestBytes, reopened.originalManifestBytes)
                assertArrayEquals(asset.readBytes(), File(reopened.packageAssets.single().uri.path!!).readBytes())
            } finally { reopened.releasePrivateAssets() }
        } finally { archive.delete(); project.releasePrivateAssets() }
    }
}
