# Balanced Multi-RR Matchup Generation

**Version target:** v0.14.0
**Date:** 2026-06-05

---

## Problem Statement

The current matchup generator runs one single round-robin and then uses a greedy "fill round" algorithm to reach the per-team target. The greedy pairing selects teams by largest game deficit, which does not account for head-to-head history. This allows one pair of teams to play each other 3 or more times while another pair never meets — a fairness failure that is visible and frustrating for coordinators and players.

The root cause is structural: fill rounds draw from the full pool of matchups without enforcing distribution constraints, so popular pairings (those that keep appearing at the top of the deficit sort) get repeated.

---

## Proposed Solution

Replace the greedy fill-round phase with a structured multi-cycle round-robin approach. For a division with **N** teams and a configured target of **T** games per team per season:

1. Compute the number of complete round-robin cycles that fit within the target:
   `fullCycles = floor(T / (N-1))`

2. Compute the number of remaining per-team games after complete cycles:
   `remainder = T mod (N-1)`

3. Generate `fullCycles` complete round-robin passes using the existing circle method. Each pass continues the circle rotation from where the previous pass ended, preserving home/away balance across cycle boundaries.

4. If `remainder > 0`, generate a partial cycle by continuing from the current rotation state and taking only as many rounds as needed. For odd-N divisions, the null bye-slot causes some teams to fall one game short after the partial cycle. Two recovery phases run after the partial cycle rounds to bring every team to T:

   - **Make-up pairing (even shortfall):** Collect all teams still at T-1. When N is odd and T is even, the shortfall count is always even — pair short teams with each other in sequential order, adding one make-up game per pair. Both teams in each pair advance from T-1 to T.
   - **Top-up game (odd shortfall):** When N is odd and T is odd, N×T is mathematically odd — it is impossible for every team to reach exactly T with an integer number of games. After make-up pairing, exactly 1 team remains at T-1. Add a single top-up game for that team against the opponent with whom it has the fewest head-to-head meetings (tie-broken by division team list order). The short team reaches T; its opponent unavoidably reaches T+1. This is the minimum achievable deviation.

**Invariant guaranteed by this approach:** For any two teams in the same division, their head-to-head count will be either `fullCycles` or `fullCycles + 1`. The maximum head-to-head difference between any two pairs in the same division is 1. No team finishes below T.

The minimum-target validation (target ≥ N-1) is unchanged. No new configuration fields are introduced.

---

## User Stories

1. **As a league coordinator**, I want all teams to play each opponent a roughly equal number of times, so that the schedule feels fair and no team has a lopsided competitive record due to repeated rematches.

2. **As a league coordinator**, I want the generator to automatically determine the right number of round-robin cycles for my target, so that I don't have to calculate or configure this manually.

3. **As a league coordinator**, I want the schedule generation output to tell me how many full and partial round-robin cycles were applied per division, so that I can understand how the matchup target was reached.

---

## Acceptance Criteria

1. For any division with N teams and a target of T games per team, every pair of teams plays each other either `floor(T / (N-1))` or `floor(T / (N-1)) + 1` times. No pair plays more than `floor(T / (N-1)) + 1` times.

2. Each team plays exactly T games per season with no exceptions for even N, or for odd N with even T. When N is odd and T is odd (making N×T odd), exactly N-1 teams play T games and exactly 1 team plays T+1 games — the minimum achievable deviation. No team may finish below T under any circumstances.

3. When T is exactly divisible by (N-1), all pairs in the division play exactly `T / (N-1)` times. There are no matchup-count differences between any two pairs.

4. The schedule generation output replaces "Fill round K complete: ..." log lines with per-division cycle log lines using the format: "Cycle K complete: &lt;team counts&gt;" for full cycles, and "Partial cycle (R of N-1 rounds) complete: &lt;team counts&gt;" for the partial cycle. Partial-cycle log lines are emitted after all make-up and top-up games, so counts reflect the final per-team totals including any recovery games.

5. A target that falls between single and double RR thresholds produces a schedule that includes all single-RR matchups, with additional games drawn from a partial second RR cycle (not repeated matchups from the first cycle).

6. A target that falls between double and triple RR thresholds produces a schedule that includes all single and double RR matchups, with additional games drawn from a partial third RR cycle.

7. The minimum-target validation (target must be ≥ N-1) continues to reject under-target configurations with the same error message format.

8. Existing tests for `TeamScheduleService` pass or are updated to reflect the new log format. No regressions in `ScheduleCommand` or `ScheduleGameCommand` behavior.

---

## Out of Scope

- Changes to Phase 2 (CP-SAT field and time assignment) — `SchedulerService` is unaffected.
- Playoff scheduling (`PlayoffBracketService`, `assignPlayoffs`).
- Practice scheduling (`assignPractices`).
- Exposing the number of RR cycles as a separate user-configurable setting on the division.
- Enforcing a maximum number of cycles (e.g., capping at triple RR) — the algorithm extends naturally to any number of complete cycles.
- Changes to how `targetGamesPerTeam` is set or displayed (`division edit`, `division list`).

---

## Dependencies

- No external dependencies. This change is isolated to `TeamScheduleService` in the scheduler package.
- `TeamScheduleResult.Success` carries `fillRoundLogs` — the field name and type may need updating to reflect the new log semantics, which would require updating callers in `ScheduleCommand`.
