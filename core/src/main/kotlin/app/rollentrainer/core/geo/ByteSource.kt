package app.rollentrainer.core.geo

import java.io.File
import java.io.RandomAccessFile

/**
 * Random access to the bytes of a pack, so the same reader works on an Android asset (opened
 * uncompressed through an AssetFileDescriptor), a downloaded file and a test byte array.
 * Implementations must be safe to call from several threads.
 */
interface ByteSource : AutoCloseable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
    override fun close() {}
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && offset + length <= bytes.size) { "read $offset+$length beyond ${bytes.size}" }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

/**
 * A file or a region of one ([start], [length]): an uncompressed APK asset is such a region of
 * the APK, so Android can pass the AssetFileDescriptor's file, start offset and length.
 */
class FileByteSource(file: File, private val start: Long = 0, length: Long = -1) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = if (length >= 0) length else raf.length() - start

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && offset + length <= size) { "read $offset+$length beyond $size" }
        val out = ByteArray(length)
        synchronized(raf) {
            raf.seek(start + offset)
            raf.readFully(out)
        }
        return out
    }

    override fun close() = raf.close()
}
