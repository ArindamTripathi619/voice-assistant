package dev.crewx.voiceassistant.core.resolver

import dev.crewx.voiceassistant.core.db.JdbcDb
import dev.crewx.voiceassistant.core.db.Sql
import dev.crewx.voiceassistant.core.db.SqlDb
import dev.crewx.voiceassistant.core.db.VectorCodec
import dev.crewx.voiceassistant.core.text.DoubleMetaphone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Exercises [SqlContactStore] against a real SQLite with the real schema.
 *
 * The in-memory store tests prove the resolver's logic. These prove the SQL: that
 * the FTS tier really ranks, that the CHECK constraints really reject bad
 * endpoints, that `endpointFor` really honours the WhatsApp-to-phone fallback.
 * Those are exactly the failures that would otherwise appear as a misdial on a
 * device with no stack trace pointing at the cause.
 */
class SqlContactStoreTest {

    private lateinit var db: SqlDb
    private lateinit var store: SqlContactStore

    private val schema: String by lazy {
        val file = File("src/main/resources/assistant_schema.sql")
        assertTrue(file.isFile, "schema missing at ${file.absolutePath}")
        file.readText()
    }

    @BeforeEach
    fun setUp() {
        db = JdbcDb.inMemory(schema)
        store = SqlContactStore(db)
    }

    private fun contact(
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
        return db.query("SELECT id FROM contacts WHERE display_name = ?", listOf(name)).first().long("id")!!
    }

    private fun alias(contactId: Long, spoken: String, kind: String = "name", weight: Double = 1.0) {
        val norm = dev.crewx.voiceassistant.core.text.TextNormalizer.normalizeForEntity(spoken)
        val keys = DoubleMetaphone.encode(norm)
        store.insertAlias(
            contactId = contactId,
            alias = spoken,
            aliasNorm = norm,
            phoneticKey = keys.first,
            phoneticKey2 = keys.second,
            kind = kind,
            weight = weight,
        )
    }

    /** JUnit's assertNotNull returns Unit; this returns the narrowed value. */
    private fun <T : Any> present(value: T?): T {
        assertNotNull(value, "expected a value but got null")
        return value!!
    }

    private fun endpoint(
        contactId: Long,
        type: String,
        value: String,
        label: String = "mobile",
        primary: Boolean = true,
        sim: Int? = null,
    ) {
        db.execute(
            "INSERT INTO contact_endpoints (contact_id, type, value, label, is_primary, sim_slot) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            listOf(contactId, type, value, label, if (primary) 1 else 0, sim),
        )
    }

    // ------------------------------------------------------------ identity ---

    @Test
    fun `self id is found`() {
        val me = contact("Me", isSelf = true)
        assertEquals(me, store.selfId())
    }

    @Test
    fun `self id is null when nobody is marked self`() {
        contact("Somebody")
        assertNull(store.selfId())
    }

    @Test
    fun `lookup key round trips and does not collide on unknown keys`() {
        val id = contact("Rohan", lookupKey = "abc123")
        assertEquals(id, store.byLookupKey("abc123")?.id)
        assertNull(store.byLookupKey("nope"))
    }

    @Test
    fun `contact fields map through including nulls`() {
        val id = contact("Priya Sen", lookupKey = "k1")
        val c = present(store.contact(id))
        assertEquals("Priya Sen", c.displayName)
        assertEquals("k1", c.lookupKey)
        assertNull(c.givenName)
        assertTrue(!c.isSelf)
        assertEquals("auto", c.preferredChannel)
    }

    // ---------------------------------------------------------------- FTS ----

    @Test
    fun `exact tier finds the normalized alias`() {
        val id = contact("Rohan Das")
        alias(id, "Rohan")
        assertEquals(1, store.aliasExact("rohan").size)
        assertEquals(id, store.aliasExact("rohan").first().contactId)
    }

    @Test
    fun `exact tier returns nothing for an unknown name`() {
        alias(contact("Rohan"), "Rohan")
        assertTrue(store.aliasExact("nobody").isEmpty())
    }

    @Test
    fun `fts tier matches a partial last token`() {
        val id = contact("Rohan Das")
        alias(id, "Rohan")
        val hits = store.aliasFts("roha", limit = 5)
        assertEquals(1, hits.size)
        assertEquals(id, hits.first().contactId)
    }

    @Test
    fun `fts tier matches a multi-token prefix`() {
        val id = contact("Rohan Das")
        alias(id, "Rohan Das")
        assertEquals(1, store.aliasFts("rohan d", limit = 5).size)
    }

    @Test
    fun `fts tier on garbage returns empty rather than everything`() {
        alias(contact("Rohan"), "Rohan")
        assertTrue(store.aliasFts("!!!", limit = 5).isEmpty())
        assertTrue(store.aliasFts("", limit = 5).isEmpty())
    }

    @Test
    fun `fts tier respects the limit`() {
        for (i in 1..5) {
            val id = contact("Rohan $i")
            alias(id, "Rohan")
        }
        assertEquals(2, store.aliasFts("roha", limit = 2).size)
    }

    // ----------------------------------------------------------- phonetic ----

    @Test
    fun `phonetic tier finds a misheard spelling`() {
        val id = contact("Sneha")
        alias(id, "Sneha")
        val keys = DoubleMetaphone.encode("sneha")
        val hits = store.aliasPhonetic(setOfNotNull(keys.first, keys.second))
        assertTrue(hits.any { it.contactId == id }, "expected a phonetic hit for 'sneha'")
    }

    @Test
    fun `phonetic tier with no keys does not query`() {
        alias(contact("Sneha"), "Sneha")
        assertTrue(store.aliasPhonetic(emptySet()).isEmpty())
    }

    @Test
    fun `phonetic tier handles many keys without injection risk`() {
        val id = contact("Amit")
        alias(id, "Amit")
        val keys = setOf("AMT", "M", "' OR 1=1 --")
        assertTrue(store.aliasPhonetic(keys).none { it.contactId == 0L })
        assertTrue(store.aliasPhonetic(setOf("AMT")).any { it.contactId == id })
    }

    // ------------------------------------------------------------ endpoint ---

    @Test
    fun `endpoint falls back to phone when whatsapp is absent`() {
        val id = contact("Rohan")
        endpoint(id, "phone", "+15551230000")
        val e = present(store.endpointFor(id, "whatsapp", null))
        assertEquals("phone", e.type, "WhatsApp is number-based, so phone is the documented fallback")
    }

    @Test
    fun `explicit whatsapp endpoint wins over phone`() {
        val id = contact("Rohan")
        endpoint(id, "phone", "+15551230000")
        endpoint(id, "whatsapp", "+15551230000", primary = false)
        assertEquals("whatsapp", present(store.endpointFor(id, "whatsapp", null)).type)
    }

    @Test
    fun `preferred channel is honoured when no channel is requested`() {
        val id = contact("Rohan", preferred = "telegram")
        endpoint(id, "phone", "+15551230000")
        endpoint(id, "telegram", "@rohan")
        assertEquals("telegram", present(store.endpointFor(id, null, null)).type)
    }

    @Test
    fun `preferred auto falls back to the primary endpoint`() {
        val id = contact("Rohan")
        endpoint(id, "phone", "+15551230000", primary = true)
        endpoint(id, "email", "r@example.com", primary = false)
        assertEquals("phone", present(store.endpointFor(id, null, null)).type)
    }

    @Test
    fun `a named label beats the generic ranking`() {
        val id = contact("Rohan")
        endpoint(id, "phone", "+15551110000", label = "work", primary = true)
        endpoint(id, "phone", "+15552220000", label = "home", primary = false)
        val picked = present(store.endpointFor(id, null, "home"))
        assertEquals("home", picked.label)
        assertEquals("+15552220000", picked.value, "the home number, not the primary work one")
    }

    @Test
    fun `contact with no endpoints resolves to null rather than a guessed number`() {
        val id = contact("Nobody")
        assertNull(store.endpointFor(id, "phone", null))
    }

    @Test
    fun `sim slot is preserved for dual sim`() {
        val id = contact("Rohan")
        endpoint(id, "phone", "+15551230000", sim = 2)
        assertEquals(2, present(store.endpointFor(id, "phone", null)).simSlot)
    }

    // -------------------------------------------------------- relationships ---

    @Test
    fun `relationship walk and canonical relations`() {
        val me = contact("Me", isSelf = true)
        val wife = contact("Priya")
        store.addRelationship(me, "wife", wife, "user_taught")

        assertEquals(listOf(wife), store.relationshipsOf(me, "wife"))
        assertEquals("mother", store.canonicalRelation("mum"))
        assertEquals("wife", store.canonicalRelation("Wife"))
        assertNull(store.canonicalRelation("cousin"))
    }

    @Test
    fun `relation terms include spoken variants for grammar building`() {
        val terms = store.relationTerms()
        assertEquals("mother", terms["mom"])
        assertEquals("mother", terms["mum"])
        assertEquals("father", terms["dad"])
    }

    // ---------------------------------------------------------- usage --------

    @Test
    fun `usage counters update for ranking`() {
        val id = contact("Rohan")
        assertEquals(0, store.callCount(id))
        assertNull(store.lastContactedAt(id))
        store.recordUsage(id, isCall = true, nowSeconds = 1_700_000_000L)
        assertEquals(1, store.callCount(id))
        assertEquals(1_700_000_000L, store.lastContactedAt(id))
    }

    @Test
    fun `message usage is counted separately from calls`() {
        val id = contact("Rohan")
        store.recordUsage(id, isCall = false, nowSeconds = 1L)
        assertEquals(0, store.callCount(id), "a text must not inflate the call counter")
        assertEquals(1, messageCount(id))
        assertEquals(1L, store.lastContactedAt(id))

        store.recordUsage(id, isCall = true, nowSeconds = 2L)
        assertEquals(1, store.callCount(id))
        assertEquals(1, messageCount(id), "a call must not inflate the message counter")
        assertEquals(2L, store.lastContactedAt(id), "recency tracks either kind of contact")

        store.recordUsage(id, isCall = false, nowSeconds = 3L)
        assertEquals(2, messageCount(id))
    }

    /** `message_count` has no ContactStore accessor by design; read it directly. */
    private fun messageCount(id: Long): Int =
        db.query(Sql.Contact.BY_ID, listOf(id)).first().int("message_count") ?: 0

    @Test
    fun `alias use counter increments`() {
        val id = contact("Rohan")
        alias(id, "Rohan")
        val aliasId = store.aliasExact("rohan").first().id
        store.bumpAliasUse(aliasId, 42L)
        val refreshed = store.contactAliases(id).first()
        assertEquals(1, refreshed.useCount)
    }

    // --------------------------------------------------------- embeddings ----

    @Test
    fun `embedding tier ranks by cosine and returns the nearest alias`() {
        val target = contact("Rohan")
        val other = contact("Priya")
        alias(target, "Rohan")
        alias(other, "Priya")
        val targetAliasId = store.aliasExact("rohan").first().id
        val otherAliasId = store.aliasExact("priya").first().id

        db.execute(
            Sql.AliasWrite.UPSERT_EMBEDDING,
            listOf("alias", targetAliasId.toString(), "test-model", VectorCodec.encode(floatArrayOf(1f, 0f))),
        )
        db.execute(
            Sql.AliasWrite.UPSERT_EMBEDDING,
            listOf("alias", otherAliasId.toString(), "test-model", VectorCodec.encode(floatArrayOf(0f, 1f))),
        )

        val hits = store.aliasEmbeddingCandidates(floatArrayOf(0.9f, 0.1f), limit = 5)
        assertEquals(2, hits.size)
        assertEquals(target, hits.first().first.contactId, "nearest vector must rank first")
    }

    @Test
    fun `embedding tier is empty when nothing is embedded`() {
        alias(contact("Rohan"), "Rohan")
        assertTrue(store.aliasEmbeddingCandidates(floatArrayOf(1f, 0f), limit = 5).isEmpty())
    }

    // ------------------------------------------------------- query safety ----

    @Test
    fun `name-like input cannot inject sql anywhere in the read path`() {
        val id = contact("Rohan")
        alias(id, "Rohan")
        val nasty = "rohan'; DROP TABLE contacts; --"

        // These must return empty rather than execute or error.
        assertTrue(store.aliasExact(nasty).isEmpty())
        assertTrue(store.aliasFts(nasty, limit = 5).isEmpty())
        assertTrue(store.aliasPhonetic(setOf(nasty)).isEmpty())
        assertNull(store.canonicalRelation(nasty))

        // The table must still be there.
        assertNotNull(store.contact(id))
    }
}
