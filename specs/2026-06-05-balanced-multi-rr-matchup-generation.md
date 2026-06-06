# Tech Spec: Balanced Multi-RR Matchup Generation

**PRD:** `features/2026-06-05-balanced-multi-rr-matchup-generation.md`
**Version target:** v0.14.0
**Date:** 2026-06-05

---

## Overview

The change is confined entirely to `TeamScheduleService` and one field rename in `TeamScheduleResult.Success`. The existing circle-method `buildRoundRobinRounds()` is refactored into a stateful, round-by-round generator that can be called across multiple cycles while preserving rotation state and a global round index. The greedy fill phase is deleted. `generate()` loops over the required number of full cycles and then over the partial-cycle rounds, collecting games and emitting one log line per cycle. No model records, commands, persistence, or scheduler services are touched beyond a field rename on `TeamScheduleResult.Success`.

---

## Component Diagram

```
TeamScheduleService.generate(League)
│
├── validatePreconditions()              [unchanged]
│
└── per eligible Division:
      │
      ├── buildInitialRotation(teams)    [new helper — sets up circle method state]
      │
      ├── loop fullCycles times:
      │     └── loop (M-1) rounds:
      │           └── buildRound(fixed, rotating, globalRound, div)   [replaces buildRoundRobinRounds]
      │                 advance rotating, increment globalRound
      │     emit "Cycle K complete: ..."
      │
      └── if remainder > 0:
            loop remainder rounds:
              └── buildRound(fixed, rotating, globalRound, div)
                    advance rotating, increment globalRound
            emit "Partial cycle (R of M-1 rounds) complete: ..."

TeamScheduleResult.Success(schedule, cycleLogs)   [fillRoundLogs renamed to cycleLogs]

ScheduleCommand.Generate                          [one reference updated: fillRoundLogs() → cycleLogs()]
TeamScheduleServiceTest                           [log format tests updated; new head-to-head tests added]
```

---

## Data Model

No new model types. One field rename:

| Type | Before | After |
|------|--------|-------|
| `TeamScheduleResult.Success` (record) | `List<String> fillRoundLogs` | `List<String> cycleLogs` |

All other records and enums are unaffected. No `league.json` schema changes; this feature touches only the matchup-generation phase (Phase 1), not anything that is persisted.

---

## Algorithm: Detailed Design

### Setup

```
N = teams in division
M = N if N is even, N+1 if N is odd   (same null-bye padding as today)
fixed = teams[0]
rotating = [teams[1] .. teams[M-1]]   (teams[M-1] is null for odd N)

fullCycles = T / (N-1)                 (integer division)
remainder  = T % (N-1)
globalRound = 0
```

### Round generation (`buildRound`)

This is the inner body of today's `buildRoundRobinRounds` loop, extracted into a method that takes `(fixed, rotating, globalRound, div)` and returns `List<RawGame>`:

```
for specI in 0 .. M/2-1:
    left, right = circle-table lookup (unchanged formula)
    skip if left or right is null (bye)
    leftIsHome = ((specI + globalRound) % 2 == 0)   ← globalRound, NOT cycle-local r
    emit RawGame(home, away, div)
```

The only change from today is that `r` (the cycle-local round index) is replaced by `globalRound` (the monotonically increasing counter across all cycles). This ensures home/away alternation continues correctly across cycle boundaries rather than resetting at the start of each cycle.

### Rotation advancement

After each call to `buildRound`, advance the rotation with the same operation as today:
```
rotating.add(0, rotating.remove(rotating.size() - 1))
globalRound++
```

The rotation is **never reset** between cycles. Continuing from where the previous cycle ended ensures a different pairing structure for each cycle.

### Cycle loop

```
for c in 1..fullCycles:
    for r in 0 .. M-2:
        games += buildRound(fixed, rotating, globalRound, div)
        advance rotating; globalRound++
    if (fullCycles > 1 || remainder > 0):
        emit "Cycle {c} complete: {team name} {count}, ..."

if remainder > 0:
    for r in 0 .. remainder-1:
        games += buildRound(fixed, rotating, globalRound, div)
        advance rotating; globalRound++
    emit "Partial cycle ({remainder} of {M-1} rounds) complete: {team name} {count}, ..."
```

**Log suppression rule:** When `fullCycles == 1` and `remainder == 0` (i.e., target == N-1, the minimum), no log lines are emitted. This preserves current behavior where a plain single round-robin produces no output. For all other cases (target > N-1), all cycle log lines are emitted including cycle 1.

### Home/away tracking

The existing `homeCount` / `awayCount` maps are retained and updated inside `buildRound` exactly as today. These are used only for log line output (team game counts), not for home/away assignment within `buildRound` (home/away is now determined by `globalRound` and `specI`).

Note: the fill-round home/away heuristic (`aImbalance >= bImbalance ? a : b`) is deleted along with the fill phase. Home/away balance is now a property of the circle-method formula applied globally.

---

## Critical Path Walkthrough

### Case 1: 4 teams, target = 8 (between double and triple RR)

```
N=4, M=4, N-1=3
fullCycles = 8/3 = 2
remainder  = 8%3 = 2

Cycle 1 (rounds 0–2, globalRound 0→2):
  Round 0: 2 games
  Round 1: 2 games
  Round 2: 2 games  → 6 games; emit "Cycle 1 complete: ..."

Cycle 2 (rounds 3–5, globalRound 3→5):
  Round 3: 2 games
  Round 4: 2 games
  Round 5: 2 games  → 12 games total; emit "Cycle 2 complete: ..."

Partial (rounds 6–7, globalRound 6→7):
  Round 6: 2 games
  Round 7: 2 games  → 16 games total; emit "Partial cycle (2 of 3 rounds) complete: ..."

Per-team count: 8 rounds × 1 game/team/round = 8 ✓
Head-to-head: full 2 cycles → each pair exactly 2 times; partial 2 of 3 rounds → 2 pairs get +1 meeting
  → every pair meets 2 or 3 times. floor(8/3) = 2, floor+1 = 3 ✓
```

### Case 2: 4 teams, target = 6 (exact double RR)

```
fullCycles = 6/3 = 2, remainder = 0

Cycle 1 (globalRound 0–2): 6 games; "Cycle 1 complete: ..."
Cycle 2 (globalRound 3–5): 6 games; "Cycle 2 complete: ..."
Total: 12 games; each pair meets exactly 2 times ✓
```

### Case 3: 4 teams, target = 3 (single RR, minimum)

```
fullCycles = 1, remainder = 0
Cycle 1 (globalRound 0–2): 6 games; no log emitted (fullCycles==1, remainder==0)
Output: 6 games, empty cycleLogs ✓ (matches current behavior)
```

### Case 4: 3 teams (odd N), target = 8

```
N=3, M=4, N-1=2
fullCycles = 8/2 = 4, remainder = 0

4 full cycles × 3 rounds each = 12 rounds
In each round: 2 games (one team sits out / null-bye)
Per team across 12 rounds: each team gets 1 bye per full cycle (M-1=3 rounds, one bye)
  → per team per cycle: 3 rounds - 1 bye = 2 games
  → per team total: 4 × 2 = 8 games ✓
Logs: "Cycle 1 complete: ...", "Cycle 2 complete: ...", "Cycle 3 complete: ...", "Cycle 4 complete: ..."
```

### Case 5: 3 teams (odd N), target = 5 (partial cycle)

```
N=3, M=4, N-1=2
fullCycles = 5/2 = 2, remainder = 1

Cycles 1–2: 2×2 = 4 games per team
Partial cycle: 1 round (3 rounds available in a full cycle)
  → 1 round: 2 games, but one team draws the bye → 2 teams get +1 game, 1 team gets +0
  → final counts: 2 teams at 5 games, 1 team at 4 games (±1 tolerance, acceptable per PRD)
Log: "Cycle 1 complete: ...", "Cycle 2 complete: ...", "Partial cycle (1 of 3 rounds) complete: ..."
```

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|----------|--------------------|--------|-----------|---------------|
| Rotation state across cycles | (A) Continue rotation from where last cycle ended. (B) Reset rotation to initial order each cycle. | A — continue | Continuing avoids home/away patterns repeating; the same team would always be home in position 0 of every cycle if we reset. PRD explicitly chose this for better balance. | Slightly harder to reason about which pairs appear in the partial cycle, but determinism is preserved. |
| Home/away formula | (A) Use `globalRound` index. (B) Use cycle-local `r` index. | A — globalRound | Cycle-local index would reset the alternation at the start of each cycle, causing systematic home/away bias for some positions across cycles. | None — globalRound is strictly more correct. |
| Log suppression | (A) Suppress logs only for single-cycle / no-partial (target == N-1). (B) Always emit logs. (C) Suppress logs for all exact-multiple targets. | A | Preserves current UX: the minimum schedule is quiet. Any target above N-1 emits cycle logs. Clean break from fill-round semantics. | Users who set target == N-1 won't see any cycle confirmation, but this matches current expectations. |
| `fillRoundLogs` field rename | (A) Rename to `cycleLogs`. (B) Keep `fillRoundLogs` for minimal churn. | A — rename | The term "fill round" is meaningless after the fill phase is removed. One rename in `ScheduleCommand` is low risk. | All callers must be updated; catch by compile error at build time. |
| Scope of home/away balancing after fill-phase removal | (A) Rely solely on circle-method globalRound formula. (B) Add a post-pass correction that redistributes home/away. | A | The circle method already produces near-perfect balance. Post-pass correction adds complexity for marginal gain in edge cases. | A team in a specific slot of the rotation may end up with 1 more home or away game across cycles; this is acceptable for a recreational league. |

---

## File Change Summary

| File | Change |
|------|--------|
| `src/main/java/…/scheduler/TeamScheduleService.java` | Replace `buildRoundRobinRounds()` + fill phase with `buildInitialRotation()` and `buildRound()` helpers; refactor `generate()` main loop. |
| `src/main/java/…/scheduler/TeamScheduleResult.java` | Rename `fillRoundLogs` → `cycleLogs` in `Success` record. |
| `src/main/java/…/command/ScheduleCommand.java` | Update `fillRoundLogs()` → `cycleLogs()` at the two call sites (print loop and empty-check). |
| `src/test/java/…/scheduler/TeamScheduleServiceTest.java` | Update log-format tests; add head-to-head balance tests. |

---

## Test Plan

### Tests to update (existing)

| Test | What changes |
|------|-------------|
| `noFillRoundLogsWhenTargetEqualsNMinus1` | Update field accessor to `cycleLogs()`. Logic unchanged. |
| `fillRoundLogsHaveCorrectCountAndFormat` | 4 teams, target=8: now 3 log lines (2 full + 1 partial) instead of 5. Update expected count and prefix format. Expected: `"Cycle 1 complete:"`, `"Cycle 2 complete:"`, `"Partial cycle (2 of 3 rounds) complete:"`. |

### New tests to add

| Test | What it verifies |
|------|-----------------|
| Double RR — each pair meets exactly twice | 4 teams, target=6: all 6 pairs have head-to-head count == 2. |
| Between-RR target — head-to-head imbalance ≤ 1 | 4 teams, target=8: every pair meets 2 or 3 times (floor=2, floor+1=3). |
| Partial cycle — no new pair from first cycle repeats in partial | 4 teams, target=4: pairs in round 4 do not repeat a pair from rounds 1–3. |
| Odd-N partial cycle — per-team count within ±1 of target | 3 teams, target=5: each team plays 4 or 5 games (not fewer than 4). |
| Log count for double RR | 4 teams, target=6: exactly 2 log lines, both prefixed "Cycle". |
| Log count for partial | 4 teams, target=8: exactly 3 log lines (2 "Cycle", 1 "Partial cycle"). |

### Existing tests that should pass without modification

All game-count, sequential-numbering, determinism, validation-failure, and multi-division tests are unaffected by the algorithm change. The `league()` helper in the test uses version 4; this is fine — no model changes.

---

## Operational Concerns

This is a CLI with no deployed service. The only operational concern is correctness:

- **Determinism**: The algorithm is deterministic (no random choices; same team list order → same rotation → same output). The existing determinism test covers this.
- **Compile-time safety**: The `fillRoundLogs` rename produces a compile error at every stale call site. `gradle compileJava` catches all misses before tests run.
- **Rollback**: If a bug is found, the old algorithm is fully contained in git history. The schema version is unchanged (this is a Phase 1 change only); no migration is needed.
- **Edge cases already handled**: 2 teams (N-1=1, every target is a multiple), odd N (null-bye padding), multi-division leagues (each division is independent).

---

## Out of Scope / Future Work

- Phase 2 (`SchedulerService`, CP-SAT field/time assignment) — unaffected.
- Playoff and practice scheduling — unaffected.
- Surfacing the cycle count (`fullCycles`, `remainder`) as a structured field on `TeamScheduleResult.Success` for programmatic use. Currently only the string logs are returned. If a future UI wants to display "2 full cycles + partial", the structured values could be added to the result record.
- Home/away correction pass to eliminate the residual ±1 imbalance from the circle method. Not needed for a recreational league.
