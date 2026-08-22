package mihon.core.archive

import android.content.Context
import android.system.Os
import android.system.StructStat
import com.hippo.unifile.UniFile
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

class ZipWriter(val context: Context, file: UniFile, compressionLevel: Int = 0) : Closeable {
    private val pfd = file.openFileDescriptor(context, "wt")
    private val archive = Archive.writeNew()
    private val entry = ArchiveEntry.new2(archive)
    private val buffer = ByteBuffer.allocateDirect(8192)

    init {
        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            Archive.writeSetFormatZip(archive)
            if (compressionLevel > 0) {
                Archive.writeZipSetCompressionDeflate(archive)
                Archive.writeSetOptions(archive, "zip:compression-level=$compressionLevel".toByteArray())
            } else {
                Archive.writeZipSetCompressionStore(archive)
            }
            Archive.writeOpenFd(archive, pfd.fd)
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }

    /**
     * Add [file] under [entryName]. The optional callback is checked before the header and every
     * buffer write so callers can stop a large archive operation without depending on coroutines.
     */
    fun write(
        file: UniFile,
        entryName: String = file.name ?: error("Cannot archive an unnamed file"),
        isCancelled: (() -> Boolean)? = null,
    ) {
        require(entryName.isNotBlank()) { "Archive entry name must not be blank" }
        file.openFileDescriptor(context, "r").use {
            val fd = it.fileDescriptor
            ArchiveEntry.clear(entry)
            ArchiveEntry.setPathnameUtf8(entry, entryName)
            val stat = Os.fstat(fd)
            ArchiveEntry.setStat(entry, stat.toArchiveStat())
            checkCancelled(isCancelled)
            Archive.writeHeader(archive, entry)
            while (true) {
                checkCancelled(isCancelled)
                buffer.clear()
                Os.read(fd, buffer)
                if (buffer.position() == 0) break
                buffer.flip()
                Archive.writeData(archive, buffer)
            }
            Archive.writeFinishEntry(archive)
        }
    }

    private fun checkCancelled(isCancelled: (() -> Boolean)?) {
        if (isCancelled?.invoke() == true) {
            throw CancellationException("Archive write cancelled")
        }
    }

    override fun close() {
        ArchiveEntry.free(entry)
        Archive.writeFree(archive)
        pfd.close()
    }
}

private fun StructStat.toArchiveStat() = ArchiveEntry.StructStat().apply {
    stDev = st_dev
    stMode = st_mode
    stNlink = st_nlink.toInt()
    stUid = st_uid
    stGid = st_gid
    stRdev = st_rdev
    stSize = st_size
    stBlksize = st_blksize
    stBlocks = st_blocks
    stAtim = st_atime.toTimespec()
    stMtim = st_mtime.toTimespec()
    stCtim = st_ctime.toTimespec()
    stIno = st_ino
}

private fun Long.toTimespec() = ArchiveEntry.StructTimespec().also { it.tvSec = this }
