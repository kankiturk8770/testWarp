package cafe.kankipro.warp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test fun traceIsRead() {
        val e = Probe.parseTrace("fl=1\nh=cloudflare.com\nip=104.28.1.2\nts=1\ncolo=FRA\nloc=DE\nwarp=on\n")!!
        assertEquals("104.28.1.2", e.ip)
        assertEquals("FRA", e.colo)
        assertEquals("DE", e.loc)
        assertEquals("on", e.warp)
        assertNull(Probe.parseTrace("<html>blocked</html>"))
    }

    @Test fun chainStartsWithChoiceThenRememberedThenRanking() {
        val o = Options(transport = Transport.MASQUE_H3, fragment = false)
        val c = Options.chain(o, Transport.WIREGUARD)
        assertEquals(listOf(Transport.MASQUE_H3, Transport.WIREGUARD, Transport.AMNEZIA), c.map { it.transport })
        assertEquals(false, c[0].fragment)
        assertTrue(c.drop(1).all { it.fragment })
        assertEquals(1, Options.chain(o.copy(autoFallback = false), null).size)
    }

    @Test fun argsNameTheTransportAndTheLoopbackPort() {
        val a = Options(transport = Transport.MASQUE_H2, scan = Scan.TURBO).toArgs("/x/identity.toml")
        assertEquals("127.0.0.1:1819", a[a.indexOf("--bind") + 1])
        assertTrue("--masque" in a && "--h2" in a && "--fragment" in a)
        assertEquals("turbo", a[a.indexOf("--scan") + 1])
    }

    @Test fun amneziaIsWireGuardWithItsOwnStrongDisguise() {
        val a = Options(transport = Transport.AMNEZIA).toArgs("/x/identity.toml")
        assertTrue("--wg" in a && "--no-profile-retry" in a)
        assertEquals("aggressive", a[a.indexOf("--noize") + 1])
        assertTrue("--noize" !in Options(transport = Transport.WIREGUARD).toArgs("/x/identity.toml"))
    }

    @Test fun optionsSurviveJson() {
        val o = Options(transport = Transport.GOOL, scan = Scan.IRONCLAD, ip = IpFamily.BOTH, fragment = false)
        assertEquals(o, Options.fromJson(o.toJson()))
    }

    private val toml = """
        device_id = "d1"
        access_token = "t"
        ipv4 = "172.16.0.2"
        ipv6 = "2606:4700:110:8a36::1"
        wg_private_key = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
        wg_peer_public_key = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        client_id = "AQID"
    """.trimIndent()

    @Test fun theWarpAccountBecomesAnAmneziaConfig() {
        val id = WarpWg.parse(toml)!!
        assertEquals(listOf<Byte>(1, 2, 3), id.clientId.toList())
        assertEquals(1L or (1L shl 8) or (2L shl 16) or (3L shl 24), id.header(1))
        val plain = id.config("162.159.192.1:2408", disguise = false)
        assertTrue("Address = 172.16.0.2/32, 2606:4700:110:8a36::1/128" in plain)
        assertTrue("Endpoint = 162.159.192.1:2408" in plain && "Jc" !in plain)
        val amnezia = id.config("162.159.192.1:2408", disguise = true)
        assertTrue("Jc = 4" in amnezia && "I1 = " in amnezia && "S1" !in amnezia)
        // the scanner's handshake can be built from it (real WireGuard crypto)
        val hello = WgHello.of(amnezia)!!
        val sent = hello.hello()
        assertEquals(4, sent.junk.size)
        assertEquals(WgHello.INIT_LEN, sent.packet.size)
        assertNull(WarpWg.parse("device_id = \"x\""))
    }

    @Test fun scanSummaryAndScore() {
        val h = WgScan.summarize("1.2.3.4:2408", listOf(50, 60, 50, 70), sent = 5)
        assertEquals(57, h.pingMs)
        assertEquals(13, h.jitterMs)
        assertEquals(20, h.lossPct)
        assertEquals(57 + 13 * 3 + 20 * 20, h.score)
        val c = WgScan.candidates(100)
        assertEquals(100, c.size)
        assertTrue("162.159.192.1:2408" in c)
    }

    @Test fun pinnedGatewaysGoToTheCore() {
        val o = Options(transport = Transport.AMNEZIA, pins = mapOf(Transport.AMNEZIA to "188.114.97.3:4500",
            Transport.MASQUE_H2 to "162.159.198.2:443"))
        val a = o.toArgs("/x/identity.toml")
        assertEquals("188.114.97.3:4500", a[a.indexOf("--wg-peer") + 1])
        val h2 = o.copy(transport = Transport.MASQUE_H2).toArgs("/x/identity.toml")
        assertEquals("162.159.198.2:443", h2[h2.indexOf("--peer") + 1])
        val back = Options.fromJson(o.copy(gamer = true, gameTransport = Transport.WIREGUARD).toJson())
        assertEquals(o.pins, back.pins)
        assertTrue(back.gamer)
        assertEquals(Transport.WIREGUARD, back.gameTransport)
    }
}
