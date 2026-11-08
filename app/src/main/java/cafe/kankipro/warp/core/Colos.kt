package cafe.kankipro.warp.core

/**
 * Cloudflare data centres by IATA code: the country of each (for the flag and the grouping) and a
 * Persian city name. The gamer list shows Turkey and Germany first (Frankfurt first: the shortest
 * path from Iran), then every other country found, each with its flag. A code not listed here
 * still shows, under «نامعلوم».
 */
object Colos {
    /** Data centre → country, for Europe, the Middle East and Iran's neighbours (where a player's ping is good). */
    val COUNTRY: Map<String, String> = buildMap {
        fun at(cc: String, vararg codes: String) = codes.forEach { this[it] = cc }
        at("DE", "FRA", "DUS", "HAM", "MUC", "BER", "STR", "TXL", "CGN", "LEJ", "NUE")
        at("TR", "IST", "SAW", "ADB", "ESB", "AYT")
        at("IT", "MXP", "FCO", "PMO", "LIN")
        at("NL", "AMS")
        at("AT", "VIE")
        at("PL", "WAW")
        at("BG", "SOF")
        at("RO", "OTP")
        at("GR", "ATH", "SKG")
        at("FR", "CDG", "MRS")
        at("GB", "LHR", "MAN", "EDI")
        at("SE", "ARN", "GOT")
        at("CZ", "PRG")
        at("HU", "BUD")
        at("CH", "ZRH", "GVA")
        at("AE", "DXB", "FJR", "AUH")
        at("QA", "DOH")
        at("BH", "BAH")
        at("KW", "KWI")
        at("OM", "MCT")
        at("SA", "RUH", "JED", "DMM")
        at("IQ", "BGW", "BSR", "EBL", "ISU", "NJF", "XNH")
        at("GE", "TBS")
        at("AM", "EVN")
        at("AZ", "GYD")
        at("KZ", "ALA", "NQZ")
        at("UZ", "TAS")
        at("PK", "KHI", "ISB", "LHE")
        at("IN", "BOM", "DEL", "MAA", "BLR", "HYD", "CCU")
        at("CY", "LCA")
        at("IL", "TLV")
        at("JO", "AMM")
        at("LB", "BEY")
        at("EG", "CAI")
        at("UA", "KBP")
        at("MD", "KIV")
        at("RS", "BEG")
        at("HR", "ZAG")
        at("SI", "LJU")
        at("SK", "BTS")
        at("DK", "CPH")
        at("FI", "HEL")
        at("NO", "OSL")
        at("ES", "MAD", "BCN")
        at("PT", "LIS")
        at("BE", "BRU")
        at("IE", "DUB")
        at("LU", "LUX")
        at("LV", "RIX")
        at("LT", "VNO")
        at("EE", "TLL")
        at("RU", "DME", "LED")
        at("SG", "SIN")
    }

    /** Two-letter country of a data centre, "" before it is known, "??" when it is not in the list. */
    fun country(colo: String): String = when {
        colo.isBlank() -> ""
        else -> COUNTRY[colo.uppercase()] ?: "??"
    }

    val NAMES = mapOf(
        "FRA" to "فرانکفورت", "DUS" to "دوسلدورف", "HAM" to "هامبورگ", "MUC" to "مونیخ", "BER" to "برلین",
        "STR" to "اشتوتگارت", "TXL" to "برلین", "IST" to "استانبول", "SAW" to "استانبول", "ADB" to "ازمیر",
        "ESB" to "آنکارا", "AYT" to "آنتالیا", "MXP" to "میلان", "FCO" to "رم", "PMO" to "پالرمو", "AMS" to "آمستردام",
        "VIE" to "وین", "WAW" to "ورشو", "SOF" to "صوفیه", "OTP" to "بخارست", "ATH" to "آتن", "SKG" to "تسالونیکی",
        "CDG" to "پاریس", "MRS" to "مارسی", "LHR" to "لندن", "MAN" to "منچستر", "ARN" to "استکهلم", "PRG" to "پراگ",
        "BUD" to "بوداپست", "ZRH" to "زوریخ", "GVA" to "ژنو", "DXB" to "دبی", "FJR" to "فجیره", "AUH" to "ابوظبی",
        "DOH" to "دوحه", "BAH" to "بحرین", "KWI" to "کویت", "MCT" to "مسقط", "RUH" to "ریاض", "JED" to "جده",
        "DMM" to "دمام", "BGW" to "بغداد", "BSR" to "بصره", "EBL" to "اربیل", "ISU" to "سلیمانیه", "NJF" to "نجف",
        "TBS" to "تفلیس", "EVN" to "ایروان", "GYD" to "باکو", "ALA" to "آلماتی", "TAS" to "تاشکند", "KHI" to "کراچی",
        "ISB" to "اسلام‌آباد", "LHE" to "لاهور", "BOM" to "بمبئی", "DEL" to "دهلی", "LCA" to "لارناکا", "TLV" to "تل‌آویو",
        "AMM" to "امان", "BEY" to "بیروت", "CAI" to "قاهره", "KBP" to "کی‌یف", "KIV" to "کیشیناو", "BEG" to "بلگراد",
        "ZAG" to "زاگرب", "LJU" to "لیوبلیانا", "BTS" to "براتیسلاوا", "CPH" to "کپنهاگ", "HEL" to "هلسینکی",
        "OSL" to "اسلو", "MAD" to "مادرید", "BCN" to "بارسلونا", "LIS" to "لیسبون", "BRU" to "بروکسل", "DUB" to "دوبلین",
        "LUX" to "لوکزامبورگ", "RIX" to "ریگا", "VNO" to "ویلنیوس", "TLL" to "تالین", "DME" to "مسکو", "LED" to "سن‌پترزبورگ",
        "SIN" to "سنگاپور",
    )

    /** Persian country names for the group titles. */
    val COUNTRY_NAMES = mapOf(
        "DE" to "آلمان", "TR" to "ترکیه", "IT" to "ایتالیا", "NL" to "هلند", "AT" to "اتریش", "PL" to "لهستان",
        "BG" to "بلغارستان", "RO" to "رومانی", "GR" to "یونان", "FR" to "فرانسه", "GB" to "انگلیس", "SE" to "سوئد",
        "CZ" to "چک", "HU" to "مجارستان", "CH" to "سوئیس", "AE" to "امارات", "QA" to "قطر", "BH" to "بحرین",
        "KW" to "کویت", "OM" to "عمان", "SA" to "عربستان", "IQ" to "عراق", "GE" to "گرجستان", "AM" to "ارمنستان",
        "AZ" to "آذربایجان", "KZ" to "قزاقستان", "UZ" to "ازبکستان", "PK" to "پاکستان", "IN" to "هند", "CY" to "قبرس",
        "IL" to "اسرائیل", "JO" to "اردن", "LB" to "لبنان", "EG" to "مصر", "UA" to "اوکراین", "MD" to "مولداوی",
        "RS" to "صربستان", "HR" to "کرواسی", "SI" to "اسلوونی", "SK" to "اسلواکی", "DK" to "دانمارک", "FI" to "فنلاند",
        "NO" to "نروژ", "ES" to "اسپانیا", "PT" to "پرتغال", "BE" to "بلژیک", "IE" to "ایرلند", "LU" to "لوکزامبورگ",
        "LV" to "لتونی", "LT" to "لیتوانی", "EE" to "استونی", "RU" to "روسیه", "SG" to "سنگاپور",
    )

    fun countryName(cc: String): String = COUNTRY_NAMES[cc] ?: "نامعلوم"

    /** Best first: Frankfurt, then the rest of Germany, by gaming score inside each. */
    fun germanyFirst(hits: List<WgScan.Hit>): List<WgScan.Hit> =
        hits.filter { it.country == "DE" }.sortedWith(compareBy({ it.colo != "FRA" }, { it.score }))

    fun turkey(hits: List<WgScan.Hit>): List<WgScan.Hit> = hits.filter { it.country == "TR" }.sortedBy { it.score }
    fun others(hits: List<WgScan.Hit>): List<WgScan.Hit> = hits.filter { it.country != "DE" && it.country != "TR" }.sortedBy { it.score }

    /** Every other country found, each with its gateways best first, the countries by their best score. */
    fun otherCountries(hits: List<WgScan.Hit>): List<Pair<String, List<WgScan.Hit>>> =
        others(hits).groupBy { it.country.ifBlank { "??" } }.toList()
            .sortedBy { (_, list) -> list.minOf { it.score } }
}
