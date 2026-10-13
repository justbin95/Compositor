package com.wonderassembly.compositor

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID

/**
 * Reads the metadata and verifies the referenced PNG headers in a Compositor package.
 * The original JSON is kept intact so fields this first Android milestone does not understand are
 * not discarded. The source package remains read-only; separate archive copies are handled by the
 * archive writer.
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

    data class PackageAsset(
        val name: String,
        val uri: Uri,
        val size: Long?,
        val isDirectory: Boolean
    )

    data class Project(
        val sourceUri: Uri,
        val packageFolderName: String,
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
        val packageAssets: List<PackageAsset>,
        /** Original manifest bytes are copied verbatim so fields this milestone does not know stay intact. */
        val originalManifestBytes: ByteArray,
        /** All original fields, including optional fields not yet modeled by Android. */
        val originalManifest: JSONObject,
        /** Private extraction backing an imported ZIP, removed when this project is replaced. */
        val archiveCacheDirectory: File? = null,
        /** Private assets appended to a project whose original package remains read-only. */
        val additionalCacheDirectories: List<File> = emptyList()
    ) {
        fun releasePrivateAssets() {
            archiveCacheDirectory?.deleteRecursively()
            additionalCacheDirectories.forEach { it.deleteRecursively() }
        }
    }

    private data class Entry(val id: String, val name: String, val mimeType: String, val size: Long?)
    private data class PngInfo(val width: Int, val height: Int, val bitDepth: Int, val colorType: Int)

    fun read(treeUri: Uri): Project {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val root = queryEntry(rootUri)
        require(root.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            "The selected location is not a folder. Choose a .comp project folder."
        }

        val rootEntries = listChildren(treeUri, rootId)
        val rootByName = uniqueEntries(rootEntries, "project folder")
        val manifestEntry = rootByName["manifest.json"]
            ?: throw IllegalArgumentException("The project does not contain manifest.json.")
        require(manifestEntry.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
            "manifest.json is not a file."
        }
        requireWithinLimit(manifestEntry, MAX_MANIFEST_BYTES.toLong(), "project manifest")
        val manifestBytes = readLimited(documentUri(treeUri, manifestEntry.id), MAX_MANIFEST_BYTES)
        val imagesEntry = rootByName["images"]
            ?: throw IllegalArgumentException("The project does not contain an images folder.")
        require(imagesEntry.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            "The project images entry is not a folder."
        }
        val packageAssets = uniqueEntries(listChildren(treeUri, imagesEntry.id), "images folder").values.map { entry ->
            PackageAsset(
                entry.name,
                documentUri(treeUri, entry.id),
                entry.size,
                entry.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            )
        }
        return readProject(treeUri, root.name, manifestBytes, packageAssets)
    }

    /** Reads a safely extracted package produced by [CompProjectArchiveImporter]. */
    internal fun readExtractedPackage(
        sourceUri: Uri,
        packageRoot: File,
        packageFolderName: String,
        cacheDirectory: File
    ): Project {
        require(packageRoot.isDirectory) { "The archive does not contain a Compositor project folder." }
        val manifestFile = File(packageRoot, "manifest.json")
        require(manifestFile.isFile) { "The project does not contain manifest.json." }
        require(manifestFile.length() <= MAX_MANIFEST_BYTES) { "The project manifest exceeds the supported size limit." }
        val manifestBytes = FileInputStream(manifestFile).use { readLimited(it, MAX_MANIFEST_BYTES) }
        val imagesDirectory = File(packageRoot, "images")
        require(imagesDirectory.isDirectory) { "The project does not contain an images folder." }
        val packageAssets = (imagesDirectory.listFiles()
            ?: throw IllegalArgumentException("Could not read the project images folder.")).map { file ->
            PackageAsset(file.name, Uri.fromFile(file), file.length(), file.isDirectory)
        }
        require(packageAssets.map { it.name }.toSet().size == packageAssets.size) {
            "The images folder contains duplicate names, so project contents are ambiguous."
        }
        return readProject(sourceUri, packageFolderName, manifestBytes, packageAssets, cacheDirectory)
    }

    internal fun readProject(
        sourceUri: Uri,
        packageFolderName: String,
        manifestBytes: ByteArray,
        packageAssets: List<PackageAsset>,
        archiveCacheDirectory: File? = null
    ): Project {
        val manifestText = String(manifestBytes, Charsets.UTF_8)
        val manifest = try {
            JSONObject(manifestText)
        } catch (error: JSONException) {
            throw IllegalArgumentException("The project manifest is not valid JSON.", error)
        }

        val version = manifest.requiredInt("version")
        require(manifest.requiredString("format") == FORMAT) { "This is not a Compositor project." }
        require(version in MIN_VERSION..CURRENT_VERSION) {
            "Project format version $version is not supported. Supported versions are $MIN_VERSION through $CURRENT_VERSION."
        }
        require(manifest.requiredString("colorSpace") == "sRGB") { "This project uses an unsupported color space." }
        val width = manifest.requiredInt("width")
        val height = manifest.requiredInt("height")
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "Canvas dimensions exceed the supported range." }
        val documentId = canonicalUuid(manifest.requiredString("documentID"), "document ID")
        val resolution = manifest.optionalDouble("resolution", 72.0)
        require(resolution.isFinite() && resolution in 1.0..9600.0) { "Project resolution is invalid." }

        val layersJson = manifest.optJSONArray("layers")
            ?: throw IllegalArgumentException("The project has no layer list.")
        require(layersJson.length() <= MAX_LAYERS) { "The project contains too many layers." }
        val layers = parseLayers(layersJson, version)
        validateHierarchy(layers)
        validateLiveMasks(layers, version)
        val ids = layers.mapTo(HashSet()) { it.id }
        val activeLayer = manifest.optNullableString("activeLayerID")?.let {
            canonicalUuid(it, "active layer ID").also { id ->
                require(ids.contains(id)) { "The active layer does not exist." }
            }
        }
        validateGuides(manifest, version)

        val imageEntries = packageAssets.associateBy { it.name }
        require(imageEntries.size == packageAssets.size) {
            "The images folder contains duplicate names, so project contents are ambiguous."
        }
        val imageUris = LinkedHashMap<String, Uri>()
        val imageSizes = LinkedHashMap<String, PixelSize>()
        val maskUris = LinkedHashMap<String, Uri>()
        val maskSizes = LinkedHashMap<String, PixelSize>()
        for (layer in layers) {
            layer.imageFile?.let { filename ->
                val entry = imageEntries[filename] ?: throw IllegalArgumentException("Image file is missing for layer “${layer.name}”.")
                require(!entry.isDirectory) { "Layer image is not a file." }
                require(entry.size == null || entry.size in 0..MAX_ASSET_BYTES) { "layer image exceeds the supported size limit." }
                val uri = entry.uri
                val png = readPngInfo(uri, "Layer “${layer.name}”")
                require(png.bitDepth <= 8) { "The PNG bit depth for layer “${layer.name}” exceeds the supported range." }
                imageUris[filename] = uri
                imageSizes[filename] = PixelSize(png.width, png.height)
            }
            layer.maskFile?.let { filename ->
                val entry = imageEntries[filename] ?: throw IllegalArgumentException("Mask file is missing for layer “${layer.name}”.")
                require(!entry.isDirectory) { "Layer mask is not a file." }
                require(entry.size == null || entry.size in 0..MAX_ASSET_BYTES) { "layer mask exceeds the supported size limit." }
                val png = readPngInfo(entry.uri, "Layer “${layer.name}” mask")
                require(png.bitDepth <= 8 && png.colorType == 0) { "The mask for layer “${layer.name}” is not an 8-bit grayscale PNG." }
                maskUris[filename] = entry.uri
                maskSizes[filename] = PixelSize(png.width, png.height)
            }
        }
        return Project(
            sourceUri = sourceUri,
            packageFolderName = packageFolderName,
            formatVersion = version,
            documentId = documentId,
            width = width,
            height = height,
            resolution = resolution,
            activeLayerId = activeLayer,
            layers = layers,
            imageUris = imageUris,
            imageSizes = imageSizes,
            maskUris = maskUris,
            maskSizes = maskSizes,
            packageAssets = packageAssets,
            originalManifestBytes = manifestBytes.copyOf(),
            originalManifest = manifest,
            archiveCacheDirectory = archiveCacheDirectory
        )
    }

    private fun parseLayers(records: JSONArray, version: Int): List<Layer> {
        val result = ArrayList<Layer>(records.length())
        val seen = HashSet<String>()
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index)
                ?: throw IllegalArgumentException("Layer ${index + 1} is malformed.")
            val id = canonicalUuid(record.requiredString("id"), "layer ID")
            require(seen.add(id)) { "The project contains duplicate layer IDs." }
            val name = record.requiredString("name")
            require(name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= MAX_LAYER_NAME_BYTES) {
                "The layer name is empty or too long."
            }
            val visible = record.requiredBoolean("isVisible")
            val group = record.optionalBoolean("isGroup", false)
            val hasAdjustment = record.has("adjustment") && !record.isNull("adjustment")
            val parentId = record.optNullableString("parentID")?.let { canonicalUuid(it, "parent group ID") }
            val maskSourceId = record.optNullableString("maskSourceID")?.let { canonicalUuid(it, "clipping mask source ID") }
            val imageFile = record.optNullableString("imageFile")
            val maskFile = record.optNullableString("maskFile")
            val maskEnabled = if (record.has("maskEnabled") && !record.isNull("maskEnabled")) {
                record.requiredBoolean("maskEnabled")
            } else {
                maskFile != null
            }
            val expectedImage = "$id.png"
            val expectedMask = "$id.mask.png"
            require(imageFile == null || imageFile == expectedImage) { "The image filename for layer “$name” is invalid." }
            require(maskFile == null || maskFile == expectedMask) { "The mask filename for layer “$name” is invalid." }
            require(!record.has("maskEnabled") || record.isNull("maskEnabled") || maskFile != null) { "A mask state was set without a mask file." }
            if (record.has("maskEnabled") && !record.isNull("maskEnabled")) record.requiredBoolean("maskEnabled")
            val opacity = record.optionalDouble("opacity", 1.0)
            require(opacity.isFinite() && opacity in 0.0..1.0) { "Opacity for layer “$name” is invalid." }
            val blendMode = record.optNullableString("blendMode") ?: "Normal"
            require(blendMode in BLEND_MODES) { "The blend mode for layer “$name” is not supported." }
            require(version >= 3 || (opacity == 1.0 && blendMode == "Normal")) { "This older project format contains unsupported layer appearance settings." }
            if (group) {
                require(imageFile == null) { "Group layers cannot contain pixel images." }
                require(blendMode == "Normal" && (version >= 8 || opacity == 1.0)) { "Group layer appearance settings are invalid." }
                require(version >= 2 || parentId == null) { "Version 1 projects cannot contain group layers." }
                require(maskFile == null || version >= 6) { "This project version cannot contain group masks." }
            } else {
                require(maskFile == null || version >= 4) { "This project version cannot contain layer masks." }
            }
            if (hasAdjustment) {
                require(version >= 7 && !group && imageFile == null) { "Adjustment layer data is invalid." }
                val adjustment = record.optJSONObject("adjustment")
                    ?: throw IllegalArgumentException("Adjustment data for layer “$name” is malformed.")
                val kind = adjustment.requiredString("kind")
                require(kind in ADJUSTMENT_KINDS) { "The adjustment type for layer “$name” is not supported." }
                require(kind !in NEW_ADJUSTMENTS || version >= 9) { "This project version cannot contain this adjustment type." }
            }
            if (record.has("text") && !record.isNull("text")) {
                require(imageFile != null && !group && !hasAdjustment) { "Text layer data is invalid." }
                val text = record.optJSONObject("text")
                    ?: throw IllegalArgumentException("Text data for layer “$name” is malformed.")
                require(version >= 10 || !text.has("colorRuns") || text.isNull("colorRuns")) { "This project version cannot contain text color runs." }
                require(version >= 11 || !text.has("fontRuns") || text.isNull("fontRuns")) { "This project version cannot contain text font runs." }
            }
            if (version < 5) require(record.optNullableString("maskSourceID") == null) { "This project version cannot contain clipping mask links." }
            val transform = record.optJSONObject("transform")
                ?: throw IllegalArgumentException("Transform data is missing for layer “$name”.")
            val layerTransform = parseTransform(transform, "Layer “$name”")
            require(!record.has("maskPlacement") || record.isNull("maskPlacement") || record.optJSONObject("maskPlacement") != null) {
                "Mask placement for layer “$name” is invalid."
            }
            val maskTransform = record.optJSONObject("maskPlacement")?.let {
                parseTransform(it, "Mask for layer “$name”")
            }
            require(!record.has("maskPlacement") || record.isNull("maskPlacement") || maskFile != null) {
                "Layer “$name” has mask placement but no mask file."
            }
            require(!record.has("maskLinked") || record.isNull("maskLinked") || maskFile != null) {
                "Layer “$name” has a mask link state but no mask file."
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
            val parent = byId[parentId] ?: throw IllegalArgumentException("Layer “${layer.name}” refers to a parent group that does not exist.")
            require(parent.group) { "The parent of layer “${layer.name}” is not a group." }
            var current: Layer? = parent
            var depth = 0
            val seen = HashSet<String>()
            seen += layer.id
            while (current != null) {
                require(seen.add(current.id)) { "The project group hierarchy contains a cycle." }
                depth++
                require(depth <= MAX_GROUP_DEPTH) { "The project group nesting is too deep." }
                current = current.parentId?.let { byId[it] }
            }
        }
    }

    private fun validateLiveMasks(layers: List<Layer>, version: Int) {
        val byId = layers.associateBy { it.id }
        val sources = HashMap<String, String>()
        for (layer in layers) {
            val sourceId = layer.maskSourceId ?: continue
            require(version >= 5) { "This project version cannot contain clipping mask links." }
            val source = byId[sourceId] ?: throw IllegalArgumentException("Layer “${layer.name}” refers to a clipping mask source that does not exist.")
            require(source.id != layer.id && !source.group && !source.adjustment && !layer.group) {
                "A clipping mask cannot target a group, adjustment layer, or itself."
            }
            sources[layer.id] = source.id
        }
        for (start in sources.keys) {
            val seen = HashSet<String>()
            var current: String? = start
            var depth = 0
            while (current != null) {
                depth++
                require(depth <= MAX_MASK_DEPTH) { "The clipping mask chain is too deep." }
                require(seen.add(current)) { "The project clipping mask chain contains a cycle." }
                current = sources[current]
            }
        }
    }

    private fun validateGuides(manifest: JSONObject, version: Int) {
        require(!manifest.has("guides") || manifest.isNull("guides") || manifest.optJSONArray("guides") != null) {
            "Project guide data is invalid."
        }
        val guides = manifest.optJSONArray("guides") ?: JSONArray()
        require(version >= 8 || guides.length() == 0) { "This project version cannot contain guides." }
        require(guides.length() <= MAX_GUIDES) { "The project contains too many guides." }
        val ids = HashSet<String>()
        for (index in 0 until guides.length()) {
            val guide = guides.optJSONObject(index) ?: throw IllegalArgumentException("Guide data is malformed.")
            val id = canonicalUuid(guide.requiredString("id"), "guide ID")
            require(ids.add(id)) { "The project contains duplicate guide IDs." }
            require(guide.requiredString("axis") in setOf("horizontal", "vertical")) { "Guide orientation is invalid." }
            val position = guide.requiredFiniteDouble("position")
            require(absWithin(position, 1_000_000.0)) { "Guide position exceeds the supported range." }
        }
    }

    private fun parseTransform(value: JSONObject, label: String): LayerTransform {
        val origin = value.optJSONObject("origin")
            ?: throw IllegalArgumentException("The transform position for ${label} is invalid.")
        val size = value.optJSONObject("size")
            ?: throw IllegalArgumentException("The transform size for ${label} is invalid.")
        val x = origin.requiredFiniteDouble("x")
        val y = origin.requiredFiniteDouble("y")
        val width = size.requiredFiniteDouble("width")
        val height = size.requiredFiniteDouble("height")
        val rotation = value.optionalDouble("rotation", 0.0)
        val flipX = value.optionalBoolean("flipX", false)
        val flipY = value.optionalBoolean("flipY", false)
        require(width in 1.0..300_000.0 && height in 1.0..300_000.0 &&
            absWithin(x, 1_000_000.0) && absWithin(y, 1_000_000.0) && rotation.isFinite()) {
            "The transform for ${label} exceeds the supported range."
        }
        val sampling = value.optNullableString("sampling") ?: "High quality"
        require(sampling in SAMPLING_MODES) { "The sampling mode for ${label} is invalid." }
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
        context.contentResolver.query(childrenUri, projection, android.os.Bundle(), null)?.use { cursor ->
            while (cursor.moveToNext()) result += cursor.toEntry()
        } ?: throw IllegalArgumentException("Could not read the selected folder.")
        return result
    }

    private fun queryEntry(uri: Uri): Entry {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        context.contentResolver.query(uri, projection, android.os.Bundle(), null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.toEntry()
        }
        throw IllegalArgumentException("Could not read the selected folder.")
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
            require(result.put(entry.name, entry) == null) { "$location contains duplicate names, so project contents are ambiguous." }
        }
        return result
    }

    private fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun requireWithinLimit(entry: Entry, maximum: Long, label: String) {
        require(entry.size == null || entry.size in 0..maximum) { "$label exceeds the supported size limit." }
    }

    private fun readLimited(uri: Uri, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input -> copyLimited(input, output, maximum.toLong()) }
            ?: throw IllegalArgumentException("Could not open the project manifest.")
        return output.toByteArray()
    }

    private fun readLimited(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        copyLimited(input, output, maximum.toLong())
        return output.toByteArray()
    }

    private fun readPngInfo(uri: Uri, label: String): PngInfo {
        val bytes = ByteArray(29)
        context.contentResolver.openInputStream(uri)?.use { input -> readExactly(input, bytes) }
            ?: throw IllegalArgumentException("Could not open the PNG for ${label}.")
        require(PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } &&
            bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(0, 0, 0, 13)) &&
            bytes.copyOfRange(12, 16).contentEquals(byteArrayOf(73, 72, 68, 82))) {
            "${label} is not a valid PNG file."
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
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "PNG dimensions for ${label} exceed the supported range." }
        require(validBitDepth && bytes[26] == 0.toByte() && bytes[27] == 0.toByte() &&
            (bytes[28] == 0.toByte() || bytes[28] == 1.toByte())) {
            "The PNG header for ${label} is invalid."
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
            require(total <= maximum) { "The project manifest exceeds the 4 MiB limit." }
            output.write(buffer, 0, count)
        }
    }

    private fun readExactly(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw IllegalArgumentException("The PNG file is incomplete.")
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
        throw IllegalArgumentException("${label} is invalid.", error)
    }

    private fun JSONObject.requiredString(key: String): String =
        (if (has(key) && !isNull(key)) opt(key) as? String else null)
            ?: throw IllegalArgumentException("Project field “$key” is missing or invalid.")

    private fun JSONObject.requiredInt(key: String): Int {
        val number = (opt(key) as? Number)?.toDouble() ?: Double.NaN
        require(number.isFinite() && number % 1.0 == 0.0 && number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            "Project field “$key” is missing or invalid."
        }
        return number.toInt()
    }

    private fun JSONObject.requiredBoolean(key: String): Boolean {
        return (if (has(key) && !isNull(key)) opt(key) as? Boolean else null)
            ?: throw IllegalArgumentException("Project field “$key” is missing or invalid.")
    }

    private fun JSONObject.requiredFiniteDouble(key: String): Double {
        val raw = opt(key)
        val value = when (raw) {
            is Number -> raw.toDouble()
            else -> Double.NaN
        }
        require(value.isFinite()) { "Project field “$key” is missing or invalid." }
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
