package cafe.kankipro.warp.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The normal mode's white-list scan: for one transport, look through many Cloudflare addresses for
 * one that still answers from this network and pin it (Options.pins), so the next connect goes
 * there without the core's own short scan. Amnezia and WireGuard: a real WireGuard handshake over
 * a wide sweep (WgScan); MASQUE over HTTP/2: a TLS handshake on 443 (H2Scan).
 */
object PinScan {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    data class State(
        val transport: Transport? = null,
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val found: Int = 0,
        /** One plain sentence about the last result. */
        val result: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun canScan(t: Transport) = t == Transport.AMNEZIA || t == Transport.WIREGUARD || t == Transport.MASQUE_H2

    /** Scans for [t]; [onPin] gets the best endpoint when one answered. */
    fun start(ctx: Context, t: Transport, onPin: (String, Int) -> Unit) {
        if (_state.value.running || !canScan(t)) return
        _state.value = State(transport = t, running = true)
        job = scope.launch {
            val progress = { d: Int, n: Int, f: Int -> _state.value = _state.value.copy(done = d, total = n, found = f) }
            val hits = when (t) {
                Transport.MASQUE_H2 -> H2Scan.scan(onProgress = progress)
                else -> {
                    val err = Engine.register(ctx, Transport.WIREGUARD)
                    val id = WarpWg.load(ctx)
                    if (id == null) {
                        _state.value = State(transport = t, result = "اکانت ساخته نشد: ${err ?: "?"}")
                        return@launch
                    }
                    WgScan.scan(id, t == Transport.AMNEZIA, wide = 1500, keep = 6, samples = 3, onProgress = progress)
                }
            }
            val best = hits.firstOrNull()
            if (best != null) {
                onPin(best.endpoint, best.pingMs)
                Engine.note("white scan ${t.title}: ${hits.size} answered, pinned ${best.endpoint} (${best.pingMs} ms)")
            }
            _state.value = _state.value.copy(
                running = false,
                result = if (best != null) "${hits.size} آدرس جواب داد؛ بهترین گذاشته شد: ${best.endpoint} (${best.pingMs} ms)"
                else "هیچ آدرسی از این شبکه جواب نداد",
            )
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(running = false, result = "اسکن متوقف شد")
    }
}
