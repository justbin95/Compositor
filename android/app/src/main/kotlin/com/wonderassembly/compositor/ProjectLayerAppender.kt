package com.wonderassembly.compositor

import android.content.Context
import org.json.JSONObject

/** Builds a candidate using existing read-only assets; ownership transfers only after validation. */
internal class ProjectLayerAppender(private val context: Context) {
    fun append(base: CompProjectReader.Project, imported: CompProjectReader.Project): CompProjectReader.Project {
        require(base.layers.size < 10) { "The current editing limit is 10 layers." }
        val layer = imported.layers.single()
        val file = requireNotNull(layer.imageFile)
        require(base.packageAssets.none { it.name == file } && base.layers.none { it.id == layer.id }) {
            "The imported layer conflicts with an existing asset."
        }
        val manifest = JSONObject(String(base.originalManifestBytes, Charsets.UTF_8))
        val record = JSONObject(imported.originalManifest.getJSONArray("layers").getJSONObject(0).toString())
        record.getJSONObject("transform").getJSONObject("origin")
            .put("x", (base.width - imported.width) / 2.0)
            .put("y", (base.height - imported.height) / 2.0)
        manifest.getJSONArray("layers").put(record)
        manifest.put("activeLayerID", layer.id)
        return CompProjectReader(context).readProject(base.sourceUri, base.packageFolderName,
            manifest.toString().toByteArray(Charsets.UTF_8), base.packageAssets + imported.packageAssets,
            base.archiveCacheDirectory).copy(additionalCacheDirectories = base.additionalCacheDirectories +
                listOfNotNull(imported.archiveCacheDirectory) + imported.additionalCacheDirectories)
    }
}
