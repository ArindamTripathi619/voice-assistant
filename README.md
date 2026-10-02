# voice-assistant

Local, privacy-first voice assistant for Android. Speaks to you, acts on your phone,
keeps every byte on the device.

**Status:** Phases 0–2 of the roadmap are complete and green on the JVM.
Phase 7 (synthetic training data + eval harness) is done and device-free.
Phases 3–6 and 8 need the physical Moto G34 5G.
Current truth lives in [ROADMAP.md](ROADMAP.md) §1a; the full plan is
[ROADMAP.md](ROADMAP.md).

---

## The thesis in one paragraph

CPU is the bottleneck, not RAM. So ~70–80% of commands never touch a neural
network at all — they are matched by a deterministic grammar and regex layer that
answers in under 250 ms. A tiny function-calling LLM (FunctionGemma 270M) is only
the fallback for whatever the grammar could not parse. Critically, **the model
never emits identifiers**: it emits the raw string a human would say
(`contact_ref`, `query`, `app_name`), and a separate resolver turns that into a
real contact or app. A hallucinated slot can therefore only ever cause a
clarification prompt — it cannot cause a text to the wrong person.

```
Wake word / power-button long-press
  → Silero VAD
  → Moonshine streaming STT
  → Fast path (grammar + regex + resolver)              ~70–80%, <250 ms
  → FunctionGemma 270M, constrained JSON               ~20–30%, ~1.0–1.3 s
  → EntityResolver (relation → exact → FTS5 → phonetic → embedding)
  → ToolExecutor (Android APIs / Shizuku / Accessibility / NotificationListener)
  → Confirm gate (never | ambiguous | always)
  → Android TTS
  → command_log (local only)
```

---

## Build and test

Requires **JDK 17+**. Always use the wrapper — system `gradle` 9.4 is incompatible
with the Android Gradle Plugin 9.x used here.

```bash
./gradlew :core:test           # 159 JVM tests, no device needed
./gradlew :app:assembleDebug   # debug APK
```

Both are currently green. The APK lands at
`app/build/outputs/apk/debug/app-debug.apk`.

`core` is a plain Kotlin/JVM module with no Android dependencies, which is why
the resolver, fast path, tool schema, and the entire SQL layer are testable in
under two seconds with no emulator.

---

## Layout

```
├── assistant_schema.sql        264-line schema: FTS5, triggers, views, constraints
├── tools.json                  17 tool definitions
├── ROADMAP.md                  phases, gates, risks, latency budget
├── core/                       pure Kotlin/JVM
│   └── …/core/
│       ├── tools/              ToolSpec, ToolsLoader (validation + group routing)
│       ├── text/               TextNormalizer, DoubleMetaphone
│       ├── resolver/           EntityResolver, ContactStore, SqlContactStore
│       ├── fastpath/           FastPath (grammar + slot extraction)
│       └── db/                 SqlDb, shared SQL, VectorCodec
├── app/                        Android module
│   └── …/
│       ├── data/SqlCipherDb    encrypted SQLite driver (Keystore-wrapped key)
│       ├── data/ContactSync    Android contacts → contacts/aliases/endpoints
│       └── MainActivity        temporary dev UI
├── scripts/
│   ├── voice_data.py           shared schema loader + validation
│   ├── gen_training_data.py    synthetic function-calling generator
│   └── eval_harness.py         exact-match scorer + refusal gates
├── eval/eval_set.jsonl         committed golden eval baseline
├── data/                       generated training data (gitignored)
└── docs/benchmarks.md          device measurement procedure
```

---

## Design decisions worth knowing before you read the code

**Raw SQL, not Room.** The schema needs FTS5 virtual tables,
`AFTER INSERT/UPDATE/DELETE` sync triggers, `CHECK` constraints, partial indexes,
and a view. Room's schema generator expresses none of those, so Room would mean a
generated schema *plus* hand-written SQL over the same tables — two sources of
truth, with Room's validator fighting every feature the resolver depends on. All
SQL lives in one file (`core/db/Sql.kt`) behind a three-method interface, so the
query layer is verified against real SQLite on every test run. See
[ROADMAP.md](ROADMAP.md) §3 for the full rationale.

**SQLCipher, not framework SQLite.** Framework SQLite does not reliably expose
FTS5 at `minSdk 26`, and the FTS tier is the whole reason the schema is shaped
this way. The passphrase is generated once and wrapped by a hardware-backed
Keystore key, so it never exists in plaintext.

**Contacts are imported as names and numbers only.** Message bodies are never
read. Not collecting them is cheaper than promising not to.

**`VectorCodec` preserves direction, not magnitude.** It normalizes by max-abs
and stores int8. Cosine similarity is scale-invariant, so this costs nothing for
the only thing embeddings are used for (the last-resort fuzzy tier) — but a decoded
vector must never be used where absolute magnitude matters. This is documented on
`decode` and pinned by tests.

**Everything outbound confirms first.** Every message and call reads the resolved
target back and waits for a spoken "yes". There is no code path that sends
silently, and adding one is a bug, not a feature.

---

## Known gaps

- `INTERNET` permission is declared but nothing uses it yet. The roadmap's
  definition of done wants a build provable without it; dropping it is a Phase 8
  task once Spotify is behind the local cache.
- No device work has started: no audio, no STT, no LLM, no executors, no
  confirmation gate.
- No instrumented tests, so FTS5 under SQLCipher on real hardware is unverified
  (it *is* verified under SQLite JDBC on the JVM).
- No migration path yet — `onUpgrade` throws rather than dropping the database,
  because dropping it would destroy taught aliases and relationships that cannot
  be resynced.
- The training corpus is synthetic and template-generated. It is a bootstrap for
  fine-tuning, not a substitute for real corrected utterances; the eval set is
  designed to absorb those as they accumulate.
- The generator has no model in the loop, so it can only produce phrasings its
  templates anticipate. Its real coverage comes from promoting `command_log`
  corrections into `eval/eval_set.jsonl` and retraining on them.

---

## Training data and evaluation

```sh
scripts/gen_training_data.py --out data/ --per-tool 60
scripts/eval_harness.py --dataset eval/eval_set.jsonl
```

Both scripts read `tools.json`, so a schema change is picked up on the next run
rather than needing edits in two places. Output is deterministic for a given
`--seed`.

**The generator only trains the LLM's actual territory.** The fast path already
handles 8 of the 17 tools, so the model only ever sees the residual — training
it on `set_flashlight` would spend gradient steps on behaviour that never
reaches it. Fast-path examples are generated too, but tagged `fast` and excluded
by default; they exist so the router can be verified, not to be trained on.

Three behaviours are taught deliberately because they are the ones a small
model gets wrong in the most damaging way:

- **Negations never become tool calls.** "don't call my wife" must not resolve to
  `call_contact`; polarity is not something to flip.
- **A missing required slot becomes `ask_clarification`,** never a partially
  filled call. A half-filled call is the most likely thing to execute wrongly.
- **An out-of-enum value becomes `ask_clarification`,** never a clamp. Asked for
  150% brightness the wrong answer is not an error — it is 100%, delivered
  confidently.

**Read the refusal gates, not the average.** The scorer exits non-zero if any
gate drops below threshold. A model that mishandles negations still scores ~77%
overall, because most examples are ordinary commands it gets right; the average
would pass it. See `eval/README.md`.

---

## Next

1. Instrumented FTS5 smoke test to close the SQLCipher question
2. Wire the eval harness to a real model run once the runner exists
3. Audio spine: Silero VAD + Moonshine on device
