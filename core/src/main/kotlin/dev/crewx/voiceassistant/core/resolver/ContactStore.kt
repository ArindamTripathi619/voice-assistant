package dev.crewx.voiceassistant.core.resolver

/**
 * Read-side view of the contact graph. The Room/SQLCipher implementation lives in
 * the Android module; the JVM tests use [InMemoryContactStore].
 *
 * Keeping this an interface is what lets the resolver — the part most likely to
 * dial the wrong person — be tested exhaustively without a device.
 */
interface ContactStore {

    fun selfId(): Long?

    fun contact(id: Long): Contact?

    fun byLookupKey(key: String): Contact?

    /** Exact match on normalized alias. */
    fun aliasExact(aliasNorm: String): List<AliasRecord>

    /**
     * Token/prefix match, equivalent to the FTS5 query in
     * `assistant_schema.sql`. [prefixQuery] is already normalized.
     */
    fun aliasFts(prefixQuery: String, limit: Int): List<AliasRecord>

    /** Aliases whose primary or secondary phonetic key is in [keys]. */
    fun aliasPhonetic(keys: Set<String>): List<AliasRecord>

    fun contactAliases(contactId: Long): List<AliasRecord>

    /** Preferred endpoint for a contact, honouring a channel request. */
    fun endpointFor(contactId: Long, channel: String?, label: String?): Endpoint?

    fun endpointsFor(contactId: Long): List<Endpoint>

    /**
     * Objects of `subject --relation--> object`. [relation] is canonical (from
     * `relation_vocab`), not the spoken term.
     */
    fun relationshipsOf(subjectId: Long, relation: String): List<Long>

    /** Spoken term -> canonical relation, e.g. "mum" -> "mother". */
    fun canonicalRelation(spokenTerm: String): String?

    /** All canonical relation terms, for grammar building. */
    fun relationTerms(): Map<String, String>

    fun callCount(contactId: Long): Int

    fun lastContactedAt(contactId: Long): Long?

    /**
     * Tier-4 embedding fallback. [queryVector] is a unit-ish float array;
     * implementations should return candidates sorted by descending cosine.
     */
    fun aliasEmbeddingCandidates(queryVector: FloatArray, limit: Int): List<Pair<AliasRecord, Float>>
}

data class Contact(
    val id: Long,
    val lookupKey: String? = null,
    val displayName: String,
    val givenName: String? = null,
    val familyName: String? = null,
    val nickname: String? = null,
    val isSelf: Boolean = false,
    val isFavorite: Boolean = false,
    val preferredChannel: String = "auto",
)

data class AliasRecord(
    val id: Long,
    val contactId: Long,
    val alias: String,
    val aliasNorm: String,
    val phoneticKey: String? = null,
    val phoneticKey2: String? = null,
    val kind: String = "name",
    val weight: Double = 1.0,
    val useCount: Int = 0,
)

data class Endpoint(
    val id: Long,
    val contactId: Long,
    val type: String,
    val value: String,
    val label: String = "mobile",
    val isPrimary: Boolean = false,
    val simSlot: Int? = null,
)

/** One scored candidate produced by a resolution tier. */
data class ResolutionCandidate(
    val contactId: Long,
    val displayName: String,
    val matchedVia: MatchTier,
    val matchedText: String,
    val score: Double,
    val endpoint: Endpoint? = null,
)

enum class MatchTier(val label: String, val baseWeight: Double) {
    RELATION("relation", 1.00),
    EXACT("exact", 1.00),
    FTS("prefix", 0.82),
    PHONETIC("phonetic", 0.70),
    EMBEDDING("semantic", 0.45);

    companion object {
        fun fromLabel(s: String): MatchTier? = entries.firstOrNull { it.label == s }
    }
}

/**
 * Outcome of resolving a raw spoken reference to a real contact.
 *
 * The whole point of [autoPick] being explicit: the executor may only act
 * automatically when this is true, which is how a 270M model that hallucinates
 * "Rohan" into a wrong-but-existing contact cannot cause a misdial.
 */
data class Resolution(
    val query: String,
    val candidates: List<ResolutionCandidate>,
    val autoPick: Boolean,
    val tier: MatchTier?,
) {
    val top: ResolutionCandidate? get() = candidates.firstOrNull()
    val ambiguous: Boolean get() = candidates.size > 1 && !autoPick

    companion object {
        fun none(query: String) = Resolution(query, emptyList(), false, null)
    }
}
