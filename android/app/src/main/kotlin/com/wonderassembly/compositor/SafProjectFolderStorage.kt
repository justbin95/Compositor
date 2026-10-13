package com.wonderassembly.compositor

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream

internal class SafProjectFolderStorage(private val resolver: ContentResolver) : ProjectFolderPublisher.Storage<Uri> {
    private val createdDirectories = mutableSetOf<Uri>()
    override fun createDirectory(parent: Uri, name: String): Uri = create(parent, name,
        DocumentsContract.Document.MIME_TYPE_DIR).also { createdDirectories.add(it) }
    override fun createFile(parent: Uri, name: String): Uri = create(parent, name,
        if (name.endsWith(".png", true)) "image/png" else "application/json")

    private fun create(parent: Uri, name: String, mime: String): Uri {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val oldIds = resolver.query(childUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), android.os.Bundle(), null)
            ?.use { cursor -> buildSet<String> { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            ?: error("Could not inspect the destination folder.")
        val result = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: error("The selected provider could not create the project entry.")
        require(DocumentsContract.getDocumentId(result) !in oldIds) { "The provider returned an existing document; saving stopped." }
        return result
    }

    override fun write(file: Uri): OutputStream = resolver.openOutputStream(file, "w")
        ?: error("Could not write the new project entry.")
    override fun read(file: Uri): InputStream = resolver.openInputStream(file)
        ?: error("Could not read back the new project entry.")
    override fun deleteCreatedDirectory(directory: Uri): Boolean = directory in createdDirectories &&
        DocumentsContract.deleteDocument(resolver, directory)
}
