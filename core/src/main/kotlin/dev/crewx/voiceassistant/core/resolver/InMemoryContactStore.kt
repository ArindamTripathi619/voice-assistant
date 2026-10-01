package dev.crewx.voiceassistant.core.resolver

import dev.crewx.voiceassistant.core.text.DoubleMetaphone
import dev.crewx.voiceassistant.core.text.TextNormalizer
import kotlin.math.sqrt

/**
 * Test double for [ContactStore] that mirrors the SQL in
 * `assistant_schema.sql` closely enough to be a meaningful harness.
 *
 * FTS is emulated with token-prefix matching rather than real FTS5, so the
 * resolver tests exercise tier *ordering* and *scoring*, not SQLite behaviour.
 * The Android layer is where FTS5 itself gets verified.
 */
class InMemoryContactStore : ContactStore {

    private val contacts = mutableMapOf<Long, Contact>()
    private val aliases = mutableListOf<AliasRecord>()
    private val endpoints = mutableListOf<Endpoint>()
    private val relationships = mutableListOf<Triple<Long, String, Long>>()
    private val relationVocab = mutableMapOf<String, String>()
    private val callCounts = mutableMapOf<Long, Int>()
    private val lastContacted = mutableMapOf<Long, Long>()
    private val embeddings = mutableMapOf<Long, Pair<FloatArray, Double>>() // aliasId -> (vec, idfWeight)

    private var nextContactId = 1L
    private var nextAliasId = 1L
    private var nextEndpointId = 1L

    // ---------------------------------------------------------------- seeding

    fun addContact(
        displayName: String,
        given: String? = null,
        family: String? = null,
        nickname: String? = null,
        isSelf: Boolean = false,
        preferredChannel: String = "auto",
        callCount: Int = 0,
        lastContactedAt: Long? = null,
        lookupKey: String? = null,
        id: Long? = null,
    ): Contact {
        val cid = id ?: nextContactId++
        val contact = Contact(
            id = cid,
            lookupKey = lookupKey,
            displayName = displayName,
            givenName = given,
            familyName = family,
            nickname = nickname,
            isSelf = isSelf,
            preferredChannel = preferredChannel,
        )
        contacts[cid] = contact
        if (callCount > 0) callCounts[cid] = callCount
        if (lastContactedAt != null) lastContacted[cid] = lastContactedAt
        return contact
    }

    /**
     * Seeds the same alias rows contact sync would create: full name, given
     * name, family name, nickname.
     */
    fun addContactWithAliases(
        displayName: String,
        given: String? = null,
        family: String? = null,
        nickname: String? = null,
        isSelf: Boolean = false,
        preferredChannel: String = "auto",
        callCount: Int = 0,
        lastContactedAt: Long? = null,
        id: Long? = null,
    ): Contact {
        val contact = addContact(
            displayName, given, family, nickname, isSelf,
            preferredChannel, callCount, lastContactedAt, id = id,
        )
        listOfNotNull(
            displayName.takeIf { it.isNotBlank() },
            given?.takeIf { it.isNotBlank() },
            family?.takeIf { it.isNotBlank() },
            nickname?.takeIf { it.isNotBlank() },
        ).distinct().forEach { addAlias(contact.id, it) }
        return contact
    }

    fun addAlias(
        contactId: Long,
        alias: String,
        kind: String = "name",
        weight: Double = 1.0,
        useCount: Int = 0,
    ): AliasRecord {
        val norm = TextNormalizer.normalizeForEntity(alias)
        val (p, s) = DoubleMetaphone.encode(norm)
        val rec = AliasRecord(
            id = nextAliasId++,
            contactId = contactId,
            alias = alias,
            aliasNorm = norm,
            phoneticKey = p.ifBlank { null },
            phoneticKey2 = s.ifBlank { null },
            kind = kind,
            weight = weight,
            useCount = useCount,
        )
        aliases.add(rec)
        return rec
    }

    fun addEndpoint(
        contactId: Long,
        type: String,
        value: String,
        label: String = "mobile",
        isPrimary: Boolean = false,
        simSlot: Int? = null,
    ): Endpoint {
        val ep = Endpoint(
            id = nextEndpointId++,
            contactId = contactId,
            type = type,
            value = value,
            label = label,
            isPrimary = isPrimary,
            simSlot = simSlot,
        )
        endpoints.add(ep)
        return ep
    }

    fun addRelation(subjectId: Long, relation: String, objectId: Long) {
        relationships.add(Triple(subjectId, relation, objectId))
    }

    fun addRelationTerm(spoken: String, canonical: String) {
        relationVocab[spoken.lowercase()] = canonical
    }

    /** Simple bag-of-characters vector; stands in for a real embedding model. */
    fun addAliasEmbedding(aliasId: Long, vec: FloatArray, weight: Double = 1.0) {
        embeddings[aliasId] = vec to weight
    }

    fun addRelationTermsFrom(seed: Map<String, String>) {
        seed.forEach { (k, v) -> addRelationTerm(k, v) }
    }

    /** Adjusts usage history so frequency/recency scoring can be exercised. */
    fun setUsage(contactId: Long, callCount: Int, lastContactedAt: Long? = null) {
        callCounts[contactId] = callCount
        if (lastContactedAt != null) lastContacted[contactId] = lastContactedAt
    }

    // --------------------------------------------------------------- ContactStore

    override fun selfId(): Long? = contacts.values.firstOrNull { it.isSelf }?.id

    override fun contact(id: Long): Contact? = contacts[id]

    override fun byLookupKey(key: String): Contact? = contacts.values.firstOrNull { it.lookupKey == key }

    override fun aliasExact(aliasNorm: String): List<AliasRecord> =
        aliases.filter { it.aliasNorm == aliasNorm }

        /**
         * Token-prefix match, mirroring the FTS5 strategy documented in the schema.
         *
         * Two-tier relevance:
         *  - 2 = every query token matches some alias token as a prefix (the FTS
         *    equivalent of `token*`, and what a partial utterance produces)
         *  - 1 = only the *last* query token matches as a prefix, and only for a
         *    single-token query. This is the "Rohan Kap…" -> "rohan kapoor" case.
         *
         * Multi-token queries never fall back to last-token-only, because
         * "my wife's brother" hitting a contact on "brother" alone is exactly the
         * false positive the margin check downstream cannot catch.
         */
        override fun aliasFts(prefixQuery: String, limit: Int): List<AliasRecord> {
            val q = prefixQuery.trim()
            if (q.isEmpty()) return emptyList()
            val qTokens = q.split(' ').filter { it.isNotEmpty() }
            if (qTokens.isEmpty()) return emptyList()

            return aliases
                .mapNotNull { rec ->
                    val aTokens = rec.aliasNorm.split(' ').filter { it.isNotEmpty() }
                    if (aTokens.isEmpty()) return@mapNotNull null

                    val allTokensMatch = qTokens.all { qt -> aTokens.any { it.startsWith(qt) } }
                    val lastTokenOnly = qTokens.size == 1 &&
                        aTokens.any { it.startsWith(qTokens.last()) }

                    when {
                        allTokensMatch -> 2
                        lastTokenOnly -> 1
                        else -> 0
                    }.takeIf { it > 0 }?.let { score -> rec to score }
                }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
        }

    override fun aliasPhonetic(keys: Set<String>): List<AliasRecord> {
        if (keys.isEmpty()) return emptyList()
        return aliases.filter { rec ->
            rec.phoneticKey in keys || rec.phoneticKey2 in keys
        }
    }

    override fun contactAliases(contactId: Long): List<AliasRecord> =
        aliases.filter { it.contactId == contactId }

    override fun endpointFor(contactId: Long, channel: String?, label: String?): Endpoint? {
        val eps = endpointsFor(contactId)
        if (eps.isEmpty()) return null

        val requested = when (channel?.lowercase()) {
            null, "auto" -> null
            else -> channel.lowercase()
        }
        val contact = contacts[contactId]

        // Explicit channel request wins.
        if (requested != null) {
            eps.firstOrNull { it.type == requested }?.let { return it }
            // WhatsApp links are number-based: fall back to the primary phone.
            if (requested == "whatsapp") {
                eps.firstOrNull { it.type == "phone" && it.isPrimary }?.let { return it }
                eps.firstOrNull { it.type == "phone" }?.let { return it }
            }
        } else {
            val preferred = contact?.preferredChannel?.lowercase()
            if (preferred != null && preferred != "auto") {
                eps.firstOrNull { it.type == preferred }?.let { return it }
            }
        }

        if (label != null) eps.firstOrNull { it.label == label.lowercase() }?.let { return it }
        eps.firstOrNull { it.isPrimary }?.let { return it }
        eps.firstOrNull { it.type == "phone" }?.let { return it }
        return eps.firstOrNull()
    }

    override fun endpointsFor(contactId: Long): List<Endpoint> =
        endpoints.filter { it.contactId == contactId }

    override fun relationshipsOf(subjectId: Long, relation: String): List<Long> =
        relationships.filter { it.first == subjectId && it.second == relation }
            .map { it.third }

    override fun canonicalRelation(spokenTerm: String): String? =
        relationVocab[spokenTerm.lowercase()]

    override fun relationTerms(): Map<String, String> = relationVocab.toMap()

    override fun callCount(contactId: Long): Int = callCounts[contactId] ?: 0

    override fun lastContactedAt(contactId: Long): Long? = lastContacted[contactId]

    override fun aliasEmbeddingCandidates(
        queryVector: FloatArray,
        limit: Int,
    ): List<Pair<AliasRecord, Float>> =
        embeddings.mapNotNull { (aliasId, pair) ->
            val rec = aliases.firstOrNull { it.id == aliasId } ?: return@mapNotNull null
            rec to cosine(queryVector, pair.first).toFloat()
        }.sortedByDescending { it.second }.take(limit)

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    companion object {
        /** Default family vocabulary, matching `relation_vocab` in the schema. */
        val DEFAULT_RELATION_TERMS = mapOf(
            "wife" to "wife", "spouse" to "spouse", "husband" to "husband",
            "mom" to "mother", "mum" to "mother", "mother" to "mother",
            "dad" to "father", "father" to "father", "papa" to "father",
            "brother" to "brother", "bhai" to "brother", "sister" to "sister",
            "son" to "son", "daughter" to "daughter",
            "boss" to "boss", "friend" to "friend",
        )
    }
}
