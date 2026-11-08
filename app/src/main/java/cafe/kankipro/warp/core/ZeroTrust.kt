package cafe.kankipro.warp.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Cloudflare Zero Trust for the gamer mode, the simplest way: the user signs in to their own team
 * in a browser (https://<team>.cloudflareaccess.com/warp). That page ends on a link,
 * com.cloudflare.warp://<team>.cloudflareaccess.com/auth?token=<jwt>, which opens this app; or the
 * link or the token alone is pasted into the card. The team comes from the link or from the token
 * itself (its «iss»), so nothing else is typed. The core then registers a WireGuard identity of the
 * team next to the personal one (identity-team-<team>.toml).
 *
 * The gamer connects with it first; if it fails, the personal WARP account takes over at once.
 * Removing deletes that file and the saved team; WARP is never touched.
 */
object ZeroTrust {
    enum class Step { OFF, WORKING, ON }

    data class State(val step: Step = Step.OFF, val team: String = "", val message: String = "", val error: String = "")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private fun sp(ctx: Context) = ctx.getSharedPreferences("kwarp", Context.MODE_PRIVATE)

    /** The team name as the core writes it: lower case, without https:// or .cloudflareaccess.com. */
    fun normalize(raw: String): String = raw.trim().lowercase()
        .removePrefix("https://").removePrefix("http://").removePrefix("com.cloudflare.warp://")
        .substringBefore('/').substringBefore('?')
        .removeSuffix(".cloudflareaccess.com").trim('.')

    /** (team, token) from a pasted link or token; team is "" when neither says it. */
    fun parse(raw: String): Pair<String, String> {
        val text = raw.trim()
        val token = Regex("token=([A-Za-z0-9_\\-.]+)").find(text)?.groupValues?.get(1)
            ?: Regex("[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+").find(text)?.value
            ?: ""
        val fromLink = Regex("([a-z0-9-]+)\\.cloudflareaccess\\.com", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        val team = fromLink ?: issuer(token)
        return normalize(team.orEmpty()) to token
    }

    /** The team of a Cloudflare Access token: its «iss» is https://<team>.cloudflareaccess.com. */
    private fun issuer(jwt: String): String? = runCatching {
        val body = jwt.split('.')[1]
        val json = JSONObject(String(Base64.getUrlDecoder().decode(body.padEnd((body.length + 3) / 4 * 4, '='))))
        json.optString("iss").takeIf { "cloudflareaccess.com" in it }
    }.getOrNull()

    fun identityFile(ctx: Context, team: String): File = File(File(ctx.filesDir, "warp"), "identity-team-$team.toml")

    /** The saved team whose identity is really there, or null. */
    fun team(ctx: Context): String? = sp(ctx).getString("zt_team", null)
        ?.takeIf { it.isNotBlank() && identityFile(ctx, it).isFile }

    /** The team's WireGuard identity, or null when Zero Trust is off. */
    fun identity(ctx: Context): WarpWg? = team(ctx)?.let { WarpWg.load(identityFile(ctx, it)) }

    fun refresh(ctx: Context) {
        if (_state.value.step == Step.WORKING) return
        _state.value = team(ctx)?.let { State(Step.ON, team = it) } ?: State()
    }

    /** Joins the team with a pasted link or token (or the link that opened the app). */
    fun join(ctx: Context, raw: String) {
        val (team, token) = parse(raw)
        if (token.length < 32) { _state.value = State(error = "توکن پیدا نشد؛ کل لینک com.cloudflare.warp://… را بگذار"); return }
        if (team.isEmpty()) { _state.value = State(error = "اسم تیم در توکن نیست؛ کل لینک را بگذار"); return }
        job?.cancel()
        _state.value = State(Step.WORKING, team, message = "ساختن اکانت WireGuard تیم $team…")
        job = scope.launch {
            val err = Engine.registerSet(ctx, "wg", listOf("--team", team, "--access-token", token)) {
                identityFile(ctx, team).isFile
            }
            if (err != null || WarpWg.load(identityFile(ctx, team)) == null) {
                Engine.note("zero trust: $team failed: $err")
                _state.value = State(team = team, error = "اکانت تیم ساخته نشد: ${err ?: "فایل خالی"} (توکن چند دقیقه اعتبار دارد؛ دوباره بگیر)")
                return@launch
            }
            sp(ctx).edit().putString("zt_team", team).apply()
            Engine.note("zero trust: joined team $team")
            _state.value = State(Step.ON, team = team)
        }
    }

    /** Leaves the team on this phone: the identity file and the saved name go; WARP stays as it was. */
    fun remove(ctx: Context) {
        job?.cancel()
        val team = sp(ctx).getString("zt_team", null)
        if (team != null) identityFile(ctx, team).delete()
        sp(ctx).edit().remove("zt_team").apply()
        Engine.note("zero trust: removed${team?.let { " ($it)" } ?: ""}")
        _state.value = State()
    }
}
