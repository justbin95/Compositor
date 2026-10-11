package com.wonderassembly.compositor

import android.os.Looper
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportLifecycleTest {
    @Test fun activityReplacementKeepsRunningTaskAndCompletionVisible() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val task = ExportTask(ExportTask.Kind.LAYER, "Test Layer")
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val destination = ByteArrayOutputStream()
        task.start(controller.get().cacheDir, {}, { destination }, { true }) { output, _, _ ->
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            output.write(byteArrayOf(1, 2, 3))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        field("exportTask").set(controller.get(), task)
        controller.recreate()
        assertSame(task, field("exportTask").get(controller.get()))
        assertFalse(task.cancellationRequested)
        assertTrue(field("exportFlowActive").getBoolean(controller.get()))
        task.releaseWhenFinished { finished.countDown() }
        resume.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(task.result!!.succeeded)
        assertFalse(field("exportFlowActive").getBoolean(controller.get()))
        val status = field("statusText").get(controller.get()) as TextView
        assertTrue(status.text.toString().contains("Test Layer"))
        controller.recreate()
        assertSame(task, field("exportTask").get(controller.get()))
        assertEquals(status.text.toString(), (field("statusText").get(controller.get()) as TextView).text.toString())
        assertArrayEquals(byteArrayOf(1, 2, 3), destination.toByteArray())
        controller.pause().stop().destroy()
    }
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
