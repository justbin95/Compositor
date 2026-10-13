package com.wonderassembly.compositor

import kotlin.math.cos
import kotlin.math.sin

/** A fresh upright box and the visible pixel descendants it carries, matching macOS group transforms. */
internal data class GroupTransformPlan(
    val box: CompProjectReader.LayerTransform,
    val originals: Map<String, CompProjectReader.LayerTransform>
) {
    fun moved(target: CompProjectReader.LayerTransform): Map<String, CompProjectReader.LayerTransform> {
        NumericLayerTransform.validated(target)
        return originals.mapValues { (_, original) -> TransformPlacement.following(original, box, target) }
    }

    companion object {
        fun forGroup(project: CompProjectReader.Project, groupId: String): GroupTransformPlan {
            val byId = project.layers.associateBy { it.id }
            require(byId[groupId]?.group == true) { "Select a group to transform." }
            val visible = PreviewLayerOrderPlanner.plan(project.layers.map {
                PreviewLayerOrderPlanner.Node(it.id, it.parentId, it.visible, it.group, it.opacity)
            }).filter { it.effectivelyVisible }.map { it.node.id }.toSet()
            val members = project.layers.filter { layer ->
                if (layer.group || layer.adjustment || layer.imageFile == null || layer.id !in visible) return@filter false
                var parent = layer.parentId
                var inside = false
                for (depth in 0 until 64) {
                    val id = parent ?: break
                    if (id == groupId) { inside = true; break }
                    parent = byId[id]?.parentId
                }
                inside
            }
            require(members.isNotEmpty()) { "This group has no visible pixel layers to transform." }
            var left = Double.POSITIVE_INFINITY; var top = Double.POSITIVE_INFINITY
            var right = Double.NEGATIVE_INFINITY; var bottom = Double.NEGATIVE_INFINITY
            for (member in members) {
                val t = member.transform
                val radians = Math.toRadians(t.rotation)
                for (x in listOf(-t.width / 2, t.width / 2)) for (y in listOf(-t.height / 2, t.height / 2)) {
                    val px = t.x + t.width / 2 + x * cos(radians) - y * sin(radians)
                    val py = t.y + t.height / 2 + x * sin(radians) + y * cos(radians)
                    left = minOf(left, px); right = maxOf(right, px)
                    top = minOf(top, py); bottom = maxOf(bottom, py)
                }
            }
            val box = NumericLayerTransform.validated(CompProjectReader.LayerTransform(left, top,
                maxOf(1.0, right - left), maxOf(1.0, bottom - top), 0.0, false, false, "High quality"))
            return GroupTransformPlan(box, members.associate { it.id to it.transform })
        }
    }
}
