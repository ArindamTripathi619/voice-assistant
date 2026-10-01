package dev.crewx.voiceassistant.core.db

/**
 * SQL text shared by every storage backend.
 *
 * There is no Room here on purpose. `assistant_schema.sql` relies on FTS5
 * virtual tables, `CREATE TRIGGER`, a partial unique index and views, none of
 * which Room can declare; using it would have meant hand-writing most of the DDL
 * anyway and paying an annotation processor for a row mapper.
 *
 * Instead the query text lives here exactly once and is executed by two thin
 * drivers: JDBC (JVM tests, so the real schema and real SQL are verified without
 * a device) and SQLCipher (on device). A bug in a query is a bug in both, so the
 * tests that catch it are meaningful.
 */

/** One result row, independent of JDBC `ResultSet` or Android `Cursor`. */
interface SqlRow {
    fun isNull(column: String): Boolean
    fun string(column: String): String?
    fun long(column: String): Long?
    fun int(column: String): Int?
    fun double(column: String): Double?
    fun blob(column: String): ByteArray?
}

/** Minimal database surface. Blocking; callers own the threading. */
interface SqlDb {
    /** Returns an empty list for statements that produce no rows. */
    fun query(sql: String, args: List<Any?> = emptyList()): List<SqlRow>

    /** Must throw on constraint violations; callers rely on that to detect them. */
    fun execute(sql: String, args: List<Any?> = emptyList())

    /** Applies a multi-statement script such as the schema. */
    fun executeScript(sql: String)
}

object Sql {

    /** Matches `PRAGMA user_version` for the shipped schema. */
    const val SCHEMA_VERSION = 1

    const val SCHEMA_RESOURCE = "/assistant_schema.sql"

    // ------------------------------------------------------------ schema ----

    /**
     * Splits a SQL script into individual statements.
     *
     * Naive splitting on `;` breaks this schema: the `aliases_fts` triggers
     * contain `BEGIN ... END;` blocks whose inner semicolons are not statement
     * terminators. This tracks string literals, comments and `BEGIN`/`END`
     * nesting so trigger bodies survive intact.
     */
    fun splitStatements(script: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        var blockDepth = 0
        while (i < script.length) {
            val c = script[i]
            when {
                c == '-' && i + 1 < script.length && script[i + 1] == '-' -> {
                    // Line comment: consume through end of line.
                    while (i < script.length && script[i] != '\n') i++
                    continue
                }
                c == '/' && i + 1 < script.length && script[i + 1] == '*' -> {
                    val end = script.indexOf("*/", i + 2)
                    i = if (end < 0) script.length else end + 2
                    continue
                }
                c == '\'' || c == '"' || c == '`' -> {
                    val quote = c
                    current.append(c); i++
                    while (i < script.length) {
                        if (script[i] == quote) {
                            // Doubled quote is an escaped quote, not a terminator.
                            if (i + 1 < script.length && script[i + 1] == quote) {
                                current.append(quote).append(quote); i += 2; continue
                            }
                            current.append(quote); i++
                            break
                        }
                        current.append(script[i]); i++
                    }
                    continue
                }
                blockDepth > 0 && c.isLetter() && script.startsWithWordAt(i, "END") -> {
                    blockDepth--
                    current.append("END"); i += 3
                    continue
                }
                blockDepth == 0 && c.isLetter() && script.startsWithWordAt(i, "BEGIN") -> {
                    blockDepth++
                    current.append("BEGIN"); i += 5
                    continue
                }
                c == ';' -> {
                    if (blockDepth == 0) {
                        // Keep the terminator: it makes the output readable in
                        // failure messages, and SQLite accepts it either way.
                        statements += current.toString() + ';'
                        current.clear()
                    } else {
                        current.append(c)
                    }
                    i++
                    continue
                }
                else -> current.append(c)
            }
            i++
        }
        statements += current.toString()
        return statements.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun String.startsWithWordAt(index: Int, word: String): Boolean {
        if (!regionMatches(index, word, 0, word.length, ignoreCase = true)) return false
        val after = index + word.length
        val next = getOrNull(after)
        return next == null || !next.isLetterOrDigit() && next != '_'
    }

    // -------------------------------------------------------------- FTS -----

    /**
     * Builds an FTS5 `MATCH` expression with a prefix on the final token.
     *
     * The input is spoken text, i.e. attacker-influenced in the sense that a
     * mis-heard name becomes a query string. FTS5 treats `:`, `"`, `*`, `-` and
     * parentheses as syntax, so anything that is not a letter or digit is
     * dropped and the query is rebuilt from scratch. Without this, a name
     * containing a colon can turn a prefix search into a column filter and
     * silently match the wrong rows.
     */
    fun ftsPrefixQuery(raw: String): String? {
        val tokens = raw.split(' ', '\t', '\n')
            .map { token -> token.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return (tokens.dropLast(1) + (tokens.last() + "*")).joinToString(" ")
    }

    // ------------------------------------------------------- projection -----

    /**
     * Columns every alias lookup returns.
     *
     * `contact_id` is carried so callers can resolve to an endpoint without a
     * second round trip, and `use_count` so the resolver can rank by frequency.
     */
    const val ALIAS_COLUMNS =
        "SELECT a.id AS id, a.contact_id AS contact_id, a.alias AS alias, " +
            "a.alias_norm AS alias_norm, a.phonetic_key AS phonetic_key, " +
            "a.phonetic_key2 AS phonetic_key2, a.kind AS kind, " +
            "a.weight AS weight, a.use_count AS use_count"

    const val ALIAS_FROM = " FROM aliases a JOIN contacts c ON c.id = a.contact_id"

    // ----------------------------------------------------------- queries ----

    object Contact {
        const val BY_ID = "SELECT * FROM contacts WHERE id = ?"
        const val BY_LOOKUP_KEY = "SELECT * FROM contacts WHERE android_lookup_key = ?"
        const val SELF = "SELECT * FROM contacts WHERE is_self = 1 LIMIT 1"
        const val ALL = "SELECT * FROM contacts ORDER BY display_name COLLATE NOCASE"
    }

    object Alias {
        const val BY_EXACT = "$ALIAS_COLUMNS $ALIAS_FROM WHERE a.alias_norm = ?"

        /**
         * FTS tier. `bm25()` ascending is "better"; ORDER BY ascending is
         * therefore best-first. `aliases_fts MATCH ?` is bound, never
         * interpolated — the value comes from [ftsPrefixQuery].
         */
        const val BY_FTS =
            "$ALIAS_COLUMNS $ALIAS_FROM JOIN aliases_fts f ON f.rowid = a.id " +
                "WHERE aliases_fts MATCH ? ORDER BY bm25(aliases_fts) LIMIT ?"

        const val BY_PHONETIC =
            "$ALIAS_COLUMNS $ALIAS_FROM WHERE a.phonetic_key IN (${'$'}{KEYS}) " +
                "OR a.phonetic_key2 IN (${'$'}{KEYS})"

        const val BY_CONTACT = "$ALIAS_COLUMNS $ALIAS_FROM WHERE a.contact_id = ? ORDER BY a.weight DESC"

        const val BUMP_USE = "UPDATE aliases SET use_count = use_count + 1, last_used_at = ? WHERE id = ?"

        const val EMBEDDINGS_FOR_MODEL =
            "SELECT kind, ref_id, vec FROM embeddings WHERE kind = ? AND model = ?"
    }

    object Endpoint {
        const val FOR_CONTACT = "SELECT * FROM contact_endpoints WHERE contact_id = ? ORDER BY is_primary DESC, id"

        /**
         * Endpoint selection.
         *
         * The `ORDER BY` is the fallback policy from the schema comments: an
         * explicit `channel` wins, then the contact's own `preferred_channel`
         * when it is not 'auto', then any endpoint of a type that can carry the
         * message, then the primary phone. Returning no row is meaningful — the
         * caller must ask rather than guess a number.
         */
        private const val RESOLVE_RANKING = """
            ORDER BY
              CASE WHEN :channel IS NOT NULL AND type = :channel THEN 0
                   WHEN :channel IS NULL AND :preferred = 'auto' THEN 0
                   WHEN :channel IS NULL AND type = :preferred THEN 1
                   WHEN type IN ('phone','whatsapp','telegram') THEN 2
                   ELSE 3 END,
              is_primary DESC, id ASC
            LIMIT 1
        """

        const val RESOLVE =
            "SELECT * FROM contact_endpoints WHERE contact_id = :contactId $RESOLVE_RANKING"

        /**
         * Spoken label ("her work number") must narrow the candidate set before
         * ranking, so it is a `WHERE` predicate rather than another sort key -
         * a sort key would merely prefer a labelled endpoint over an unlabelled
         * one, not exclude the unlabelled ones.
         */
        const val RESOLVE_LABELLED =
            "SELECT * FROM contact_endpoints WHERE contact_id = :contactId AND label = :label $RESOLVE_RANKING"

        const val MARK_CALL_USED =
            "UPDATE contacts SET call_count = call_count + 1, last_contacted_at = ? WHERE id = ?"

        /**
         * Messages are counted separately from calls: `last_contacted_at` is shared
         * (recency of any contact) but the two counters answer different questions,
         * and folding messages into `call_count` would make a frequently texted
         * contact look like a frequently called one.
         */
        const val MARK_MESSAGE_USED =
            "UPDATE contacts SET message_count = message_count + 1, last_contacted_at = ? WHERE id = ?"
    }

    object Relation {
        const val VOCAB_TERM = "SELECT canonical FROM relation_vocab WHERE term = ?"
        const val ALL_VOCAB = "SELECT term, canonical FROM relation_vocab"
        const val OBJECTS = "SELECT object_id FROM relationships WHERE subject_id = ? AND relation = ?"
        const val UPSERT =
            "INSERT OR IGNORE INTO relationships (subject_id, relation, object_id, source) VALUES (?, ?, ?, ?)"
        const val SUBJECTS = "SELECT subject_id FROM relationships WHERE object_id = ? AND relation = ?"
    }

    object AliasWrite {
        const val INSERT = """
            INSERT INTO aliases (contact_id, alias, alias_norm, phonetic_key, phonetic_key2,
                                 kind, language, weight, source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        /**
         * Idempotent name import, used by contact sync.
         *
         * `OR IGNORE` rather than an upsert: an alias is a *taught* fact, and a
         * resync that silently rewrote `alias`, `kind`, `source` or `weight` would
         * destroy the record of how the user chose to refer to this person. The
         * phonetic keys are filled only when the insert actually happened, because
         * a conflicting row already has them.
         */
        const val INSERT_IGNORE_NAME = """
            INSERT OR IGNORE INTO aliases (contact_id, alias, alias_norm, phonetic_key, phonetic_key2,
                                           kind, source)
            VALUES (?, ?, ?, ?, ?, 'name', 'android')
        """

        const val SET_PHONETIC =
            "UPDATE aliases SET phonetic_key = ?, phonetic_key2 = ? " +
                "WHERE contact_id = ? AND alias_norm = ?"

        const val UPSERT_EMBEDDING =
            "INSERT OR REPLACE INTO embeddings (kind, ref_id, model, vec) VALUES (?, ?, ?, ?)"
    }

    object App {
        const val BY_NORM = "SELECT * FROM apps WHERE label_norm = ? LIMIT 1"
        const val ALL = "SELECT * FROM apps ORDER BY label_norm"
    }

    object SpotifyItem {
        const val BY_NORM = "SELECT * FROM spotify_items WHERE name_norm = ? LIMIT 1"
        const val BY_ARTIST_NORM =
            "SELECT * FROM spotify_items WHERE artist_norm = ? ORDER BY local_play_count DESC LIMIT ?"
        const val BY_PHONETIC = "SELECT * FROM spotify_items WHERE phonetic_key = ? LIMIT 1"
        const val PLAYABLE = """
            SELECT * FROM spotify_items
            WHERE source IN ('liked','playlist','top','followed')
              AND (name_norm LIKE ? OR artist_norm LIKE ?)
            ORDER BY local_play_count DESC LIMIT 5
        """

        const val BUMP_PLAY = """
            UPDATE spotify_items
            SET local_play_count = local_play_count + 1, last_played_at = ?
            WHERE uri = ?
        """
    }

    object Routine {
        const val BY_NORM = "SELECT * FROM routines WHERE name_norm = ?"
        const val ALL = "SELECT * FROM routines ORDER BY name_norm"
        const val INSERT = "INSERT INTO routines (name, name_norm, steps_json) VALUES (?, ?, ?)"
    }

    object Kv {
        const val GET = "SELECT value FROM kv WHERE key = ?"
        const val PUT = "INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)"
        const val DELETE = "DELETE FROM kv WHERE key = ?"
    }

    object CommandLog {
        const val INSERT = """
            INSERT INTO command_log (transcript, stt_model, route, tool_call_json, resolved_json,
                                     outcome, corrected_call_json, stt_ms, route_ms, exec_ms, audio_path)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        const val RECENT = "SELECT * FROM command_log ORDER BY ts DESC LIMIT ?"
        const val CORRECTED = """
            SELECT transcript, tool_call_json, corrected_call_json
            FROM command_log WHERE corrected_call_json IS NOT NULL ORDER BY ts DESC LIMIT ?
        """

        const val LATENCY = """
            SELECT route, COUNT(*) AS n, AVG(route_ms) AS avg_route_ms, AVG(exec_ms) AS avg_exec_ms
            FROM command_log WHERE ts >= ? GROUP BY route
        """
    }

    object KnownTerms {
        /**
         * Feeds the STT grammar/hotword list and post-ASR phonetic matching.
         * Mirrors the `v_known_terms` view in the schema.
         */
        const val ALL = """
            SELECT alias_norm AS term, 'contact' AS kind FROM aliases
            UNION SELECT label_norm, 'app' FROM apps
            UNION SELECT name_norm, 'spotify' FROM spotify_items
                  WHERE source IN ('liked','playlist','top','followed')
            UNION SELECT name_norm, 'routine' FROM routines
            UNION SELECT term, 'relation' FROM relation_vocab
            ORDER BY kind, term
        """
    }
}
