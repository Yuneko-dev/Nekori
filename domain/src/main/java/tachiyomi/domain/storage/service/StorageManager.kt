package tachiyomi.domain.storage.service

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.domain.storage.model.StorageStats

class StorageManager(
    private val context: Context,
    storagePreferences: StoragePreferences,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private var baseDir: UniFile? = getBaseDir(storagePreferences.baseStorageDirectory.get())

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        storagePreferences.baseStorageDirectory.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                baseDir = getBaseDir(uri)
                baseDir?.let { parent ->
                    getOrCreateDirectory(parent, AUTOMATIC_BACKUPS_PATH)
                    getOrCreateDirectory(parent, LOCAL_SOURCE_PATH)
                    getOrCreateDirectory(parent, LOCAL_NOVEL_SOURCE_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                    getOrCreateDirectory(parent, LNREADER_PLUGINS_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                    getOrCreateDirectory(parent, FONTS_PATH)
                    getOrCreateDirectory(parent, TRANSLATIONS_PATH)
                    getOrCreateDirectory(parent, DOWNLOADS_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                }
                _changes.send(Unit)
            }
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        return UniFile.fromUri(context, uri.toUri())
            .takeIf { it?.exists() == true }
    }

    private fun getOrCreateDirectory(parent: UniFile?, name: String): UniFile? {
        parent ?: return null
        val existing = parent.findFile(name)
        if (existing?.isDirectory == true) return existing
        return parent.createDirectory(name)
    }

    fun getAutomaticBackupsDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, AUTOMATIC_BACKUPS_PATH)
    }

    fun getDownloadsDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, DOWNLOADS_PATH)
    }

    fun getLocalSourceDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, LOCAL_SOURCE_PATH)
    }

    fun getLocalNovelSourceDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, LOCAL_NOVEL_SOURCE_PATH)
    }

    fun getLNReaderPluginsDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, LNREADER_PLUGINS_PATH)
    }

    fun getFontsDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, FONTS_PATH)
    }

    fun getTranslationsDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, TRANSLATIONS_PATH)
    }

    fun getMassImportDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, MASS_IMPORT_PATH)
    }

    fun getQuotesDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, QUOTES_PATH)
    }

    fun getStats(): StorageStats? {
        val root = baseDir?.takeIf { it.exists() } ?: return null
        val sizes = root.uri
            .takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
            ?.let(::getSafDirectorySizes)
            ?: root.listFiles()?.mapNotNull { file -> file.name?.let { it to file.sizeInBytes() } }?.toMap()
            ?: return null
        fun sizeOf(vararg paths: String) = paths.sumOf { sizes[it] ?: 0L }

        return StorageStats(
            path = root.displayablePath,
            downloadedChaptersBytes = sizeOf(DOWNLOADS_PATH),
            localNovelsBytes = sizeOf(LOCAL_NOVEL_SOURCE_PATH),
            translationsBytes = sizeOf(TRANSLATIONS_PATH),
            pluginsAndFontsBytes = sizeOf(LNREADER_PLUGINS_PATH, FONTS_PATH),
            backupsAndOtherBytes = sizeOf(
                AUTOMATIC_BACKUPS_PATH,
                LOCAL_SOURCE_PATH,
                MASS_IMPORT_PATH,
                QUOTES_PATH,
            ),
            availableBytes = DiskUtil.getAvailableStorageSpace(root).takeIf { it >= 0L },
        )
    }

    private fun getSafDirectorySizes(treeUri: Uri): Map<String, Long>? {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: runCatching { DocumentsContract.getDocumentId(treeUri) }.getOrNull()
            ?: return null
        val sizes = mutableMapOf<String, Long>()
        val success = queryChildren(treeUri, rootId) { id, name, mimeType, size ->
            if (name != null && name in MANAGED_PATHS) {
                sizes[name] = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    getTreeSize(treeUri, id) ?: return@queryChildren false
                } else {
                    size ?: return@queryChildren false
                }
            }
            true
        }
        return sizes.takeIf { success }
    }

    private fun getTreeSize(treeUri: Uri, documentId: String): Long? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            runCatching { DocumentsContract.getDocumentMetadata(context.contentResolver, documentUri) }
                .getOrNull()
                ?.takeIf { it.containsKey(DocumentsContract.METADATA_TREE_SIZE) }
                ?.let { return it.getLong(DocumentsContract.METADATA_TREE_SIZE) }
        }

        val pending = ArrayDeque<String>().apply { add(documentId) }
        var total = 0L
        while (pending.isNotEmpty()) {
            val success = queryChildren(treeUri, pending.removeFirst()) { id, _, mimeType, size ->
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    pending.add(id)
                } else {
                    total += size ?: return@queryChildren false
                }
                true
            }
            if (!success) return null
        }
        return total
    }

    private fun queryChildren(
        treeUri: Uri,
        parentId: String,
        consume: (id: String, name: String?, mimeType: String?, size: Long?) -> Boolean,
    ): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        return runCatching {
            context.contentResolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    if (
                        !consume(
                            cursor.getString(idColumn),
                            cursor.getString(nameColumn),
                            cursor.getString(mimeColumn),
                            if (cursor.isNull(sizeColumn)) null else cursor.getLong(sizeColumn),
                        )
                    ) {
                        return@use false
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
    }
}

internal fun UniFile.sizeInBytes(): Long {
    return if (isDirectory) {
        listFiles()?.sumOf { it.sizeInBytes() } ?: 0L
    } else {
        length().coerceAtLeast(0L)
    }
}

private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "local"
private const val LOCAL_NOVEL_SOURCE_PATH = "localnovels"
private const val LNREADER_PLUGINS_PATH = "lnreader_plugins"
private const val FONTS_PATH = "fonts"
private const val TRANSLATIONS_PATH = "translations"
private const val MASS_IMPORT_PATH = "mass_import"
private const val QUOTES_PATH = "quotes"

private val MANAGED_PATHS = setOf(
    AUTOMATIC_BACKUPS_PATH,
    DOWNLOADS_PATH,
    LOCAL_SOURCE_PATH,
    LOCAL_NOVEL_SOURCE_PATH,
    LNREADER_PLUGINS_PATH,
    FONTS_PATH,
    TRANSLATIONS_PATH,
    MASS_IMPORT_PATH,
    QUOTES_PATH,
)

private val DOCUMENT_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
)
