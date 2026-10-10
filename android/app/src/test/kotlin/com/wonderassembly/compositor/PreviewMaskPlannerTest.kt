package com.wonderassembly.compositor

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewMaskPlannerTest {
    @Test
    fun appliesOuterAndInnerGroupMasksBeforeTheLayersOwnMask() {
        val outer = layer("outer", group = true, maskFile = "outer.mask.png")
        val inner = layer("inner", parentId = "outer", group = true, maskFile = "inner.mask.png")
        val child = layer("child", parentId = "inner", maskFile = "child.mask.png")
        val layers = listOf(outer, inner, child).associateBy { it.id }

        val masks = PreviewMaskPlanner.forLayer(child, layers)

        assertEquals(listOf("outer.mask.png", "inner.mask.png", "child.mask.png"), masks.map { it.file })
    }

    @Test
    fun ignoresDisabledMasksAndUsesAnUnlinkedMaskPlacement() {
        val group = layer("group", group = true, maskFile = "group.mask.png", maskEnabled = false)
        val own = layer(
            "child", parentId = "group", maskFile = "child.mask.png", maskTransform = transform(x = 17.0)
        )

        val masks = PreviewMaskPlanner.forLayer(own, listOf(group, own).associateBy { it.id })

        assertEquals(1, masks.size)
        assertEquals("child.mask.png", masks.single().file)
        assertEquals(17.0, masks.single().transform.x, 0.0)
    }

    @Test
    fun linkedMasksFollowTheirLayerAndGroupTransforms() {
        val group = layer("group", group = true, maskFile = "group.mask.png")
            .copy(transform = transform(x = 8.0))
        val child = layer("child", parentId = "group", maskFile = "child.mask.png")
            .copy(transform = transform(x = 23.0))

        val masks = PreviewMaskPlanner.forLayer(child, listOf(group, child).associateBy { it.id })

        assertEquals(listOf(8.0, 23.0), masks.map { it.transform.x })
    }

    private fun layer(
        id: String,
        parentId: String? = null,
        group: Boolean = false,
        maskFile: String? = null,
        maskEnabled: Boolean = maskFile != null,
        maskTransform: CompProjectReader.LayerTransform? = null
    ) = CompProjectReader.Layer(
        id = id,
        name = id,
        visible = true,
        group = group,
        adjustment = false,
        parentId = parentId,
        maskSourceId = null,
        imageFile = if (group) null else "$id.png",
        maskFile = maskFile,
        maskEnabled = maskEnabled,
        transform = transform(),
        maskTransform = maskTransform,
        opacity = 1.0,
        blendMode = "Normal",
        hasEffects = false
    )

    private fun transform(x: Double = 0.0) = CompProjectReader.LayerTransform(
        x = x,
        y = 0.0,
        width = 100.0,
        height = 100.0,
        rotation = 0.0,
        flipX = false,
        flipY = false,
        sampling = "High quality"
    )
}
