package com.zenithblue.nativecode.agent.chat.store

import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.TurnItem

/** One store mutation. Pure data so the reducer and recovery can be tested without SQLite; [ChatStore.apply] runs a batch in one transaction. */
sealed interface ChatWrite {
    val threadId: String

    data class PutThread(val t: ChatThread) : ChatWrite { override val threadId get() = t.id }
    class UpdateThread(override val threadId: String, val f: (ChatThread) -> ChatThread) : ChatWrite
    data class PutRun(val r: Run) : ChatWrite { override val threadId get() = r.threadId }
    class UpdateRun(override val threadId: String, val id: String, val f: (Run) -> Run) : ChatWrite
    /** Upsert by id, else by (thread, native_ref): a replayed native item updates its old row. */
    data class PutItem(val i: TurnItem) : ChatWrite { override val threadId get() = i.threadId }
    class UpdateItem(override val threadId: String, val id: String, val f: (TurnItem) -> TurnItem) : ChatWrite
    data class PutRequest(val r: ChatRequest) : ChatWrite { override val threadId get() = r.threadId }
    /** Items of [runId] still pending/running/waiting get [status]. */
    data class SettleItems(override val threadId: String, val runId: String, val status: ItemStatus) : ChatWrite
    /** A queued run and its items (Queued sheet › Remove). */
    data class DeleteRun(override val threadId: String, val id: String) : ChatWrite
    data class DeleteThread(override val threadId: String) : ChatWrite
}

/** Startup reconcile (plan 9.2.8 "Process death and restore"), as pure decisions over what the store found. */
object ChatRecovery {
    const val SESSION_ENDED = "Session ended"

    /**
     * [active] = runs left starting/running/waiting; [pending] = pending requests. Runs → interrupted + a
     * "Session ended" notice; approvals → expired; user-input questions stay pending (answered as a new message);
     * queued runs are untouched (the controller holds them).
     */
    fun plan(active: List<Run>, pending: List<ChatRequest>, nextOrdinal: (String) -> Int, now: Long, newId: () -> String): List<ChatWrite> {
        val out = mutableListOf<ChatWrite>()
        val ords = HashMap<String, Int>()
        for (r in active.filter { it.status.active }) {
            out += ChatWrite.PutRun(r.copy(status = RunStatus.INTERRUPTED, completedAt = now))
            out += ChatWrite.SettleItems(r.threadId, r.id, ItemStatus.INTERRUPTED)
            val ord = ords.getOrPut(r.threadId) { nextOrdinal(r.threadId) }
            ords[r.threadId] = ord + 1
            out += ChatWrite.PutItem(TurnItem(newId(), r.threadId, r.id, ord, null, null, ItemStatus.COMPLETED, ItemPayload.SystemNotice(SESSION_ENDED), now))
        }
        for (q in pending.filter { it.status == RequestStatus.PENDING && it.kind != RequestKind.USER_INPUT }) {
            out += ChatWrite.PutRequest(q.copy(status = RequestStatus.EXPIRED, resolvedAt = now))
            q.itemId?.let { out += ChatWrite.UpdateItem(q.threadId, it) { i -> i.copy(status = ItemStatus.CANCELLED, updatedAt = now) } }
        }
        return out
    }
}
