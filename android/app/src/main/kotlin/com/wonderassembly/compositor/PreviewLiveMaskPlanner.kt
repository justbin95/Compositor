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
            require(result.size < MAX_MASK_DEPTH) { "The clipping mask chain is too deep." }
            require(seen.add(currentId)) { "The clipping mask chain contains a cycle." }
            val layer = layersById[currentId]
                ?: throw IllegalArgumentException("A layer referenced by the clipping mask does not exist.")
            result += layer
            currentId = layer.maskSourceId
        }
        return result
    }

    private const val MAX_MASK_DEPTH = 256
}
