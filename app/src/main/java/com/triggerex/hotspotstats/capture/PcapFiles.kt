package com.triggerex.hotspotstats.capture

import java.io.File
import java.io.RandomAccessFile

object PcapFiles {

    private const val HEADER = 24L
    private const val RECORD_HEADER = 16
    /** Larger than any snaplen the helper uses; a bigger value means garbage. */
    private const val MAX_RECORD = 262_144L

    /**
     * Cuts a libpcap file back to its last complete record. The pipe from the helper carries
     * bytes, not packets, so a capture that ended abruptly (process death, forced stop) can
     * end mid-record, which pcap readers report as corrupt.
     *
     * Returns the resulting length, or -1 if the file is not a complete-header pcap file.
     */
    fun truncateToLastRecord(file: File): Long {
        RandomAccessFile(file, "rw").use { raf ->
            val length = raf.length()
            if (length < HEADER) return -1
            val magic = ByteArray(4).also { raf.readFully(it) }
            val littleEndian = when {
                magic.contentEquals(byteArrayOf(0xd4.toByte(), 0xc3.toByte(), 0xb2.toByte(), 0xa1.toByte())) -> true
                magic.contentEquals(byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte())) -> false
                else -> return -1
            }
            val header = ByteArray(RECORD_HEADER)
            var pos = HEADER
            while (pos + RECORD_HEADER <= length) {
                raf.seek(pos)
                raf.readFully(header)
                val included = u32(header, 8, littleEndian)
                if (included > MAX_RECORD || pos + RECORD_HEADER + included > length) break
                pos += RECORD_HEADER + included
            }
            if (pos < length) raf.setLength(pos)
            return pos
        }
    }

    private fun u32(b: ByteArray, off: Int, le: Boolean): Long {
        val v = if (le) {
            (b[off].toLong() and 0xff) or ((b[off + 1].toLong() and 0xff) shl 8) or
                ((b[off + 2].toLong() and 0xff) shl 16) or ((b[off + 3].toLong() and 0xff) shl 24)
        } else {
            (b[off + 3].toLong() and 0xff) or ((b[off + 2].toLong() and 0xff) shl 8) or
                ((b[off + 1].toLong() and 0xff) shl 16) or ((b[off].toLong() and 0xff) shl 24)
        }
        return v
    }
}
