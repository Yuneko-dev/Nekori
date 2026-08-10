package tachiyomi.source.local.image

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.LocalNovelSourceFileSystem
import java.io.InputStream

private const val DEFAULT_COVER_NAME = "cover.jpg"

/**
 * Cover manager for local novel source.
 * Uses [LocalNovelSourceFileSystem] to resolve novel directories.
 */
class LocalNovelCoverManager(
    private val context: Context,
    private val fileSystem: LocalNovelSourceFileSystem,
) {

    fun find(mangaUrl: String): UniFile? {
        return fileSystem.getFilesInNovelDirectory(mangaUrl)
            .filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
            .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }
    }

    fun update(
        manga: SManga,
        inputStream: InputStream,
    ): UniFile? {
        val directory = fileSystem.getNovelDirectory(manga.url)
        if (directory == null) {
            inputStream.close()
            return null
        }

        val targetFile = find(manga.url) ?: directory.createFile(DEFAULT_COVER_NAME)!!

        inputStream.use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }

        DiskUtil.createNoMediaFile(directory, context)

        manga.thumbnail_url = targetFile.uri.toString()
        return targetFile
    }
}
