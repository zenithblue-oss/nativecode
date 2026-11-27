package com.zenithblue.nativecode.terminal

/**
 * Host PulseAudio TCP endpoint. Abstract/loopback ports are shared by every installed NativeCode build, so two
 * apps (Play + F-Droid) cannot both own 4713: Play keeps the stock port (and its stock config strings), any other
 * applicationId gets a fixed port derived from its id (stable across restarts and updates).
 */
object PulseHost {
    const val DEFAULT_PORT = 4713
    private const val PLAY_ID = "com.zenithblue.nativecode"

    fun portFor(applicationId: String): Int =
        if (applicationId == PLAY_ID) DEFAULT_PORT else DEFAULT_PORT + 1 + (applicationId.hashCode() and 0x7fffffff) % 200

    val PORT: Int = portFor(TermuxHostPaths.PACKAGE)

    /**
     * Non-Play builds play through AAudio: termux's default module-sles-sink needs OpenSL ES vendor libs that a
     * linker64-spawned process cannot load (MIUI: libcodec2_client / libskuparser), which leaves only the null sink.
     * Play keeps its stock sles setup untouched.
     */
    val USES_AAUDIO: Boolean = PORT != DEFAULT_PORT

    /** Guest `PULSE_SERVER` value; Play stays the bare "127.0.0.1" it always was. */
    fun server(port: Int = PORT): String = if (port == DEFAULT_PORT) "127.0.0.1" else "127.0.0.1:$port"

    /** Arguments after `module-native-protocol-tcp`; Play's string is unchanged. */
    fun tcpArgs(port: Int = PORT): String =
        "auth-ip-acl=127.0.0.1 auth-anonymous=1" + if (port == DEFAULT_PORT) "" else " port=$port"

    private val SERVER_LINE = Regex("""(PULSE_SERVER=)(['"]?)(tcp:)?127\.0\.0\.1(?::\d+)?\2""")

    /** [text] with every PULSE_SERVER=127.0.0.1[:port] assignment pointing at [port]; other text is untouched. */
    fun retarget(text: String, port: Int = PORT): String =
        SERVER_LINE.replace(text) { m -> "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}${server(port)}${m.groupValues[2]}" }
}
