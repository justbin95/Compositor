package com.wonderassembly.compositor

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

internal class ProjectFolderSave(private val context: Context,
    private val captureBudget: () -> AndroidRasterBudget = { AndroidRasterBudget.capture(context) }) {
    fun publish(archive: File, tree: Uri, expected: CompProjectReader.Project, cancelled: () -> Boolean): Uri {
        val resolver = context.contentResolver
        val reader = CompProjectReader(context)
        val checked = CompProjectArchiveImporter(context, reader).read(Uri.fromFile(archive), cancelled)
        try {
            require(checked.originalManifestBytes.contentEquals(expected.originalManifestBytes))
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            return ProjectFolderPublisher(SafProjectFolderStorage(resolver)).publish(parent,
                File(requireNotNull(checked.archiveCacheDirectory), "package"), { folder ->
                    val savedName = resolver.query(folder, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE), android.os.Bundle(), null)?.use { cursor ->
                        require(cursor.moveToFirst() && cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            "The saved project is not a folder."
                        }
                        cursor.getString(0)
                    } ?: error("Could not inspect the saved folder name.")
                    require(savedName.endsWith(".comp", true)) { "The provider changed the .comp project extension." }
                    val id = DocumentsContract.getDocumentId(folder)
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, id)
                    val assets = mutableListOf<CompProjectReader.PackageAsset>()
                    var images: Uri? = null
                    var manifest: Uri? = null
                    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME), android.os.Bundle(), null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0))
                            when (cursor.getString(1)) { "images" -> images = uri; "manifest.json" -> manifest = uri }
                        }
                    } ?: error("Could not inspect the saved project.")
                    val imagesUri = requireNotNull(images) { "The provider changed the images folder name." }
                    resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(folder,
                        DocumentsContract.getDocumentId(imagesUri)), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE), android.os.Bundle(), null)?.use { cursor ->
                        while (cursor.moveToNext()) assets.add(CompProjectReader.PackageAsset(cursor.getString(1),
                            DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0)),
                            if (cursor.isNull(3)) null else cursor.getLong(3),
                            cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                    } ?: error("Could not inspect the saved project images.")
                    require(assets.map { it.name }.toSet() == checked.packageAssets.map { it.name }.toSet() &&
                        assets.size == checked.packageAssets.size) { "The saved project assets do not match." }
                    val bytes = resolver.openInputStream(requireNotNull(manifest))!!.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() <= 4 * 1024 * 1024 - count) { "The saved manifest is too large." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    require(bytes.contentEquals(checked.originalManifestBytes)) { "The saved manifest does not match." }
                    val saved = reader.readProject(folder, savedName, bytes, assets)
                    val budget = captureBudget()
                    for ((name, uri) in saved.imageUris + saved.maskUris) {
                        if (cancelled()) throw java.util.concurrent.CancellationException("Folder saving was cancelled.")
                        val size = requireNotNull(saved.imageSizes[name] ?: saved.maskSizes[name])
                        val options = BitmapFactory.Options().apply { inSampleSize = budget.sampleSizeFor(size.width, size.height) }
                        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                            ?: error("Could not decode a saved project image.")
                        bitmap.recycle()
                    }
                }, cancelled, expected.packageFolderName)
        } finally { checked.releasePrivateAssets() }
    }
}
