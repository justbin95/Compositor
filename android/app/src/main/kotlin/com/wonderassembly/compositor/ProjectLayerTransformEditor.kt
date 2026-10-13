package com.wonderassembly.compositor

import android.content.Context
import org.json.JSONObject
import kotlin.math.*

internal class ProjectLayerTransformEditor(private val context: Context) {
    fun update(base: CompProjectReader.Project, layerId: String,
        transform: CompProjectReader.LayerTransform): CompProjectReader.Project {
        NumericLayerTransform.validated(transform)
        return updateAll(base, mapOf(layerId to transform))
    }

    fun updateGroup(base: CompProjectReader.Project, groupId: String,
        target: CompProjectReader.LayerTransform): CompProjectReader.Project =
        updateAll(base, GroupTransformPlan.forGroup(base, groupId).moved(target))

    private fun updateAll(base: CompProjectReader.Project,
        transforms: Map<String, CompProjectReader.LayerTransform>): CompProjectReader.Project {
        val layers = base.layers.associateBy { it.id }
        // Validate every member before touching a candidate. A rejected group never partly moves.
        for ((id, transform) in transforms) {
            val layer = requireNotNull(layers[id])
            validateMember(layer, transform)
        }
        if (transforms.all { (id, transform) -> transform == layers.getValue(id).transform }) return base
        val manifest = JSONObject(String(base.originalManifestBytes, Charsets.UTF_8))
        val records = manifest.getJSONArray("layers")
        val byId = (0 until records.length()).map { records.getJSONObject(it) }
            .associateBy { it.getString("id").lowercase(java.util.Locale.ROOT) }
        for ((id, transform) in transforms) {
            val layer = layers.getValue(id)
            if (transform == layer.transform) continue
            updateRecord(base, layer, byId.getValue(id.lowercase(java.util.Locale.ROOT)), transform)
        }
        return CompProjectReader(context).readProject(base.sourceUri, base.packageFolderName,
            manifest.toString().toByteArray(Charsets.UTF_8), base.packageAssets, base.archiveCacheDirectory)
            .copy(additionalCacheDirectories = base.additionalCacheDirectories)
    }

    companion object {
        internal fun maskPlacement(layer: CompProjectReader.Layer, size: CompProjectReader.PixelSize,
            linked: Boolean, transform: CompProjectReader.LayerTransform): CompProjectReader.LayerTransform? {
            val placement = if (size.width == 1 && size.height == 1) null
                else if (linked) layer.maskTransform?.let { TransformPlacement.following(it, layer.transform, transform) }
                else layer.maskTransform ?: layer.transform
            return placement?.takeUnless { it.copy(sampling = transform.sampling) == transform }
        }

        internal fun validateMember(layer: CompProjectReader.Layer, transform: CompProjectReader.LayerTransform) {
            require(!layer.group && !layer.adjustment && layer.imageFile != null) { "Select a pixel layer to transform." }
            require(listOf(transform.x, transform.y, transform.width, transform.height, transform.rotation).all { it.isFinite() } &&
                abs(transform.x) <= 1_000_000 && abs(transform.y) <= 1_000_000 &&
                transform.width in 1.0..300_000.0 && transform.height in 1.0..300_000.0 &&
                transform.sampling in listOf("Nearest", "Smooth", "High quality")) { "The transform exceeds the supported range." }
        }
    }

    private fun updateRecord(base: CompProjectReader.Project, layer: CompProjectReader.Layer, record: JSONObject,
        transform: CompProjectReader.LayerTransform) {
        val previous = JSONObject(record.getJSONObject("transform").toString())
        writeTransform(record.getJSONObject("transform"), transform)
        if (layer.maskFile != null) {
            val size = requireNotNull(base.maskSizes[layer.maskFile])
            val placement = maskPlacement(layer, size, record.optBoolean("maskLinked", true), transform)
            if (placement == null) record.remove("maskPlacement")
            else {
                val value = record.optJSONObject("maskPlacement") ?: previous
                writeTransform(value, placement)
                record.put("maskPlacement", value)
            }
        }
    }

    private fun writeTransform(value: JSONObject, transform: CompProjectReader.LayerTransform) {
        value.getJSONObject("origin").put("x", transform.x).put("y", transform.y)
        value.getJSONObject("size").put("width", transform.width).put("height", transform.height)
        value.put("rotation", transform.rotation).put("flipX", transform.flipX).put("flipY", transform.flipY)
            .put("sampling", transform.sampling)
    }
}

/** Matches macOS LayerTransform.following/placing, including its deliberate removal of shear. */
internal object TransformPlacement {
    private data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val x: Double, val y: Double) {
        fun times(other: Affine) = Affine(a * other.a + c * other.b, b * other.a + d * other.b,
            a * other.c + c * other.d, b * other.c + d * other.d,
            a * other.x + c * other.y + x, b * other.x + d * other.y + y)
        fun inverse(): Affine {
            val determinant = a * d - b * c
            require(determinant.isFinite() && determinant != 0.0) { "The transform is not invertible." }
            return Affine(d / determinant, -b / determinant, -c / determinant, a / determinant,
                (c * y - d * x) / determinant, (b * x - a * y) / determinant)
        }
    }
    private fun matrix(value: CompProjectReader.LayerTransform): Affine {
        val radians = Math.toRadians(value.rotation)
        val w = value.width * if (value.flipX) -1 else 1
        val h = value.height * if (value.flipY) -1 else 1
        val a = cos(radians) * w; val b = sin(radians) * w
        val c = -sin(radians) * h; val d = cos(radians) * h
        return Affine(a, b, c, d, value.x + value.width / 2 - (a + c) / 2,
            value.y + value.height / 2 - (b + d) / 2)
    }
    fun following(value: CompProjectReader.LayerTransform, old: CompProjectReader.LayerTransform,
        new: CompProjectReader.LayerTransform): CompProjectReader.LayerTransform {
        if (old == new) return value
        if (old.width == new.width && old.height == new.height && old.rotation == new.rotation &&
            old.flipX == new.flipX && old.flipY == new.flipY) return value.copy(x = value.x + new.x - old.x, y = value.y + new.y - old.y)
        val map = matrix(new).times(matrix(old).inverse()).times(matrix(value))
        val sign = if (value.flipX) -1.0 else 1.0
        val angle = atan2(map.b * sign, map.a * sign)
        val along = -map.c * sin(angle) + map.d * cos(angle)
        val width = hypot(map.a, map.b); val height = abs(along)
        val degrees = Math.toDegrees(angle)
        val turns = (value.rotation - degrees) / 360
        val nearest = if (turns >= 0) floor(turns + 0.5) else ceil(turns - 0.5)
        return value.copy(x = map.x + (map.a + map.c) / 2 - width / 2,
            y = map.y + (map.b + map.d) / 2 - height / 2, width = width, height = height,
            rotation = degrees + nearest * 360, flipY = along < 0)
    }
}
