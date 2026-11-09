package cafe.kankipro.warp.core

import org.json.JSONObject

/** How the core reaches Cloudflare. [flags] are the core's own command-line switches. */
enum class Transport(val title: String, val hint: String, val flags: List<String>) {
    // WireGuard with AmneziaWG 1.5 style disguise: signature packets (I1-I5) that look like other
    // protocols and junk packets around the handshake. Cloudflare's plain WireGuard server drops
    // them unread, so they need no server of ours.
    // Strongest profile first; if it does not pass on a gateway, the core tries balanced, light and off
    // there before giving up on that gateway.
    AMNEZIA("Amnezia", "WireGuard با بسته‌های پوششی شدید، مثل AmneziaVPN", listOf("--wg", "--noize", "aggressive")),
    WIREGUARD("WireGuard", "WireGuard با پوشش متعادل؛ اگر نشد سبک‌تر و شدیدتر", listOf("--wg")),
    MASQUE_H2("MASQUE · HTTP/2", "روی TCP 443، مثل یک سایت معمولی؛ وقتی UDP بسته است", listOf("--masque", "--h2")),
    MASQUE_H3("MASQUE · HTTP/3", "روی QUIC (UDP)؛ کم‌پینگ‌ترین اگر باز باشد", listOf("--masque", "--h3")),
    GOOL("Gool", "WireGuard داخل MASQUE؛ دو لایه", listOf("--gool")),
    MIM("MASQUE در MASQUE", "دو تونل تو در تو؛ سخت‌ترین برای فیلتر، کندتر", listOf("--mim")),
    // Psiphon alone, without WARP: for when every Cloudflare address is blocked. Its own network and
    // obfuscation (built for Iran); slower, not for games. The core runs the psiphon-tunnel-core
    // program the app carries (libpsiphon.so), see Engine.startCore.
    PSIPHON("Psiphon", "شبکه‌ی خود Psiphon، بدون کلادفلر؛ برای وقتی همه‌چیز بسته است", listOf("--psiphon-only"));

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name }
    }
}

/** How hard the core looks for a Cloudflare gateway that answers. */
enum class Scan(val title: String, val value: String) {
    TURBO("سریع", "turbo"),
    BALANCED("متعادل", "balanced"),
    THOROUGH("کامل", "thorough"),
    IRONCLAD("با تونل واقعی", "ironclad");
}

/** The DNS the phone asks through the tunnel. AdGuard answers ad and tracker names with nothing: fewer ads, less data. */
enum class Dns(val title: String, val servers: List<String>) {
    CLOUDFLARE("کلادفلر", listOf("1.1.1.1", "1.0.0.1")),
    ADGUARD("AdGuard · بدون تبلیغ", listOf("94.140.14.14", "94.140.15.15")),
}

/** How the TLS ClientHello is cut (the core's --fragment-size / --fragment-delay, both «n» or «a-b»). */
enum class Frag(val title: String, val size: String, val delay: String) {
    STANDARD("استاندارد", "8-16", "2-10"),
    TINY("ریز", "1-5", "5-15"),
    IRAN("ایران", "40", "10"),
    LARGE("درشت", "50-100", "1-5"),
}

/** The core's junk-packet profile (--noize); AUTO leaves the core's own default per transport. */
enum class Noize(val title: String, val value: String?) {
    AUTO("خودکار", null), OFF("خاموش", "off"), LIGHT("سبک", "light"), FIREWALL("فایروال", "firewall"), GFW("قوی", "gfw"),
}

/** The name the MASQUE handshakes show in their ClientHello (--masque-sni). */
enum class Sni(val title: String, val value: String?) {
    CLOUDFLARE("www.cloudflare.com", null),
    WARP("مثل اپ WARP", "consumer-masque.cloudflareclient.com"),
}

/** How many Amnezia junk packets the gamer engine sends before each handshake, and their size. */
enum class GameJunk(val title: String, val jc: Int, val jmin: Int, val jmax: Int) {
    LIGHT("سبک", 2, 20, 40), NORMAL("معمول", 4, 40, 70), HEAVY("سنگین", 8, 64, 128),
}

enum class IpFamily(val title: String, val value: String) {
    V4("IPv4", "v4"), V6("IPv6", "v6"), BOTH("هر دو", "both");
}

data class Options(
    val transport: Transport = Transport.AMNEZIA,
    val scan: Scan = Scan.TURBO,
    val ip: IpFamily = IpFamily.V4,
    val fragment: Boolean = true,
    val autoFallback: Boolean = true,
    val mtu: Int = 1500,
    val port: Int = 1819,
    /** Normal mode: a gateway the white-list scan found for a transport, used instead of the core's own scan. */
    val pins: Map<Transport, String> = emptyMap(),
    /** The handshake time the scan measured for each pin, for its badge. */
    val pinPing: Map<Transport, Int> = emptyMap(),
    /** «کاربر گیمر»: Amnezia/WireGuard straight on the TUN (GameEngine) instead of the core. */
    val gamer: Boolean = false,
    /**
     * «حالت ایران»: the setup the core's Android client (RIX) ships for Iran: MASQUE over HTTP/2 on TCP 443
     * first (looks like an ordinary HTTPS site, untouched by UDP blocking), the handshake cut into
     * 40-byte pieces 10 ms apart, a full gateway scan, then Amnezia and Gool if it does not answer.
     */
    val iran: Boolean = false,
    val dns: Dns = Dns.CLOUDFLARE,
    /** Psiphon's exit country (two letters), "" = Psiphon picks. */
    val psiphonRegion: String = "",
    val gameTransport: Transport = Transport.AMNEZIA,
    // ---- against heavy filtering (normal user, the core)
    val frag: Frag = Frag.STANDARD,
    val noize: Noize = Noize.AUTO,
    val sni: Sni = Sni.CLOUDFLARE,
    /** Encrypted Client Hello: the server name goes hidden (--ech auto). */
    val ech: Boolean = false,
    /** The QUIC v2 opener for HTTP/3 (on in the core by default). */
    val quicV2: Boolean = true,
    // ---- gamer engine
    val gameJunk: GameJunk = GameJunk.NORMAL,
    val gameMtu: Int = 1280,
) {
    /** The core's arguments: one SOCKS5 listener on the loopback, identity kept in [configPath]. */
    fun toArgs(configPath: String): List<String> = buildList {
        add("--bind"); add("127.0.0.1:$port")
        add("--config"); add(configPath)
        add("--log-level"); add("info")
        // a junk profile picked by hand replaces the transport's own (Amnezia's «aggressive» too)
        val flags = transport.flags.toMutableList()
        noize.value?.let { v ->
            val i = flags.indexOf("--noize")
            if (i >= 0) flags[i + 1] = v else { flags += "--noize"; flags += v }
        }
        addAll(flags)
        add("--scan"); add(if (iran) Scan.THOROUGH.value else scan.value)
        add("--ip"); add(ip.value)
        if (fragment || iran) {
            add("--fragment")
            // Iran mode cuts in 40-byte pieces unless another size was picked by hand
            val f = if (iran && frag == Frag.STANDARD) Frag.IRAN else frag
            if (f != Frag.STANDARD) { add("--fragment-size"); add(f.size); add("--fragment-delay"); add(f.delay) }
        }
        sni.value?.let { add("--masque-sni"); add(it) }
        if (ech) { add("--ech"); add("auto") }
        if (!quicV2) add("--no-quic-v2")
        // Reuse the gateway that worked last time without rescanning, when it still answers; with a
        // pinned gateway nothing else is tried: the core goes to that one only and never scans.
        add(if (pins[transport] != null && pinnable) "--no-quick-reconnect" else "--quick-reconnect")
        if (transport == Transport.PSIPHON && psiphonRegion.isNotBlank()) { add("--psiphon-region"); add(psiphonRegion) }
        pins[transport]?.let { pin ->
            when (transport) {
                Transport.AMNEZIA, Transport.WIREGUARD -> { add("--wg-peer"); add(pin) }
                // Gool: the MASQUE hop it rides (the core's --peer, not --wg-peer, which would pick the classic gool)
                Transport.MASQUE_H2, Transport.MASQUE_H3, Transport.GOOL -> { add("--peer"); add(pin) }
                // MASQUE-in-MASQUE: the outer hop, the one this network sees; the inner one the core picks
                Transport.MIM -> { add("--mim-outer"); add(pin) }
                else -> Unit
            }
        }
    }

    /** Transports that take a pinned gateway. */
    val pinnable: Boolean get() = transport in PINNABLE

    fun toJson(): String = JSONObject()
        .put("transport", transport.name)
        .put("scan", scan.name)
        .put("ip", ip.name)
        .put("fragment", fragment)
        .put("autoFallback", autoFallback)
        .put("pins", JSONObject().apply { pins.forEach { (t, v) -> put(t.name, v) } })
        .put("pinPing", JSONObject().apply { pinPing.forEach { (t, v) -> put(t.name, v) } })
        .put("gamer", gamer)
        .put("iran", iran)
        .put("dns", dns.name)
        .put("psiphonRegion", psiphonRegion)
        .put("gameTransport", gameTransport.name)
        .put("frag", frag.name).put("noize", noize.name).put("sni", sni.name).put("ech", ech).put("quicV2", quicV2)
        .put("gameJunk", gameJunk.name).put("gameMtu", gameMtu)
        .toString()

    companion object {
        fun fromJson(raw: String?): Options {
            if (raw.isNullOrBlank()) return Options()
            return try {
                val j = JSONObject(raw)
                Options(
                    transport = Transport.of(j.optString("transport")) ?: Transport.AMNEZIA,
                    scan = Scan.entries.firstOrNull { it.name == j.optString("scan") } ?: Scan.TURBO,
                    ip = IpFamily.entries.firstOrNull { it.name == j.optString("ip") } ?: IpFamily.V4,
                    fragment = j.optBoolean("fragment", true),
                    autoFallback = j.optBoolean("autoFallback", true),
                    pins = j.optJSONObject("pins")?.let { o ->
                        o.keys().asSequence().mapNotNull { k -> Transport.of(k)?.let { it to o.optString(k) } }
                            .filter { it.second.isNotBlank() }.toMap()
                    } ?: emptyMap(),
                    pinPing = j.optJSONObject("pinPing")?.let { o ->
                        o.keys().asSequence().mapNotNull { k -> Transport.of(k)?.let { it to o.optInt(k) } }.toMap()
                    } ?: emptyMap(),
                    gamer = j.optBoolean("gamer", false),
                    iran = j.optBoolean("iran", false),
                    dns = Dns.entries.firstOrNull { it.name == j.optString("dns") } ?: Dns.CLOUDFLARE,
                    psiphonRegion = j.optString("psiphonRegion", ""),
                    gameTransport = Transport.of(j.optString("gameTransport"))
                        ?.takeIf { it == Transport.AMNEZIA || it == Transport.WIREGUARD } ?: Transport.AMNEZIA,
                    frag = Frag.entries.firstOrNull { it.name == j.optString("frag") } ?: Frag.STANDARD,
                    noize = Noize.entries.firstOrNull { it.name == j.optString("noize") } ?: Noize.AUTO,
                    sni = Sni.entries.firstOrNull { it.name == j.optString("sni") } ?: Sni.CLOUDFLARE,
                    ech = j.optBoolean("ech", false),
                    quicV2 = j.optBoolean("quicV2", true),
                    gameJunk = GameJunk.entries.firstOrNull { it.name == j.optString("gameJunk") } ?: GameJunk.NORMAL,
                    gameMtu = j.optInt("gameMtu", 1280).takeIf { it in 1200..1500 } ?: 1280,
                )
            } catch (_: Exception) {
                Options()
            }
        }

        val PINNABLE = setOf(Transport.AMNEZIA, Transport.WIREGUARD, Transport.MASQUE_H2, Transport.MASQUE_H3, Transport.GOOL, Transport.MIM)

        /** Order tried after the user's choice: what survives a hostile network first. */
        val RANKING = listOf(
            Transport.AMNEZIA, Transport.MASQUE_H2, Transport.WIREGUARD, Transport.GOOL, Transport.MASQUE_H3, Transport.MIM
        )

        /**
         * The transports one connection tries, at most [limit]: the user's choice as it is, then the
         * one that worked last time, then [RANKING]. The fallbacks always fragment the handshake.
         */
        /** Iran mode's fixed order: what survives DPI and UDP blocking first. */
        val IRAN = listOf(Transport.MASQUE_H2, Transport.AMNEZIA, Transport.GOOL, Transport.PSIPHON)

        fun chain(options: Options, remembered: Transport?, limit: Int = 3): List<Options> {
            if (options.iran) return IRAN.map { options.copy(transport = it, fragment = true) }
            if (!options.autoFallback) return listOf(options)
            val out = mutableListOf(options)
            val order = listOfNotNull(remembered) + RANKING
            for (t in order) {
                if (out.size >= limit) break
                if (out.any { it.transport == t }) continue
                out += options.copy(transport = t, fragment = true)
            }
            return out
        }
    }
}
