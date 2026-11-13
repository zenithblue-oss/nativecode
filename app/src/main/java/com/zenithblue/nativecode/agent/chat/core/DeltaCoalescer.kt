package com.zenithblue.nativecode.agent.chat.core

/**
 * Per-key 50 ms coalescing of full snapshots (t3code `ProviderTextDeltaCoalescer`): a burst of upserts of one
 * item becomes its latest snapshot once per window. [flush] first before any other event so order holds.
 * Not thread-safe: call from the controller's loop only. [schedule] runs its block later on that same loop.
 */
class DeltaCoalescer<T>(
    private val windowMs: Long = 50,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    private val emit: (T) -> Unit,
) {
    private val pending = LinkedHashMap<String, T>()
    private var scheduled = false

    fun offer(key: String, value: T) {
        pending[key] = value
        if (!scheduled) {
            scheduled = true
            schedule(windowMs) { scheduled = false; flush() }
        }
    }

    fun flush() {
        if (pending.isEmpty()) return
        val out = pending.values.toList()
        pending.clear()
        out.forEach(emit)
    }

    val isEmpty: Boolean get() = pending.isEmpty()
}
