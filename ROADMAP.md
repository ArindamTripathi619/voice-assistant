# Local RAG Voice Assistant — Build Roadmap

**Device target:** Moto G34 5G (8 GB, Snapdragon 695: 2× Cortex-A78 @2.2GHz + 6× A55 @1.8GHz, Adreno 619, Android 14)
**Constraints:** local-only (no user-data tracking), fast, tool-call optimized (not chat quality)
**Assets:** `assistant_schema.sql` (v1), `tools.json` (17 tools)
**Start here:** `README.md` for overview and current gaps; §1a below for phase status.

---

## 0. Core thesis

CPU is the bottleneck, not RAM. Therefore:

1. **Fast deterministic path owns ~70–80% of commands.** No LLM, <250 ms.
2. **Tiny function-calling LLM is the fallback**, never the hot path.
3. **The model never emits IDs, numbers or URIs.** It emits raw spoken strings
   (`contact_ref`, `query`, `app_name`). A separate resolver maps those to real
   entities. A hallucinated slot can therefore cause a *clarification prompt*,
   never a wrong action.
4. **Entity resolution is structured lookup, not RAG.** Embeddings are a last-resort
   tier, used only when exact/FTS/phonetic all fail.

```
Wake word / long-press power
  → Silero VAD
  → Moonshine streaming STT (partials, finalize on end-of-speech)
  → FAST PATH (grammar + regex + resolver)          ~70-80%   <250 ms
  → TIER 1 LLM (FunctionGemma 270M, constrained JSON)  ~20-30%   ~1.0-1.3 s
  → EntityResolver (relation → exact → FTS5 → phonetic → embedding)
  → ToolExecutor (Android APIs / Shizuku / Accessibility / NotificationListener)
  → Confirm gate (never | ambiguous | always)
  → Android system TTS
  → command_log (local only; feeds eval + fine-tuning)
```

---

## 1. Phase plan with gates

Every phase has a **numeric exit gate**. Phases 0–2 are device-independent
(pure JVM, unit-testable) and can be completed immediately. Phases 3–6 are
**device-gated** and need the Moto G34 in hand.

| # | Phase | Device needed | Exit gate |
|---|---|---|---|
| 0 | Repo & contracts | No | `assembleDebug` builds; test asserts all 17 tools parse, every `required` param defined, group router keeps injected schemas short |
| 1 | Data & entity resolution | No | Resolver resolves `my wife` (1 hop) and `my wife's brother` (2 hops); returns **top-2 without auto-pick** for two "Rahul"s; resolves a misheard name via phonetic tier |
| 2 | Fast path | No | ≥70% of a 100-utterance eval set handled deterministically; p95 < 250 ms; **zero** LLM calls on the covered intents |
| 3 | Audio spine | **Yes** | Moonshine Tiny vs Small measured on-device; pick winner; STT finalize p95 < 1000 ms; wake-word FP rate acceptable |
| 4 | Tier-1 LLM | **Yes** | ≥90% exact-match (tool + required params) on eval set; warm avg < 1.3 s; 2 threads pinned to big cores |
| 5 | Spotify | **Yes** | Warm play-by-URI < 400 ms; **command path performs zero network calls** once library synced |
| 6 | Comms deep | **Yes** | Every send reads back and waits for confirmation; no silent-send path exists |
| 7 | Learning loop | No | Corrections captured → `asr_variant` aliases; eval export runs; synthetic data generator produces valid JSONL |
| 8 | Hardening | **Yes** | Thermal degradation ladder verified; idle LLM unload works; survives Doze / App Standby |

### Dependency DAG

```
Phase 0 ──> Phase 1 ──> Phase 2 ──┬──> Phase 3 ──┐
                                  │              ├──> Phase 6 ──> Phase 7 ──> Phase 8
                                  └──> Phase 4 ──┴──> Phase 5 ──┘
```

Phases 3 and 4 can proceed in parallel once Phase 2 lands (both need the
resolver to map raw slots). Phase 7 is device-independent and can start early.

---

## 1a. Current status

Verified with `./gradlew :core:test :app:assembleDebug` — **159 tests, 0 failures**,
debug APK builds. Everything below is host-verified; nothing has run on hardware.

| # | Phase | State | Evidence / what's left |
|---|---|---|---|
| 0 | Repo & contracts | **Done** | 17 tools parse; `required` coverage asserted; group router keeps injected schemas minimal. `ToolsLoaderTest` (22 tests) |
| 1 | Data & entity resolution | **Done** | All five tiers covered: relation chain (8), exact (6), phonetic (3), embedding (3), ambiguity/disambiguation (4). Top-2 without auto-pick on two "Rahul"s. Schema verified against real SQLite: `SqlSchemaTest` (21), store behaviour `SqlContactStoreTest` (28) |
| 2 | Fast path | **Done (unit)** | 30 tests across flashlight, brightness, volume, settings, alarm, timer, apps, media. Person intents route but never guess. **Gate not yet measured** — needs the 100-utterance eval set and real p95 |
| 3 | Audio spine | Not started | — |
| 4 | Tier-1 LLM | Not started | — |
| 5 | Spotify | Not started | — |
| 6 | Comms deep | Not started | — |
| 7 | Learning loop | Not started | `scripts/` and `eval/` do not exist |
| 8 | Hardening | Not started | — |

**Storage layer: complete on the JVM, unverified on device.** `SqlContactStore`
runs against real SQLite via JDBC on every test run. `SqlCipherDb` (SQLCipher
4.19.1, Keystore-wrapped passphrase) and `ContactSync` compile and package into the
APK but have never executed — closing that gap is checklist item 5 below.

**Two deliberate deviations from the original plan**, both recorded above:
raw SQL instead of Room (§3), and `VectorCodec` as an int8 direction-only fallback
rather than a hot-path vector store.

**Bugs found and fixed during Phase 1**, worth remembering because both were
invisible to the obvious test:

- `VectorCodec.decode` never re-applied the scale `encode` divided out, so
  decoded vectors were 127× too small. Every cosine test passed, because cosine is
  scale-invariant. Fixed; the suite now asserts direction and end-to-end cosine.
- `recordUsage(isCall = false)` incremented neither `call_count` nor
  `message_count`, so message usage was silently discarded. Split into
  `MARK_CALL_USED` / `MARK_MESSAGE_USED`.

---

## 2. Model selection (final)

| Role | Model | Quant | RAM | Notes |
|---|---|---|---|---|
| STT (primary) | **Moonshine Small** (123M) | ONNX | ~250 MB | Better on Indian names/accent + code-switching. 7.84% WER (vendor) |
| STT (thermal fallback) | Moonshine Tiny (34M) | ONNX | ~100 MB | 12.0% WER (vendor) |
| VAD | Silero VAD | — | ~10 MB | Streaming |
| Wake word | openWakeWord | — | ~20 MB | Always-on is cheap |
| LLM tier 1 | **FunctionGemma 270M** | Q5_K_M → Q8_0 | ~280/350 MB | Purpose-built for function calling; needs `developer` role |
| LLM tier 2 (optional) | Qwen2.5 0.5B Instruct | Q4_K_M | ~400 MB | Rare multi-step / fuzzy only |
| Embeddings | MiniLM-L6-v2 int8 | ONNX | ~30 MB | Tier-5 fuzzy match only |
| TTS | Android system TTS | — | ~0 | Free. Piper (~20 MB) only if custom voice needed |

**Runtime:** MNN (Android, better CPU scheduling on mid-tier Snapdragon) with
llama.cpp as a drop-in alternative, both behind one `LlmEngine` interface.

**Why no 1.5B:** a ~30-token tool call would take ~7–10 s on this SoC. Unusable
on the hot path. Skip entirely unless chat quality becomes a goal.

**Threading:** start at `n_threads = 2` pinned to the two A78 cores. Threads 3–4
spill onto A55s and *increase* latency on this chip. Measure both.

**Target RAM envelope (everything resident):** ~1.0–1.4 GB of 8 GB. RAM buys
warm models and cached prefixes, **not** speed.

---

## 3. Resolver design (the moat)

Resolution order, fixed and implemented in that order:

| Tier | Match | Handles |
|---|---|---|
| 0 | **Relation chain** | "my wife", "my wife's brother" — BFS over `relationships` from `is_self=1`, depth ≤ 3, cycle-guarded |
| 1 | **Exact `alias_norm`** | Clean names, taught aliases |
| 2 | **FTS5 token/prefix** | Partial names, prefixes (last token matched with `*`) |
| 3 | **Phonetic** (Double Metaphone, computed in Kotlin) | ASR mishearing — the main defense for Indian names |
| 4 | **Embedding cosine** in RAM | Fuzzy only |

**Scoring:** `score = tier_weight × tier_base + 0.1·ln(1+call_count) + recency_boost`
**Auto-pick gate:** only when `top ≥ T_HIGH` **and** `top − runner_up ≥ MARGIN`.
Otherwise return top-2 and ask "Rahul Das or Rahul Sen?" — this is what keeps
`call_contact` (whose `confirm` is `ambiguous`) safe.

**Schema note:** family words live in `relationships`, never in `aliases`. Aliases
are names/nicknames only.

**Storage decision — raw SQL, not Room.** `assistant_schema.sql` relies on FTS5
virtual tables, `AFTER INSERT/UPDATE/DELETE` sync triggers, `CHECK` constraints,
partial indexes, and a `contact_log` view. Room's schema generator cannot express
any of those, so using Room would mean a generated schema plus hand-written SQL
migrating the same tables — two sources of truth, with Room's validator fighting
every feature the resolver actually depends on. The cost of going raw is losing
compile-time query checking, which is instead bought back by keeping *all* SQL in
one place (`core/db/Sql.kt`) behind a two-method interface:

```
SqlDb { query(sql, args): List<SqlRow>; execute(sql, args); executeScript(sql) }
```

`SqlContactStore` is the only writer and is fully unit-tested against real SQLite
through JDBC, so the query layer is verified on every `./gradlew test` without a
device. Android supplies one implementation, `SqlCipherDb`, which wraps
`SupportSQLiteOpenHelper` — needed because framework SQLite does not expose FTS5
reliably at minSdk 26 (risk R2). Embeddings are not stored as vectors in the
resolver's critical path; the int8 `VectorCodec` is a fallback tier, and its
contract is explicitly "direction only", since cosine is scale-invariant.

---

## 4. Android integration reality (sideload assumed)

Play Store restrictions do not apply, so be aggressive — but some things still
have no clean API.

| Capability | Mechanism | Reality |
|---|---|---|
| Flashlight / brightness / volume | Standard APIs | Brightness needs `WRITE_SETTINGS` (special, one-time grant) |
| Wi-Fi / mobile data / Bluetooth / airplane / hotspot / NFC / location / DND | **Shizuku** + wireless debugging | Binder death on reboot is likely → always keep Settings deep-link as a first-class supported path |
| Calls | `CALL_PHONE` | Straightforward |
| SMS | `SmsManager` + `SEND_SMS` | Sideload-only permission |
| **RCS** | No public send API | Prefill Google Messages + Accessibility tap-send, or notification `RemoteInput` |
| **WhatsApp** | No personal-account API | `wa.me` deep link + Accessibility tap-send, or notification `RemoteInput` |
| **Telegram** | **TDLib** | The clean one. Fully local after one-time auth |
| Notification read/reply | `NotificationListenerService` + `RemoteInput` | Best cross-app win — "reply yes to that" without opening anything |
| Screen-aware ("tap second result") | `AccessibilityService` | Opt-in, privacy-sensitive, disabled by default |

**Safety invariant:** every outbound message/call reads back the resolved target
and waits for a spoken "yes". No code path may send silently.

---

## 5. Risk register

| # | Risk | Mitigation | Fallback |
|---|---|---|---|
| R1 | CPU inference slower than target | Fast-path dominance ≥70%; LLM idle-unload ~45 s; Q5_K_M; 2-thread big-core pin | STT→Tiny, then fast-path-only, then group-filtered micro-prompt |
| R2 | **FTS5 missing from framework SQLite** | Use SQLCipher-for-Android (bundles FTS5) from day one, not framework SQLite | Pure-JVM fuzzy matcher over a loaded alias map |
| R3 | FunctionGemma exact-match <90% | Grammar-constrained JSON (never free sampling); group-filtered injection | LoRA fine-tune on Mobile Actions + synthetic |
| R4 | Shizuku auth lost on reboot | Detect binder death, prompt re-authorize | Settings deep-link (supported, not degraded) |
| R5 | Accessibility send breaks on app updates | Opt-in flag; always read-back + confirm | "Open WhatsApp instead" |
| R6 | Play Store distribution impossible (`SEND_SMS`, Accessibility, NotificationListener) | F-Droid / sideload, single-user | Design constraint, not a blocker |
| R7 | STT mishears contact names | Phonetic tier + learned `asr_variant` + top-2 clarify | Always ask, never guess |
| R8 | Spotify API churn (Feb 2026 Dev Mode; Audio Features removed) | Local `spotify_items` is the source of truth; Web API = background sync only | Command path works offline once synced |
| R9 | Thermal throttling mid-conversation | Progressive ladder: drop tier → slow STT frames → unload LLM | Fast path always survives |
| R10 | 17 tools blow up prompt cost | `ToolGroupRouter` injects 1–3 groups; KV prefix cache keyed by sorted group set | Prefix caching |

---

## 6. Latency budget (measured on Moto G34 5G)

| Stage | Target | Gate |
|---|---|---|
| Wake word | < 250 ms | — |
| VAD end-of-speech | < 120 ms | — |
| STT finalize (Moonshine Small) | 700–950 ms | p95 < 1000 ms |
| Fast path (grammar + resolver + exec) | 80–250 ms | p95 < 250 ms |
| FunctionGemma 270M, warm, cached prefix | 900–1400 ms | avg < 1300 ms |
| **End-to-end (STT → action)** | **1.2–1.6 s** | p95 < 2.0 s |
| Spotify play-by-URI (warm) | 200–400 ms | < 400 ms |

---

## 7. Device benchmark checklist (gate for Phases 3–5)

Run before committing to Phase 4. Procedure lives in `docs/benchmarks.md`.

1. **STT:** Moonshine Tiny vs Small, 15 real utterances including 5 real contact
   names and 2 Hinglish commands. Record finalize p50/p95, CPU%, subjective WER.
2. **LLM:** FunctionGemma Q5_K_M, 8 tool-call queries, `n_threads` 2 vs 4, warm
   prefix cache. Record TTFT, total, tokens/s, exact-match.
3. **Resolver:** real contacts seeded. Verify phonetic on 2–3 genuinely
   mispronounced names and the two-"Rahul" ambiguity path.
4. **Spotify:** warm App Remote connect; time URI → playback start for 3 tracks.
5. **FTS5:** instrumented smoke test asserting `aliases_fts` exists.

**Decision rule:** if tier-1 avg > 1.4 s, retry with Q5 + stricter group filtering
+ 2-thread pin before considering any model change.

---

## 8. Definition of done (v1)

- [ ] ≥70% of daily commands answered by fast path, p95 < 250 ms
- [ ] "call my wife", "text my wife's brother …", ambiguous-name clarify all work
- [ ] Outbound message always reads back target and waits for "yes"
- [ ] Spotify plays a named artist from a warm connection in < 400 ms
- [ ] `command_log` captures every command with route/timings/outcome
- [ ] Core APK module can be built with **no INTERNET permission** (provable privacy)
- [ ] Device survives 1 h of intermittent use without thermal death or service kill

---

## 9. Repo layout

```
voice-assistant/
├── README.md                   ← overview, build commands, current gaps
├── ROADMAP.md                  ← you are here
├── assistant_schema.sql        ← single source of truth for data
├── tools.json                  ← single source of truth for tools
├── core/                       ← pure Kotlin/JVM, NO Android deps (unit-testable)
│   ├── src/main/kotlin/…/core/
│   │   ├── tools/              ToolSpec, ToolsLoader (validation + group routing)
│   │   ├── text/               TextNormalizer, DoubleMetaphone
│   │   ├── resolver/           EntityResolver, ContactStore, SqlContactStore
│   │   ├── fastpath/           FastPath (grammar + slot extraction)
│   │   └── db/                 SqlDb/SqlRow, shared SQL, VectorCodec
│   └── src/test/kotlin/…/core/db/
│       └── JdbcDb.kt           test-only SQLite driver (NOT in main — it uses java.sql)
├── app/                        ← Android module (SQLCipher driver, sync, services, executors)
│   └── src/main/kotlin/…/
│       ├── data/               SqlCipherDb, ContactSync
│       └── MainActivity.kt     temporary dev UI
├── scripts/                    ← not yet created
│   ├── gen_training_data.py    synthetic function-calling JSONL
│   └── eval_harness.py         exact-match scorer
├── eval/                       ← not yet created; eval_set.jsonl grows from command_log
└── docs/benchmarks.md          device measurement procedure
```

**Build tooling note.** Use `./gradlew`, never the system `gradle`: Gradle 9.5.0 is
required by AGP 9.3.2. AGP 9 provides Kotlin support itself, so
`org.jetbrains.kotlin.android` is *not* applied — applying it is a hard failure.
