package cafe.kankipro.warp.core

import org.json.JSONObject

/** How the core reaches Cloudflare. [flags] are the core's own command-line switches. */
enum class Transport(val title: String, val hint: String, val flags: List<String>) {
    MASQUE_H2("MASQUE · HTTP/2", "روی TCP 443، مثل یک سایت معمولی؛ وقتی UDP بسته است", listOf("--masque", "--h2")),
    MASQUE_H3("MASQUE · HTTP/3", "روی QUIC (UDP)؛ کم‌پینگ‌ترین اگر باز باشد", listOf("--masque", "--h3")),
    GOOL("Gool", "WireGuard داخل MASQUE؛ دو لایه", listOf("--gool")),
    MIM("MASQUE در MASQUE", "دو تونل تو در تو؛ سخت‌ترین برای فیلتر، کندتر", listOf("--mim")),
    WIREGUARD("WireGuard", "ساده و سبک؛ در ایران اغلب بسته است", listOf("--wg"));

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

enum class IpFamily(val title: String, val value: String) {
    V4("IPv4", "v4"), V6("IPv6", "v6"), BOTH("هر دو", "both");
}

data class Options(
    val transport: Transport = Transport.MASQUE_H2,
    val scan: Scan = Scan.BALANCED,
    val ip: IpFamily = IpFamily.V4,
    val fragment: Boolean = true,
    val autoFallback: Boolean = true,
    val mtu: Int = 1500,
    val port: Int = 1819,
) {
    /** The core's arguments: one SOCKS5 listener on the loopback, identity kept in [configPath]. */
    fun toArgs(configPath: String): List<String> = buildList {
        add("--bind"); add("127.0.0.1:$port")
        add("--config"); add(configPath)
        add("--log-level"); add("info")
        addAll(transport.flags)
        add("--scan"); add(scan.value)
        add("--ip"); add(ip.value)
        if (fragment) add("--fragment")
    }

    fun toJson(): String = JSONObject()
        .put("transport", transport.name)
        .put("scan", scan.name)
        .put("ip", ip.name)
        .put("fragment", fragment)
        .put("autoFallback", autoFallback)
        .toString()

    companion object {
        fun fromJson(raw: String?): Options {
            if (raw.isNullOrBlank()) return Options()
            return try {
                val j = JSONObject(raw)
                Options(
                    transport = Transport.of(j.optString("transport")) ?: Transport.MASQUE_H2,
                    scan = Scan.entries.firstOrNull { it.name == j.optString("scan") } ?: Scan.BALANCED,
                    ip = IpFamily.entries.firstOrNull { it.name == j.optString("ip") } ?: IpFamily.V4,
                    fragment = j.optBoolean("fragment", true),
                    autoFallback = j.optBoolean("autoFallback", true),
                )
            } catch (_: Exception) {
                Options()
            }
        }

        /** Order tried after the user's choice: what survives a hostile network first. */
        val RANKING = listOf(
            Transport.MASQUE_H2, Transport.GOOL, Transport.MASQUE_H3, Transport.MIM, Transport.WIREGUARD
        )

        /**
         * The transports one connection tries, at most [limit]: the user's choice as it is, then the
         * one that worked last time, then [RANKING]. The fallbacks always fragment the handshake.
         */
        fun chain(options: Options, remembered: Transport?, limit: Int = 3): List<Options> {
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
