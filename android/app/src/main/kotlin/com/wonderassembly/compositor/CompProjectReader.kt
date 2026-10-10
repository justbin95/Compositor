package com.wonderassembly.compositor

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID

/**
 * Reads the metadata and verifies the referenced PNG headers in a Compositor directory package.
 * The original JSON is kept intact so fields this first Android milestone does not understand are
 * not discarded. This reader is deliberately read-only; saving is added only with safe package
 * replacement support for the selected document provider.
 */
class CompProjectReader(private val context: Context) {
    data class Layer(
        val id: String,
        val name: String,
        val visible: Boolean,
        val group: Boolean,
        val adjustment: Boolean,
        val parentId: String?,
        val maskSourceId: String?,
        val imageFile: String?,
        val maskFile: String?,
        val maskEnabled: Boolean,
        val transform: LayerTransform,
        val maskTransform: LayerTransform?,
        val opacity: Double,
        val blendMode: String,
        val hasEffects: Boolean
    )

    data class LayerTransform(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val rotation: Double,
        val flipX: Boolean,
        val flipY: Boolean,
        val sampling: String
    )

    data class PixelSize(val width: Int, val height: Int)

    data class Project(
        val formatVersion: Int,
        val documentId: String,
        val width: Int,
        val height: Int,
        val resolution: Double,
        val activeLayerId: String?,
        val layers: List<Layer>,
        val imageUris: Map<String, Uri>,
        val imageSizes: Map<String, PixelSize>,
        val maskUris: Map<String, Uri>,
        val maskSizes: Map<String, PixelSize>,
        /** All original fields, including optional fields not yet modeled by Android. */
        val originalManifest: JSONObject
    )

    private data class Entry(val id: String, val name: String, val mimeType: String, val size: Long?)
    private data class PngInfo(val width: Int, val height: Int, val bitDepth: Int, val colorType: Int)

    fun read(treeUri: Uri): Project {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val root = queryEntry(rootUri)
        require(root.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            "所选位置不是文件夹。请选择 .comp 项目文件夹。"
        }

        val rootEntries = listChildren(treeUri, rootId)
        val rootByName = uniqueEntries(rootEntries, "项目文件夹")
        val manifestEntry = rootByName["manifest.json"]
            ?: throw IllegalArgumentException("项目中找不到 manifest.json。")
        require(manifestEntry.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
            "项目清单 manifest.json 不是文件。"
        }
        requireWithinLimit(manifestEntry, MAX_MANIFEST_BYTES.toLong(), "项目清单")
        val manifestBytes = readLimited(documentUri(treeUri, manifestEntry.id), MAX_MANIFEST_BYTES)
        val manifestText = String(manifestBytes, Charsets.UTF_8)
        val manifest = try {
            JSONObject(manifestText)
        } catch (error: JSONException) {
            throw IllegalArgumentException("项目清单不是有效的 JSON。", error)
        }

        val version = manifest.requiredInt("version")
        require(manifest.requiredString("format") == FORMAT) { "这不是 Compositor 项目。" }
        require(version in MIN_VERSION..CURRENT_VERSION) {
            "此项目格式版本为 $version，当前支持 $MIN_VERSION 至 $CURRENT_VERSION。"
        }
        require(manifest.requiredString("colorSpace") == "sRGB") { "项目使用不支持的色彩空间。" }
        val width = manifest.requiredInt("width")
        val height = manifest.requiredInt("height")
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "画布尺寸超出支持范围。" }
        val documentId = canonicalUuid(manifest.requiredString("documentID"), "文档 ID")
        val resolution = manifest.optionalDouble("resolution", 72.0)
        require(resolution.isFinite() && resolution in 1.0..9600.0) { "项目分辨率无效。" }

        val layersJson = manifest.optJSONArray("layers")
            ?: throw IllegalArgumentException("项目缺少图层列表。")
        require(layersJson.length() <= MAX_LAYERS) { "项目图层数量超出支持范围。" }
        val layers = parseLayers(layersJson, version)
        validateHierarchy(layers)
        validateLiveMasks(layers, version)
        val ids = layers.mapTo(HashSet()) { it.id }
        val activeLayer = manifest.optNullableString("activeLayerID")?.let {
            canonicalUuid(it, "活动图层 ID").also { id ->
                require(ids.contains(id)) { "活动图层不存在。" }
            }
        }
        validateGuides(manifest, version)

        val imagesEntry = rootByName["images"]
            ?: throw IllegalArgumentException("项目中找不到 images 图层文件夹。")
        require(imagesEntry.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            "项目的 images 位置不是文件夹。"
        }
        val imageEntries = uniqueEntries(listChildren(treeUri, imagesEntry.id), "images 文件夹")
        var imagePixels = 0L
        var maskPixels = 0L
        val imageUris = LinkedHashMap<String, Uri>()
        val imageSizes = LinkedHashMap<String, PixelSize>()
        val maskUris = LinkedHashMap<String, Uri>()
        val maskSizes = LinkedHashMap<String, PixelSize>()
        for (layer in layers) {
            layer.imageFile?.let { filename ->
                val entry = imageEntries[filename] ?: throw IllegalArgumentException("图层“${layer.name}”的图片文件缺失。")
                require(entry.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) { "图层图片不是文件。" }
                requireWithinLimit(entry, MAX_ASSET_BYTES, "图层图片")
                val uri = documentUri(treeUri, entry.id)
                val png = readPngInfo(uri, "图层“${layer.name}”")
                require(png.bitDepth <= 8) { "图层“${layer.name}”的 PNG 位深超出支持范围。" }
                imagePixels += png.width.toLong() * png.height.toLong()
                require(imagePixels <= MAX_TOTAL_PIXELS) { "项目图层像素总量超出支持范围。" }
                imageUris[filename] = uri
                imageSizes[filename] = PixelSize(png.width, png.height)
            }
            layer.maskFile?.let { filename ->
                val entry = imageEntries[filename] ?: throw IllegalArgumentException("图层“${layer.name}”的蒙版文件缺失。")
                require(entry.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) { "蒙版不是文件。" }
                requireWithinLimit(entry, MAX_ASSET_BYTES, "图层蒙版")
                val png = readPngInfo(documentUri(treeUri, entry.id), "图层“${layer.name}”的蒙版")
                require(png.bitDepth <= 8 && png.colorType == 0) { "图层“${layer.name}”的蒙版不是 8 位灰度 PNG。" }
                maskPixels += png.width.toLong() * png.height.toLong()
                require(maskPixels <= MAX_TOTAL_PIXELS) { "项目蒙版像素总量超出支持范围。" }
                maskUris[filename] = documentUri(treeUri, entry.id)
                maskSizes[filename] = PixelSize(png.width, png.height)
            }
        }
        return Project(
            version, documentId, width, height, resolution, activeLayer, layers,
            imageUris, imageSizes, maskUris, maskSizes, manifest
        )
    }

    private fun parseLayers(records: JSONArray, version: Int): List<Layer> {
        val result = ArrayList<Layer>(records.length())
        val seen = HashSet<String>()
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index)
                ?: throw IllegalArgumentException("第 ${index + 1} 个图层信息损坏。")
            val id = canonicalUuid(record.requiredString("id"), "图层 ID")
            require(seen.add(id)) { "项目中有重复的图层 ID。" }
            val name = record.requiredString("name")
            require(name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= MAX_LAYER_NAME_BYTES) {
                "图层名称无效或过长。"
            }
            val visible = record.requiredBoolean("isVisible")
            val group = record.optionalBoolean("isGroup", false)
            val hasAdjustment = record.has("adjustment") && !record.isNull("adjustment")
            val parentId = record.optNullableString("parentID")?.let { canonicalUuid(it, "父组 ID") }
            val maskSourceId = record.optNullableString("maskSourceID")?.let { canonicalUuid(it, "剪贴蒙版来源 ID") }
            val imageFile = record.optNullableString("imageFile")
            val maskFile = record.optNullableString("maskFile")
            val maskEnabled = if (record.has("maskEnabled") && !record.isNull("maskEnabled")) {
                record.requiredBoolean("maskEnabled")
            } else {
                maskFile != null
            }
            val expectedImage = "$id.png"
            val expectedMask = "$id.mask.png"
            require(imageFile == null || imageFile == expectedImage) { "图层“$name”的图片文件名无效。" }
            require(maskFile == null || maskFile == expectedMask) { "图层“$name”的蒙版文件名无效。" }
            require(!record.has("maskEnabled") || record.isNull("maskEnabled") || maskFile != null) { "没有蒙版文件却设置了蒙版状态。" }
            if (record.has("maskEnabled") && !record.isNull("maskEnabled")) record.requiredBoolean("maskEnabled")
            val opacity = record.optionalDouble("opacity", 1.0)
            require(opacity.isFinite() && opacity in 0.0..1.0) { "图层“$name”的不透明度无效。" }
            val blendMode = record.optNullableString("blendMode") ?: "Normal"
            require(blendMode in BLEND_MODES) { "图层“$name”的混合模式不受支持。" }
            require(version >= 3 || (opacity == 1.0 && blendMode == "Normal")) { "旧格式项目包含不支持的图层外观字段。" }
            if (group) {
                require(imageFile == null) { "组图层不能包含像素图片。" }
                require(blendMode == "Normal" && (version >= 8 || opacity == 1.0)) { "组图层外观字段无效。" }
                require(version >= 2 || parentId == null) { "版本 1 项目不能包含组图层。" }
                require(maskFile == null || version >= 6) { "此项目版本不能包含组蒙版。" }
            } else {
                require(maskFile == null || version >= 4) { "此项目版本不能包含图层蒙版。" }
            }
            if (hasAdjustment) {
                require(version >= 7 && !group && imageFile == null) { "调整图层信息无效。" }
                val adjustment = record.optJSONObject("adjustment")
                    ?: throw IllegalArgumentException("图层“$name”的调整信息损坏。")
                val kind = adjustment.requiredString("kind")
                require(kind in ADJUSTMENT_KINDS) { "图层“$name”的调整类型不受支持。" }
                require(kind !in NEW_ADJUSTMENTS || version >= 9) { "此项目版本不能包含该调整类型。" }
            }
            if (record.has("text") && !record.isNull("text")) {
                require(imageFile != null && !group && !hasAdjustment) { "文字图层信息无效。" }
                val text = record.optJSONObject("text")
                    ?: throw IllegalArgumentException("图层“$name”的文字信息损坏。")
                require(version >= 10 || !text.has("colorRuns") || text.isNull("colorRuns")) { "此项目版本不能包含文字颜色段。" }
                require(version >= 11 || !text.has("fontRuns") || text.isNull("fontRuns")) { "此项目版本不能包含文字字体段。" }
            }
            if (version < 5) require(record.optNullableString("maskSourceID") == null) { "此项目版本不能包含剪贴蒙版链接。" }
            val transform = record.optJSONObject("transform")
                ?: throw IllegalArgumentException("图层“$name”的变换信息缺失。")
            val layerTransform = parseTransform(transform, "图层“$name”")
            require(!record.has("maskPlacement") || record.isNull("maskPlacement") || record.optJSONObject("maskPlacement") != null) {
                "图层“$name”的蒙版位置无效。"
            }
            val maskTransform = record.optJSONObject("maskPlacement")?.let {
                parseTransform(it, "图层“$name”的蒙版")
            }
            require(!record.has("maskPlacement") || record.isNull("maskPlacement") || maskFile != null) {
                "图层“$name”设置了蒙版位置，却没有蒙版文件。"
            }
            require(!record.has("maskLinked") || record.isNull("maskLinked") || maskFile != null) {
                "图层“$name”设置了蒙版链接状态，却没有蒙版文件。"
            }
            if (record.has("maskLinked") && !record.isNull("maskLinked")) record.requiredBoolean("maskLinked")
            result += Layer(
                id, name, visible, group, hasAdjustment, parentId, maskSourceId, imageFile, maskFile,
                maskEnabled, layerTransform, maskTransform, opacity, blendMode,
                record.optJSONObject("effects")?.length()?.let { it > 0 } == true
            )
        }
        return result
    }

    private fun validateHierarchy(layers: List<Layer>) {
        val byId = layers.associateBy { it.id }
        for (layer in layers) {
            val parentId = layer.parentId ?: continue
            val parent = byId[parentId] ?: throw IllegalArgumentException("图层“${layer.name}”引用了不存在的父组。")
            require(parent.group) { "图层“${layer.name}”的父级不是组图层。" }
            var current: Layer? = parent
            var depth = 0
            val seen = HashSet<String>()
            seen += layer.id
            while (current != null) {
                require(seen.add(current.id)) { "项目的组层级形成循环。" }
                depth++
                require(depth <= MAX_GROUP_DEPTH) { "项目的组嵌套层级过深。" }
                current = current.parentId?.let { byId[it] }
            }
        }
    }

    private fun validateLiveMasks(layers: List<Layer>, version: Int) {
        val byId = layers.associateBy { it.id }
        val sources = HashMap<String, String>()
        for (layer in layers) {
            val sourceId = layer.maskSourceId ?: continue
            require(version >= 5) { "此项目版本不能包含剪贴蒙版链接。" }
            val source = byId[sourceId] ?: throw IllegalArgumentException("图层“${layer.name}”引用了不存在的剪贴蒙版来源。")
            require(source.id != layer.id && !source.group && !source.adjustment && !layer.group) {
                "剪贴蒙版不能连接到组、调整图层或自身。"
            }
            sources[layer.id] = source.id
        }
        for (start in sources.keys) {
            val seen = HashSet<String>()
            var current: String? = start
            var depth = 0
            while (current != null) {
                depth++
                require(depth <= MAX_MASK_DEPTH) { "剪贴蒙版链接层级过深。" }
                require(seen.add(current)) { "项目的剪贴蒙版链接形成循环。" }
                current = sources[current]
            }
        }
    }

    private fun validateGuides(manifest: JSONObject, version: Int) {
        require(!manifest.has("guides") || manifest.isNull("guides") || manifest.optJSONArray("guides") != null) {
            "项目参考线数据格式无效。"
        }
        val guides = manifest.optJSONArray("guides") ?: JSONArray()
        require(version >= 8 || guides.length() == 0) { "此项目版本不能包含参考线。" }
        require(guides.length() <= MAX_GUIDES) { "项目参考线数量超出支持范围。" }
        val ids = HashSet<String>()
        for (index in 0 until guides.length()) {
            val guide = guides.optJSONObject(index) ?: throw IllegalArgumentException("参考线信息损坏。")
            val id = canonicalUuid(guide.requiredString("id"), "参考线 ID")
            require(ids.add(id)) { "项目中有重复的参考线 ID。" }
            require(guide.requiredString("axis") in setOf("horizontal", "vertical")) { "参考线方向无效。" }
            val position = guide.requiredFiniteDouble("position")
            require(absWithin(position, 1_000_000.0)) { "参考线位置超出支持范围。" }
        }
    }

    private fun parseTransform(value: JSONObject, label: String): LayerTransform {
        val origin = value.optJSONObject("origin")
            ?: throw IllegalArgumentException("${label}的变换位置无效。")
        val size = value.optJSONObject("size")
            ?: throw IllegalArgumentException("${label}的变换尺寸无效。")
        val x = origin.requiredFiniteDouble("x")
        val y = origin.requiredFiniteDouble("y")
        val width = size.requiredFiniteDouble("width")
        val height = size.requiredFiniteDouble("height")
        val rotation = value.optionalDouble("rotation", 0.0)
        val flipX = value.optionalBoolean("flipX", false)
        val flipY = value.optionalBoolean("flipY", false)
        require(width in 1.0..300_000.0 && height in 1.0..300_000.0 &&
            absWithin(x, 1_000_000.0) && absWithin(y, 1_000_000.0) && rotation.isFinite()) {
            "${label}的变换超出支持范围。"
        }
        val sampling = value.optNullableString("sampling") ?: "High quality"
        require(sampling in SAMPLING_MODES) { "${label}的采样方式无效。" }
        return LayerTransform(x, y, width, height, rotation, flipX, flipY, sampling)
    }

    private fun listChildren(treeUri: Uri, parentId: String): List<Entry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val result = ArrayList<Entry>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) result += cursor.toEntry()
        } ?: throw IllegalArgumentException("无法读取所选文件夹。")
        return result
    }

    private fun queryEntry(uri: Uri): Entry {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.toEntry()
        }
        throw IllegalArgumentException("无法读取所选文件夹。")
    }

    private fun Cursor.toEntry(): Entry {
        val sizeIndex = getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        return Entry(
            getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)),
            getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) ?: "",
            getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) ?: "",
            if (sizeIndex >= 0 && !isNull(sizeIndex)) getLong(sizeIndex) else null
        )
    }

    private fun uniqueEntries(entries: List<Entry>, location: String): Map<String, Entry> {
        val result = LinkedHashMap<String, Entry>()
        for (entry in entries) {
            require(result.put(entry.name, entry) == null) { "$location 中有重名文件，无法安全判断项目内容。" }
        }
        return result
    }

    private fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun requireWithinLimit(entry: Entry, maximum: Long, label: String) {
        require(entry.size == null || entry.size in 0..maximum) { "$label 大小超出支持范围。" }
    }

    private fun readLimited(uri: Uri, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input -> copyLimited(input, output, maximum.toLong()) }
            ?: throw IllegalArgumentException("无法打开项目清单。")
        return output.toByteArray()
    }

    private fun readPngInfo(uri: Uri, label: String): PngInfo {
        val bytes = ByteArray(29)
        context.contentResolver.openInputStream(uri)?.use { input -> readExactly(input, bytes) }
            ?: throw IllegalArgumentException("无法打开${label}的 PNG 文件。")
        require(PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } &&
            bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(0, 0, 0, 13)) &&
            bytes.copyOfRange(12, 16).contentEquals(byteArrayOf(73, 72, 68, 82))) {
            "${label}不是有效的 PNG 文件。"
        }
        val width = readInt32(bytes, 16)
        val height = readInt32(bytes, 20)
        val bitDepth = bytes[24].toInt() and 0xff
        val colorType = bytes[25].toInt() and 0xff
        val validBitDepth = when (colorType) {
            0 -> bitDepth in setOf(1, 2, 4, 8, 16)
            2, 4, 6 -> bitDepth == 8 || bitDepth == 16
            3 -> bitDepth in setOf(1, 2, 4, 8)
            else -> false
        }
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "${label}的 PNG 尺寸超出支持范围。" }
        require(validBitDepth && bytes[26] == 0.toByte() && bytes[27] == 0.toByte() &&
            (bytes[28] == 0.toByte() || bytes[28] == 1.toByte())) {
            "${label}的 PNG 头部数据无效。"
        }
        return PngInfo(width, height, bitDepth, colorType)
    }

    private fun copyLimited(input: InputStream, output: ByteArrayOutputStream, maximum: Long) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maximum) { "项目清单大小超出 4 MiB 限制。" }
            output.write(buffer, 0, count)
        }
    }

    private fun readExactly(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw IllegalArgumentException("PNG 文件不完整。")
            offset += count
        }
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun canonicalUuid(value: String, label: String): String = try {
        require(UUID_PATTERN.matches(value))
        UUID.fromString(value).toString().uppercase(Locale.ROOT)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("${label}格式无效。", error)
    }

    private fun JSONObject.requiredString(key: String): String =
        (if (has(key) && !isNull(key)) opt(key) as? String else null)
            ?: throw IllegalArgumentException("项目字段“$key”缺失或无效。")

    private fun JSONObject.requiredInt(key: String): Int {
        val number = (opt(key) as? Number)?.toDouble() ?: Double.NaN
        require(number.isFinite() && number % 1.0 == 0.0 && number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            "项目字段“$key”缺失或无效。"
        }
        return number.toInt()
    }

    private fun JSONObject.requiredBoolean(key: String): Boolean {
        return (if (has(key) && !isNull(key)) opt(key) as? Boolean else null)
            ?: throw IllegalArgumentException("项目字段“$key”缺失或无效。")
    }

    private fun JSONObject.requiredFiniteDouble(key: String): Double {
        val raw = opt(key)
        val value = when (raw) {
            is Number -> raw.toDouble()
            else -> Double.NaN
        }
        require(value.isFinite()) { "项目字段“$key”缺失或无效。" }
        return value
    }

    private fun JSONObject.optionalDouble(key: String, default: Double): Double =
        if (!has(key) || isNull(key)) default else requiredFiniteDouble(key)

    private fun JSONObject.optionalBoolean(key: String, default: Boolean): Boolean {
        if (!has(key) || isNull(key)) return default
        return requiredBoolean(key)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else requiredString(key)

    companion object {
        private const val FORMAT = "com.compositor.project"
        private const val MIN_VERSION = 1
        private const val CURRENT_VERSION = 11
        private const val MAX_SIDE = 30_000
        private const val MAX_LAYERS = 10_000
        private const val MAX_GUIDES = 1_000
        private const val MAX_GROUP_DEPTH = 64
        private const val MAX_MASK_DEPTH = 256
        private const val MAX_LAYER_NAME_BYTES = 16_384
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        private const val MAX_ASSET_BYTES = 512L * 1024 * 1024
        private const val MAX_TOTAL_PIXELS = 800_000_000L
        private val BLEND_MODES = setOf(
            "Normal", "Darken", "Multiply", "Color Burn", "Linear Burn", "Lighten", "Screen", "Color Dodge",
            "Linear Dodge (Add)", "Overlay", "Soft Light", "Hard Light", "Vivid Light", "Linear Light", "Pin Light",
            "Hard Mix", "Difference", "Exclusion", "Subtract", "Divide", "Hue", "Saturation", "Color", "Luminosity"
        )
        private val ADJUSTMENT_KINDS = setOf(
            "Hue/Saturation", "Levels", "Curves", "Exposure", "Gradient Map", "Grain", "Invert",
            "Black & White", "Color Balance", "Gaussian Blur", "Motion Blur", "Add Noise"
        )
        private val NEW_ADJUSTMENTS = setOf("Gaussian Blur", "Motion Blur", "Add Noise")
        private val SAMPLING_MODES = setOf("Nearest", "Smooth", "High quality")
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

        private fun absWithin(value: Double, bound: Double) = value >= -bound && value <= bound
    }
}
