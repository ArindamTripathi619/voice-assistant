# Device benchmark procedure

Everything in this file requires the physical **Moto G34 5G**. Nothing here has
been run yet — the numbers in [ROADMAP.md](../ROADMAP.md) §6 are targets, not
measurements.

Phases 3–5 are gated on this. Run it once, in this order, and do not start the
LLM work until items 1 and 2 are answered, because they decide whether the model
plan survives contact with this SoC.

## Before you start

- Phone in performance mode, plugged in, screen off. Charging is not optional for
  STT and LLM measurements.
- Cool room. Note the ambient temperature.
- Disable the OS battery optimiser and any vendor "protected apps" list for the
  app. This is not optional and is the single most common reason a benchmark looks
  inexplicably bad.
- `adb shell settings put global stay_on_while_plugged_in 3`
- Clear the app's data before each family of measurements so caches are honest.

## 1. STT — Moonshine Tiny vs Moonshine Small

Fifteen real utterances, spoken by you, recorded at arm's length in a quiet room:

| # | Utterance | Notes |
|---|---|---|
| 1–5 | Real contact names | The only part that matters for this project |
| 6–8 | Two Hinglish commands | Code-switching is where the small models break |
| 9–15 | Six everyday commands | Baseline |

Per model, record:

- finalize latency, p50 and p95, over 3 runs of each utterance
- subjective WER for the 5 contact names specifically, not aggregated
- CPU% via `adb shell top -n 1 -p <pid>`

**Decision rule:** Small wins if its name WER is better by more than one name out
of five. If they tie, take Tiny — the RAM and the latency headroom are worth more
than a marginal WER gain, and R7 (misheard names) already has a resolver-side
defence in the phonetic tier.

## 2. LLM — FunctionGemma 270M

Q5_K_M, 8 tool-call queries spanning at least 3 different tool groups.

Measure `n_threads` 2 vs 4. The roadmap predicts 2 wins here, because threads 3–4
spill onto the A55 cores and *increase* latency on this chip. If 4 wins by more
than 15%, that prediction is wrong and the plan changes.

Record: TTFT, total, tokens/s, exact-match (tool name **and** all required params
must match), with the group-filtered prompt and a warm prefix cache.

**Decision rule:** if tier-1 warm average > 1.4 s, retry at Q5 with stricter
group filtering and the 2-thread pin before considering any model change. Do not
skip to a larger model; a bigger model cannot fit the budget.

## 3. Resolver, against real contacts

Seed at least 20 real contacts, deliberately including:

- two people sharing a first name (the "Rahul" ambiguity path)
- two or three genuinely mispronounced Indian names
- one relationship chain three hops deep

Verify by hand that:

- mispronounced names land on the phonetic tier, not on a lucky exact match
- the ambiguity path returns top-2 and asks, rather than auto-picking
- a three-hop chain resolves, and a cycle in `relationships` terminates instead of
  hanging

## 4. Spotify, warm path

Sync the library once. Then, with the App Remote connection warm, time
URI → audible playback for three different tracks.

**Decision rule:** >400 ms means the local `spotify_items` cache is not doing its
job, because the command path should not touch the network at all after sync.

## 5. FTS5 under SQLCipher — the one open correctness risk

FTS5 is verified on the JVM through SQLite JDBC, but the device build uses
SQLCipher's bundled SQLite, which is a *different* SQLite build. This is risk R2,
and it is the cheapest thing on this list to close.

Write an instrumented test that:

1. creates the real database via `SqlCipherDb.open`
2. asserts `aliases_fts` exists and a `SELECT` against it does not throw
3. inserts an alias, queries it by prefix, updates it, queries again, deletes it,
   queries a third time — the last query **must** return nothing
4. confirms the file on disk is not readable as plaintext SQLite

If step 2 fails, FTS5 is unavailable in the bundled build and the fallback is the
pure-JVM fuzzy matcher over a loaded alias map. That is a real cost: it loses
prefix ranking and forces the whole alias map into RAM on every cold start.

## 6. Thermal ladder

The device requirement in Phase 8. Run 1 hour of intermittent use while logging:

- `adb shell dumpsys thermalservice` every 10 s
- STT finalize p95 per 5-minute bucket
- LLM warm average per 5-minute bucket

Confirm the degradation ladder actually engages and that the fast path keeps
working when the LLM is unloaded. A thermal death in the middle of a voice command
is a product failure, not a benchmark result.
