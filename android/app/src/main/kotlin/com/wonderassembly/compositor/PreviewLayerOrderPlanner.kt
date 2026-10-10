package com.wonderassembly.compositor

/** Resolves the native bottom-to-top layer list into its depth-first render order. */
internal object PreviewLayerOrderPlanner {
    data class Node(
        val id: String,
        val parentId: String?,
        val visible: Boolean,
        val group: Boolean,
        val opacity: Double
    )

    data class Entry(
        val node: Node,
        val effectivelyVisible: Boolean,
        val effectiveOpacity: Double
    )

    fun plan(nodes: List<Node>): List<Entry> {
        val children = nodes.groupBy { it.parentId }
        val result = ArrayList<Entry>(nodes.size)
        val visiting = HashSet<String>()

        fun append(parentId: String?, parentVisible: Boolean, parentOpacity: Double) {
            for (node in children[parentId].orEmpty()) {
                // ProjectReader rejects cycles. This guard also keeps the planner safe for callers
                // that pass hand-built data in tests or future UI code.
                if (!visiting.add(node.id)) continue
                val visible = parentVisible && node.visible
                val opacity = (parentOpacity * node.opacity).coerceIn(0.0, 1.0)
                result += Entry(node, visible, opacity)
                if (node.group) append(node.id, visible, opacity)
                visiting.remove(node.id)
            }
        }

        append(parentId = null, parentVisible = true, parentOpacity = 1.0)
        return result
    }
}
