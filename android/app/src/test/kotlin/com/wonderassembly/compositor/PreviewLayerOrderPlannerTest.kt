package com.wonderassembly.compositor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLayerOrderPlannerTest {
    @Test
    fun preservesBottomToTopOrderAndExpandsGroupsInPlace() {
        val nodes = listOf(
            node("bottom"),
            node("folder", group = true),
            node("inside-bottom", parentId = "folder"),
            node("nested-folder", parentId = "folder", group = true),
            node("inside-nested", parentId = "nested-folder"),
            node("inside-top", parentId = "folder"),
            node("top")
        )

        val order = PreviewLayerOrderPlanner.plan(nodes).map { it.node.id }

        assertEquals(
            listOf("bottom", "folder", "inside-bottom", "nested-folder", "inside-nested", "inside-top", "top"),
            order
        )
    }

    @Test
    fun inheritsGroupVisibilityAndMultipliesOpacityThroughNestedGroups() {
        val nodes = listOf(
            node("hidden-folder", group = true, visible = false, opacity = 0.5),
            node("hidden-child", parentId = "hidden-folder", opacity = 0.8),
            node("outer", group = true, opacity = 0.5),
            node("inner", parentId = "outer", group = true, opacity = 0.4),
            node("nested-child", parentId = "inner", opacity = 0.5)
        )

        val entries = PreviewLayerOrderPlanner.plan(nodes).associateBy { it.node.id }

        assertFalse(entries.getValue("hidden-child").effectivelyVisible)
        assertEquals(0.4, entries.getValue("hidden-child").effectiveOpacity, 0.000001)
        assertTrue(entries.getValue("nested-child").effectivelyVisible)
        assertEquals(0.1, entries.getValue("nested-child").effectiveOpacity, 0.000001)
    }

    private fun node(
        id: String,
        parentId: String? = null,
        visible: Boolean = true,
        group: Boolean = false,
        opacity: Double = 1.0
    ) = PreviewLayerOrderPlanner.Node(id, parentId, visible, group, opacity)
}
