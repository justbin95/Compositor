package com.wonderassembly.compositor

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class GroupProjectFixture(val project: CompProjectReader.Project, val root: String,
    val nested: String, val first: String, val second: String, val hidden: String, val hiddenChild: String, val outside: String) {
    companion object {
        fun create(context: Context): GroupProjectFixture {
            val base = BlankProjectCreator(context) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }.create(20, 10, 72.0) { false }
            fun id() = UUID.randomUUID().toString().uppercase(java.util.Locale.ROOT)
            val root = id(); val nested = id()
            val second = id(); val hidden = id()
            val hiddenGroup = id(); val hiddenChild = id()
            val outside = id(); val first = base.layers.single().id
            val manifest = JSONObject(String(base.originalManifestBytes, Charsets.UTF_8)).put("version", 8)
            val template = manifest.getJSONArray("layers").getJSONObject(0)
            val assets = base.packageAssets.toMutableList()
            val source = File(base.imageUris.values.single().path!!)
            fun asset(name: String) {
                val file = File(source.parentFile, name)
                if (name.endsWith(".mask.png")) {
                    val gray = java.awt.image.BufferedImage(20, 10, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
                    for (y in 0 until 10) for (x in 0 until 20) gray.raster.setSample(x, y, 0, 255)
                    javax.imageio.ImageIO.write(gray, "png", file)
                } else source.copyTo(file)
                assets += CompProjectReader.PackageAsset(name, Uri.fromFile(file), file.length(), false)
            }
            fun record(id: String, parent: String?, x: Double, group: Boolean = false, visible: Boolean = true): JSONObject {
                val record = JSONObject(template.toString()).put("id", id).put("name", id).put("isVisible", visible)
                    .put("futureLayerField", "kept")
                if (parent == null) record.remove("parentID") else record.put("parentID", parent)
                record.getJSONObject("transform").getJSONObject("origin").put("x", x)
                record.getJSONObject("transform").put("futureTransformField", 42)
                if (group) { record.put("isGroup", true); record.remove("imageFile") }
                else { record.put("imageFile", "$id.png"); if (id != first) asset("$id.png") }
                return record
            }
            val folder = record(root, null, 0.0, group = true).put("maskFile", "$root.mask.png").put("maskLinked", true)
            asset("$root.mask.png")
            val a = record(first, root, 0.0).put("maskFile", "$first.mask.png").put("maskLinked", true)
            asset("$first.mask.png")
            val b = record(second, nested, 40.0).put("maskFile", "$second.mask.png").put("maskLinked", false)
            asset("$second.mask.png")
            manifest.put("layers", JSONArray().put(folder).put(a).put(record(nested, root, 0.0, group = true))
                .put(b).put(record(hidden, root, 5000.0, visible = false))
                .put(record(hiddenGroup, root, 0.0, group = true, visible = false)).put(record(hiddenChild, hiddenGroup, 8000.0))
                .put(record(outside, null, -100.0)))
            manifest.put("activeLayerID", root)
            val project = CompProjectReader(context).readProject(base.sourceUri, base.packageFolderName,
                manifest.toString().toByteArray(Charsets.UTF_8), assets, base.archiveCacheDirectory)
            return GroupProjectFixture(project, root, nested, first, second, hidden, hiddenChild, outside)
        }
    }
}
