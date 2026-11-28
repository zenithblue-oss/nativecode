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
     * Every build plays through AAudio, with OpenSL ES (module-sles-sink) only as the fallback when it fails to load.
     * The stock sles sink alone leaves only the null sink on phones where a linker64-spawned process cannot load
     * OpenSL ES vendor libs (MIUI: libcodec2_client / libskuparser).
     */
    const val SINK_MODULE = "module-aaudio-sink"
    const val FALLBACK_SINK_MODULE = "module-sles-sink"

    /**
     * PulseAudio's `LD_LIBRARY_PATH`: system libs first. The bootstrap's own liblzma/libz/libcrypto/libexpat in PREFIX/lib
     * shadow the system ones that libaaudio/libOpenSLES pull in (libunwindstack -> Xzs_Construct), so either sink fails
     * with "Failed to open module" when PREFIX/lib leads. Scoped to the pulseaudio/pactl/pacmd processes only.
     */
    fun ldPath(lib: String): String = "/system/lib64:$lib"

    /** True when `pactl list short sinks` printed no sink row ("<index>\t<name>..."), so the OpenSL ES fallback should be tried. */
    fun needsFallback(shortSinks: String): Boolean = shortSinks.lines().none { it.firstOrNull()?.isDigit() == true }

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
