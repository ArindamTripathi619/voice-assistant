-- assistant_schema.sql  (v1)
-- Target: SQLite 3.35+ with FTS5. Encrypt with SQLCipher in production.
-- NOTE: Android's framework SQLite may not expose FTS5 on every version;
-- SQLCipher-for-Android or requery/sqlite-android bundle their own build
-- (verify on your device before committing to FTS5).
--
-- ENTITY RESOLUTION ORDER (done in app code, not SQL):
--   0. Possessive relation chain ("my wife's brother") -> relationships table
--   1. Exact alias_norm match
--   2. FTS5 token/prefix match on aliases_fts
--   3. Phonetic match (Double Metaphone keys computed in app code; SQLite has none)
--   4. Embedding cosine over `embeddings` (loaded into RAM; brute force is fine
--      for a few hundred rows)
--   Score = tier_score * weight + 0.1*ln(1+call_count) + recency_boost
--   Auto-pick only if top score >= T_HIGH and gap to runner-up >= MARGIN;
--   otherwise ask "Rahul Das or Rahul Sen?" using the top 2.

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
PRAGMA user_version = 1;

-- ---------------------------------------------------------------- contacts --

CREATE TABLE contacts (
  id                  INTEGER PRIMARY KEY,
  android_lookup_key  TEXT UNIQUE,            -- ContactsContract LOOKUP_KEY; NULL for assistant-only contacts
  display_name        TEXT NOT NULL,
  given_name          TEXT,
  family_name         TEXT,
  nickname            TEXT,
  is_self             INTEGER NOT NULL DEFAULT 0 CHECK (is_self IN (0,1)),
  is_favorite         INTEGER NOT NULL DEFAULT 0 CHECK (is_favorite IN (0,1)),
  preferred_channel   TEXT NOT NULL DEFAULT 'auto'
                        CHECK (preferred_channel IN ('auto','sms','rcs','whatsapp','telegram')),
  call_count          INTEGER NOT NULL DEFAULT 0,
  message_count       INTEGER NOT NULL DEFAULT 0,
  last_contacted_at   INTEGER,                -- unix seconds
  updated_at          INTEGER NOT NULL DEFAULT (strftime('%s','now'))
);

-- exactly one "me" row, so relationships can hang off it
CREATE UNIQUE INDEX idx_contacts_one_self ON contacts(is_self) WHERE is_self = 1;

CREATE TABLE contact_endpoints (
  id          INTEGER PRIMARY KEY,
  contact_id  INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
  type        TEXT NOT NULL CHECK (type IN ('phone','whatsapp','telegram','email')),
  value       TEXT NOT NULL,                  -- E.164 for phone/whatsapp; @username or numeric id for telegram
  label       TEXT NOT NULL DEFAULT 'mobile' CHECK (label IN ('mobile','home','work','other')),
  is_primary  INTEGER NOT NULL DEFAULT 0 CHECK (is_primary IN (0,1)),
  sim_slot    INTEGER CHECK (sim_slot IN (1,2)),   -- preferred SIM for calls to this number
  UNIQUE (contact_id, type, value)
);
CREATE INDEX idx_endpoints_contact ON contact_endpoints(contact_id, type);
-- Resolver rule: if a contact has no 'whatsapp' endpoint, fall back to its
-- primary 'phone' endpoint (WhatsApp links are number-based).

-- ----------------------------------------------------------------- aliases --
-- Names and nicknames only. Family/relationship words live in `relationships`.

CREATE TABLE aliases (
  id            INTEGER PRIMARY KEY,
  contact_id    INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
  alias         TEXT NOT NULL,                -- as stored/taught
  alias_norm    TEXT NOT NULL,                -- lowercase, diacritics stripped, punctuation removed
  phonetic_key  TEXT,                         -- primary Double Metaphone (app-computed)
  phonetic_key2 TEXT,                         -- secondary key
  kind          TEXT NOT NULL DEFAULT 'name'
                  CHECK (kind IN ('name','nickname','taught','asr_variant')),
  language      TEXT,                         -- 'en','hi','bn',... optional
  weight        REAL NOT NULL DEFAULT 1.0,    -- taught/asr_variant can be boosted
  source        TEXT NOT NULL DEFAULT 'android'
                  CHECK (source IN ('android','user_taught','learned_asr','manual')),
  use_count     INTEGER NOT NULL DEFAULT 0,
  last_used_at  INTEGER,
  created_at    INTEGER NOT NULL DEFAULT (strftime('%s','now')),
  UNIQUE (alias_norm, contact_id)
);
CREATE INDEX idx_aliases_norm     ON aliases(alias_norm);
CREATE INDEX idx_aliases_phon1    ON aliases(phonetic_key);
CREATE INDEX idx_aliases_phon2    ON aliases(phonetic_key2);
CREATE INDEX idx_aliases_contact  ON aliases(contact_id);

-- On contact sync, app code inserts kind='name' rows for: full name, given
-- name, family name, nickname. When the STT repeatedly mishears a name and the
-- user corrects it, insert the misheard form as kind='asr_variant'.

CREATE VIRTUAL TABLE aliases_fts USING fts5(
  alias_norm,
  content='aliases', content_rowid='id',
  tokenize='unicode61 remove_diacritics 2'
);
CREATE TRIGGER aliases_ai AFTER INSERT ON aliases BEGIN
  INSERT INTO aliases_fts(rowid, alias_norm) VALUES (new.id, new.alias_norm);
END;
CREATE TRIGGER aliases_ad AFTER DELETE ON aliases BEGIN
  INSERT INTO aliases_fts(aliases_fts, rowid, alias_norm) VALUES ('delete', old.id, old.alias_norm);
END;
CREATE TRIGGER aliases_au AFTER UPDATE ON aliases BEGIN
  INSERT INTO aliases_fts(aliases_fts, rowid, alias_norm) VALUES ('delete', old.id, old.alias_norm);
  INSERT INTO aliases_fts(rowid, alias_norm) VALUES (new.id, new.alias_norm);
END;

-- ----------------------------------------------------------- relationships --
-- Meaning: object IS the subject's <relation>.
--   "my wife"           -> subject = self,      relation = 'wife'
--   "my wife's brother" -> (self,'wife',X) then (X,'brother',Y)
-- Seed the self->X rows from Android's Relation field; ask once when unknown
-- ("Who is your wife?") and store the answer.

CREATE TABLE relation_vocab (
  term       TEXT PRIMARY KEY,                -- as spoken, normalized lowercase
  canonical  TEXT NOT NULL                    -- canonical relation used in `relationships`
);

CREATE TABLE relationships (
  subject_id  INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
  relation    TEXT NOT NULL,                  -- canonical value from relation_vocab
  object_id   INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
  source      TEXT NOT NULL DEFAULT 'user_taught'
                CHECK (source IN ('android','user_taught','manual')),
  PRIMARY KEY (subject_id, relation, object_id),
  CHECK (subject_id <> object_id)
);
CREATE INDEX idx_relationships_object ON relationships(object_id);

INSERT INTO relation_vocab(term, canonical) VALUES
  ('wife','wife'), ('spouse','spouse'), ('husband','husband'),
  ('mom','mother'), ('mum','mother'), ('mother','mother'),
  ('dad','father'), ('father','father'),
  ('brother','brother'), ('sister','sister'),
  ('son','son'), ('daughter','daughter'),
  ('boss','boss'), ('friend','friend');
-- Add your own spoken variants (Hindi / Bengali / Hinglish terms) as extra rows.

-- -------------------------------------------------------------- embeddings --
-- Fallback fuzzy matching only. Store int8/float16 vectors; load into RAM at
-- startup. `model` lets you re-embed cleanly when you swap embedding models.

CREATE TABLE embeddings (
  kind    TEXT NOT NULL CHECK (kind IN ('alias','spotify','app','routine')),
  ref_id  TEXT NOT NULL,                      -- aliases.id / spotify uri / package / routine id
  model   TEXT NOT NULL,
  vec     BLOB NOT NULL,
  PRIMARY KEY (kind, ref_id, model)
);

-- ----------------------------------------------------------------- spotify --
-- Synced from the Web API (your own library only) so "play X" resolves locally
-- to a URI with no network call. Playback goes through App Remote.

CREATE TABLE spotify_items (
  uri               TEXT PRIMARY KEY,         -- spotify:track:... etc.
  kind              TEXT NOT NULL CHECK (kind IN ('track','artist','album','playlist')),
  name              TEXT NOT NULL,
  name_norm         TEXT NOT NULL,
  artist_names      TEXT,
  artist_norm       TEXT,
  phonetic_key      TEXT,
  source            TEXT NOT NULL CHECK (source IN ('liked','playlist','top','followed','recent')),
  local_play_count  INTEGER NOT NULL DEFAULT 0,   -- your own counter, for ranking
  last_played_at    INTEGER,
  synced_at         INTEGER NOT NULL DEFAULT (strftime('%s','now'))
);
CREATE INDEX idx_spotify_kind ON spotify_items(kind);
CREATE INDEX idx_spotify_phon ON spotify_items(phonetic_key);

CREATE VIRTUAL TABLE spotify_fts USING fts5(
  name_norm, artist_norm,
  content='spotify_items', content_rowid='rowid',
  tokenize='unicode61 remove_diacritics 2'
);
CREATE TRIGGER spotify_ai AFTER INSERT ON spotify_items BEGIN
  INSERT INTO spotify_fts(rowid, name_norm, artist_norm) VALUES (new.rowid, new.name_norm, new.artist_norm);
END;
CREATE TRIGGER spotify_ad AFTER DELETE ON spotify_items BEGIN
  INSERT INTO spotify_fts(spotify_fts, rowid, name_norm, artist_norm) VALUES ('delete', old.rowid, old.name_norm, old.artist_norm);
END;
CREATE TRIGGER spotify_au AFTER UPDATE ON spotify_items BEGIN
  INSERT INTO spotify_fts(spotify_fts, rowid, name_norm, artist_norm) VALUES ('delete', old.rowid, old.name_norm, old.artist_norm);
  INSERT INTO spotify_fts(rowid, name_norm, artist_norm) VALUES (new.rowid, new.name_norm, new.artist_norm);
END;

-- -------------------------------------------------------------------- apps --

CREATE TABLE apps (
  package           TEXT PRIMARY KEY,
  label             TEXT NOT NULL,
  label_norm        TEXT NOT NULL,
  phonetic_key      TEXT,
  launch_count      INTEGER NOT NULL DEFAULT 0,
  last_launched_at  INTEGER
);
CREATE INDEX idx_apps_norm ON apps(label_norm);

-- ---------------------------------------------------------------- routines --
-- steps_json: [{"tool":"toggle_setting","args":{"setting":"do_not_disturb","state":"on"}}, ...]
-- Same tool names/args as tools.json, so a routine is just a replayed call list.

CREATE TABLE routines (
  id          INTEGER PRIMARY KEY,
  name        TEXT NOT NULL,
  name_norm   TEXT NOT NULL UNIQUE,
  steps_json  TEXT NOT NULL,
  created_at  INTEGER NOT NULL DEFAULT (strftime('%s','now'))
);

-- ------------------------------------------------------------- command log --
-- Local only. Feeds (a) your eval set and (b) LoRA fine-tuning data.
-- corrected_call_json is filled when you say "no, I meant..." or fix a call.

CREATE TABLE command_log (
  id                  INTEGER PRIMARY KEY,
  ts                  INTEGER NOT NULL DEFAULT (strftime('%s','now')),
  transcript          TEXT NOT NULL,
  stt_model           TEXT,
  route               TEXT NOT NULL CHECK (route IN ('fast','tier1','tier2','manual')),
  tool_call_json      TEXT,                   -- what the router/LLM produced
  resolved_json       TEXT,                   -- after entity resolution (IDs, URIs)
  outcome             TEXT NOT NULL CHECK (outcome IN ('ok','clarified','cancelled','failed','unsupported')),
  corrected_call_json TEXT,
  stt_ms              INTEGER,
  route_ms            INTEGER,
  exec_ms             INTEGER,
  audio_path          TEXT                    -- NULL by default; opt in if you want audio for STT evals
);
CREATE INDEX idx_command_log_ts ON command_log(ts);

CREATE TABLE kv (
  key    TEXT PRIMARY KEY,
  value  TEXT NOT NULL
);

-- ------------------------------------------------------------------- views --
-- Everything the STT/post-processor should "know": handy for grammar/hotword
-- lists (e.g. Vosk-style grammars) or for post-ASR phonetic matching.

CREATE VIEW v_known_terms AS
  SELECT alias_norm AS term, 'contact' AS kind FROM aliases
  UNION SELECT label_norm, 'app'      FROM apps
  UNION SELECT name_norm,  'spotify'  FROM spotify_items WHERE source IN ('liked','playlist','top','followed')
  UNION SELECT name_norm,  'routine'  FROM routines
  UNION SELECT term,       'relation' FROM relation_vocab;

-- ---------------------------------------------------- example resolver SQL --
-- Step 1, exact:
--   SELECT c.id, c.display_name, a.weight
--   FROM aliases a JOIN contacts c ON c.id = a.contact_id
--   WHERE a.alias_norm = :q;
--
-- Step 2, FTS (prefix on last token):
--   SELECT a.contact_id, bm25(aliases_fts) AS score
--   FROM aliases_fts JOIN aliases a ON a.id = aliases_fts.rowid
--   WHERE aliases_fts MATCH :fts_query ORDER BY score LIMIT 5;
--
-- Step 3, phonetic:
--   SELECT contact_id, weight FROM aliases
--   WHERE phonetic_key IN (:k1, :k2) OR phonetic_key2 IN (:k1, :k2);
--
-- "my <relation>":
--   SELECT r.object_id FROM relationships r
--   JOIN contacts me ON me.id = r.subject_id AND me.is_self = 1
--   JOIN relation_vocab v ON v.canonical = r.relation
--   WHERE v.term = :spoken_relation;
