package dev.crewx.voiceassistant.core.resolver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("EntityResolver")
class EntityResolverTest {

    private lateinit var store: InMemoryContactStore
    private lateinit var resolver: EntityResolver

    private var meId = 0L
    private var wifeId = 0L
    private var wifeBrotherId = 0L
    private var rohan1Id = 0L
    private var rohan2Id = 0L
    private var buntyId = 0L
    private var priyaId = 0L

    @BeforeEach
    fun setUp() {
        store = InMemoryContactStore()
        store.addRelationTermsFrom(InMemoryContactStore.DEFAULT_RELATION_TERMS)

        meId = store.addContactWithAliases(displayName = "Arjun Mehta", isSelf = true).id
        wifeId = store.addContactWithAliases(
            displayName = "Priya Mehta", given = "Priya", family = "Mehta", id = 100,
        ).id
        wifeBrotherId = store.addContactWithAliases(
            displayName = "Vikram Mehta", given = "Vikram", family = "Mehta", id = 101,
        ).id
        rohan1Id = store.addContactWithAliases(
            displayName = "Rahul Das", given = "Rahul", family = "Das", id = 200,
        ).id
        rohan2Id = store.addContactWithAliases(
            displayName = "Rahul Sen", given = "Rahul", family = "Sen", id = 201,
        ).id
        buntyId = store.addContactWithAliases(
            displayName = "Rohan Kapoor", given = "Rohan", family = "Kapoor", id = 202,
        ).id
        priyaId = store.addContactWithAliases(
            displayName = "Priya Nair", given = "Priya", family = "Nair", id = 203,
        ).id

        // Relationship graph: me -> wife -> brother
        store.addRelation(meId, "wife", wifeId)
        store.addRelation(wifeId, "brother", wifeBrotherId)

        store.addEndpoint(wifeId, "phone", "+919812345678", isPrimary = true)
        store.addEndpoint(wifeBrotherId, "whatsapp", "+919812345679")
        store.addEndpoint(rohan1Id, "phone", "+919700000001", isPrimary = true)
        store.addEndpoint(rohan2Id, "phone", "+919700000002", isPrimary = true)

        resolver = EntityResolver(store)
    }

    @Nested
    @DisplayName("tier 0: relation chain")
    inner class RelationTiers {

        @Test
        fun `resolves my wife in one hop`() {
            val r = resolver.resolve("my wife")
            assertTrue(r.autoPick, "expected auto-pick, got ${r.candidates}")
            assertEquals(MatchTier.RELATION, r.tier)
            assertEquals(wifeId, r.top!!.contactId)
            assertEquals("Priya Mehta", r.top!!.displayName)
        }

        @Test
        fun `resolves bare relation word`() {
            val r = resolver.resolve("wife")
            assertTrue(r.autoPick)
            assertEquals(wifeId, r.top!!.contactId)
        }

        @Test
        fun `resolves wife's brother in two hops`() {
            val r = resolver.resolve("my wife's brother")
            assertTrue(r.autoPick, "got ${r.candidates}")
            assertEquals(wifeBrotherId, r.top!!.contactId)
            assertEquals("Vikram Mehta", r.top!!.displayName)
        }

        @Test
        fun `attaches the preferred endpoint for the resolved contact`() {
            val r = resolver.resolve("my wife", channel = "whatsapp")
            assertEquals(wifeId, r.top!!.contactId)
            // Priya has no whatsapp endpoint, so it must fall back to her phone.
            assertEquals("phone", r.top!!.endpoint?.type)
            assertEquals("+919812345678", r.top!!.endpoint?.value)
        }

        @Test
        fun `honours an explicit whatsapp endpoint`() {
            val r = resolver.resolve("my wife's brother", channel = "whatsapp")
            assertEquals("whatsapp", r.top!!.endpoint?.type)
            assertEquals("+919812345679", r.top!!.endpoint?.value)
        }

        @Test
        fun `unknown relation yields no resolution`() {
            val r = resolver.resolve("my uncle")
            assertNull(r.top)
        }

        @Test
        fun `relation cycle terminates instead of looping`() {
            // Malformed graph: wife -> mother -> wife.
            store.addRelation(wifeId, "mother", meId)
            val r = resolver.resolve("my wife's mother")
            assertNull(r.top, "cyclic chain must not resolve")
        }

        @Test
        fun `multi-valued intermediate relation stops the chain`() {
            // Two brothers: the chain must not guess which one.
            store.addContactWithAliases(displayName = "Arjun Mehta 2", id = 300)
            store.addRelation(wifeId, "brother", 300)
            val r = resolver.resolve("my wife's brother")
            assertFalse(r.autoPick, "ambiguous intermediate step must not auto-pick")
        }
    }

    @Nested
    @DisplayName("tier 1: exact alias")
    inner class ExactTier {

        @Test
        fun `resolves exact full name`() {
            val r = resolver.resolve("Priya Nair")
            assertTrue(r.autoPick)
            assertEquals(priyaId, r.top!!.contactId)
        }

        @Test
        fun `resolves given name alone`() {
            val r = resolver.resolve("Vikram")
            assertEquals(wifeBrotherId, r.top!!.contactId)
        }

        @Test
        fun `is case and punctuation insensitive`() {
            val r = resolver.resolve("  rohan kapoor. ")
            assertTrue(r.autoPick)
            assertEquals(buntyId, r.top!!.contactId)
        }

        @Test
        fun `ignores filler and possessive words`() {
            val r = resolver.resolve("please call my wife")
            assertEquals(wifeId, r.top!!.contactId)
        }

        @Test
        fun `unknown person yields no resolution`() {
            assertNull(resolver.resolve("Nobody Here").top)
        }

        @Test
        fun `blank query yields no resolution`() {
            assertNull(resolver.resolve("").top)
            assertNull(resolver.resolve("   ").top)
        }
    }

    @Nested
    @DisplayName("ambiguity safety")
    inner class Ambiguity {

        @Test
        fun `two contacts sharing a first name must not auto-pick`() {
            val r = resolver.resolve("Rahul")
            assertFalse(r.autoPick, "got ${r.candidates}")
            assertTrue(r.ambiguous)
            assertEquals(2, r.candidates.size)
            val names = r.candidates.map { it.displayName }.toSet()
            assertEquals(setOf("Rahul Das", "Rahul Sen"), names)
        }

        @Test
        fun `frequent contact wins the margin`() {
            // Rahul Das becomes clearly more likely through usage history.
            val st = InMemoryContactStore()
            st.addRelationTermsFrom(InMemoryContactStore.DEFAULT_RELATION_TERMS)
            val a = st.addContactWithAliases(displayName = "Rahul Das", given = "Rahul", id = 1).id
            val b = st.addContactWithAliases(displayName = "Rahul Sen", given = "Rahul", id = 2).id
            st.setUsage(a, callCount = 40, lastContactedAt = System.currentTimeMillis() / 1000)
            st.setUsage(b, callCount = 0)

            val r = EntityResolver(st).resolve("Rahul")
            assertEquals(a, r.top!!.contactId, "usage should break the tie")
        }

        @Test
        fun `same contact matched twice is not treated as ambiguous`() {
            // "Rohan Kapoor" matches on both full name and given name -> one person.
            val r = resolver.resolve("Rohan Kapoor")
            assertEquals(1, r.candidates.size)
            assertTrue(r.autoPick)
            assertEquals(buntyId, r.top!!.contactId)
        }
    }

    @Nested
    @DisplayName("tier 3: phonetic recovery from STT errors")
    inner class PhoneticTier {

        @Test
        fun `resolves a misheard name via phonetic key`() {
            val r = resolver.resolve("Rohan Kapur")
            assertNotNull(r.top, "phonetic tier should recover a misheard surname")
            assertEquals(MatchTier.PHONETIC, r.tier)
            assertEquals(buntyId, r.top!!.contactId)
        }

        @Test
        fun `phonetic tier does not override an exact match`() {
            val r = resolver.resolve("Rohan Kapoor")
            assertEquals(MatchTier.EXACT, r.tier)
        }

        @Test
        fun `very short queries are not phonetically matched`() {
            // Single letters would produce nonsense keys.
            val r = resolver.resolve("x")
            assertNull(r.top)
        }
    }

    @Nested
    @DisplayName("tier 4: embedding fallback")
    inner class EmbeddingTier {

        @Test
        fun `resolves via embedding when string tiers all fail`() {
            val st = InMemoryContactStore()
            val target = st.addContactWithAliases(displayName = "Chandrasekhar Iyer", id = 1).id
            st.addContactWithAliases(displayName = "Someone Else", id = 2)
            val rec = st.contactAliases(target).first()
            st.addAliasEmbedding(rec.id, floatArrayOf(1f, 0f, 0f))

            val index = object : EmbeddingIndex {
                override fun embed(text: String) = floatArrayOf(0.9f, 0.1f, 0f)
            }
            // "sekhar" is an interior fragment: no prefix match, and its phonetic
            // key (SEKHAR) differs from the stored one (CHANTRAS), so only the
            // embedding tier can rescue it.
            val r = EntityResolver(st, embeddings = index).resolve("sekhar")
            assertNotNull(r.top, "embedding tier should rescue an interior fragment")
            assertEquals(target, r.top!!.contactId)
            assertEquals(MatchTier.EMBEDDING, r.tier)
        }

        @Test
        fun `prefix match is preferred over embedding`() {
            val st = InMemoryContactStore()
            val target = st.addContactWithAliases(displayName = "Chandrasekhar Iyer", id = 1).id
            val rec = st.contactAliases(target).first()
            st.addAliasEmbedding(rec.id, floatArrayOf(1f, 0f, 0f))

            val index = object : EmbeddingIndex {
                override fun embed(text: String) = floatArrayOf(0.9f, 0.1f, 0f)
            }
            // "chandrasekhar" is a real token prefix, so FTS must win outright and
            // the (more expensive) embedding tier must not even be consulted.
            val r = EntityResolver(st, embeddings = index).resolve("Chandrasekhar")
            assertEquals(MatchTier.FTS, r.tier)
            assertEquals(target, r.top!!.contactId)
        }

        @Test
        fun `dissimilar embeddings are rejected`() {
            val st = InMemoryContactStore()
            val target = st.addContactWithAliases(displayName = "Chandrasekhar Iyer", id = 1).id
            val rec = st.contactAliases(target).first()
            st.addAliasEmbedding(rec.id, floatArrayOf(1f, 0f, 0f))

            val index = object : EmbeddingIndex {
                override fun embed(text: String) = floatArrayOf(0f, 1f, 0f) // cosine 0
            }
            assertNull(EntityResolver(st, embeddings = index).resolve("something unrelated").top)
        }
    }

    @Nested
    @DisplayName("disambiguation prompts")
    inner class Clarification {

        @Test
        fun `ambiguous resolution surfaces both candidates for a spoken question`() {
            val r = resolver.resolve("Rahul")
            assertTrue(r.ambiguous)
            val names = r.candidates.map { it.displayName }
            assertEquals(listOf("Rahul Das", "Rahul Sen"), names)
        }
    }
}
