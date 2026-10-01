package dev.crewx.voiceassistant.core.resolver

import dev.crewx.voiceassistant.core.db.Sql
import dev.crewx.voiceassistant.core.db.SqlDb
import dev.crewx.voiceassistant.core.db.SqlRow

/**
 * [ContactStore] over raw SQL, matching `assistant_schema.sql`.
 *
 * The Android build wraps this in SQLCipher; the JVM tests wrap it in JDBC, so
 * the same queries are exercised in both places.
 *
 * Every method here is read-only. Usage counters are bumped through
 * [recordUsage] as a separate explicit call, so a lookup that is about to be
 * abandoned (ambiguous, then clarified away) does not skew the ranking that
 * produced the ambiguity.
 */
class SqlContactStore(private val db: SqlDb) : ContactStore {

    override fun selfId(): Long? =
        db.query(Sql.Contact.SELF).firstOrNull()?.long("id")

    override fun contact(id: Long): Contact? =
        db.query(Sql.Contact.BY_ID, listOf(id)).firstOrNull()?.toContact()

    override fun byLookupKey(key: String): Contact? =
        db.query(Sql.Contact.BY_LOOKUP_KEY, listOf(key)).firstOrNull()?.toContact()

    override fun aliasExact(aliasNorm: String): List<AliasRecord> =
        db.query(Sql.Alias.BY_EXACT, listOf(aliasNorm)).map { it.toAlias() }

    override fun aliasFts(prefixQuery: String, limit: Int): List<AliasRecord> {
        val ftsQuery = Sql.ftsPrefixQuery(prefixQuery) ?: return emptyList()
        return db.query(Sql.Alias.BY_FTS, listOf(ftsQuery, limit)).map { it.toAlias() }
    }

    override fun aliasPhonetic(keys: Set<String>): List<AliasRecord> {
        if (keys.isEmpty()) return emptyList()
        // Placeholders are generated from the set size, never from user text, so
        // there is no injection surface here.
        val placeholders = keys.joinToString(", ") { "?" }
        val sql = Sql.Alias.BY_PHONETIC
            .replace("\${KEYS}", placeholders)
        return db.query(sql, keys.toList()).map { it.toAlias() }
    }

    override fun contactAliases(contactId: Long): List<AliasRecord> =
        db.query(Sql.Alias.BY_CONTACT, listOf(contactId)).map { it.toAlias() }

    override fun endpointFor(contactId: Long, channel: String?, label: String?): Endpoint? {
        val preferred = contact(contactId)?.preferredChannel ?: "auto"
        // Named binds keep the query readable; every value stays a bind
        // parameter rather than being concatenated.
        val args = mapOf(
            ":contactId" to contactId,
            ":channel" to channel,
            ":preferred" to preferred,
        )
        val sql = if (label == null) Sql.Endpoint.RESOLVE else Sql.Endpoint.RESOLVE_LABELLED
        val named = if (label == null) args else args + mapOf(":label" to label)
        return db.queryNamed(sql, named).firstOrNull()?.toEndpoint()
    }

    override fun endpointsFor(contactId: Long): List<Endpoint> =
        db.query(Sql.Endpoint.FOR_CONTACT, listOf(contactId)).map { it.toEndpoint() }

    override fun relationshipsOf(subjectId: Long, relation: String): List<Long> =
        db.query(Sql.Relation.OBJECTS, listOf(subjectId, relation))
            .mapNotNull { it.long("object_id") }

    override fun canonicalRelation(spokenTerm: String): String? =
        db.query(Sql.Relation.VOCAB_TERM, listOf(spokenTerm.lowercase()))
            .firstOrNull()
            ?.string("canonical")

    override fun relationTerms(): Map<String, String> =
        db.query(Sql.Relation.ALL_VOCAB)
            .mapNotNull { row -> row.string("term")?.let { it to row.string("canonical").orEmpty() } }
            .toMap()

    override fun callCount(contactId: Long): Int =
        db.query("SELECT call_count FROM contacts WHERE id = ?", listOf(contactId))
            .firstOrNull()
            ?.int("call_count")
            ?: 0

    override fun lastContactedAt(contactId: Long): Long? =
        db.query("SELECT last_contacted_at FROM contacts WHERE id = ?", listOf(contactId))
            .firstOrNull()
            ?.long("last_contacted_at")

    override fun aliasEmbeddingCandidates(queryVector: FloatArray, limit: Int): List<Pair<AliasRecord, Float>> {
        // The schema's guidance is to load vectors into RAM and brute-force; a few
        // hundred aliases is far below the point where an ANN index would pay off,
        // and an exact scan is reproducible, which matters for the eval set.
        val aliasIds = db.query(
            "SELECT id FROM aliases WHERE kind = ?",
            listOf("name"),
        ).mapNotNull { it.long("id") }.toSet()
        if (aliasIds.isEmpty()) return emptyList()

        val model = db.query("SELECT model FROM embeddings WHERE kind = ? LIMIT 1", listOf("alias"))
            .firstOrNull()?.string("model") ?: return emptyList()

        val scored = mutableListOf<Pair<AliasRecord, Float>>()
        for (row in db.query(Sql.Alias.EMBEDDINGS_FOR_MODEL, listOf("alias", model))) {
            val refId = row.string("ref_id")?.toLongOrNull() ?: continue
            if (refId !in aliasIds) continue
            val vec = row.blob("vec") ?: continue
            val similarity = dev.crewx.voiceassistant.core.db.VectorCodec.cosine(
                queryVector,
                dev.crewx.voiceassistant.core.db.VectorCodec.decode(vec),
            )
            val aliasRow = db.query(
                "${Sql.Alias.BY_CONTACT.substringBefore(" WHERE ")} WHERE a.id = ?",
                listOf(refId),
            ).firstOrNull() ?: continue
            scored += aliasRow.toAlias() to similarity
        }
        return scored
            .sortedByDescending { it.second }
            .take(limit)
    }

    /**
     * Records that a contact was acted on, for ranking and the command log.
     * Kept out of the read path on purpose (see the class comment).
     */
    /**
     * Records that this contact was contacted, incrementing `call_count` or
     * `message_count` as appropriate.
     *
     * `last_contacted_at` is shared by both because "when did I last reach this
     * person" is a single question; the counters stay separate because "who do I
     * call" and "who do I text" are different answers and are weighted differently
     * when picking a default channel.
     */
    fun recordUsage(contactId: Long, isCall: Boolean, nowSeconds: Long) {
        db.execute(
            if (isCall) Sql.Endpoint.MARK_CALL_USED else Sql.Endpoint.MARK_MESSAGE_USED,
            listOf(nowSeconds, contactId),
        )
    }

    fun bumpAliasUse(aliasId: Long, nowSeconds: Long) {
        db.execute(Sql.Alias.BUMP_USE, listOf(nowSeconds, aliasId))
    }

    /** Exposed for the resolver's `my <relation>` walk and for sync code. */
    fun addRelationship(subjectId: Long, relation: String, objectId: Long, source: String) {
        db.execute(Sql.Relation.UPSERT, listOf(subjectId, relation, objectId, source))
    }

    fun insertAlias(
        contactId: Long,
        alias: String,
        aliasNorm: String,
        phoneticKey: String? = null,
        phoneticKey2: String? = null,
        kind: String = "name",
        language: String? = null,
        weight: Double = 1.0,
        source: String = "android",
    ) {
        db.execute(
            Sql.AliasWrite.INSERT,
            listOf(contactId, alias, aliasNorm, phoneticKey, phoneticKey2, kind, language, weight, source),
        )
    }
}

// --------------------------------------------------------------- mapping ----

private fun SqlRow.toContact() = Contact(
    id = long("id") ?: 0L,
    lookupKey = string("android_lookup_key"),
    displayName = string("display_name").orEmpty(),
    givenName = string("given_name"),
    familyName = string("family_name"),
    nickname = string("nickname"),
    isSelf = int("is_self") == 1,
    isFavorite = int("is_favorite") == 1,
    preferredChannel = string("preferred_channel") ?: "auto",
)

private fun SqlRow.toAlias() = AliasRecord(
    id = long("id") ?: 0L,
    contactId = long("contact_id") ?: 0L,
    alias = string("alias").orEmpty(),
    aliasNorm = string("alias_norm").orEmpty(),
    phoneticKey = string("phonetic_key"),
    phoneticKey2 = string("phonetic_key2"),
    kind = string("kind") ?: "name",
    weight = double("weight") ?: 1.0,
    useCount = int("use_count") ?: 0,
)

private fun SqlRow.toEndpoint() = Endpoint(
    id = long("id") ?: 0L,
    contactId = long("contact_id") ?: 0L,
    type = string("type").orEmpty(),
    value = string("value").orEmpty(),
    label = string("label") ?: "mobile",
    isPrimary = int("is_primary") == 1,
    simSlot = int("sim_slot"),
)

/** Named-parameter convenience so callers can keep SQL readable. */
private fun SqlDb.queryNamed(sql: String, args: Map<String, Any?>): List<SqlRow> {
    val ordered = mutableListOf<Any?>()
    val rewritten = StringBuilder()
    var j = 0
    while (j < sql.length) {
        if (sql[j] == ':') {
            var k = j + 1
            while (k < sql.length && (sql[k].isLetterOrDigit() || sql[k] == '_')) k++
            val name = sql.substring(j, k)
            if (args.containsKey(name)) {
                rewritten.append('?')
                ordered += args[name]
                j = k
                continue
            }
        }
        rewritten.append(sql[j]); j++
    }
    return query(rewritten.toString(), ordered)
}
