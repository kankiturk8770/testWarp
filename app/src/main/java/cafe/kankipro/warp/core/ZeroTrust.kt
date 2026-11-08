package cafe.kankipro.warp.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Cloudflare Zero Trust for the gamer mode: the device joins the user's own team
 * (<team>.cloudflareaccess.com) with a one-time code sent by email, and the core saves a WireGuard
 * identity of that team next to the personal one (identity-team-<team>.toml). The gamer then
 * connects with it first; if it fails, the personal WARP account takes over at once, as before.
 *
 * The sign-in token lives only in the core's memory and is needed only to register; once the
 * identity file is there nothing else is kept. Removing deletes that file and the saved team.
 */
object ZeroTrust {
    enum class Step { OFF, CODE, WORKING, ON }

    data class State(
        val step: Step = Step.OFF,
        val team: String = "",
        val email: String = "",
        val message: String = "",
        val error: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var session = 0L
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private fun sp(ctx: Context) = ctx.getSharedPreferences("kwarp", Context.MODE_PRIVATE)

    /** The team name as the core writes it: lower case, without https:// or .cloudflareaccess.com. */
    fun normalize(raw: String): String = raw.trim().lowercase()
        .removePrefix("https://").removePrefix("http://")
        .substringBefore('/')
        .removeSuffix(".cloudflareaccess.com").trim('.')

    fun identityFile(ctx: Context, team: String): File = File(File(ctx.filesDir, "warp"), "identity-team-$team.toml")

    /** The saved team whose identity is really there, or null. */
    fun team(ctx: Context): String? = sp(ctx).getString("zt_team", null)
        ?.takeIf { it.isNotBlank() && identityFile(ctx, it).isFile }

    /** The team's WireGuard identity, or null when Zero Trust is off. */
    fun identity(ctx: Context): WarpWg? = team(ctx)?.let { WarpWg.load(identityFile(ctx, it)) }

    fun refresh(ctx: Context) {
        if (_state.value.step == Step.CODE || _state.value.step == Step.WORKING) return
        _state.value = team(ctx)?.let { State(Step.ON, team = it) } ?: State()
    }

    /** Asks the team to email a one-time code to [email]. */
    fun requestCode(ctx: Context, rawTeam: String, email: String) {
        val team = normalize(rawTeam)
        if (team.isEmpty() || !Regex("[a-z0-9-]+").matches(team)) {
            _state.value = State(error = "اسم تیم درست نیست (همان بخش اول <team>.cloudflareaccess.com)"); return
        }
        if ("@" !in email) { _state.value = State(team = team, error = "ایمیل درست نیست"); return }
        job?.cancel()
        _state.value = State(Step.WORKING, team, email.trim(), message = "درخواست کد از $team…")
        job = scope.launch {
            val r = runCatching {
                if (!Native.ensure()) error("هسته بارگذاری نشد: ${Native.loadError}")
                val payload = JSONObject().put("team", team).put("email", email.trim()).toString()
                await(Native.teamCodeRequest(payload), 60_000)
            }
            r.fold(
                onSuccess = { res ->
                    session = res.optLong("session")
                    _state.value = State(Step.CODE, team, res.optString("email", email.trim()),
                        message = "کد به ${res.optString("email", email.trim())} فرستاده شد")
                },
                onFailure = { e ->
                    Engine.note("zero trust: code request failed: ${e.message}")
                    _state.value = State(team = team, email = email, error = "کد فرستاده نشد: ${e.message}")
                },
            )
        }
    }

    /** Sends the emailed [code], then registers the team's WireGuard identity. */
    fun submitCode(ctx: Context, code: String) {
        val s = _state.value
        if (session <= 0) { _state.value = s.copy(step = Step.OFF, error = "اول کد بگیر"); return }
        job?.cancel()
        _state.value = s.copy(step = Step.WORKING, message = "بررسی کد…", error = "")
        job = scope.launch {
            try {
                val res = await(Native.teamCodeSubmit(session, code.trim()), 60_000)
                if (!res.optBoolean("signed_in")) {
                    _state.value = s.copy(step = Step.CODE, message = "", error = "کد پذیرفته نشد؛ دوباره نگاه کن")
                    return@launch
                }
                val token = res.optString("token")
                runCatching { Native.teamSessionFree(session) }
                session = 0
                _state.value = s.copy(step = Step.WORKING, message = "ساختن اکانت WireGuard تیم…", error = "")
                val err = Engine.registerSet(ctx, "wg", listOf("--team", s.team, "--access-token", token)) {
                    identityFile(ctx, s.team).isFile
                }
                if (err != null || WarpWg.load(identityFile(ctx, s.team)) == null) {
                    _state.value = s.copy(step = Step.OFF, message = "", error = "اکانت تیم ساخته نشد: ${err ?: "فایل خالی"}")
                    return@launch
                }
                sp(ctx).edit().putString("zt_team", s.team).apply()
                Engine.note("zero trust: joined team ${s.team}")
                _state.value = State(Step.ON, team = s.team)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = s.copy(step = Step.CODE, message = "", error = e.message ?: "خطا")
            }
        }
    }

    /** Leaves the team on this phone: the identity file and the saved name go; WARP stays as it was. */
    fun remove(ctx: Context) {
        job?.cancel()
        if (session > 0) runCatching { Native.teamSessionFree(session) }
        session = 0
        val team = sp(ctx).getString("zt_team", null)
        if (team != null) identityFile(ctx, team).delete()
        sp(ctx).edit().remove("zt_team").apply()
        Engine.note("zero trust: removed${team?.let { " ($it)" } ?: ""}")
        _state.value = State()
    }

    fun cancel(ctx: Context) {
        job?.cancel()
        if (session > 0) runCatching { Native.teamSessionFree(session) }
        session = 0
        refresh(ctx.also { _state.value = State() })
    }

    /** Waits for a core job started by [reply] and returns its result, or throws its error. */
    private suspend fun await(reply: String, timeoutMs: Long): JSONObject = withContext(Dispatchers.IO) {
        val start = JSONObject(reply)
        if (!start.optBoolean("ok")) error(start.optString("error", "هسته جواب نداد"))
        val id = start.optLong("job")
        val until = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < until) {
                Engine.pumpLog()
                val raw = Native.jobPoll(id) ?: error("هسته جواب نداد")
                val j = JSONObject(raw)
                if (!j.optBoolean("ok")) error(j.optString("error"))
                if (j.optString("state") != "running") {
                    val r = j.optJSONObject("result") ?: error("جواب خالی")
                    if (!r.optBoolean("ok", true)) error(r.optString("error"))
                    return@withContext r
                }
                delay(250)
            }
            error("کلادفلر در ${timeoutMs / 1000} ثانیه جواب نداد")
        } finally {
            runCatching { Native.jobFree(id) }
        }
    }
}
