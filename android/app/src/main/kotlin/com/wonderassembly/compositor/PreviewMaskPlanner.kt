package com.wonderassembly.compositor

/** Resolves the raster masks which multiply the coverage of one visible pixel layer. */
internal object PreviewMaskPlanner {
    data class Mask(val file: String, val transform: CompProjectReader.LayerTransform)

    fun forLayer(
        layer: CompProjectReader.Layer,
        layersById: Map<String, CompProjectReader.Layer>
    ): List<Mask> {
        val ancestors = ArrayList<CompProjectReader.Layer>()
        var parentId = layer.parentId
        while (parentId != null && ancestors.size < MAX_GROUP_DEPTH) {
            val parent = layersById[parentId] ?: break
            if (parent.group) ancestors += parent
            parentId = parent.parentId
        }

        val result = ArrayList<Mask>(ancestors.size + 1)
        for (group in ancestors.asReversed()) {
            group.maskFile?.takeIf { group.maskEnabled }?.let { file ->
                result += Mask(file, group.maskTransform ?: group.transform)
            }
        }
        layer.maskFile?.takeIf { layer.maskEnabled }?.let { file ->
            result += Mask(file, layer.maskTransform ?: layer.transform)
        }
        return result
    }

    private const val MAX_GROUP_DEPTH = 64
}
