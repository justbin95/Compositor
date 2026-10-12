package com.wonderassembly.compositor

import android.os.Looper
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryTaskTest {
    @Test fun capturedRevisionSurvivesRecreationWithoutAcknowledgingNewerEdits() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val project = BlankProjectCreator(activity) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }
            .create(16, 16, 72.0) { false }
        val root = File(activity.filesDir, "recovery-task-${UUID.randomUUID()}")
        val entered = CountDownLatch(1); val resume = CountDownLatch(1); val finished = CountDownLatch(1)
        val store = RecoveryStore(root, {}, { if (it == RecoveryStore.Stage.CREATED) {
            entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
        } })
        val epoch = field("recoveryEpoch").get(activity) as String
        val task = RecoveryTask(activity.applicationContext, project, 5, epoch, emptyMap(), store)
        field("currentProject").set(activity, project)
        field("recoveryDocumentId").set(activity, project.documentId)
        field("recoveryRevision").setLong(activity, 6)
        field("recoveryTask").set(activity, task)
        task.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            assertTrue(store.copies().isEmpty())
            controller.recreate()
            assertSame(task, field("recoveryTask").get(controller.get()))
            task.releaseWhenFinished { finished.countDown() }
            resume.countDown(); assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertNull(task.result!!.failure)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(5L, field("recoveredRevision").getLong(controller.get()))
            assertEquals(6L, field("recoveryRevision").getLong(controller.get()))
            val status = field("recoveryStatusText").get(controller.get()) as TextView
            assertTrue(status.text.toString().contains("Newer edits"))
            field("recoveryEpoch").set(controller.get(), "another-session")
            field("recoveredRevision").setLong(controller.get(), -1)
            MainActivity::class.java.getDeclaredMethod("consumeRecoveryResult", RecoveryTask::class.java)
                .apply { isAccessible = true }.invoke(controller.get(), task)
            assertEquals(-1L, field("recoveredRevision").getLong(controller.get()))
        } finally {
            resume.countDown()
            field("recoveryDocumentId").set(controller.get(), null)
            controller.pause().stop().destroy()
            root.deleteRecursively()
        }
    }

    @Test fun replacingDocumentDefersSourceDeletionUntilSnapshotFinishes() {
        val context = RuntimeEnvironment.getApplication()
        val project = BlankProjectCreator(context) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }
            .create(16, 16, 72.0) { false }
        val root = File(context.filesDir, "recovery-pin-${UUID.randomUUID()}")
        val entered = CountDownLatch(1); val resume = CountDownLatch(1); val finished = CountDownLatch(1)
        val task = RecoveryTask(context, project, 1, "test", emptyMap(), RecoveryStore(root, {}, {
            if (it == RecoveryStore.Stage.CREATED) { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        }))
        task.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            RecoveryTask.releaseProjectWhenFinished(project)
            assertTrue(project.archiveCacheDirectory!!.exists())
            task.releaseWhenFinished { finished.countDown() }
            resume.countDown(); assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertNotNull(task.result!!.saved)
            assertFalse(project.archiveCacheDirectory!!.exists())
            val restored = CompProjectArchiveImporter(context, CompProjectReader(context))
                .read(android.net.Uri.fromFile(task.result!!.saved!!.file)) { false }
            try { assertArrayEquals(project.originalManifestBytes, restored.originalManifestBytes) }
            finally { restored.releasePrivateAssets() }
        } finally { resume.countDown(); root.deleteRecursively(); project.releasePrivateAssets() }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
