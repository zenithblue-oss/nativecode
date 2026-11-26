package com.zenithblue.nativecode.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.zenithblue.nativecode.agent.chat.AgentChats
import com.zenithblue.nativecode.agent.chat.store.ChatDb
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's own data that lives outside the guest home (chat history) and so is not in `tar` of the home: staged into
 * `<guest home>/[BackupPlan.APP_DIR]` before a backup, merged back after a restore. Chat rows are never copied as a live
 * WAL file: the snapshot is built row by row on the chat writer thread (one writer, so it is consistent) into a fresh
 * [ChatDb], and a restore reads the snapshot through [ChatDb] too, so an older `user_version` goes through the normal migration.
 */
object AppDataBackup {
    private const val SNAP = "agent_chat_backup.db"
    private const val SNAP_TIMEOUT_S = 120L
    private val TABLES = listOf("threads" to "id", "runs" to "thread_id", "turn_items" to "thread_id", "requests" to "thread_id")

    /** Files under `filesDir/agent` that travel with the chat DB: model lists (a cache) and delegated-task links. */
    private fun agentFiles(ctx: Context): List<File> =
        File(ctx.filesDir, "agent").listFiles { f -> f.isFile && (f.name == "nc-tasks.json" || (f.name.startsWith("models-") && f.name.endsWith(".json"))) }?.toList() ?: emptyList()

    /** Fills [dest] (cleared first). Returns false when there was nothing to stage or it failed; never throws. */
    fun stage(ctx: Context, dest: File): Boolean = try {
        dest.deleteRecursively()
        File(dest, "agent").mkdirs()
        var any = false
        for (f in agentFiles(ctx)) { f.copyTo(File(dest, "agent/${f.name}"), overwrite = true); any = true }
        if (ctx.getDatabasePath(ChatDb.NAME).exists()) any = snapshotChat(ctx, File(dest, ChatDb.NAME)) || any
        if (!any) dest.deleteRecursively()
        any
    } catch (_: Exception) {
        dest.deleteRecursively()
        false
    }

    private fun snapshotChat(ctx: Context, out: File): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        AgentChats.store(ctx).importWith({ live ->
            ctx.deleteDatabase(SNAP)
            val h = ChatDb(ctx, SNAP)
            try {
                val d = h.writableDatabase
                mergeChat(d, live)
                d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            } finally { h.close() }
            ctx.getDatabasePath(SNAP).copyTo(out, overwrite = true)
            emptySet()
        }) { r -> ok = r.isSuccess; latch.countDown() }
        latch.await(SNAP_TIMEOUT_S, TimeUnit.SECONDS)
        ctx.deleteDatabase(SNAP)
        return ok
    }

    /**
     * Chat DB merge: every thread of [src] whose id is not in [dst] comes over with its runs, items and requests
     * (one transaction). Threads already in [dst] are left alone, so a restore never loses newer local work.
     * Returns the imported thread ids.
     */
    fun mergeChat(dst: SQLiteDatabase, src: SQLiteDatabase): Set<String> {
        val have = HashSet<String>()
        dst.rawQuery("SELECT id FROM threads", null).use { while (it.moveToNext()) have += it.getString(0) }
        val fresh = LinkedHashSet<String>()
        src.rawQuery("SELECT id FROM threads", null).use { while (it.moveToNext()) it.getString(0).let { id -> if (id !in have) fresh += id } }
        if (fresh.isEmpty()) return fresh
        dst.beginTransaction()
        try {
            for ((table, key) in TABLES) src.query(table, null, null, null, null, null, null).use { c ->
                val k = c.getColumnIndexOrThrow(key)
                while (c.moveToNext()) if (c.getString(k) in fresh) dst.insertWithOnConflict(table, null, row(c), SQLiteDatabase.CONFLICT_IGNORE)
            }
            dst.setTransactionSuccessful()
        } finally { dst.endTransaction() }
        return fresh
    }

    private fun row(c: Cursor) = ContentValues().apply {
        for (i in 0 until c.columnCount) {
            val n = c.getColumnName(i)
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> put(n, c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> put(n, c.getDouble(i))
                Cursor.FIELD_TYPE_STRING -> put(n, c.getString(i))
                Cursor.FIELD_TYPE_BLOB -> put(n, c.getBlob(i))
                else -> putNull(n)
            }
        }
    }

    /**
     * Restore from an extracted [srcDir]: files the device already has are kept (the model lists are a cache and the
     * task links are tied to local threads), then the chat threads are merged by id on the live store. [then] gets the
     * number of threads added, or the failure (nothing is half-merged: the merge is one transaction).
     */
    fun restore(ctx: Context, srcDir: File, then: (Result<Int>) -> Unit) {
        runCatching {
            for (f in File(srcDir, "agent").listFiles() ?: emptyArray()) {
                val d = File(ctx.filesDir, "agent/${f.name}")
                if (f.isFile && !d.exists()) { d.parentFile?.mkdirs(); f.copyTo(d) }
            }
        }
        val snap = File(srcDir, ChatDb.NAME)
        if (!snap.exists()) { then(Result.success(0)); return }
        runCatching {
            ctx.deleteDatabase(SNAP)
            val target = ctx.getDatabasePath(SNAP)
            target.parentFile?.mkdirs()
            snap.copyTo(target, overwrite = true)
        }.onFailure { then(Result.failure(it)); return }
        AgentChats.store(ctx).importWith({ live ->
            val h = ChatDb(ctx, SNAP)
            try { mergeChat(live, h.writableDatabase) } finally { h.close() } // opening migrates an older snapshot; a newer one throws
        }) { r ->
            ctx.deleteDatabase(SNAP)
            then(r.map { it.size })
        }
    }
}
