package com.wonderassembly.compositor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Imports .comp.zip into a private, bounded cache before handing it to the shared project reader. */
internal class CompProjectArchiveImporter(
    private val context: Context,
    private val reader: CompProjectReader
) {
    private data class CentralEntry(
        val name: String,
        val flags: Int,
        val method: Int,
        val isDirectory: Boolean
    )

    private data class ArchivePath(
        val wrapped: Boolean,
        val packageFolderName: String,
        val relativePath: String
    )

    fun read(archiveUri: Uri, isCancelled: () -> Boolean): CompProjectReader.Project {
        val cacheDirectory = File(context.cacheDir, "compositor-import-${UUID.randomUUID()}")
        require(cacheDirectory.mkdirs()) { "Could not prepare private storage for the project archive." }
        val archiveFile = File(cacheDirectory, "source.zip")
        var imported: CompProjectReader.Project? = null
        try {
            val input = context.contentResolver.openInputStream(archiveUri)
                ?: throw IllegalArgumentException("Could not open the selected project archive.")
            input.use { copyArchiveBounded(it, archiveFile, isCancelled) }
            checkCancelled(isCancelled)

            ZipFile(archiveFile).use { zip ->
                val centralEntries = readCentralDirectory(archiveFile)
                val zipEntries = zip.entries().asSequence().toList()
                require(zipEntries.size == centralEntries.size) { "The project archive directory is malformed." }
                require(zipEntries.isNotEmpty() && zipEntries.size <= MAX_ENTRIES) {
                    "The project archive contains an unsupported number of entries."
                }

                val packageRoot = File(cacheDirectory, "package")
                require(packageRoot.mkdir()) { "Could not prepare the project extraction folder." }
                val names = HashSet<String>()
                val seenTargets = HashSet<String>()
                var layout: Boolean? = null
                var wrappedFolderName: String? = null
                var sawManifest = false
                var sawImagesDirectory = false
                var totalExtracted = 0L

                for ((index, entry) in zipEntries.withIndex()) {
                    checkCancelled(isCancelled)
                    val central = centralEntries[index]
                    require(entry.name == central.name) { "The project archive directory is inconsistent." }
                    require(names.add(entry.name)) { "The project archive contains duplicate paths." }
                    require(central.flags and ENCRYPTED_FLAG == 0) { "Encrypted project archives are not supported." }
                    require(central.method == ZipEntry.STORED || central.method == ZipEntry.DEFLATED) {
                        "The project archive uses an unsupported compression method."
                    }
                    require(entry.method == central.method && entry.isDirectory == central.isDirectory) {
                        "The project archive directory is malformed."
                    }

                    val parsed = parseArchivePath(entry.name, central.isDirectory)
                    if (layout == null) {
                        layout = parsed.wrapped
                        if (parsed.wrapped) wrappedFolderName = parsed.packageFolderName
                    } else {
                        require(layout == parsed.wrapped) { "The project archive mixes multiple root layouts." }
                        if (parsed.wrapped) {
                            require(wrappedFolderName == parsed.packageFolderName) {
                                "The project archive contains more than one project folder."
                            }
                        }
                    }

                    val relative = parsed.relativePath
                    require(seenTargets.add(relative)) { "The project archive contains duplicate target paths." }
                    if (relative.isEmpty()) {
                        require(central.isDirectory) { "The project archive root entry is not a folder." }
                        verifyEmptyDirectoryEntry(zip, entry, isCancelled)
                        continue
                    }
                    when (relative) {
                        "manifest.json" -> {
                            require(!central.isDirectory) { "manifest.json is not a file." }
                            require(entry.size in 0..MAX_MANIFEST_BYTES) { "The project manifest exceeds the 4 MiB limit." }
                            sawManifest = true
                        }
                        "images" -> {
                            require(central.isDirectory) { "The project images entry is not a folder." }
                            verifyEmptyDirectoryEntry(zip, entry, isCancelled)
                            sawImagesDirectory = true
                            continue
                        }
                        else -> {
                            require(relative.startsWith("images/")) { "The project archive contains an unexpected path." }
                            require(!central.isDirectory) { "Nested folders inside images/ are not supported." }
                            val assetName = relative.removePrefix("images/")
                            require(assetName.isNotEmpty() && '/' !in assetName) {
                                "Nested folders inside images/ are not supported."
                            }
                            require(entry.size in 0..MAX_ASSET_BYTES) { "A project asset exceeds the 512 MiB limit." }
                            sawImagesDirectory = true
                        }
                    }
                    val declaredSize = entry.size
                    val maximum = if (relative == "manifest.json") MAX_MANIFEST_BYTES else MAX_ASSET_BYTES
                    require(declaredSize in 0..maximum) { "A project archive entry exceeds the supported size limit." }
                    val output = safeOutputFile(packageRoot, relative)
                    require(output.parentFile?.isDirectory == true || output.parentFile?.mkdirs() == true) {
                        "Could not create a private project asset file."
                    }
                    require(output.createNewFile()) { "The project archive contains duplicate target paths." }

                    var actualSize = 0L
                    val crc = CRC32()
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    zip.getInputStream(entry).use { assetInput ->
                        FileOutputStream(output).use { assetOutput ->
                            while (true) {
                                checkCancelled(isCancelled)
                                val count = assetInput.read(buffer)
                                if (count < 0) break
                                actualSize = Math.addExact(actualSize, count.toLong())
                                totalExtracted = Math.addExact(totalExtracted, count.toLong())
                                require(actualSize <= maximum && totalExtracted <= MAX_TOTAL_EXTRACTED_BYTES) {
                                    "The project archive expands beyond the supported size limit."
                                }
                                crc.update(buffer, 0, count)
                                assetOutput.write(buffer, 0, count)
                            }
                        }
                    }
                    require(actualSize == entry.size && crc.value == entry.crc) {
                        "The project archive contains a damaged or incomplete entry."
                    }
                }

                require(sawManifest) { "The project archive does not contain manifest.json." }
                require(sawImagesDirectory) { "The project archive does not contain an images folder." }
                val folderName = wrappedFolderName ?: directRootFolderName(archiveUri)
                imported = reader.readExtractedPackage(archiveUri, packageRoot, folderName, cacheDirectory)
            }
            checkCancelled(isCancelled)
            archiveFile.delete()
            return imported ?: throw IllegalArgumentException("The project archive could not be imported.")
        } catch (error: Exception) {
            cacheDirectory.deleteRecursively()
            throw error
        } catch (error: OutOfMemoryError) {
            cacheDirectory.deleteRecursively()
            throw error
        }
    }

    private fun parseArchivePath(name: String, isDirectory: Boolean): ArchivePath {
        require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && name.none(Character::isISOControl)) {
            "The project archive contains an unsafe path."
        }
        val normalized = if (isDirectory) name.removeSuffix("/") else name
        require(normalized.isNotEmpty() && !normalized.endsWith('/')) { "The project archive contains an invalid path." }
        val parts = normalized.split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." }) {
            "The project archive contains an unsafe path."
        }

        val isDirectRoot = parts.first() == "manifest.json" || parts.first() == "images"
        if (isDirectRoot) {
            require(parts.size <= 2) { "The project archive contains an unexpected path." }
            return ArchivePath(false, "", normalized)
        }

        val rootName = parts.first()
        require(rootName.endsWith(".comp", ignoreCase = true) && rootName.length > ".comp".length) {
            "The archive must contain one .comp project folder or a direct manifest.json/images layout."
        }
        require(parts.size in 1..3) { "The project archive contains an unexpected path." }
        val relative = parts.drop(1).joinToString("/")
        if (relative.isEmpty()) {
            require(isDirectory) { "The project archive root entry is not a folder." }
        } else {
            require(relative == "manifest.json" || relative == "images" || relative.startsWith("images/")) {
                "The project archive contains an unexpected path."
            }
        }
        return ArchivePath(true, rootName, relative)
    }

    private fun safeOutputFile(packageRoot: File, relative: String): File {
        val output = File(packageRoot, relative)
        val rootPath = packageRoot.canonicalPath + File.separator
        require(output.canonicalPath.startsWith(rootPath)) { "The project archive contains an unsafe path." }
        return output
    }

    private fun verifyEmptyDirectoryEntry(
        zip: ZipFile,
        entry: ZipEntry,
        isCancelled: () -> Boolean
    ) {
        require(entry.size == 0L) { "The project archive contains a malformed directory entry." }
        val crc = CRC32()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        zip.getInputStream(entry).use { input ->
            while (true) {
                checkCancelled(isCancelled)
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
                throw IllegalArgumentException("The project archive contains a malformed directory entry.")
            }
        }
        require(crc.value == entry.crc) { "The project archive contains a damaged directory entry." }
    }

    private fun copyArchiveBounded(input: InputStream, archive: File, isCancelled: () -> Boolean) {
        var copied = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        FileOutputStream(archive).use { output ->
            while (true) {
                checkCancelled(isCancelled)
                val count = input.read(buffer)
                if (count < 0) break
                copied = Math.addExact(copied, count.toLong())
                require(copied <= MAX_ARCHIVE_BYTES) { "The project archive exceeds the 1 GiB size limit." }
                output.write(buffer, 0, count)
            }
        }
    }

    private fun readCentralDirectory(file: File): List<CentralEntry> = RandomAccessFile(file, "r").use { input ->
        val eocdOffset = findEndOfCentralDirectory(input)
        input.seek(eocdOffset)
        input.skipBytes(4)
        val diskNumber = input.readUnsignedShortLe()
        val centralDisk = input.readUnsignedShortLe()
        val diskCount = input.readUnsignedShortLe()
        val totalCount = input.readUnsignedShortLe()
        val centralSize = input.readUnsignedIntLe()
        val centralOffset = input.readUnsignedIntLe()
        val commentLength = input.readUnsignedShortLe()
        require(diskNumber == 0 && centralDisk == 0 && diskCount == totalCount) {
            "Multi-part project archives are not supported."
        }
        require(totalCount != ZIP64_U16 && centralSize != ZIP64_U32 && centralOffset != ZIP64_U32) {
            "ZIP64 project archives are not supported yet."
        }
        require(eocdOffset + EOCD_FIXED_BYTES + commentLength == input.length()) {
            "The project archive has trailing or malformed data."
        }
        require(centralOffset + centralSize <= eocdOffset) { "The project archive directory is malformed." }
        input.seek(centralOffset)
        val result = ArrayList<CentralEntry>(totalCount)
        repeat(totalCount) {
            require(input.readUnsignedIntLe() == CENTRAL_DIRECTORY_SIGNATURE) {
                "The project archive directory is malformed."
            }
            val versionMadeBy = input.readUnsignedShortLe()
            input.skipBytes(2) // version needed
            val flags = input.readUnsignedShortLe()
            val method = input.readUnsignedShortLe()
            input.skipBytes(16) // timestamps, CRC and compressed/uncompressed sizes
            val nameLength = input.readUnsignedShortLe()
            val extraLength = input.readUnsignedShortLe()
            val entryCommentLength = input.readUnsignedShortLe()
            input.skipBytes(2) // disk number start
            input.skipBytes(2) // internal file attributes
            val externalAttributes = input.readUnsignedIntLe()
            input.skipBytes(4) // local header offset
            require(nameLength > 0) { "The project archive contains an empty path." }
            val nameBytes = ByteArray(nameLength)
            input.readFully(nameBytes)
            val name = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(nameBytes)).toString()
            } catch (error: Exception) {
                throw IllegalArgumentException("The project archive contains a path with invalid text encoding.", error)
            }
            val optionalFieldsEnd = input.filePointer + extraLength + entryCommentLength
            require(optionalFieldsEnd <= centralOffset + centralSize) { "The project archive directory is malformed." }
            input.seek(optionalFieldsEnd)
            val directoryByName = name.endsWith('/')
            val host = versionMadeBy ushr 8
            if (host == UNIX_HOST || host == MAC_OS_X_HOST) {
                val mode = (externalAttributes ushr 16).toInt() and UNIX_TYPE_MASK
                require(mode == 0 || mode == UNIX_REGULAR_FILE || mode == UNIX_DIRECTORY) {
                    "The project archive contains a symbolic link or special file."
                }
                if (mode == UNIX_DIRECTORY) {
                    require(directoryByName) { "The project archive contains a malformed directory entry." }
                } else if (mode == UNIX_REGULAR_FILE) {
                    require(!directoryByName) { "The project archive contains a malformed file entry." }
                }
            }
            result += CentralEntry(name, flags, method, directoryByName)
        }
        require(input.filePointer <= centralOffset + centralSize) { "The project archive directory is malformed." }
        result
    }

    private fun findEndOfCentralDirectory(input: RandomAccessFile): Long {
        val length = input.length()
        require(length >= EOCD_FIXED_BYTES) { "The selected file is not a valid project archive." }
        val tailLength = minOf(length, (EOCD_FIXED_BYTES + MAX_ZIP_COMMENT_BYTES).toLong())
        val tail = ByteArray(tailLength.toInt())
        input.seek(length - tailLength)
        input.readFully(tail)
        for (index in tail.size - EOCD_FIXED_BYTES downTo 0) {
            if (tail.readUnsignedIntLe(index) != EOCD_SIGNATURE) continue
            val commentLength = tail.readUnsignedShortLe(index + 20)
            if (index + EOCD_FIXED_BYTES + commentLength == tail.size) {
                return length - tailLength + index
            }
        }
        throw IllegalArgumentException("The selected file is not a valid project archive.")
    }

    private fun directRootFolderName(uri: Uri): String {
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty()
        val baseName = displayName.substringAfterLast('/').removeSuffixIgnoreCase(".zip")
            .map { if (it in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') || Character.isISOControl(it)) '_' else it }
            .joinToString("").trim().trimEnd('.').ifBlank { "Project" }
        return if (baseName.endsWith(".comp", ignoreCase = true)) baseName else "$baseName.comp"
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw java.util.concurrent.CancellationException("Project archive import was cancelled.")
    }

    private fun RandomAccessFile.readUnsignedShortLe(): Int {
        val low = readUnsignedByte()
        return low or (readUnsignedByte() shl 8)
    }

    private fun RandomAccessFile.readUnsignedIntLe(): Long {
        var result = 0L
        repeat(4) { result = result or (readUnsignedByte().toLong() shl (it * 8)) }
        return result
    }

    private fun ByteArray.readUnsignedShortLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readUnsignedIntLe(offset: Int): Long =
        (this[offset].toLong() and 0xff) or
            ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or
            ((this[offset + 3].toLong() and 0xff) shl 24)

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_ARCHIVE_BYTES = 1L * 1024L * 1024L * 1024L
        const val MAX_TOTAL_EXTRACTED_BYTES = 1L * 1024L * 1024L * 1024L
        const val MAX_MANIFEST_BYTES = 4L * 1024L * 1024L
        const val MAX_ASSET_BYTES = 512L * 1024L * 1024L
        const val MAX_ENTRIES = 20_003
        const val MAX_ZIP_COMMENT_BYTES = 65_535
        const val EOCD_FIXED_BYTES = 22
        const val ZIP64_U16 = 0xffff
        const val ZIP64_U32 = 0xffff_ffffL
        const val EOCD_SIGNATURE = 0x06054b50L
        const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
        const val ENCRYPTED_FLAG = 1
        const val UNIX_HOST = 3
        const val MAC_OS_X_HOST = 19
        const val UNIX_TYPE_MASK = 0xf000
        const val UNIX_REGULAR_FILE = 0x8000
        const val UNIX_DIRECTORY = 0x4000
    }
}
