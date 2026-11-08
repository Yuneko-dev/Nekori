package tachiyomi.core.common.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile

/** Avoid provider-added extensions while preserving the requested filename on SAF and raw storage. */
fun UniFile.createFileWithExactName(context: Context, name: String): UniFile? {
    val created = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        DocumentsContract.createDocument(context.contentResolver, uri, "application/octet-stream", name)
            ?.let { UniFile.fromUri(context, it) }
    } else {
        createFile(name)
    }
    return created?.takeIf { it.name == name } ?: run {
        created?.delete()
        null
    }
}

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
