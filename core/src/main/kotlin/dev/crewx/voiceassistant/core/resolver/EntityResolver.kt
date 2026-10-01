package dev.crewx.voiceassistant.core.resolver

import dev.crewx.voiceassistant.core.text.DoubleMetaphone
import dev.crewx.voiceassistant.core.text.TextNormalizer
import kotlin.math.ln

/**
 * Turns a raw spoken reference ("my wife", "Rohan", "bunty") into a real contact.
 *
 * Tiers run in the order fixed by the header of `assistant_schema.sql`, and the
 * first tier that produces a confident winner wins outright — cheaper tiers are
 * never diluted by weaker ones:
 *
 *  0. RELATION   possessive chains, e.g. "my wife's brother" (two hops)
 *  1. EXACT      normalized alias equality
 *  2. FTS        token/prefix match
 *  3. PHONETIC   Double Metaphone, the defense against STT mishearing
 *  4. EMBEDDING  cosine similarity, last resort only
 *
 * Scoring inside a tier is
 * `score = baseWeight * tierSignal + 0.1*ln(1+callCount) + recencyBoost`,
 * and a result is auto-picked only when the top score clears [HIGH_THRESHOLD]
 * *and* leads the runner-up by [MARGIN]. Otherwise the caller gets the top two
 * and asks. This gate is what makes `call_contact` (confirm = ambiguous) safe.
 */
class EntityResolver(
    private val store: ContactStore,
    private val embeddings: EmbeddingIndex? = null,
    private val highThreshold: Double = HIGH_THRESHOLD,
    private val margin: Double = MARGIN,
    private val maxDepth: Int = MAX_RELATION_DEPTH,
    private val recencyBoostHalfLifeDays: Double = 30.0,
) {

    /**
     * @param channel optional channel preference ("sms","whatsapp","telegram",…)
     * @param endpointLabel optional "mobile"/"home"/"work"
     */
    fun resolve(
        spoken: String,
        channel: String? = null,
        endpointLabel: String? = null,
    ): Resolution {
        val raw = spoken.trim()
        if (raw.isEmpty()) return Resolution.none(spoken)

        // Tier 0: relation phrases, e.g. "my wife", "wife", "my wife's brother".
        val relationHits = resolveRelationChain(raw, channel, endpointLabel)
        if (relationHits.size == 1) {
            return Resolution(raw, relationHits, autoPick = true, tier = MatchTier.RELATION)
        }
        if (relationHits.size > 1) {
            // Several people match the relation word ("friend"): fall through to
            // normal matching but keep relation candidates as a floor.
            val ranked = rank(relationHits).map { it.copy(matchedVia = MatchTier.RELATION) }
            return finish(raw, ranked)
        }

        // A possessive survived normalization as a separate "s" token
        // ("wife s brother"), so drop it before treating tokens as names.
        val queryNorm = TextNormalizer.normalizeForEntity(raw)
            .split(' ')
            .filter { it.isNotEmpty() && it != "s" }
            .joinToString(" ")
        if (queryNorm.isEmpty()) return Resolution.none(spoken)

        // Tier 1: exact.
        val exact = store.aliasExact(queryNorm)
        if (exact.isNotEmpty()) {
            val cands = exact.map { it.toCandidate(MatchTier.EXACT, queryNorm) }
            return finish(raw, rank(cands).map { it.copy(matchedVia = MatchTier.EXACT) })
        }

        // Tier 2: token/prefix. A multi-token hit here means the user spoke a
        // real partial name ("rohan kap"), so it is trusted over phonetic.
        val fts = store.aliasFts(queryNorm, FTS_LIMIT)
        if (fts.isNotEmpty()) {
            val cands = fts.map { it.toCandidate(MatchTier.FTS, queryNorm) }
            return finish(raw, rank(cands).map { it.copy(matchedVia = MatchTier.FTS) })
        }

        // Tier 3: phonetic. Keyed per token, not on the whole string, so
        // "rohan kapur" can still find "rohan kapoor" via the surname alone.
        val keys = phoneticKeys(queryNorm)
        if (keys.isNotEmpty()) {
            val phon = store.aliasPhonetic(keys)
            if (phon.isNotEmpty()) {
                val cands = phon.map { rec ->
                    // Reward token overlap: matching more of the query with the
                    // same key is stronger evidence than matching one token.
                    val qTokens = queryNorm.split(' ').filter { it.isNotEmpty() }
                    val aTokens = rec.aliasNorm.split(' ').filter { it.isNotEmpty() }
                    val sharedTokens = qTokens.count { q -> aTokens.any { it == q } }
                    rec.toCandidate(MatchTier.PHONETIC, queryNorm).copy(
                        score = rec.toCandidate(MatchTier.PHONETIC, queryNorm).score +
                            0.06 * sharedTokens,
                    )
                }
                return finish(raw, rank(cands).map { it.copy(matchedVia = MatchTier.PHONETIC) })
            }
        }

        // Tier 4: embedding.
        if (embeddings != null) {
            val vec = embeddings.embed(queryNorm)
            if (vec != null) {
                val sem = store.aliasEmbeddingCandidates(vec, EMBED_LIMIT)
                    .filter { it.second >= EMBED_MIN_SIMILARITY }
                if (sem.isNotEmpty()) {
                    val cands = sem.map { (rec, sim) ->
                        rec.toCandidate(MatchTier.EMBEDDING, queryNorm).copy(
                            score = MatchTier.EMBEDDING.baseWeight * sim,
                        )
                    }
                    return finish(raw, rank(cands).map { it.copy(matchedVia = MatchTier.EMBEDDING) })
                }
            }
        }

        return Resolution.none(spoken)
    }

    /**
     * Handles possessives: "my wife", "wife", "my wife's brother".
     *
     * Depth is capped at [maxDepth] and every visited id is tracked, so a
     * malformed graph ("my wife's wife's wife") terminates instead of looping.
     */
    private fun resolveRelationChain(
        raw: String,
        channel: String?,
        endpointLabel: String?,
    ): List<ResolutionCandidate> {
        val self = store.selfId() ?: return emptyList()
        // Normalization turns "wife's" into the two tokens "wife" + "s"; the
        // clitic carries no meaning here, so it is dropped before the walk.
        val tokens = TextNormalizer.normalizeForEntity(raw)
            .split(' ')
            .filter { it.isNotEmpty() && it != "s" }
        if (tokens.isEmpty()) return emptyList()

        // Collect relation words in order; the chain walks subject forward.
        var current = self
        val visited = mutableSetOf(self)
        var hops = 0

        for ((index, token) in tokens.withIndex()) {
            val canonical = store.canonicalRelation(token) ?: return emptyList()

            // A possessive ("wife's") was normalized away by the stripper, so we
            // also accept the possessive form explicitly.
            val possessiveToken = token.removeSuffix("s")
            val canonical2 = if (possessiveToken != token) store.canonicalRelation(possessiveToken) else null
            val rel = canonical ?: canonical2 ?: return emptyList()

            val objects = store.relationshipsOf(current, rel)
            if (objects.isEmpty()) return emptyList()
            if (++hops > maxDepth) return emptyList()

            // Only a terminal single match can be resolved; multi-match
            // intermediate steps stop the chain and let the caller clarify.
            if (objects.size > 1) return emptyList()

            val next = objects.first()
            if (!visited.add(next)) return emptyList()
            current = next

            // If this was the last token, we have our answer.
            if (index == tokens.lastIndex) {
                val contact = store.contact(current) ?: return emptyList()
                return listOf(
                    ResolutionCandidate(
                        contactId = contact.id,
                        displayName = contact.displayName,
                        matchedVia = MatchTier.RELATION,
                        matchedText = raw,
                        score = MatchTier.RELATION.baseWeight * RELATION_SIGNAL,
                        endpoint = store.endpointFor(contact.id, channel, endpointLabel),
                    ),
                )
            }
        }
        return emptyList()
    }

    /**
     * Sorts candidates and decides auto-pick.
     *
     * Two candidates that are the *same contact* (e.g. matched on both "rohan"
     * and "rohan das") are collapsed first — otherwise the margin check would
     * treat one person as ambiguous against themselves.
     */
    private fun finish(query: String, ranked: List<ResolutionCandidate>): Resolution {
        val collapsed = ranked
            .sortedByDescending { it.score }
            .fold(mutableListOf<ResolutionCandidate>()) { acc, c ->
                val existing = acc.indexOfFirst { it.contactId == c.contactId }
                if (existing >= 0) {
                    if (c.score > acc[existing].score) acc[existing] = c
                } else {
                    acc.add(c)
                }
                acc
            }

        val top = collapsed.firstOrNull() ?: return Resolution.none(query)
        if (collapsed.size == 1) {
            return Resolution(query, collapsed, autoPick = true, tier = top.matchedVia)
        }

        val runnerUp = collapsed[1]
        val gap = top.score - runnerUp.score
        val autoPick = top.score >= highThreshold && gap >= margin
        return Resolution(query, collapsed, autoPick = autoPick, tier = top.matchedVia)
    }

    private fun rank(candidates: List<ResolutionCandidate>): List<ResolutionCandidate> =
        candidates.sortedByDescending { it.score }

    private fun AliasRecord.toCandidate(tier: MatchTier, queryNorm: String): ResolutionCandidate {
        val contact = store.contact(contactId)
        val signal = when (tier) {
            MatchTier.EXACT -> if (aliasNorm == queryNorm) EXACT_SIGNAL else PREFIX_SIGNAL
            MatchTier.FTS -> PREFIX_SIGNAL
            MatchTier.PHONETIC -> PHONETIC_SIGNAL
            else -> PREFIX_SIGNAL
        }
        val usage = 0.10 * ln(1.0 + useCount)
        val recency = recencyBoost(store.lastContactedAt(contactId))
        return ResolutionCandidate(
            contactId = contactId,
            displayName = contact?.displayName ?: alias,
            matchedVia = tier,
            matchedText = alias,
            score = tier.baseWeight * signal + usage + recency + aliasWeightBonus(weight),
        )
    }

    private fun aliasWeightBonus(weight: Double): Double = 0.05 * (weight - 1.0).coerceIn(0.0, 2.0)

    private fun recencyBoost(lastContactedAt: Long?): Double {
        if (lastContactedAt == null || lastContactedAt <= 0L) return 0.0
        val nowSec = System.currentTimeMillis() / 1000
        val days = ((nowSec - lastContactedAt) / 86_400.0).coerceAtLeast(0.0)
        return 0.08 * 0.5.pow(days / recencyBoostHalfLifeDays)
    }

    private fun Double.pow(exponent: Double): Double =
        Math.pow(this, exponent)

    private fun phoneticKeys(queryNorm: String): Set<String> {
        if (queryNorm.length < 2) return emptySet()
        val (p, s) = DoubleMetaphone.encode(queryNorm)
        return setOf(p, s).filter { it.isNotBlank() }.toSet()
    }

    companion object {
        const val HIGH_THRESHOLD = 1.00
        const val MARGIN = 0.12

        private const val MAX_RELATION_DEPTH = 3
        private const val FTS_LIMIT = 8
        private const val EMBED_LIMIT = 8
        private const val EMBED_MIN_SIMILARITY = 0.55

        private const val RELATION_SIGNAL = 1.0
        private const val EXACT_SIGNAL = 1.0
        private const val PREFIX_SIGNAL = 0.9
        private const val PHONETIC_SIGNAL = 0.8
    }
}

/** Optional tier-4 helper. Null embeddings simply disable the tier. */
interface EmbeddingIndex {
    fun embed(text: String): FloatArray?
}

object NoopEmbeddingIndex : EmbeddingIndex {
    override fun embed(text: String): FloatArray? = null
}
