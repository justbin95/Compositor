package com.wonderassembly.compositor

import android.content.Context
import org.json.JSONObject

internal class ProjectLayerMetadataEditor(private val context: Context) {
    fun move(base: CompProjectReader.Project, layerId: String, direction: Int): CompProjectReader.Project {
        require(direction == -1 || direction == 1) { "Choose one adjacent layer position." }
        val layer = base.layers.single { it.id == layerId }
        val siblings = base.layers.filter { it.parentId == layer.parentId }
        val destination = siblings.indexOf(layer) + direction
        if (destination !in siblings.indices) return base
        val other = siblings[destination]
        val manifest = JSONObject(String(base.originalManifestBytes, Charsets.UTF_8))
        val records = manifest.getJSONArray("layers")
        val from = base.layers.indexOf(layer)
        val to = base.layers.indexOf(other)
        val record = records.getJSONObject(from)
        records.put(from, records.getJSONObject(to))
        records.put(to, record)
        return CompProjectReader(context).readProject(base.sourceUri, base.packageFolderName,
            manifest.toString().toByteArray(Charsets.UTF_8), base.packageAssets, base.archiveCacheDirectory)
            .copy(additionalCacheDirectories = base.additionalCacheDirectories)
    }

    fun update(base: CompProjectReader.Project, layerId: String, name: String? = null,
        visible: Boolean? = null, opacity: Double? = null): CompProjectReader.Project {
        val layer = base.layers.single { it.id == layerId }
        require(name == null || (name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= 16384)) {
            "The layer name is empty or too long."
        }
        require(opacity == null || (opacity.isFinite() && opacity in 0.0..1.0)) { "Opacity must be between 0 and 100%." }
        if ((name == null || name == layer.name) && (visible == null || visible == layer.visible) &&
            (opacity == null || opacity == layer.opacity)) return base
        val manifest = JSONObject(String(base.originalManifestBytes, Charsets.UTF_8))
        val records = manifest.getJSONArray("layers")
        val record = (0 until records.length()).map { records.getJSONObject(it) }.single { it.getString("id").equals(layerId, ignoreCase = true) }
        name?.let { record.put("name", it) }
        visible?.let { record.put("isVisible", it) }
        opacity?.let {
            record.put("opacity", it)
            if (it != 1.0) manifest.put("version", maxOf(base.formatVersion, if (layer.group) 8 else 3))
        }
        return CompProjectReader(context).readProject(base.sourceUri, base.packageFolderName,
            manifest.toString().toByteArray(Charsets.UTF_8), base.packageAssets, base.archiveCacheDirectory)
            .copy(additionalCacheDirectories = base.additionalCacheDirectories)
    }
}
