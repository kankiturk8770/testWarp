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
        assertTrue("--wg" in a && "--no-profile-retry" !in a) // falls back to the balanced profiles
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
        assertTrue("I1" !in plain && "Jc" !in plain)
        // the engine needs all four headers once Jc is set; plain WireGuard's own values
        assertTrue((1..4).all { "H$it = $it" in amnezia })
        assertTrue("H1" !in plain)
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
        // pinned: no quick reconnect to an old gateway
        assertTrue("--no-quick-reconnect" in a && "--quick-reconnect" !in a)
        val h2 = o.copy(transport = Transport.MASQUE_H2).toArgs("/x/identity.toml")
        assertEquals("162.159.198.2:443", h2[h2.indexOf("--peer") + 1])
        val back = Options.fromJson(o.copy(gamer = true, gameTransport = Transport.WIREGUARD).toJson())
        assertEquals(o.pins, back.pins)
        assertTrue(back.gamer)
        assertEquals(Transport.WIREGUARD, back.gameTransport)
    }

    @Test fun scannerTimestampsAlwaysRise() {
        val a = WgHello.stamp(); val b = WgHello.stamp()
        assertTrue(java.math.BigInteger(1, b) > java.math.BigInteger(1, a))
    }

    @Test fun gameReadyMeansSteadyAndNoLoss() {
        assertTrue(WgScan.Hit("x:1", 60, 4, 0).gameReady)
        assertTrue(!WgScan.Hit("x:1", 60, 4, 10).gameReady)
        assertTrue(!WgScan.Hit("x:1", 130, 4, 0).gameReady)
    }

    @Test fun theConnectionSaysWhatReallyCarriesIt() {
        Engine.wgProfile = "aggressive"
        assertEquals("Amnezia · پوشش aggressive", Engine.actualOf(Transport.AMNEZIA))
        Engine.wgProfile = "off"
        assertEquals("WireGuard ساده (بی‌پوشش)", Engine.actualOf(Transport.AMNEZIA))
        Engine.wgProfile = "balanced"
        assertEquals("WireGuard · پوشش balanced", Engine.actualOf(Transport.WIREGUARD))
        assertEquals(Transport.MASQUE_H2.title, Engine.actualOf(Transport.MASQUE_H2))
        Engine.wgProfile = ""
    }

    /** The same rules as the engine's parser (wgtunnel/amneziawg-go device/obf.go), so a bad tag never reaches the phone. */
    @Test fun signaturePacketsUseOnlyTagsTheEngineReads() {
        val tag = Regex("""<([a-z]+)(?:\s+([^>]*))?>""")
        for (spec in WarpWg.SIGNATURES) {
            var rest = spec
            for (m in tag.findAll(spec)) {
                rest = rest.replace(m.value, "")
                val key = m.groupValues[1]
                val v = m.groupValues[2]
                when (key) {
                    "b" -> { val hex = v.removePrefix("0x"); assertTrue(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it.isLetterOrDigit() }) }
                    "t" -> assertTrue(v.isEmpty())
                    "r", "rc", "rd" -> assertTrue("$key needs one whole number: '$v'", v.toIntOrNull()?.let { it > 0 } == true)
                    else -> throw AssertionError("tag <$key> is not one the engine reads")
                }
            }
            assertTrue("text outside tags: '$rest'", rest.isBlank())
        }
    }

    @Test fun iranModeIsHttp2FirstWithFragmentsAndAFullScan() {
        val o = Options(transport = Transport.WIREGUARD, iran = true, fragment = false, autoFallback = false)
        assertEquals(listOf(Transport.MASQUE_H2, Transport.AMNEZIA, Transport.GOOL, Transport.PSIPHON), Options.chain(o, Transport.WIREGUARD).map { it.transport })
        val a = Options.chain(o, null)[0].toArgs("/x/identity.toml")
        assertTrue("--masque" in a && "--h2" in a && "--fragment" in a)
        assertEquals("40", a[a.indexOf("--fragment-size") + 1])
        assertEquals("thorough", a[a.indexOf("--scan") + 1])
        assertTrue(Options.fromJson(o.toJson()).iran)
    }

    @Test fun psiphonIsTheLastIranStepAndTakesItsRegion() {
        val o = Options(iran = true)
        assertEquals(Transport.PSIPHON, Options.chain(o, null).last().transport)
        val a = Options(transport = Transport.PSIPHON, psiphonRegion = "DE").toArgs("/x/identity.toml")
        assertTrue("--psiphon-only" in a)
        assertEquals("DE", a[a.indexOf("--psiphon-region") + 1])
        assertTrue("--psiphon-region" !in Options(transport = Transport.WIREGUARD, psiphonRegion = "DE").toArgs("/x/i.toml"))
        assertEquals("DE", Options.fromJson(Options(psiphonRegion = "DE").toJson()).psiphonRegion)
    }

    @Test fun zeroTrustTakesTheTeamFromTheLinkOrTheToken() {
        assertEquals("myteam", ZeroTrust.normalize(" https://MyTeam.cloudflareaccess.com/ "))
        val b64 = { t: String -> java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(t.toByteArray()) }
        val jwt = b64("{\"alg\":\"RS256\"}") + "." + b64("{\"iss\":\"https://acme.cloudflareaccess.com\",\"sub\":\"x\"}") + ".c2lnbmF0dXJlLXNpZ25hdHVyZQ"
        assertEquals("acme" to jwt, ZeroTrust.parse("com.cloudflare.warp://acme.cloudflareaccess.com/auth?token=$jwt"))
        assertEquals("acme" to jwt, ZeroTrust.parse("  $jwt  "))
        assertEquals("" to "", ZeroTrust.parse("hello"))
    }

    @Test fun everyCountryFoundIsGroupedWithTurkeyAndGermanyApart() {
        val hits = listOf(
            WgScan.Hit("a:1", 70, 2, 0, "MXP"), WgScan.Hit("b:1", 60, 2, 0, "IST"),
            WgScan.Hit("c:1", 90, 2, 0, "VIE"), WgScan.Hit("d:1", 80, 2, 0, "FCO"), WgScan.Hit("e:1", 99, 2, 0, "ZZZ"),
        )
        assertEquals(listOf("IT", "AT", "??"), Colos.otherCountries(hits).map { it.first })
        assertEquals(listOf("a:1", "d:1"), Colos.otherCountries(hits)[0].second.map { it.endpoint })
        assertEquals("ایتالیا", Colos.countryName("IT"))
    }

    @Test fun zeroTrustUsesPlainHeadersAndTriesItsOwnGatewayFirst() {
        val toml = "wg_private_key = \"a\"\nwg_peer_public_key = \"b\"\nipv4 = \"100.96.0.2\"\n" +
            "client_id = \"AQID\"\nassigned_endpoint = \"162.159.193.10\"\n"
        val zt = WarpWg.parse(toml)!!.copy(team = true)
        // on a phone the gateway answered only the plain handshake: no device id in the headers
        assertTrue("H1" !in zt.config("162.159.193.10:2408", disguise = false))
        assertEquals(1 + (1 shl 8) + (2 shl 16) + (3 shl 24).toLong(), zt.header(1))
        assertEquals("162.159.193.10:2408", zt.ownGateways().first())
        assertTrue(WarpWg.parse(toml)!!.ownGateways().isEmpty())
        assertTrue(443 in WgScan.PORTS && 8443 in WgScan.PORTS)
    }

    @Test fun ipTestTargetsAreRandomInTheRangesAndDistinct() {
        val a = IpTest.randomTargets(WgScan.PREFIXES, WgScan.PORTS, 300, kotlin.random.Random(1))
        val b = IpTest.randomTargets(WgScan.PREFIXES, WgScan.PORTS, 300, kotlin.random.Random(2))
        assertEquals(300, a.size)
        assertEquals(a.size, a.toSet().size)
        assertTrue(a.all { ep -> ep.substringBeforeLast(':').substringBeforeLast('.') in WgScan.PREFIXES })
        assertTrue(a.all { ep -> ep.substringAfterLast(':').toInt() in WgScan.PORTS })
        assertTrue(a != b && a.toSet() != b.toSet())
        // not in order: neither sorted nor one range after another
        assertTrue(a != a.sorted())
        assertTrue(a.take(20).map { it.substringBeforeLast('.') }.toSet().size > 3)
    }

    @Test fun ipTestGoldenListAndCountries() {
        fun f(ep: String, ping: Int, loss: Int, colo: String, proto: Boolean = true) =
            IpTest.Found(ep, IpTest.Kind.GAMING, proto, tcp = !proto, pingMs = ping, lossPct = loss, colo = colo)
        val list = listOf(
            f("a:1", 90, 0, "FRA"), f("b:1", 60, 16, "IST"), f("c:1", 70, 0, "IST"), f("d:1", 190, 0, "DXB"),
            f("e:1", 450, 0, "FRA"), f("g:1", 0, 0, "", proto = false), f("h:1", 80, 100, ""), f("i:1", 120, -1, "MXP"),
        )
        // golden: measured, little loss, up to 200 ms
        assertEquals(listOf("c:1", "a:1", "d:1"), IpTest.golden(list).map { it.endpoint })
        // working: every one that answers, any ping, lowest first (450 ms too); not the dead or the TCP-only
        assertEquals(listOf("b:1", "c:1", "a:1", "i:1", "d:1", "e:1"), IpTest.working(list).map { it.endpoint })
        val c = IpTest.byCountry(list)
        assertEquals(listOf("TR", "DE", "IT", "AE"), c.map { it.first })
        assertEquals(listOf("a:1", "e:1"), c[1].second.map { it.endpoint })
    }

    @Test fun eachScanKindPinsOnlyTheProtocolsThatCanUseIt() {
        assertEquals(listOf(Transport.MASQUE_H3, Transport.GOOL, Transport.MIM), IpTest.Kind.H3.transports)
        assertEquals(listOf(Transport.MASQUE_H2), IpTest.Kind.H2.transports)
        val gool = Options(transport = Transport.GOOL, pins = mapOf(Transport.GOOL to "162.159.198.1:443")).toArgs("/x")
        assertEquals("162.159.198.1:443", gool[gool.indexOf("--peer") + 1])
        assertTrue("--wg-peer" !in gool)
        val mim = Options(transport = Transport.MIM, pins = mapOf(Transport.MIM to "162.159.198.1:443")).toArgs("/x")
        assertEquals("162.159.198.1:443", mim[mim.indexOf("--mim-outer") + 1])
        assertEquals(IpTest.Kind.H2, IpTest.parse("NORMAL,1.2.3.4:443,1,1,90,0,0,,wifi,5")!!.kind)
    }

    @Test fun quicProbeIsAnswerableWithoutCrypto() {
        val p = H3Scan.probe(kotlin.random.Random(3))
        assertEquals(1200, p.packet.size)
        assertEquals(0xC0, p.packet[0].toInt() and 0xC0)
        // a Version Negotiation answer: long header, version 0, dcid = our scid
        val vn = byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 8) + p.scid + byteArrayOf(8) + ByteArray(8) + byteArrayOf(0, 0, 0, 1)
        assertTrue(H3Scan.echoedId(vn)!!.contentEquals(p.scid))
        assertNull(H3Scan.echoedId(p.packet))
    }

    @Test fun ipTestArchiveKeepsTheNewestOfEachAddress() {
        val old = IpTest.Found("1.2.3.4:2408", IpTest.Kind.GAMING, true, false, 80, 3, 0, "FRA", "wifi", 10)
        val new = old.copy(pingMs = 70, seenAt = 20)
        val other = old.copy(network = "cell:x", seenAt = 5)
        val m = IpTest.merge(listOf(old, other), listOf(new))
        assertEquals(listOf(new, other), m)
        assertEquals(m, m.map { IpTest.parse(IpTest.line(it))!! })
        assertNull(IpTest.parse("garbage"))
    }

    @Test fun channelPasteIsReadPerScanKind() {
        val post = """
            🔥 WARP IPs today 🔥
            162.159.192.7:2408
            188.114.97.3:1002 ✅ 85ms
            ip: 162.159.198.2  port: 443
            999.1.1.1:2408  8.8.8.8:70000
            [2606:4700:d0::a29f:c001]:2408
            162.159.192.7:2408
        """.trimIndent()
        assertEquals(listOf("162.159.192.7:2408", "188.114.97.3:1002", "[2606:4700:d0::a29f:c001]:2408"),
            IpTest.parseList(post, IpTest.Kind.GAMING))
        // HTTP/2 takes a bare address on 443
        assertTrue("162.159.198.2:443" in IpTest.parseList(post, IpTest.Kind.H2))
    }

    @Test fun antiFilterSettingsReachTheCore() {
        val a = Options(transport = Transport.MASQUE_H2, frag = Frag.TINY, noize = Noize.GFW, sni = Sni.WARP, ech = true, quicV2 = false)
            .toArgs("/x")
        assertEquals("1-5", a[a.indexOf("--fragment-size") + 1])
        assertEquals("gfw", a[a.indexOf("--noize") + 1])
        assertEquals("consumer-masque.cloudflareclient.com", a[a.indexOf("--masque-sni") + 1])
        assertEquals("auto", a[a.indexOf("--ech") + 1])
        assertTrue("--no-quic-v2" in a)
        // a picked profile replaces Amnezia's own, once
        val am = Options(transport = Transport.AMNEZIA, noize = Noize.LIGHT).toArgs("/x")
        assertEquals(1, am.count { it == "--noize" })
        assertEquals("light", am[am.indexOf("--noize") + 1])
        // defaults add nothing new; Iran mode cuts in 40-byte pieces
        val d = Options(transport = Transport.MASQUE_H2).toArgs("/x")
        assertTrue("--fragment-size" !in d && "--masque-sni" !in d && "--ech" !in d && "--no-quic-v2" !in d)
        val ir = Options(iran = true).toArgs("/x")
        assertEquals("40", ir[ir.indexOf("--fragment-size") + 1])
        val o = Options(frag = Frag.LARGE, noize = Noize.OFF, gameJunk = GameJunk.HEAVY, gameMtu = 1420, ech = true)
        assertEquals(o, Options.fromJson(o.toJson()))
    }
}
