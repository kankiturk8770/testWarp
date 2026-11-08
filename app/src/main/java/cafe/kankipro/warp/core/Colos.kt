package cafe.kankipro.warp.core

/**
 * Cloudflare data centres by IATA code, for the countries that matter to a player in Iran:
 * Germany (Frankfurt first: the shortest path from Iran) and Turkey. Anything else is «other».
 */
object Colos {
    val GERMANY = setOf("FRA", "DUS", "HAM", "MUC", "BER", "STR", "TXL", "CGN", "LEJ", "NUE")
    val TURKEY = setOf("IST", "SAW", "ADB", "ESB", "AYT")

    /** Two-letter country of a data centre, or "" when it is not one of ours. */
    fun country(colo: String): String = when (colo.uppercase()) {
        in GERMANY -> "DE"
        in TURKEY -> "TR"
        "" -> ""
        else -> "??"
    }

    val NAMES = mapOf(
        "FRA" to "فرانکفورت", "DUS" to "دوسلدورف", "HAM" to "هامبورگ", "MUC" to "مونیخ", "BER" to "برلین",
        "STR" to "اشتوتگارت", "TXL" to "برلین", "IST" to "استانبول", "SAW" to "استانبول", "ADB" to "ازمیر",
        "ESB" to "آنکارا", "AYT" to "آنتالیا", "MXP" to "میلان", "AMS" to "آمستردام", "VIE" to "وین",
        "WAW" to "ورشو", "SOF" to "صوفیه", "OTP" to "بخارست", "ATH" to "آتن", "CDG" to "پاریس", "LHR" to "لندن",
        "ARN" to "استکهلم", "PRG" to "پراگ", "BUD" to "بوداپست", "ZRH" to "زوریخ", "DXB" to "دبی",
    )

    /** Best first: Frankfurt, then the rest of Germany, by gaming score inside each. */
    fun germanyFirst(hits: List<WgScan.Hit>): List<WgScan.Hit> =
        hits.filter { it.country == "DE" }.sortedWith(compareBy({ it.colo != "FRA" }, { it.score }))

    fun turkey(hits: List<WgScan.Hit>): List<WgScan.Hit> = hits.filter { it.country == "TR" }.sortedBy { it.score }
    fun others(hits: List<WgScan.Hit>): List<WgScan.Hit> = hits.filter { it.country != "DE" && it.country != "TR" }.sortedBy { it.score }
}
