package com.wonderassembly.compositor

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectFolderSaveTest {
    @Test fun providerChangingPackageExtensionFailsAndCleansOnlyNewFolder() {
        withProvider { provider, tree ->
            provider.renameExtension = true
            val context = RuntimeEnvironment.getApplication()
            val source = BlankProjectCreator(context) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }
                .create(16, 16, 72.0) { false }
            val archive = File(context.cacheDir, "folder-${UUID.randomUUID()}.zip")
            try {
                archive.outputStream().use { CompProjectArchiveWriter(context.contentResolver).write(it, source, emptyMap()) }
                try { ProjectFolderSave(context).publish(archive, tree, source) { false }; fail() }
                catch (error: ProjectFolderPublisher.PublicationFailure) { assertFalse(error.cleanupFailed) }
                assertEquals(1, provider.root.listFiles()!!.size)
                assertEquals("User work", File(provider.root, "Old.comp/manifest.json").readText())
            } finally { source.releasePrivateAssets(); archive.delete() }
        }
    }
    @Test fun productionSafSaveCanReopenWithoutSourceAndPreservesOldVersion() {
        withProvider { provider, tree ->
            val context = RuntimeEnvironment.getApplication()
            val source = BlankProjectCreator(context) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }
                .create(16, 16, 72.0, BlankProjectCreator.Background.WHITE) { false }
            val archive = File(context.cacheDir, "folder-${UUID.randomUUID()}.zip")
            try {
                archive.outputStream().use { CompProjectArchiveWriter(context.contentResolver).write(it, source, emptyMap()) }
                val folder = ProjectFolderSave(context) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }.publish(archive, tree, source) { false }
                val folderTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, DocumentsContract.getDocumentId(folder))
                val saved = CompProjectReader(context).read(folderTree)
                assertArrayEquals(source.originalManifestBytes, saved.originalManifestBytes)
                val originalPixels = File(source.packageAssets.single().uri.path!!).readBytes()
                source.releasePrivateAssets()
                assertArrayEquals(originalPixels, context.contentResolver.openInputStream(saved.packageAssets.single().uri)!!.use { it.readBytes() })
                assertEquals("User work", File(provider.root, "Old.comp/manifest.json").readText())
            } finally { source.releasePrivateAssets(); archive.delete() }
        }
    }

    @Test fun providerReturningExistingFolderIsNeverWrittenOrDeleted() {
        withProvider { provider, tree ->
            provider.returnExisting = true
            val context = RuntimeEnvironment.getApplication()
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
            val storage = SafProjectFolderStorage(context.contentResolver)
            try { storage.createDirectory(parent, "New.comp"); fail() } catch (_: IllegalArgumentException) {}
            assertEquals("User work", File(provider.root, "Old.comp/manifest.json").readText())
            assertFalse(storage.deleteCreatedDirectory(DocumentsContract.buildDocumentUriUsingTree(tree, "root/Old.comp")))
            assertEquals(1, provider.root.listFiles()!!.size)
        }
    }

    private fun withProvider(block: (DiskProvider, Uri) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val provider = DiskProvider()
        provider.root = File(context.cacheDir, "provider-${UUID.randomUUID()}").apply { mkdirs() }
        File(provider.root, "Old.comp").mkdir()
        File(provider.root, "Old.comp/manifest.json").writeText("User work")
        provider.attachInfo(context, ProviderInfo().apply {
            authority = AUTHORITY; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        try { block(provider, DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")) }
        finally { provider.root.deleteRecursively() }
    }

    class DiskProvider : DocumentsProvider() {
        lateinit var root: File
        var returnExisting = false
        var renameExtension = false
        override fun onCreate() = true
        private fun file(id: String): File = if (id == "root") root else File(root, id.removePrefix("root/"))
        private fun id(file: File) = if (file == root) "root" else "root/" + file.relativeTo(root).invariantSeparatorsPath
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: emptyArray())
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = rows(listOf(file(documentId)), projection)
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
            rows(file(parentDocumentId).listFiles()!!.toList(), projection)
        private fun rows(files: List<File>, projection: Array<out String>?): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)
            return MatrixCursor(columns).apply {
                for (file in files) addRow(columns.map<String, Any?> { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id(file)
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    DocumentsContract.Document.COLUMN_SIZE -> file.length()
                    DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                    else -> null
                } }.toTypedArray())
            }
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            if (returnExisting) return "root/Old.comp"
            val child = File(file(parentDocumentId), if (renameExtension && displayName.endsWith(".comp")) "$displayName.changed" else displayName)
            check(if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile())
            return id(child)
        }
        override fun deleteDocument(documentId: String) { check(file(documentId).deleteRecursively()) }
        override fun isChildDocument(parentDocumentId: String, documentId: String) =
            documentId.startsWith("$parentDocumentId/")
    }

    companion object { private const val AUTHORITY = "compositor.test.documents" }
}
