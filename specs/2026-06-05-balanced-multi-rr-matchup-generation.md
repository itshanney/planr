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

### Case 5: 3 teams (odd N), target = 5 (odd target) — **Updated by Errata E-3**

```
N=3, M=4, N-1=2
fullCycles = 5/2 = 2, remainder = 5%2 = 1

Full cycles 1–2: each team plays 4 games; H2H after 2 cycles: A-B:2, A-C:2, B-C:2
Partial round 4 (globalRound=6): A-bye, B vs C → A=4, B=5, C=5; H2H: B-C:3

E-2 pairing: shortTeams=[A], size=1 → 0 make-up games
E-3 top-up: A needs 1 game. H2H: A-B:2, A-C:2 (equal). Tie → first in div.teams() = B.
            globalRound=7, leftIsHome=(7%2==0)=false → B is home, A is away.
            Add B vs A. A=5✓, B=6(T+1), C=5✓

Emit partial-cycle log: "Partial cycle (1 of 3 rounds) complete: A 5, B 6, C 5"
Final: A=5, B=6, C=5. All teams ≥ T=5; B has 1 extra game (mathematically unavoidable). ✓
```

### Case 6: 5 teams (odd N), target = 6 (even, non-multiple of N-1=4) — **Added by Errata E-2**

```
N=5, M=6, N-1=4
fullCycles = 6/4 = 1, remainder = 6%4 = 2

Full cycle 1 (rounds 0–4): each team plays exactly 4 games. Emit "Cycle 1 complete: ..."

Partial cycle (rounds 5–6, globalRound 5→6):

  rotating at cycle start: [B, C, D, E, null]

  Partial round 0 (globalRound=5):
    specI=0: A vs rotating[4]=null → BYE for A
    specI=1: rotating[0]=B vs rotating[3]=E → B vs E
    specI=2: rotating[1]=C vs rotating[2]=D → C vs D
    advance rotating → [null, B, C, D, E]; globalRound=6

  Partial round 1 (globalRound=6):
    specI=0: A vs rotating[4]=E → A plays
    specI=1: rotating[0]=null vs rotating[3]=D → BYE for D
    specI=2: rotating[1]=B vs rotating[2]=C → B vs C
    advance rotating → [E, null, B, C, D]; globalRound=7

After 2 partial rounds: A=5, B=6, C=6, D=5, E=6

Short teams (gameCount < 6): [A, D]  ← both at T-1; list preserves div.teams() order

Make-up game (globalRound=7):
  shortTeams in div.teams() order: [A, D]
  left=A (index 0), right=D (index 1)
  leftIsHome = (7 % 2 == 0) = false → right(D) is home, left(A) is away
  Emit RawGame(home=D, away=A); A=6 ✓, D=6 ✓; globalRound=8

Emit partial-cycle log (now after make-up): "Partial cycle (2 of 5 rounds) complete: A 6, B 6, C 6, D 6, E 6"

Final: 5×6/2 = 15 games; all 5 teams at 6. ✓
```

### Case 7: 3 teams (odd N), target = 3 (odd target, minimum+1) — **Added by Errata E-3**

```
N=3, M=4, N-1=2
fullCycles = 3/2 = 1, remainder = 3%2 = 1

Full cycle 1 (rounds 0–2, globalRound 0→2):
  rotating initial: [B, C, null]

  Round 0 (globalRound=0): A vs rotating[2]=null → BYE for A; B vs C
    advance rotating → [null, B, C]; globalRound=1
  Round 1 (globalRound=1): A vs rotating[2]=C → A plays C; null vs B → BYE for B
    advance rotating → [C, null, B]; globalRound=2
  Round 2 (globalRound=2): A vs rotating[2]=B → A plays B; C vs null → BYE for C
    advance rotating → [B, C, null]; globalRound=3

After cycle 1: A=2, B=2, C=2; H2H: A-B:1, A-C:1, B-C:1. Emit "Cycle 1 complete: A 2, B 2, C 2"

Partial cycle (1 round, globalRound=3):
  Round 3: A vs rotating[2]=null → BYE for A; B vs C
  globalRound=4

After partial: A=2, B=3, C=3; H2H: B-C:2

E-2 pairing: shortTeams=[A], size=1 → cannot pair → 0 make-up games

E-3 top-up: 1 team still at T-1. Short team: A.
  H2H candidates: B(H2H with A = 1 game), C(H2H with A = 1 game). Equal → first in div.teams() = B.
  leftIsHome = (globalRound=4 % 2 == 0) = true → A(left) is home, B(right) is away.
  Add RawGame(home=A, away=B). A=3✓, B=4(T+1), C=3✓. globalRound=5.

Emit partial-cycle log: "Partial cycle (1 of 3 rounds) complete: A 3, B 4, C 3"

Final: 5 games total. A=3, B=4, C=3.
All teams ≥ T=3; B plays 1 extra game. N×T=9 is odd — exactly 1 team at T+1 is the minimum
possible deviation, and it is unavoidable. ✓
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
| Odd-N non-multiple target handling (Errata E-1) | (A) Validate and reject — require `target % (N-1) == 0` for odd N. (B) Accept with ±1 tolerance, distribute byes across teams. (C) Add make-up rounds after the partial cycle to bring short teams to target. | A — validate and reject | (B) cannot achieve the user's stated requirement of exactly T games for every team; moving the bye to a different team merely shifts which team is short. (C) is mathematically impossible when opponents are at T: each make-up game adds +1 to both teams, bumping an at-T opponent to T+1. | Superseded by Errata E-2, which observes that the short teams after a partial cycle are always at T-1 themselves and can be paired with each other. |
| Odd-N even target with make-up games (Errata E-2) | (A) Validate and reject only odd targets for odd N; add make-up games pairing short-short teams. (B) Keep E-1's stricter multiple-of-(N-1) requirement. (C) Run additional partial rounds until all counts converge. | A — parity check + make-up pairing | (B) rejects legitimate even targets like N=5 target=6, forcing users to skip to target=8. (C) causes extra rounds to give at-T teams T+1 games. (A) exploits the mathematical guarantee that remainder is always even when T is even, so short teams always count to an even number and can be exhaustively paired. | Superseded for odd-T case by Errata E-3. The pairing order (sequential within div.teams()) is deterministic and simple but does not minimize head-to-head imbalance. For a recreational league this is acceptable. |
| Odd-N odd target — top-up game (Errata E-3) | (A) Remove the odd-T validation; add 1 top-up game for the 1 remaining short team, selecting the opponent with minimum H2H meetings (tie-broken by div.teams() order). (B) Reject odd T (keep E-2 validation). (C) Use randomness to select the top-up opponent. | A — deterministic min-H2H top-up | N×T is odd when N and T are both odd; it is mathematically impossible for all teams to play exactly T games. The closest achievable outcome is 1 team at T+1 and N-1 teams at T. (B) forces users to adjust their target, which is unacceptable per the requirement. (A) is deterministic (same input → same output, preserving the existing determinism guarantee), and min-H2H selection is the most principled fairness criterion available. (C) would break determinism and provide no stronger fairness guarantee than (A). | The team selected as top-up opponent will play T+1 games in this division. This is disclosed in the partial-cycle log line. |

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
| ~~Odd-N partial cycle — per-team count within ±1 of target~~ | ~~3 teams, target=5: each team plays 4 or 5 games.~~ **Removed** — superseded by Errata E-3 (odd T now succeeds). |
| **Odd-N odd target — E-3 success (N=3, T=5)** | N=3, target=5: `Success`; A=5, B=6 (top-up opponent), C=5. Exactly 1 team at T+1. Test: `oddNOddTargetFiveSucceeds`. |
| **Odd-N odd target — E-3 success (N=3, T=3)** | N=3, target=3: `Success`; A=3, B=4 (top-up opponent), C=3. Test: `oddNOddTargetThreeSucceeds`. |
| **Odd-N odd target — partial-cycle log post-top-up** | N=3, target=3: partial-cycle log shows B at 4 (not 3), emitted after E-3 game. Test: `oddNOddTargetPartialCycleLogReflectsTopUpCounts`. |
| **Odd-N odd target — E-3 success (N=5, T=7)** | N=5, target=7: `Success`; exactly 4 teams at 7, 1 team at 8. Test: `fiveTeamsOddTargetExactlyOneTeamAtTPlus1`. |
| **Odd-N even non-multiple target — all teams reach target** | N=5, target=6 (even, remainder=2): all 5 teams get exactly 6 games via E-2 make-up game. Test: `fiveTeamsEvenNonMultipleTargetAllReachTarget`. |
| **Odd-N even non-multiple target — make-up game is between two short teams** | N=5, target=6: the one make-up game is between the two teams that had 5 games after the partial cycle (not between a 5-game and 6-game team). |
| Log count for double RR | 4 teams, target=6: exactly 2 log lines, both prefixed "Cycle". |
| Log count for partial | 4 teams, target=8: exactly 3 log lines (2 "Cycle", 1 "Partial cycle"). |
| **Odd-N even non-multiple target — cycle log shows correct final counts** | N=5, target=6: the partial-cycle log line (emitted after make-up) shows all 5 teams at 6, not 5. |

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

---

## Errata

### E-1 — Odd-N partial cycles produce `target-1` games for `teams[0]` (2026-06-06)

#### Observed behavior

When a division has an odd number of teams `N` and a target `T` where `T % (N-1) != 0`, the first team in the division's team list (`teams[0]`) — the circle-method fixed team — consistently receives `T-1` games instead of `T`.

#### Root cause

The circle-method rotation is initialised as:

```
slots    = [teams[0], teams[1], ..., teams[N-1], null]  (M = N+1 elements)
fixed    = slots[0]                                     (teams[0])
rotating = slots[1..M-1]                                (ends with null)
```

After exactly `M-1` rotation steps — one complete cycle — the `rotating` list returns to its initial state: `null` is back at index `M-2` (the last position). This is because `M-1` left-shifts of a list of length `M-1` is a full cycle. Consequently:

- **Round 0 of every cycle start** always has `rotating.get(M-2) = null`, so `buildRound` at `specI=0` pairs `fixed` with `null` and skips the game — `teams[0]` draws a bye.
- The partial cycle begins with the rotation in this exact same state (there is no offset between the end of a full cycle and the start of the next).
- Round 0 of every partial cycle is therefore a systematic bye for `teams[0]`.

For any `remainder > 0`:

```
games for teams[0] = fullCycles × (N-1) + (remainder - 1)
                   = [T - remainder] + [remainder - 1]
                   = T - 1
```

The off-by-one is not a loop-bound error; it is a structural consequence of the rotation always returning to the same state and `teams[0]` always occupying the fixed position that draws the bye in round 0.

#### Mathematical constraint

For odd N (padded to M = N+1), each round produces exactly one bye. Across a partial cycle of `remainder` rounds, `remainder` byes are distributed among N teams. For all N teams to play the same number of partial-cycle games, the `remainder` byes must divide evenly — but `0 < remainder < N-1 < N`, so `remainder` is never a multiple of `N`. Exact per-team parity in a partial cycle is therefore mathematically impossible for any odd-N division with `remainder > 0`.

Adding make-up rounds is not a valid escape:
- Each make-up round gives `+1` to the short team **and** `+1` to its opponent.
- The opponent was already at `T`; it becomes `T+1` with no mechanism to trim it back.
- The invariant "all teams exactly T games" cannot be achieved by appending rounds.

#### Fix — extend `validatePreconditions` in `TeamScheduleService`

Add a check after the existing minimum-target check:

```
for each eligible division where N is odd (N % 2 != 0):
    if target % (N - 1) != 0:
        int lo = (target / (N - 1)) * (N - 1);
        int hi = lo + (N - 1);
        emit error: "Division \"{name}\" has {N} teams (odd). Target {T} is not a multiple
                     of {N-1}, so the circle method cannot give every team exactly {T} games.
                     Choose a multiple of {N-1}: nearest valid targets are {lo} and {hi}."
```

This fires before any scheduling work is done and is caught together with other validation errors in the existing `Failure` path.

**No changes to the scheduling loop, `buildRound`, rotation, or log format are required.** The loop only runs when all preconditions pass, so valid odd-N inputs (remainder = 0) are unaffected.

#### Affected spec sections

| Section | Change |
|---------|--------|
| **Critical Path Walkthrough — Case 5** | Superseded: N=3 target=5 is now a validation error; the case is struck through and annotated. |
| **Tradeoff Log** | New row added: odd-N non-multiple target handling (option A — validate and reject). |
| **Test Plan — New tests** | `oddNPartialCyclePerTeamCountWithinOneTolerance` removed; two new validation-failure tests added (N=3 target=5; N=5 target=5). |
| **Test Plan — Existing tests** | `oddNPartialCycleLogDenominatorIsMRounds` (N=3, target=3): `3 % 2 = 1`, now a validation error — test must be changed to assert `Failure` instead of a cycle log. |
| **Operational Concerns** | "Edge cases already handled" note for odd N is updated: odd N with exact-multiple targets works as before; odd N with non-multiple targets now fails fast at validation. |

#### Validity table — E-1 (superseded; kept for reference)

| N | N-1 | Valid targets under E-1           | Invalid under E-1 but valid under E-2 |
|---|-----|-----------------------------------|---------------------------------------|
| 3 | 2   | 2, 4, 6, 8, 10, …                | *(none — E-1 and E-2 agree for N=3)* |
| 5 | 4   | 4, 8, 12, 16, …                  | 6, 10, 14, 18, … (all even non-multiples) |
| 7 | 6   | 6, 12, 18, 24, …                 | 8, 10, 14, 16, 20, 22, … |

For even N, all targets ≥ N-1 remain valid (no partial-cycle bye issue).

---

### E-2 — Accept all even targets for odd-N divisions via make-up game pairing (2026-06-06)

**Supersedes:** The *validation rule* in Errata E-1 (`target % (N-1) != 0` → reject). E-1's root-cause analysis and the identification of which teams are short remain correct and are the foundation of this design.

#### Why E-1's "pair with an at-T opponent" impossibility does not apply here

E-1 correctly proved that adding a make-up *round* forces a T+1 outcome on an at-T opponent. What E-1 missed is that after a partial cycle of `remainder` rounds, **exactly `remainder` teams are at T-1 — and they can be paired with each other**, not with any at-T team. Each such make-up game brings two T-1 teams to T without touching any other team's count.

#### Mathematical proof that remainder is always even for odd N, even T

```
N  odd   →  N-1 even
T  even
remainder = T % (N-1)

T = fullCycles × (N-1) + remainder
    └── even    └── even × integer = even
∴  remainder = T − fullCycles×(N-1) = even − even = even  □
```

When T is odd, N×T = odd × odd = odd. An integer number of games contributes exactly 2 team-appearances each, so the total team-appearances must be even. Odd T with odd N violates this — it is fundamentally impossible and must be rejected.

#### Validation change — replaces E-1's check

```
for each eligible division where N is odd (N % 2 != 0):
    if target % 2 != 0:    // ← replaces "target % (N-1) != 0"
        emit error: "Division \"{name}\" has {N} teams (odd). Target {T} is odd — "
                  + "the total team-games (N × T = {N*T}) would be odd, which cannot "
                  + "be distributed into an integer number of games. "
                  + "Nearest valid targets: {T-1} and {T+1}."
```

For N=3 this produces the exact same accept/reject set as E-1 (N-1=2, so T%2 and T%(N-1) are identical). For N≥5 this is strictly more permissive.

#### Algorithm extension — make-up game phase

Insert the following block immediately **before** the `cycleLogs.add(...)` call in the `remainder > 0` branch. This ensures the partial-cycle log reflects post-make-up counts.

```
// Collect teams short of target; always an even count for valid (odd N, even T) inputs.
List<Team> shortTeams = div.teams().stream()
    .filter(t -> gameCount.get(t.id()) < target)
    .collect(toList());  // preserves div.teams() order for determinism

for (int i = 0; i < shortTeams.size(); i += 2) {
    Team left  = shortTeams.get(i);
    Team right = shortTeams.get(i + 1);
    boolean leftIsHome = (globalRound % 2 == 0);
    Team home = leftIsHome ? left : right;
    Team away = leftIsHome ? right : left;
    // Inline: do not call buildRound; this pairing is independent of circle-method rotation.
    addRound(List.of(new RawGame(home, away, div)), ordered, gameCount);
    globalRound++;
}
// cycleLogs.add(...) follows here — now reflects post-make-up counts.
```

**Nothing else changes.** The full-cycle loop, partial-cycle loop, rotation advancement, `buildRound`, and full-cycle log format are all unmodified. The `RawGame` constructor is private to `TeamScheduleService` so callers see only the public `generate()` interface.

#### Validity table — E-2

| N | N-1 | Minimum target | Valid targets (all even ≥ N-1) | Invalid targets (odd) |
|---|-----|----------------|-------------------------------|-----------------------|
| 3 | 2   | 2              | 2, 4, 6, 8, 10, …            | 3, 5, 7, 9, …        |
| 5 | 4   | 4              | 4, 6, 8, 10, 12, 14, …       | 5, 7, 9, 11, …       |
| 7 | 6   | 6              | 6, 8, 10, 12, 14, …          | 7, 9, 11, 13, …      |

#### Affected spec sections

| Section | Change |
|---------|--------|
| **Critical Path Walkthrough — Case 5** | Unchanged: odd target still invalid under E-2 for the same reason. |
| **Critical Path Walkthrough — Case 6** | Added: N=5, target=6 demonstrates the make-up pairing. |
| **Tradeoff Log** | E-1 row updated to note it is superseded; new E-2 row added. |
| **Test Plan — New tests** | E-1 validation tests updated with new error message text (simpler); two new make-up-pairing tests for N=5 target=6; odd-target tests for N=5 now use the new message. |
| **File Change Summary** | `TeamScheduleService.java` gains the make-up game loop and the updated validation check. |

#### File change delta (relative to E-1)

| File | Change |
|------|--------|
| `TeamScheduleService.java` | Replace `target % (n-1) != 0` validation with `target % 2 != 0`; update error message text; insert make-up game loop before `cycleLogs.add(...)` in the `remainder > 0` branch. |
| `TeamScheduleServiceTest.java` | Update error-message assertions in two existing tests; add new make-up-pairing success tests for N=5 target=6. |

---

### E-3 — Accept all odd targets for odd-N divisions via top-up game (2026-06-06)

**Supersedes:** The *odd-T validation rule* in Errata E-2 (`target % 2 != 0` → reject).

#### Why E-2's "N×T is odd → impossible" argument does not require rejection

E-2 correctly proved that N×T being odd means it is impossible for **all** teams to play exactly T games. What E-2 missed is that "impossible for all teams to reach exactly T" does not mean the input should be rejected — it means the best achievable outcome is N-1 teams at T and exactly 1 team at T+1. This is a tolerable, mathematically sound result that the scheduler can produce deterministically.

#### Mathematical constraint (restated with E-3 resolution)

For odd N and odd T: N×T is odd. Each game contributes 2 team-appearances, so total appearances must be even. The minimum deviation from all-T is exactly 1 team at T+1 (total = (N-1)×T + (T+1) = N×T + 1 — which is even). This is achievable and is the result E-3 produces.

#### How many teams remain short after E-2 pairing (odd T case)

```
N  odd   →  N-1 even
T  odd
remainder = T % (N-1)

T = fullCycles × (N-1) + remainder
    └── odd    └── even × integer = even
∴  remainder = T − even = odd

E-2 pairs floor(remainder / 2) pairs of short teams.
Teams covered by E-2 = 2 × floor(remainder / 2) = remainder − 1   (since remainder is odd)
Teams remaining short = remainder − (remainder − 1) = 1  □
```

After E-2, exactly 1 team is still at T-1. This is always the case for odd N + odd T.

#### Algorithm extension — top-up game phase (E-3)

Insert the following block immediately **after** the E-2 make-up loop and **before** the `cycleLogs.add(...)` call:

```
// Re-collect teams still short (size is 0 for even T, 1 for odd T + odd N).
List<Team> stillShort = div.teams().stream()
    .filter(t -> gameCount.get(t.id()) < target)
    .toList();

if (stillShort.size() == 1) {
    Team shortTeam = stillShort.get(0);
    // Select top-up opponent: team in div.teams() with fewest head-to-head games
    // against shortTeam in `ordered`. Tie-broken by first appearance in div.teams().
    Team topUpOpponent = div.teams().stream()
        .filter(t -> !t.id().equals(shortTeam.id()))
        .min(Comparator.comparingLong((Team t) ->
            ordered.stream()
                .filter(g -> g.division().id().equals(div.id())
                    && ((g.home().id().equals(shortTeam.id()) && g.away().id().equals(t.id()))
                     || (g.home().id().equals(t.id()) && g.away().id().equals(shortTeam.id()))))
                .count()))
        .orElseThrow();
    boolean leftIsHome = (globalRound % 2 == 0);
    addRound(List.of(new RawGame(
        leftIsHome ? shortTeam : topUpOpponent,
        leftIsHome ? topUpOpponent : shortTeam,
        div)), ordered, gameCount);
    globalRound++;
}
// cycleLogs.add(...) follows here — reflects post-top-up counts.
```

**Nothing else changes.** The E-2 loop bound changes from `i < shortTeams.size()` to `i + 1 < shortTeams.size()` (guards against reading index `i+1` when `shortTeams.size()` is odd) — this is a latent correctness fix that is a no-op for even T (where size is always even).

#### Opponent selection — fairness rationale

The top-up opponent is selected by minimum H2H meetings. For N=3 with any odd T, both non-short teams have identical H2H counts (the rotation is perfectly balanced across full cycles), so the tie-break by `div.teams()` natural order is deterministic and effectively arbitrary — either choice is equally fair. For N≥5 with larger odd remainders, the H2H counts may differ slightly, and min-H2H minimises the head-to-head imbalance introduced by the top-up game.

#### Outcome summary

| T parity | N parity | Short teams after partial cycle | E-2 pairs | E-3 top-up | Final state |
|----------|----------|--------------------------------|-----------|------------|-------------|
| even     | even     | 0                              | 0         | 0          | All at T |
| even     | odd      | even (= remainder)             | remainder/2 | 0        | All at T |
| odd      | even     | 0                              | 0         | 0          | All at T |
| odd      | odd      | odd (= remainder)              | (remainder−1)/2 | 1   | N−1 at T, 1 at T+1 |

#### Validity table — E-3 (all T ≥ N-1 now valid)

| N | N-1 | Minimum target | All valid targets |
|---|-----|----------------|-------------------|
| 3 | 2   | 2              | 2, 3, 4, 5, 6, 7, 8, … (all ≥ 2) |
| 5 | 4   | 4              | 4, 5, 6, 7, 8, 9, … (all ≥ 4) |
| 7 | 6   | 6              | 6, 7, 8, 9, 10, … (all ≥ 6) |

#### Affected spec sections

| Section | Change |
|---------|--------|
| **Critical Path Walkthrough — Case 5** | Updated: N=3 target=5 now shows E-3 top-up producing A=5, B=6, C=5. |
| **Critical Path Walkthrough — Case 7** | Added: N=3 target=3, full E-3 walkthrough. |
| **Tradeoff Log** | E-2 row updated to note it is superseded for odd-T case; new E-3 row added. |
| **Test Plan — New tests** | `oddNNonMultipleTargetReturnsFailure` and `oddNNonMultipleTargetThreeReturnsFailure` rewritten to assert `Success`; three new E-3 tests added. |

#### File change delta (relative to E-2)

| File | Change |
|------|--------|
| `TeamScheduleService.java` | Remove `else if (n % 2 != 0 && target % 2 != 0)` validation block entirely; fix E-2 loop bound to `i + 1 < shortTeams.size()`; add E-3 top-up block after E-2 loop. |
| `TeamScheduleServiceTest.java` | Rewrite `oddNNonMultipleTargetReturnsFailure` → `oddNOddTargetFiveSucceeds` (asserts A=5, B=6, C=5); rewrite `oddNNonMultipleTargetThreeReturnsFailure` → `oddNOddTargetThreeSucceeds` (asserts A=3, B=4, C=3); add `oddNOddTargetPartialCycleLogReflectsTopUpCounts`, `fiveTeamsEvenNonMultipleTargetAllReachTarget`, `fiveTeamsOddTargetExactlyOneTeamAtTPlus1`. |
