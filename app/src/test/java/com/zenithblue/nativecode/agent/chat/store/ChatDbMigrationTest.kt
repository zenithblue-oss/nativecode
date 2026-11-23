package com.zenithblue.nativecode.agent.chat.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 -> v2 (unified chat). SQLite is not on the JVM test classpath (no Robolectric), so this pins the statements:
 * nullable ADD COLUMNs only (old rows read as before), old runs attributed to their thread's tool, never a rewrite.
 * The statements were also run against a v1 database with the sqlite3 library (see the commit message).
 */
class ChatDbMigrationTest {
    @Test fun versionIsTwo() = assertEquals(2, ChatDb.VERSION)

    @Test fun v2OnlyAddsNullableColumns() {
        val adds = ChatDb.MIGRATE_V2.filter { it.startsWith("ALTER TABLE") }
        assertEquals(
            listOf("ALTER TABLE threads ADD COLUMN handoff_from TEXT", "ALTER TABLE runs ADD COLUMN tool TEXT", "ALTER TABLE runs ADD COLUMN model TEXT"),
            adds,
        )
        assertTrue(adds.none { it.contains("NOT NULL") || it.contains("DEFAULT") })
    }

    @Test fun oldRunsAreAttributedToTheirThreadsToolAndNothingIsDeleted() {
        val upd = ChatDb.MIGRATE_V2.filter { it.startsWith("UPDATE") }.single()
        assertEquals("UPDATE runs SET tool=(SELECT tool FROM threads WHERE threads.id=runs.thread_id)", upd)
        assertTrue(ChatDb.MIGRATE_V2.none { it.startsWith("DROP") || it.startsWith("DELETE") })
        // the columns are added before they are filled
        assertTrue(ChatDb.MIGRATE_V2.indexOfFirst { it.startsWith("UPDATE") } > ChatDb.MIGRATE_V2.indexOfFirst { it.contains("runs ADD COLUMN tool") })
    }

    @Test fun v1SchemaIsUntouched() {
        assertFalse(ChatDb.SCHEMA_V1.any { it.contains("handoff_from") || it.contains("runs.tool") })
        assertTrue(ChatDb.SCHEMA_V1.first().contains("CREATE TABLE threads"))
    }
}
