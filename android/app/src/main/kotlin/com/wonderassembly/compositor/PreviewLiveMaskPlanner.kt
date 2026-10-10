package com.wonderassembly.compositor

/** Resolves a live-mask source followed by the sources which mask that source. */
internal object PreviewLiveMaskPlanner {
    fun chain(
        sourceId: String,
        layersById: Map<String, CompProjectReader.Layer>
    ): List<CompProjectReader.Layer> {
        val result = ArrayList<CompProjectReader.Layer>()
        val seen = HashSet<String>()
        var currentId: String? = sourceId
        while (currentId != null) {
            require(result.size < MAX_MASK_DEPTH) { "剪贴蒙版链接层级过深。" }
            require(seen.add(currentId)) { "剪贴蒙版链接形成循环。" }
            val layer = layersById[currentId]
                ?: throw IllegalArgumentException("剪贴蒙版引用的图层不存在。")
            result += layer
            currentId = layer.maskSourceId
        }
        return result
    }

    private const val MAX_MASK_DEPTH = 256
}
