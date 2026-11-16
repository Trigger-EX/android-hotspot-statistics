package com.triggerex.hotspotstats.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ParsersTest {

    @Test
    fun `parses proc net arp and skips incomplete entries`() {
        val text = """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.43.12    0x1         0x2         AA:BB:CC:DD:EE:01     *        wlan1
            192.168.43.13    0x1         0x0         00:00:00:00:00:00     *        wlan1
            10.0.0.1         0x1         0x6         aa:bb:cc:dd:ee:02     *        wlan0
        """.trimIndent()
        val entries = Parsers.parseProcNetArp(text)
        assertEquals(2, entries.size)
        assertEquals("192.168.43.12", entries[0].ip)
        assertEquals("aa:bb:cc:dd:ee:01", entries[0].mac)
        assertEquals("wlan1", entries[0].device)
        assertEquals("PERMANENT", entries[1].state)
    }

    @Test
    fun `parses ip neigh and drops failed and ipv6 entries`() {
        val text = """
            192.168.43.12 dev wlan1 lladdr aa:bb:cc:dd:ee:01 REACHABLE
            192.168.43.20 dev wlan1 lladdr aa:bb:cc:dd:ee:03 STALE
            192.168.43.9 dev wlan1  FAILED
            fe80::1c2b dev wlan1 lladdr aa:bb:cc:dd:ee:01 router STALE
        """.trimIndent()
        val entries = Parsers.parseIpNeigh(text)
        assertEquals(listOf("192.168.43.12", "192.168.43.20"), entries.map { it.ip })
        assertEquals("STALE", entries[1].state)
    }

    @Test
    fun `parses iw station dump`() {
        val text = """
            Station aa:bb:cc:dd:ee:01 (on wlan1)
            	inactive time:	1200 ms
            	rx bytes:	123456
            	rx packets:	789
            	tx bytes:	654321
            	tx packets:	456
            	tx retries:	12
            	tx failed:	1
            	signal:  	-45 [-47, -48] dBm
            	signal avg:	-46 [-48, -49] dBm
            	tx bitrate:	433.3 MBit/s VHT-MCS 9 80MHz short GI VHT-NSS 1
            	rx bitrate:	390.0 MBit/s VHT-MCS 8 80MHz short GI VHT-NSS 1
            	authorized:	yes
            	connected time:	3600 seconds
            Station aa:bb:cc:dd:ee:02 (on wlan1)
            	signal:  	-71 dBm
        """.trimIndent()
        val stations = Parsers.parseIwStationDump(text)
        assertEquals(2, stations.size)
        val s = stations[0]
        assertEquals("aa:bb:cc:dd:ee:01", s.mac)
        assertEquals(-45, s.signalDbm)
        assertEquals(-46, s.signalAvgDbm)
        assertEquals(433.3, s.txBitrateMbps!!, 0.001)
        assertEquals(390.0, s.rxBitrateMbps!!, 0.001)
        assertEquals(123456L, s.rxBytes)
        assertEquals(1200L, s.inactiveMs)
        assertEquals(3600L, s.connectedSeconds)
        assertEquals(true, s.authorized)
        assertEquals(-71, stations[1].signalDbm)
        assertNull(stations[1].txBitrateMbps)
    }

    @Test
    fun `parses android 13 soft ap xml`() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <WifiConfigStoreData>
            <int name="Version" value="3" />
            <SoftAp>
            <string name="WifiSsid">&quot;Ted&apos;s Pixel&quot;</string>
            <boolean name="HiddenSSID" value="false" />
            <int name="SecurityType" value="3" />
            <string name="Passphrase">s3cret&amp;pw</string>
            <int name="MaxNumberOfClients" value="0" />
            <boolean name="AutoShutdownEnabled" value="true" />
            <int name="ApBand" value="3" />
            <int name="Channel" value="0" />
            </SoftAp>
            </WifiConfigStoreData>
        """.trimIndent()
        val c = Parsers.parseSoftApXml(xml)!!
        assertEquals("Ted's Pixel", c.ssid)
        assertEquals("s3cret&pw", c.passphrase)
        assertEquals("WPA3-Personal (SAE)", c.security)
        assertEquals("2.4 GHz + 5 GHz", c.band)
        assertNull(c.channel)
        assertNull(c.maxClients)
        assertEquals(false, c.hidden)
        assertEquals(true, c.autoShutdown)
    }

    @Test
    fun `parses android 11 soft ap xml with plain SSID`() {
        val xml = """<SoftAp><string name="SSID">AndroidAP_1234</string><int name="SecurityType" value="1" /><int name="ApBand" value="2" /><int name="Channel" value="36" /></SoftAp>"""
        val c = Parsers.parseSoftApXml(xml)!!
        assertEquals("AndroidAP_1234", c.ssid)
        assertEquals("WPA2-Personal", c.security)
        assertEquals("5 GHz", c.band)
        assertEquals(36, c.channel)
        assertNull(c.passphrase)
    }

    @Test
    fun `decodes hex ssid`() {
        assertEquals("Hello", Parsers.decodeWifiSsid("48656c6c6f"))
        assertEquals("Plain", Parsers.decodeWifiSsid("\"Plain\""))
    }

    @Test
    fun `parses dnsmasq leases`() {
        val leases = Parsers.parseDnsmasqLeases(
            "1700000000 aa:bb:cc:dd:ee:01 192.168.43.12 Pixel-7 01:aa:bb:cc:dd:ee:01\n" +
                "1700000001 aa:bb:cc:dd:ee:02 192.168.43.13 * *\n",
        )
        assertEquals(2, leases.size)
        assertEquals("Pixel-7", leases[0].hostname)
        assertNull(leases[1].hostname)
    }

    @Test
    fun `detects randomized mac`() {
        assertTrue(Parsers.isRandomizedMac("da:a1:19:00:00:01"))
        assertFalse(Parsers.isRandomizedMac("00:1a:2b:3c:4d:5e"))
        assertFalse(Parsers.isRandomizedMac(null))
        assertNull(Parsers.normalizeMac("00:00:00:00:00:00"))
        assertEquals("aa:bb:cc:dd:ee:ff", Parsers.normalizeMac("AA-BB-CC-DD-EE-FF"))
    }

    @Test
    fun `enumerates subnet hosts excluding self network and broadcast`() {
        val hosts = Parsers.subnetHosts("192.168.43.1", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.43.2", hosts.first())
        assertEquals("192.168.43.254", hosts.last())
        assertFalse("192.168.43.1" in hosts)
    }

    @Test
    fun `narrows oversized subnets`() {
        val hosts = Parsers.subnetHosts("10.20.30.40", 8)
        assertEquals(1021, hosts.size) // a /22 minus network, broadcast and self
        assertTrue(hosts.all { it.startsWith("10.20.") })
    }

    @Test
    fun `private ipv4 ranges`() {
        assertTrue(Parsers.isPrivateIpv4("192.168.1.1"))
        assertTrue(Parsers.isPrivateIpv4("172.20.0.1"))
        assertTrue(Parsers.isPrivateIpv4("10.0.0.1"))
        assertFalse(Parsers.isPrivateIpv4("172.32.0.1"))
        assertFalse(Parsers.isPrivateIpv4("8.8.8.8"))
    }

    @Test
    fun `channel from frequency`() {
        assertEquals(1, Parsers.channelFromFrequency(2412))
        assertEquals(6, Parsers.channelFromFrequency(2437))
        assertEquals(14, Parsers.channelFromFrequency(2484))
        assertEquals(36, Parsers.channelFromFrequency(5180))
        assertEquals(5, Parsers.channelFromFrequency(5975))
    }

    @Test
    fun `netbios request has wildcard name and nbstat type`() {
        val req = Parsers.buildNetbiosStatusRequest(0x1234)
        assertEquals(50, req.size)
        assertEquals(0x12.toByte(), req[0])
        assertEquals(0x20.toByte(), req[12])
        assertEquals('C'.code.toByte(), req[13]) // '*' = 0x2A -> 'C','K'
        assertEquals('K'.code.toByte(), req[14])
        assertEquals(0x21.toByte(), req[47])
    }

    @Test
    fun `parses netbios status response skipping group names`() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0))
        out.write(Parsers.buildNetbiosStatusRequest(0).copyOfRange(12, 46)) // encoded name
        out.write(byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0, 0, 0)) // type, class, ttl, rdlength
        out.write(2)
        fun entry(name: String, flags: Int) {
            out.write(name.padEnd(15).toByteArray(Charsets.US_ASCII))
            out.write(0x00)
            out.write(flags shr 8)
            out.write(flags and 0xff)
        }
        entry("WORKGROUP", 0x8400)
        entry("DESKTOP-ABC", 0x0400)
        assertEquals("DESKTOP-ABC", Parsers.parseNetbiosStatusResponse(out.toByteArray()))
    }

    @Test
    fun `round trips mdns ptr query and response`() {
        val name = Parsers.reverseArpaName("192.168.43.12")!!
        assertEquals("12.43.168.192.in-addr.arpa", name)
        val query = Parsers.buildPtrQuery(7, name)

        val out = ByteArrayOutputStream()
        out.write(query, 0, 4)
        out.write(byteArrayOf(0, 1, 0, 1, 0, 0, 0, 0)) // qd=1 an=1 ns=0 ar=0
        out.write(query, 12, query.size - 12) // question
        out.write(byteArrayOf(0xc0.toByte(), 0x0c, 0, 12, 0, 1, 0, 0, 0, 120))
        val target = byteArrayOf(12) + "Johns-iPhone".toByteArray() + byteArrayOf(5) + "local".toByteArray() + byteArrayOf(0)
        out.write(byteArrayOf(0, target.size.toByte()))
        out.write(target)

        assertEquals("Johns-iPhone.local", Parsers.parsePtrResponse(out.toByteArray()))
    }

    @Test
    fun `device hints prefer hostnames then ports`() {
        assertEquals("Apple iOS/iPadOS device", Parsers.deviceHint(emptyList(), "Johns-iPhone.local"))
        assertEquals("Windows PC", Parsers.deviceHint(emptyList(), "DESKTOP-ABC"))
        assertEquals("Apple iOS/iPadOS device", Parsers.deviceHint(listOf(80, 62078), null))
        assertEquals("Windows / SMB file sharing", Parsers.deviceHint(listOf(445, 139), null))
        assertNull(Parsers.deviceHint(emptyList(), null))
    }
}
