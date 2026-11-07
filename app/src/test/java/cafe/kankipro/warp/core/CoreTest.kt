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
        assertEquals(listOf(Transport.MASQUE_H3, Transport.WIREGUARD, Transport.MASQUE_H2), c.map { it.transport })
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

    @Test fun optionsSurviveJson() {
        val o = Options(transport = Transport.GOOL, scan = Scan.IRONCLAD, ip = IpFamily.BOTH, fragment = false)
        assertEquals(o, Options.fromJson(o.toJson()))
    }
}
