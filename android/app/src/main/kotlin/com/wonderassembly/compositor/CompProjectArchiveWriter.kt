package com.wonderassembly.compositor

import android.content.ContentResolver
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes an independent, Mac-shaped .comp.zip package without changing the selected source folder. */
internal class CompProjectArchiveWriter(private val resolver: ContentResolver) {
    fun suggestedFilename(project: CompProjectReader.Project): String =
        "${safePackageBase(project.packageFolderName)}-Edited.comp.zip"

    /** The caller retains ownership of [output]. */
    fun write(
        output: OutputStream,
        project: CompProjectReader.Project,
        editedAssets: Map<String, LayerEditingSession>,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        frozenAssets: Map<String, FrozenLayerExport> = emptyMap()
    ) {
        val root = "${safePackageBase(project.packageFolderName)}.comp/"
        require(project.originalManifestBytes.size <= MAX_MANIFEST_BYTES) {
            "The project manifest exceeds the supported size limit."
        }
        val assetsByName = project.packageAssets.associateBy { it.name }
        require(assetsByName.size == project.packageAssets.size) {
            "The project assets contain duplicate names."
        }
        for (assetName in editedAssets.keys + frozenAssets.keys) {
            require(assetsByName[assetName]?.isDirectory == false) {
                "An edited layer image is missing from the project package."
            }
        }

        ZipOutputStream(NonClosingOutputStream(output)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            writeEntry(zip, "$root", isCancelled) { zip.write(ByteArray(0)) }
            writeEntry(zip, "${root}manifest.json", isCancelled) {
                zip.write(project.originalManifestBytes)
            }
            writeEntry(zip, "${root}images/", isCancelled) { zip.write(ByteArray(0)) }

            for (asset in project.packageAssets) {
                checkNotCancelled(isCancelled)
                require(!asset.isDirectory) {
                    "The project contains a nested images folder that this archive writer cannot preserve safely."
                }
                require(isSafeAssetName(asset.name)) {
                    "The project contains an unsafe asset filename."
                }
                require(asset.size == null || asset.size in 0..MAX_ASSET_BYTES) {
                    "Project asset \"${asset.name}\" exceeds the supported size limit."
                }
                val entryName = "${root}images/${asset.name}"
                zip.putNextEntry(ZipEntry(entryName).apply { time = 0L })
                try {
                    val edited = editedAssets[asset.name]
                    val frozen = frozenAssets[asset.name]
                    if (edited != null || frozen != null) {
                        val limited = LimitedEntryOutputStream(zip, MAX_ASSET_BYTES)
                        if (frozen != null) frozen.writePng(limited) else requireNotNull(edited).writePng(limited, isCancelled)
                        limited.flush()
                    } else {
                        val input = resolver.openInputStream(asset.uri)
                            ?: throw IllegalArgumentException("Could not read project asset \"${asset.name}\".")
                        input.use { copyLimited(it, zip, asset.name, isCancelled) }
                    }
                } finally {
                    zip.closeEntry()
                }
            }
            checkNotCancelled(isCancelled)
        }
    }

    private fun writeEntry(
        zip: ZipOutputStream,
        name: String,
        isCancelled: () -> Boolean,
        write: () -> Unit
    ) {
        checkNotCancelled(isCancelled)
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        try {
            write()
        } finally {
            zip.closeEntry()
        }
    }

    private fun copyLimited(
        input: InputStream,
        output: OutputStream,
        assetName: String,
        isCancelled: () -> Boolean
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            checkNotCancelled(isCancelled)
            val count = input.read(buffer)
            if (count < 0) break
            total = Math.addExact(total, count.toLong())
            require(total <= MAX_ASSET_BYTES) {
                "Project asset \"$assetName\" exceeds the supported size limit."
            }
            output.write(buffer, 0, count)
        }
    }

    private fun checkNotCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw java.util.concurrent.CancellationException("Project archive writing was cancelled.")
    }

    private fun isSafeAssetName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name && name.none(Character::isISOControl)

    private fun safePackageBase(folderName: String): String {
        val withoutExtension = folderName.trim().removeSuffixIgnoreCase(".comp")
        val safe = withoutExtension.map { character ->
            if (character in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') || Character.isISOControl(character)) {
                '_'
            } else {
                character
            }
        }.joinToString("").trim().trimEnd('.')
        return safe.ifBlank { "Project" }
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    /** Lets ZipOutputStream finish its central directory without taking ownership of SAF's stream. */
    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
    }

    /** Bounds uncompressed edited PNG bytes while leaving the enclosing ZIP entry open. */
    private class LimitedEntryOutputStream(
        output: OutputStream,
        private val maximumBytes: Long
    ) : FilterOutputStream(output) {
        private var written = 0L

        override fun write(value: Int) {
            require(written < maximumBytes) { "The edited layer PNG exceeds the supported size limit." }
            out.write(value)
            written++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Invalid output range." }
            val next = Math.addExact(written, length.toLong())
            require(next <= maximumBytes) { "The edited layer PNG exceeds the supported size limit." }
            out.write(bytes, offset, length)
            written = next
        }

        override fun close() = flush()
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 4L * 1024L * 1024L
        const val MAX_ASSET_BYTES = 512L * 1024L * 1024L
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
