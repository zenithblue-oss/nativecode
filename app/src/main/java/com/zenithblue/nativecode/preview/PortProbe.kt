package com.zenithblue.nativecode.preview

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Dev-server discovery (plan 12). PRoot shares the app's network namespace, so a guest server on
 * 127.0.0.1:PORT is reachable from the app. Two cheap sources: URLs printed in terminal / chat output,
 * and a short connect probe of common dev ports. No /proc/net/tcp (unreadable for apps).
 */
object PortProbe {
    val COMMON = listOf(3000, 3001, 4200, 4321, 5000, 5173, 5174, 8000, 8080, 8081, 8787, 8888, 1313)

    private val URL = Regex("""https?://(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]):(\d{2,5})(/[^\s"'<>`)\]]*)?""", RegexOption.IGNORE_CASE)

    /** Loopback dev-server URLs in [text], normalised to http://127.0.0.1:PORT/path (0.0.0.0 is not browsable). */
    fun findUrls(text: String): List<String> = URL.findAll(text).mapNotNull { m ->
        val port = m.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
        val scheme = if (m.value.startsWith("https", ignoreCase = true)) "https" else "http"
        "$scheme://127.0.0.1:$port" + m.groupValues[3].trimEnd('.', ',', ';', ':').ifEmpty { "/" }
    }.distinct().toList()

    fun portOf(url: String): Int? = runCatching { java.net.URI(url.trim()).port }.getOrNull()?.takeIf { it > 0 }

    /** Only loopback hosts may load inside the in-app WebView; everything else opens in the browser. */
    fun isLoopback(url: String): Boolean {
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase()?.removeSurrounding("[", "]") ?: return false
        val scheme = url.trim().substringBefore(':').lowercase()
        return scheme in setOf("http", "https") && host in setOf("127.0.0.1", "localhost", "::1")
    }

    fun isOpen(port: Int, timeoutMs: Int = 150): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs) }; true
    }.getOrDefault(false)

    /** Listening ports among [ports], probed in parallel (blocking; call off the main thread). */
    fun probe(ports: Collection<Int> = COMMON): List<Int> {
        val pool = Executors.newFixedThreadPool(ports.size.coerceIn(1, 16))
        return try {
            pool.invokeAll(ports.map { p -> Callable { p.takeIf { isOpen(it) } } }, 2, TimeUnit.SECONDS)
                .mapNotNull { f -> runCatching { f.get() }.getOrNull() }.sorted()
        } finally { pool.shutdownNow() }
    }

    private val seen = LinkedHashSet<Int>() // ports seen in output this process (terminal + chat)

    /** True the first time [port] is seen this process. */
    @Synchronized fun remember(port: Int): Boolean = seen.add(port)
    @Synchronized fun seenPorts(): List<Int> = seen.toList()
}
