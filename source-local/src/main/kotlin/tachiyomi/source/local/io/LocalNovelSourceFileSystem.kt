package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.domain.storage.service.StorageManager

class LocalNovelSourceFileSystem(
    private val storageManager: StorageManager,
) {

    fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalNovelSourceDirectory()
    }

    fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectory()?.listFiles().orEmpty().toList()
    }

    fun getNovelDirectory(name: String): UniFile? {
        return getBaseDirectory()
            ?.findFile(name)
            ?.takeIf { it.isDirectory }
    }

    fun getNovelEntry(name: String): UniFile? {
        val base = getBaseDirectory() ?: return null
        return base.findFile(name)
            ?: base.listFiles().orEmpty().firstOrNull {
                !it.isDirectory && it.nameWithoutExtension.orEmpty().equals(name, ignoreCase = true)
            }
    }

    fun deleteNovel(name: String): Boolean = getNovelEntry(name)?.delete() == true

    fun getFilesInNovelDirectory(name: String): List<UniFile> {
        return getNovelDirectory(name)?.listFiles().orEmpty().toList()
    }
}
