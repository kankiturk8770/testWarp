package cafe.kankipro.warp.core

import android.content.Context

/** Settings and the last transport that produced a verified tunnel. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("kwarp", Context.MODE_PRIVATE)

    fun options(ctx: Context): Options = Options.fromJson(sp(ctx).getString("options", null))
    fun save(ctx: Context, o: Options) = sp(ctx).edit().putString("options", o.toJson()).apply()

    fun lastGood(ctx: Context): Transport? = Transport.of(sp(ctx).getString("last_good", null))
    fun rememberGood(ctx: Context, t: Transport) = sp(ctx).edit().putString("last_good", t.name).apply()

    /** The gamer scan's best gateways for one network (WgScan.Hit as «endpoint,ping,jitter,loss» lines). */
    fun gameHits(ctx: Context, network: String): List<WgScan.Hit> =
        sp(ctx).getString("game:$network", null).orEmpty().lines().mapNotNull { l ->
            val p = l.split(',')
            if (p.size != 4) null else runCatching { WgScan.Hit(p[0], p[1].toInt(), p[2].toInt(), p[3].toInt()) }.getOrNull()
        }

    fun saveGameHits(ctx: Context, network: String, hits: List<WgScan.Hit>) = sp(ctx).edit()
        .putString("game:$network", hits.joinToString("\n") { "${it.endpoint},${it.pingMs},${it.jitterMs},${it.lossPct}" })
        .apply()
}
