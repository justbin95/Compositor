package com.wonderassembly.compositor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreviewLiveMaskPlannerTest {
    @Test
    fun followsUpstreamCoverageSourcesEvenWhenTheyAreHidden() {
        val upstream = layer("upstream", visible = false)
        val source = layer("source", maskSourceId = "upstream", visible = false)
        val clipped = layer("clipped", maskSourceId = "source")

        val chain = PreviewLiveMaskPlanner.chain("source", listOf(upstream, source, clipped).associateBy { it.id })

        assertEquals(listOf("source", "upstream"), chain.map { it.id })
        assertFalse(chain.first().visible)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCyclesAsAnExtraSafetyCheck() {
        val first = layer("first", maskSourceId = "second")
        val second = layer("second", maskSourceId = "first")

        PreviewLiveMaskPlanner.chain("first", listOf(first, second).associateBy { it.id })
    }

    private fun layer(id: String, maskSourceId: String? = null, visible: Boolean = true) = CompProjectReader.Layer(
        id = id,
        name = id,
        visible = visible,
        group = false,
        adjustment = false,
        parentId = null,
        maskSourceId = maskSourceId,
        imageFile = "$id.png",
        maskFile = null,
        maskEnabled = false,
        transform = CompProjectReader.LayerTransform(0.0, 0.0, 100.0, 100.0, 0.0, false, false, "High quality"),
        maskTransform = null,
        opacity = 1.0,
        blendMode = "Normal",
        hasEffects = false
    )
}
