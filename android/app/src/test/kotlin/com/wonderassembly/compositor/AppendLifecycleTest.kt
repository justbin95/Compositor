package com.wonderassembly.compositor

import android.net.Uri
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppendLifecycleTest {
    @Test fun failedAppendKeepsProjectAssetsSessionsAndDirtyState() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val base = BlankProjectCreator(activity) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }
            .create(16, 16, 72.0) { false }
        field("currentProject").set(activity, base)
        field("documentStructureChanged").setBoolean(activity, true)
        val sessions = field("layerEditingSessions").get(activity)
        val method = MainActivity::class.java.getDeclaredMethod("appendImageLayer", Uri::class.java).apply { isAccessible = true }
        method.invoke(activity, Uri.fromFile(File(activity.cacheDir, "does-not-exist.png")))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (field("projectOpenInProgress").getBoolean(activity) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.yield()
        }
        assertFalse(field("projectOpenInProgress").getBoolean(activity))
        assertSame(base, field("currentProject").get(activity))
        assertSame(sessions, field("layerEditingSessions").get(activity))
        assertTrue(field("documentStructureChanged").getBoolean(activity))
        assertTrue(base.archiveCacheDirectory!!.exists())
        controller.pause().stop().destroy()
        assertFalse(base.archiveCacheDirectory!!.exists())
    }
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
