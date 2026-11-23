package com.zenithblue.nativecode.agent.chat.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** `agent_chat.db` (plan 9.2.5): projection tables only, WAL, foreign keys on, `user_version` migrations here. */
class ChatDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, NAME, null, VERSION) {

    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) { (SCHEMA_V1 + MIGRATE_V2).forEach(db::execSQL) }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Add `if (oldVersion < N) { ... }` steps here, in order. v1 is the first shipped schema.
        if (oldVersion < 2) MIGRATE_V2.forEach(db::execSQL)
    }

    companion object {
        const val NAME = "agent_chat.db"
        const val VERSION = 2

        /**
         * v2 (unified chat, provider switch mid-thread): nullable columns only, so old rows read as before. Until now a thread
         * never changed tool, so every old run belongs to its thread's tool (exact); the model is unknown and stays null.
         */
        val MIGRATE_V2 = listOf(
            "ALTER TABLE threads ADD COLUMN handoff_from TEXT",
            "ALTER TABLE runs ADD COLUMN tool TEXT",
            "ALTER TABLE runs ADD COLUMN model TEXT",
            "UPDATE runs SET tool=(SELECT tool FROM threads WHERE threads.id=runs.thread_id)",
        )

        val SCHEMA_V1 = listOf(
            """CREATE TABLE threads(
  id TEXT PRIMARY KEY,
  project_path TEXT,
  cwd TEXT NOT NULL,
  tool TEXT NOT NULL,
  title TEXT NOT NULL,
  native_id TEXT,
  model TEXT, effort TEXT,
  runtime_mode TEXT NOT NULL DEFAULT 'approval-required',
  interaction_mode TEXT NOT NULL DEFAULT 'default',
  draft_json TEXT,
  pinned_at INTEGER, archived_at INTEGER, last_visited_at INTEGER,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
            "CREATE INDEX threads_scope ON threads(project_path, archived_at, updated_at)",
            """CREATE TABLE runs(
  id TEXT PRIMARY KEY,
  thread_id TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  status TEXT NOT NULL,
  queue_position INTEGER,
  native_turn_id TEXT,
  failure_json TEXT,
  usage_json TEXT,
  chk_base TEXT, chk_end TEXT,
  diff_stat_json TEXT,
  started_at INTEGER, completed_at INTEGER,
  UNIQUE(thread_id, ordinal))""",
            """CREATE TABLE turn_items(
  id TEXT PRIMARY KEY,
  thread_id TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
  run_id TEXT, ordinal INTEGER NOT NULL,
  native_ref TEXT,
  parent_id TEXT,
  type TEXT NOT NULL,
  status TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  updated_at INTEGER NOT NULL)""",
            "CREATE INDEX items_thread ON turn_items(thread_id, ordinal)",
            "CREATE UNIQUE INDEX items_native ON turn_items(thread_id, native_ref) WHERE native_ref IS NOT NULL",
            """CREATE TABLE requests(
  id TEXT PRIMARY KEY,
  thread_id TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
  run_id TEXT, item_id TEXT,
  native_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  status TEXT NOT NULL,
  options_json TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  answer_json TEXT,
  created_at INTEGER NOT NULL, resolved_at INTEGER)""",
            "CREATE INDEX requests_open ON requests(thread_id, status)",
        )
    }
}
