package com.triggerex.hotspotstats.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureProtocolTest {

    private val base = CaptureRequest(
        iface = "wlan1",
        hosts = listOf(CaptureHost("192.168.43.12", "AA:BB:CC:DD:EE:01", "Phone")),
        durationSec = 600,
        maxBytes = 50_000_000,
        snaplen = 65535,
    )

    @Test
    fun `builds command with validated arguments`() {
        val cmd = CaptureProtocol.command("/data/app/x/lib/arm64/libhscapture.so", base)
        assertEquals(
            "'/data/app/x/lib/arm64/libhscapture.so' -i wlan1 -a 192.168.43.12 -m aa:bb:cc:dd:ee:01 " +
                "-s 65535 -d 600 -C 50000000 -r 40",
            cmd,
        )
    }

    @Test
    fun `no hosts captures everything`() {
        val cmd = CaptureProtocol.command("/bin/x", base.copy(hosts = emptyList()))
        assertEquals("'/bin/x' -i wlan1 -s 65535 -d 600 -C 50000000 -r 40", cmd)
    }

    @Test
    fun `rejects shell injection and bad addresses`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureProtocol.command("/bin/x", base.copy(iface = "wlan1;reboot"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureProtocol.command("/bin/x", base.copy(hosts = listOf(CaptureHost("1.2.3.4 -i lo", null, "x"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureProtocol.command("/bin/x", base.copy(hosts = listOf(CaptureHost(null, "\$(id)", "x"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureProtocol.command("/bin/x", base.copy(hosts = listOf(CaptureHost(null, null, "x"))))
        }
    }

    @Test
    fun `quotes binary path`() {
        assertEquals("'/a b/it'\\''s'", CaptureProtocol.shellQuote("/a b/it's"))
    }

    @Test
    fun `clamps limits`() {
        val cmd = CaptureProtocol.command("/bin/x", base.copy(hosts = emptyList(), durationSec = 0, maxBytes = 1, snaplen = 10))
        assertEquals("'/bin/x' -i wlan1 -s 64 -d 1 -C 1024 -r 40", cmd)
    }

    @Test
    fun `parses status lines`() {
        assertEquals(CaptureEvent.Started(1, "wlan1"), CaptureProtocol.parse("I 1 wlan1"))
        assertEquals(CaptureEvent.Stats(1000, 14, 998, 2, 40), CaptureProtocol.parse("S 1000 14 998 2 40"))
        assertEquals(
            CaptureEvent.Packet(1700000000123, 74, "TCP 192.168.43.12:51234 > 142.250.1.1:443 [S]"),
            CaptureProtocol.parse("P 1700000000123 74 TCP 192.168.43.12:51234 > 142.250.1.1:443 [S]"),
        )
        assertEquals(CaptureEvent.Error("socket: Operation not permitted (root required)"),
            CaptureProtocol.parse("E socket: Operation not permitted (root required)"))
        assertEquals(CaptureEvent.Ended("time-limit", 5, 500, 0), CaptureProtocol.parse("X time-limit 5 500 0"))
    }

    @Test
    fun `ignores malformed lines`() {
        assertNull(CaptureProtocol.parse(""))
        assertNull(CaptureProtocol.parse("Permission denied"))
        assertNull(CaptureProtocol.parse("S 1 2 three 4 5"))
        assertNull(CaptureProtocol.parse("P notanumber 5 x"))
        assertNull(CaptureProtocol.parse("Z 1 2"))
    }
}
