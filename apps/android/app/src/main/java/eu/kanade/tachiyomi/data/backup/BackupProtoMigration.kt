package eu.kanade.tachiyomi.data.backup

import java.io.ByteArrayOutputStream

/**
This is a temporary migration fix to keep compatibility with old backup files.
 - BackupManga stored isNovel as a varint at proto number 112 before it moved to 8000.
 - BackupExtensionStore stored isNovel as a varint at proto number 8 before it moved to 8000
 (proto number 8 now holds extensionListUrl, a length-delimited string from upstream).
Both legacy values are varints where the current field is either absent or length-delimited, so a
varint at the legacy field is unambiguously the old isNovel and is rewritten to 8000 before decoding.
 TODO: Remove this class after a few releases.
 */
internal object BackupProtoMigration {
    private const val BACKUP_MANGA_FIELD = 1
    private const val BACKUP_EXTENSION_STORE_FIELD = 106

    private const val MANGA_LEGACY_IS_NOVEL_FIELD = 112
    private const val EXTENSION_STORE_LEGACY_IS_NOVEL_FIELD = 8
    private const val IS_NOVEL_FIELD = 8000

    private const val WIRE_VARINT = 0
    private const val WIRE_I64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_I32 = 5

    private fun legacyFieldFor(parentField: Int): Int? = when (parentField) {
        BACKUP_MANGA_FIELD -> MANGA_LEGACY_IS_NOVEL_FIELD
        BACKUP_EXTENSION_STORE_FIELD -> EXTENSION_STORE_LEGACY_IS_NOVEL_FIELD
        else -> null
    }

    // For per-blob decode sites that bypass migrateLegacyIsNovel. Presence check first so a
    // current-schema blob (the common case) skips the deep copy.
    fun migrateManga(body: ByteArray): ByteArray = try {
        if (!subMessageHasLegacyVarint(body, 0, body.size, MANGA_LEGACY_IS_NOVEL_FIELD)) {
            body
        } else {
            migrateSubMessage(body, MANGA_LEGACY_IS_NOVEL_FIELD)
        }
    } catch (_: Exception) {
        body
    }

    fun migrateExtensionStore(body: ByteArray): ByteArray = try {
        if (!subMessageHasLegacyVarint(body, 0, body.size, EXTENSION_STORE_LEGACY_IS_NOVEL_FIELD)) {
            body
        } else {
            migrateSubMessage(body, EXTENSION_STORE_LEGACY_IS_NOVEL_FIELD)
        }
    } catch (_: Exception) {
        body
    }

    fun migrateLegacyIsNovel(input: ByteArray): ByteArray {
        return try {
            if (!hasLegacyIsNovel(input)) return input
            val reader = Reader(input)
            val out = ByteArrayOutputStream(input.size + 16)
            var changed = false
            while (reader.hasRemaining()) {
                val tag = reader.readVarint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x7).toInt()
                val legacyField = legacyFieldFor(field)
                if (legacyField != null && wire == WIRE_LEN) {
                    val body = reader.readLengthDelimited()
                    val migrated = migrateSubMessage(body, legacyField)
                    if (migrated !== body) changed = true
                    writeTag(out, field, WIRE_LEN)
                    writeVarint(out, migrated.size.toLong())
                    out.write(migrated)
                } else {
                    writeVarint(out, tag)
                    copyPayload(reader, out, wire)
                }
            }
            if (changed) out.toByteArray() else input
        } catch (_: Exception) {
            input
        }
    }

    private fun hasLegacyIsNovel(buf: ByteArray): Boolean {
        val reader = Reader(buf)
        while (reader.hasRemaining()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            val legacyField = legacyFieldFor(field)
            if (legacyField != null && wire == WIRE_LEN) {
                val len = reader.readVarint().toInt()
                val end = reader.pos + len
                if (subMessageHasLegacyVarint(buf, reader.pos, end, legacyField)) return true
                reader.pos = end
            } else {
                skipPayload(reader, wire)
            }
        }
        return false
    }

    private fun subMessageHasLegacyVarint(buf: ByteArray, start: Int, end: Int, legacyField: Int): Boolean {
        val reader = Reader(buf)
        reader.pos = start
        while (reader.pos < end) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            if (field == legacyField && wire == WIRE_VARINT) return true
            skipPayload(reader, wire)
        }
        return false
    }

    private fun skipPayload(reader: Reader, wire: Int) {
        when (wire) {
            WIRE_VARINT -> reader.readVarint()
            WIRE_I64 -> reader.skip(8)
            WIRE_LEN -> reader.skip(reader.readVarint().toInt())
            WIRE_I32 -> reader.skip(4)
            else -> throw IllegalStateException("Unsupported wire type $wire")
        }
    }

    private fun migrateSubMessage(body: ByteArray, legacyField: Int): ByteArray {
        val reader = Reader(body)
        val out = ByteArrayOutputStream(body.size + 2)
        var changed = false
        while (reader.hasRemaining()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            if (field == legacyField && wire == WIRE_VARINT) {
                writeTag(out, IS_NOVEL_FIELD, WIRE_VARINT)
                writeVarint(out, reader.readVarint())
                changed = true
            } else {
                writeVarint(out, tag)
                copyPayload(reader, out, wire)
            }
        }
        return if (changed) out.toByteArray() else body
    }

    private fun copyPayload(reader: Reader, out: ByteArrayOutputStream, wire: Int) {
        when (wire) {
            WIRE_VARINT -> writeVarint(out, reader.readVarint())
            WIRE_I64 -> out.write(reader.readBytes(8))
            WIRE_LEN -> {
                val body = reader.readLengthDelimited()
                writeVarint(out, body.size.toLong())
                out.write(body)
            }
            WIRE_I32 -> out.write(reader.readBytes(4))
            else -> throw IllegalStateException("Unsupported wire type $wire")
        }
    }

    private fun writeTag(out: ByteArrayOutputStream, field: Int, wire: Int) =
        writeVarint(out, (field.toLong() shl 3) or wire.toLong())

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) {
                out.write(b or 0x80)
            } else {
                out.write(b)
                return
            }
        }
    }

    private class Reader(private val buf: ByteArray) {
        var pos = 0

        fun hasRemaining() = pos < buf.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun readBytes(n: Int): ByteArray {
            val slice = buf.copyOfRange(pos, pos + n)
            pos += n
            return slice
        }

        fun readLengthDelimited(): ByteArray = readBytes(readVarint().toInt())

        fun skip(n: Int) {
            pos += n
        }
    }
}
