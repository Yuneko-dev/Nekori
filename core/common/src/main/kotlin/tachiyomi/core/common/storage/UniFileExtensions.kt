package tachiyomi.core.common.storage

import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile

val UniFile.extension: String?
    get() = name?.substringAfterLast('.')

val UniFile.nameWithoutExtension: String?
    get() = name?.substringBeforeLast('.')

val UniFile.displayablePath: String
    get() = try {
        filePath ?: uri.storagePath ?: uri.toString()
    } catch (_: Exception) {
        uri.toString()
    }

private val Uri.storagePath: String?
    get() {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull()
            ?: runCatching { DocumentsContract.getDocumentId(this) }.getOrNull()
            ?: return null
        val decodedId = Uri.decode(documentId)
        if (decodedId.startsWith("raw:")) return decodedId.removePrefix("raw:")
        if (authority != "com.android.externalstorage.documents") return null

        val (volume, path) = decodedId.split(":", limit = 2).takeIf { it.size == 2 } ?: return null
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (path.isEmpty()) root else "$root/$path"
    }
