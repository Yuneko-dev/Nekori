package mihon.core.archive

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile
import mihon.core.archive.ArchiveEntry as MihonArchiveEntry

internal class ArchiveInputStream private constructor(openArchive: (Long) -> Unit) : InputStream() {
    constructor(buffer: Long, size: Long) : this({ Archive.readOpenMemoryUnsafe(it, buffer, size) })

    /** Borrows the descriptor; its owner must keep it open for the lifetime of this stream. */
    constructor(fd: Int) : this({ Archive.readOpenFd(it, fd, 64 * 1024L) })
    private val lock = Any()

    @Volatile
    private var isClosed = false

    private val archive = Archive.readNew()

    init {
        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            openArchive(archive)
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }

    private val oneByteBuffer = ByteBuffer.allocateDirect(1)

    override fun read(): Int {
        read(oneByteBuffer)
        return if (oneByteBuffer.hasRemaining()) oneByteBuffer.get().toUByte().toInt() else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val buffer = ByteBuffer.wrap(b, off, len).slice()
        if (len == 0) return 0
        read(buffer)
        return if (buffer.hasRemaining()) buffer.remaining() else -1
    }

    private fun read(buffer: ByteBuffer) {
        buffer.clear()
        Archive.readData(archive, buffer)
        buffer.flip()
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
        }

        Archive.readFree(archive)
    }

    fun getNextEntry(): MihonArchiveEntry? {
        return Archive.readNextHeader(archive).takeUnless { it == 0L }?.let { entry ->
            val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.decodeToString() ?: return null
            val isFile = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG
            MihonArchiveEntry(name, isFile)
        }
    }
}
