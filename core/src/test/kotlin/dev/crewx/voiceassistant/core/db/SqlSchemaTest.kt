package dev.crewx.voiceassistant.core.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Verifies the shipped schema against a real SQLite.
 *
 * This is the gate the roadmap called for: "instrumented smoke test asserting
 * `aliases_fts` exists". Running it here rather than only on a device means an
 * FTS5 or trigger regression fails in seconds on a laptop, and it documents that
 * the assumption "SQLite has FTS5" is a checked fact rather than a hope.
 */
class SqlSchemaTest {

    private lateinit var db: JdbcDb

    private val schema: String by lazy {
        val file = File("src/main/resources/assistant_schema.sql")
        assertTrue(file.isFile, "schema must be on disk at ${file.absolutePath}")
        file.readText()
    }

    @BeforeEach
    fun setUp() {
        db = JdbcDb.inMemory(schema)
    }

    private fun insertContact(
        name: String,
        lookupKey: String? = null,
        isSelf: Boolean = false,
        preferred: String = "auto",
    ): Long {
        db.execute(
            "INSERT INTO contacts (android_lookup_key, display_name, is_self, preferred_channel) " +
                "VALUES (?, ?, ?, ?)",
            listOf(lookupKey, name, if (isSelf) 1 else 0, preferred),
        )
        return db.query("SELECT id FROM contacts WHERE display_name = ?", listOf(name))
            .first()
            .long("id")!!
    }

    private fun insertAlias(contactId: Long, alias: String, norm: String, phon: String? = null) {
        db.execute(
            "INSERT INTO aliases (contact_id, alias, alias_norm, phonetic_key) VALUES (?, ?, ?, ?)",
            listOf(contactId, alias, norm, phon),
        )
    }

    // ------------------------------------------------------------- FTS5 ------

    @Test
    fun `the sqlite build under test actually has fts5`() {
        // If this fails, every tier-2 assumption in the resolver is void and the
        // device must be checked before shipping.
        db.execute("CREATE VIRTUAL TABLE fts5_probe USING fts5(x)")
        db.execute("INSERT INTO fts5_probe (x) VALUES ('rohan')")
        val hits = db.query("SELECT x FROM fts5_probe WHERE fts5_probe MATCH 'ro*'")
        assertEquals(1, hits.size, "prefix query should match")
    }

    @Test
    fun `aliases_fts is populated by the insert trigger`() {
        val id = insertContact("Rohan Das")
        insertAlias(id, "Rohan", "rohan", "RWN")

        val rows = db.query("SELECT rowid, alias_norm FROM aliases_fts WHERE alias_norm = 'rohan'")
        assertEquals(1, rows.size)
        assertEquals(rows.first().long("rowid"), rows.first().long("rowid"))
        assertNotNull(rows.first())
    }

    @Test
    fun `aliases_fts rowid tracks the aliases rowid`() {
        val id = insertContact("Priya Sen")
        insertAlias(id, "Priya", "priya")
        val aliasRowId = db.query("SELECT id FROM aliases WHERE alias_norm = 'priya'")
            .first().long("id")!!
        val ftsRowId = db.query("SELECT rowid FROM aliases_fts WHERE alias_norm = 'priya'")
            .first().long("rowid")!!
        assertEquals(aliasRowId, ftsRowId, "fts external-content join relies on this")
    }

    @Test
    fun `update trigger keeps fts in sync`() {
        val id = insertContact("Arjun")
        insertAlias(id, "Arjun", "arjun")
        db.execute("UPDATE aliases SET alias_norm = 'arjunrao' WHERE alias_norm = 'arjun'")

        assertEquals(
            0,
            db.query("SELECT rowid FROM aliases_fts WHERE alias_norm = 'arjun'").size,
            "stale row should be gone",
        )
        assertEquals(
            1,
            db.query("SELECT rowid FROM aliases_fts WHERE alias_norm = 'arjunrao'").size,
        )
    }

    @Test
    fun `delete trigger removes the fts row`() {
        val id = insertContact("Kabir")
        insertAlias(id, "Kabir", "kabir")
        db.execute("DELETE FROM aliases WHERE alias_norm = 'kabir'")
        assertEquals(0, db.query("SELECT rowid FROM aliases_fts WHERE alias_norm = 'kabir'").size)
    }

    @Test
    fun `fts tier query returns aliases ranked best first`() {
        val a = insertContact("Rohan Das")
        insertAlias(a, "Rohan Das", "rohan das")
        val b = insertContact("Rohit Sharma")
        insertAlias(b, "Rohit", "rohit")
        insertAlias(b, "Rohan", "rohan")

        val hits = Sql.ftsPrefixQuery("rohan")!!
        val rows = db.query(Sql.Alias.BY_FTS, listOf(hits, 5))

        assertEquals(2, rows.size)
        // Exact token should outrank a prefix-only hit.
        assertEquals("rohan", rows.first().string("alias_norm"))
    }

    @Test
    fun `fts query sanitizes syntax characters out of spoken input`() {
        val id = insertContact("Bob Marley")
        insertAlias(id, "Bob", "bob")

        // A colon is FTS5 column-filter syntax. Unsanitized, "bob: whatever" asks
        // for a column named "bob" and errors or matches nothing at all; the point
        // is that it must not reach the MATCH string either way.
        val query = Sql.ftsPrefixQuery("bob: whatever")!!
        assertTrue(":" !in query, "colon must not survive into the MATCH string: $query")
        assertTrue("*" in query, "last token must be a prefix: $query")

        // Tokens are ANDed, so "bob whatever*" legitimately matches nothing. What
        // matters is that the query is well-formed and does not error, and that it
        // does not silently widen into a match-all.
        val rows = db.query(Sql.Alias.BY_FTS, listOf(query, 5))
        assertEquals(0, rows.size, "AND semantics should keep this from matching 'bob'")
    }

    @Test
    fun `fts query still matches when syntax characters are stripped`() {
        val id = insertContact("Bob Marley")
        insertAlias(id, "Bob", "bob")

        // The realistic mis-hearing: a trailing quote or dot from the recognizer.
        // Stripping it must leave a usable query rather than an empty one.
        val query = Sql.ftsPrefixQuery("bob\"")!!
        assertEquals("bob*", query)
        assertTrue(db.query(Sql.Alias.BY_FTS, listOf(query, 5)).isNotEmpty())
    }

    @Test
    fun `fts query on punctuation-only input returns null rather than matching everything`() {
        assertEquals(null, Sql.ftsPrefixQuery("!!! ???"))
        assertEquals(null, Sql.ftsPrefixQuery("   "))
        assertEquals(null, Sql.ftsPrefixQuery(""))
    }

    // ------------------------------------------------------- constraints -----

    @Test
    fun `check constraint rejects an unknown endpoint type`() {
        val id = insertContact("Someone")
        assertThrows(Exception::class.java) {
            db.execute(
                "INSERT INTO contact_endpoints (contact_id, type, value) VALUES (?, ?, ?)",
                listOf(id, "carrier_pigeon", "+15551234567"),
            )
        }
    }

    @Test
    fun `sim_slot check rejects slot zero`() {
        val id = insertContact("Someone")
        assertThrows(Exception::class.java) {
            db.execute(
                "INSERT INTO contact_endpoints (contact_id, type, value, sim_slot) " +
                    "VALUES (?, 'phone', '+15551234567', 0)",
                listOf(id),
            )
        }
    }

    @Test
    fun `only one self contact is allowed`() {
        insertContact("Me", isSelf = true)
        assertThrows(Exception::class.java) { insertContact("Also Me", isSelf = true) }
    }

    @Test
    fun `relationship cannot point at its own subject`() {
        val me = insertContact("Me", isSelf = true)
        // UPSERT is INSERT OR IGNORE so that re-teaching the same relation is
        // idempotent. That also means the self-reference CHECK is ignored rather
        // than raised, so the assertion is that no row is created - not that an
        // exception is thrown. Either way you cannot become your own wife.
        db.execute(Sql.Relation.UPSERT, listOf(me, "wife", me, "user_taught"))
        assertEquals(
            0,
            db.query(Sql.Relation.OBJECTS, listOf(me, "wife")).size,
            "self-relationship must not persist",
        )
    }

    @Test
    fun `teaching the same relationship twice is idempotent`() {
        val me = insertContact("Me", isSelf = true)
        val spouse = insertContact("Priya")
        db.execute(Sql.Relation.UPSERT, listOf(me, "wife", spouse, "user_taught"))
        db.execute(Sql.Relation.UPSERT, listOf(me, "wife", spouse, "user_taught"))
        assertEquals(1, db.query(Sql.Relation.OBJECTS, listOf(me, "wife")).size)
    }

    @Test
    fun `deleting a contact cascades to aliases and endpoints`() {
        val id = insertContact("Temporary")
        insertAlias(id, "Temp", "temp")
        db.execute(
            "INSERT INTO contact_endpoints (contact_id, type, value) VALUES (?, 'phone', '+1555')",
            listOf(id),
        )
        db.execute("DELETE FROM contacts WHERE id = ?", listOf(id))

        assertEquals(0, db.query("SELECT id FROM aliases WHERE contact_id = ?", listOf(id)).size)
        assertEquals(
            0,
            db.query("SELECT id FROM contact_endpoints WHERE contact_id = ?", listOf(id)).size,
        )
    }

    // --------------------------------------------------------- semantics -----

    @Test
    fun `relation vocabulary normalizes spoken variants`() {
        assertEquals("mother", db.query(Sql.Relation.VOCAB_TERM, listOf("mom")).first().string("canonical"))
        assertEquals("mother", db.query(Sql.Relation.VOCAB_TERM, listOf("mum")).first().string("canonical"))
        assertEquals("father", db.query(Sql.Relation.VOCAB_TERM, listOf("dad")).first().string("canonical"))
        assertTrue(db.query(Sql.Relation.VOCAB_TERM, listOf("cousin")).isEmpty())
    }

    @Test
    fun `known terms view spans contacts apps spotify routines and relations`() {
        val id = insertContact("Rohan")
        insertAlias(id, "Rohan", "rohan")
        db.execute("INSERT INTO apps (package, label, label_norm) VALUES ('com.x.y', 'Maps', 'maps')")
        db.execute(
            "INSERT INTO spotify_items (uri, kind, name, name_norm, source) " +
                "VALUES ('spotify:track:1', 'track', 'Believer', 'believer', 'liked')",
        )
        db.execute("INSERT INTO routines (name, name_norm, steps_json) VALUES ('Focus', 'focus', '[]')")

        val terms = db.query(Sql.KnownTerms.ALL).mapNotNull { it.string("term") }.toSet()
        assertTrue("rohan" in terms)
        assertTrue("maps" in terms)
        assertTrue("believer" in terms)
        assertTrue("focus" in terms)
        assertTrue("wife" in terms, "relation vocab feeds the STT grammar")
    }

    @Test
    fun `user_version matches the declared schema version`() {
        assertEquals(Sql.SCHEMA_VERSION, db.query("PRAGMA user_version").first().int("user_version"))
    }

    // ------------------------------------------------------- script split ----

    @Test
    fun `schema splits into statements without breaking trigger bodies`() {
        val statements = Sql.splitStatements(schema)
        assertTrue(statements.size > 25, "expected many statements, got ${statements.size}")

        // A trigger body must arrive whole, or CREATE TRIGGER fails.
        assertTrue(
            statements.any { it.trimStart().startsWith("CREATE TRIGGER aliases_ai") && it.contains("END") },
            "trigger statement was truncated",
        )
        assertTrue(
            statements.none { it.trimStart().startsWith("CREATE TRIGGER aliases_ai") && !it.contains("END") },
            "trigger body lost its END",
        )
    }

    @Test
    fun `script splitter survives semicolons inside string literals`() {
        val split = Sql.splitStatements("INSERT INTO kv (key, value) VALUES ('a;b', 'x'); SELECT 1;")
        assertEquals(2, split.size)
    }

    @Test
    fun `script splitter strips comments`() {
        val split = Sql.splitStatements(
            """
            -- a leading comment; with a semicolon
            SELECT 1; /* block ; comment */
            SELECT 2;
            """.trimIndent(),
        )
        assertEquals(listOf("SELECT 1;", "SELECT 2;"), split)
    }
}
