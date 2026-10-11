package com.wonderassembly.compositor.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PngInterruptionDiagnosticTest {
    @Test fun interruptedRowEncodingLeavesIncompletePngWithoutIend() {
        val output = ByteArrayOutputStream()
        try {
            ReferencePngWriter.writeRgba8PremultipliedRows(output, 1800, 1800) { y, _ ->
                if (y == 1138) Thread.currentThread().interrupt()
                check(!Thread.currentThread().isInterrupted) { "PNG export was cancelled." }
            }
            fail("Expected the same interruption guard used by the layer encoder")
        } catch (error: IllegalStateException) {
            assertEquals("PNG export was cancelled.", error.message)
        } finally { Thread.interrupted() }
        val bytes = output.toByteArray()
        assertTrue(bytes.size > 8)
        assertFalse(bytes.takeLast(8).toByteArray().contentEquals(byteArrayOf(73, 69, 78, 68, -82, 66, 96, -126)))
    }
}
