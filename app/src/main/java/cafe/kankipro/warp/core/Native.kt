package cafe.kankipro.warp.core

/** JNI functions of libkwarp.so (src/main/cpp/bridge.c). */
object Native {
    private var loaded: Boolean? = null
    var loadError: String? = null
        private set

    /** Loads the bridge and, through it, libaether.so and libhev-socks5-tunnel.so. */
    @Synchronized
    fun ensure(): Boolean {
        loaded?.let { return it }
        val ok = try {
            System.loadLibrary("kwarp")
            loadError = nativeLoad()
            loadError == null
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            false
        }
        loaded = ok
        return ok
    }

    @JvmStatic private external fun nativeLoad(): String?
    @JvmStatic external fun coreVersion(): String?
    @JvmStatic external fun coreStart(argsJson: String): String
    @JvmStatic external fun jobPoll(id: Long): String?
    @JvmStatic external fun jobCancel(id: Long): String?
    @JvmStatic external fun jobFree(id: Long): String?
    /** Zero Trust email code: {"team","email"} → a job whose result has the session id. */
    @JvmStatic external fun teamCodeRequest(payloadJson: String): String
    /** The code from the email → a job whose result says signed_in and the token. */
    @JvmStatic external fun teamCodeSubmit(session: Long, code: String): String
    @JvmStatic external fun teamSessionFree(session: Long): String?
    @JvmStatic external fun hevRun(configPath: String, tunFd: Int): Int
    @JvmStatic external fun hevStop()
    /** tx packets, tx bytes, rx packets, rx bytes. */
    @JvmStatic external fun hevStats(): LongArray?
    @JvmStatic external fun drainLog(): String?
}
