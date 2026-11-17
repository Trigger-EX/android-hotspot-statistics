package com.triggerex.hotspotstats.capture

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcapFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pcap(order: ByteOrder, records: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(24).order(order).putInt(0xa1b2c3d4.toInt()).putShort(2).putShort(4)
            .putInt(0).putInt(0).putInt(65535).putInt(1).array())
        for (len in records) {
            out.write(ByteBuffer.allocate(16).order(order).putInt(1).putInt(2).putInt(len).putInt(len).array())
            out.write(ByteArray(len) { 7 })
        }
        return out.toByteArray()
    }

    @Test
    fun `complete file is unchanged`() {
        val data = pcap(ByteOrder.LITTLE_ENDIAN, listOf(60, 1500, 42))
        val f = tmp.newFile().apply { writeBytes(data) }
        assertEquals(data.size.toLong(), PcapFiles.truncateToLastRecord(f))
        assertEquals(data.size.toLong(), f.length())
    }

    @Test
    fun `cut in the middle of a payload drops the partial record`() {
        val data = pcap(ByteOrder.LITTLE_ENDIAN, listOf(60, 1500))
        val f = tmp.newFile().apply { writeBytes(data.copyOf(data.size - 700)) }
        assertEquals(24L + 16 + 60, PcapFiles.truncateToLastRecord(f))
        assertEquals(24L + 16 + 60, f.length())
    }

    @Test
    fun `cut in the middle of a record header`() {
        val data = pcap(ByteOrder.BIG_ENDIAN, listOf(60, 80))
        val f = tmp.newFile().apply { writeBytes(data.copyOf(24 + 16 + 60 + 9)) }
        assertEquals(24L + 16 + 60, PcapFiles.truncateToLastRecord(f))
    }

    @Test
    fun `header only and non pcap files`() {
        val headerOnly = tmp.newFile().apply { writeBytes(pcap(ByteOrder.LITTLE_ENDIAN, emptyList())) }
        assertEquals(24L, PcapFiles.truncateToLastRecord(headerOnly))
        val short = tmp.newFile().apply { writeBytes(ByteArray(10)) }
        assertEquals(-1L, PcapFiles.truncateToLastRecord(short))
        val garbage = tmp.newFile().apply { writeBytes(ByteArray(100) { 1 }) }
        assertEquals(-1L, PcapFiles.truncateToLastRecord(garbage))
        assertEquals(100L, garbage.length())
    }
}
