package com.wonderassembly.compositor

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wonderassembly.compositor.core.PointD
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecoveryPipelineTest {
    @Test fun durableCopyContainsCapturedPixelsWhileNewerEditsContinue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val project = BlankProjectCreator(context).create(520, 16, 72.0) { false }
        val filename = project.layers.single().imageFile!!
        val session = LayerEditingSession(context, project.imageUris.getValue(filename), 520, 16, 8L shl 20)
        val directory = File(context.filesDir, "recovery-device-test-${UUID.randomUUID()}")
        try {
            val stroke = session.raster.beginBrushStroke(4.0, 0xff000000.toInt())
            stroke.addPoint(PointD(254.5, 8.5)); stroke.addPoint(PointD(258.5, 8.5))
            val patch = stroke.commit()!!
            val captured = session.freezeForRecovery(4L shl 20)
            patch.undo()
            val newer = session.raster.beginBrushStroke(4.0, 0xffff0000.toInt())
            newer.addPoint(PointD(4.5, 8.5)); newer.commit()
            val task = RecoveryTask(context, project, 1, "instrumentation", mapOf(filename to captured),
                RecoveryStore(directory, RecoveryTask::syncDirectory))
            val finished = CountDownLatch(1)
            task.start(); task.releaseWhenFinished { finished.countDown() }
            assertTrue(finished.await(30, TimeUnit.SECONDS))
            assertNull(task.result!!.failure)
            val copy = task.result!!.saved!!
            val reopened = CompProjectArchiveImporter(context, CompProjectReader(context)).read(Uri.fromFile(copy.file)) { false }
            try {
                assertArrayEquals(project.originalManifestBytes, reopened.originalManifestBytes)
                val bitmap = BitmapFactory.decodeFile(reopened.imageUris.getValue(filename).path!!)!!
                try {
                    assertEquals(0, bitmap.getPixel(4, 8))
                    assertEquals(0xff000000.toInt(), bitmap.getPixel(255, 8))
                    assertEquals(0xff000000.toInt(), bitmap.getPixel(257, 8))
                } finally { bitmap.recycle() }
            } finally { reopened.releasePrivateAssets() }
            assertTrue(session.hasCommittedChanges)
        } finally { session.close(); project.releasePrivateAssets(); directory.deleteRecursively() }
    }
}
