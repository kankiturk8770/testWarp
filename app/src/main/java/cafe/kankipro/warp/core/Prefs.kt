package cafe.kankipro.warp.core

import android.content.Context

/** Settings and the last transport that produced a verified tunnel. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("kwarp", Context.MODE_PRIVATE)

    fun options(ctx: Context): Options = Options.fromJson(sp(ctx).getString("options", null))
    fun save(ctx: Context, o: Options) = sp(ctx).edit().putString("options", o.toJson()).apply()

    fun lastGood(ctx: Context): Transport? = Transport.of(sp(ctx).getString("last_good", null))
    fun rememberGood(ctx: Context, t: Transport) = sp(ctx).edit().putString("last_good", t.name).apply()
}
