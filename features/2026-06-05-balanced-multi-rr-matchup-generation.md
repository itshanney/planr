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

4. If `remainder > 0`, generate a partial cycle by continuing from the current rotation state and taking only as many rounds as needed to give each team exactly `remainder` more games. Because each round of the circle method is a perfect 1-factor (every team appears exactly once), taking `remainder` rounds guarantees no pair plays more than once in the partial cycle. For odd-N divisions, some teams will draw a bye in the partial cycle and receive `remainder - 1` games; this ±1 per-team variation is acceptable.

**Invariant guaranteed by this approach:** For any two teams in the same division, their head-to-head count will be either `fullCycles` or `fullCycles + 1`. The maximum head-to-head difference between any two pairs in the same division is 1.

The minimum-target validation (target ≥ N-1) is unchanged. No new configuration fields are introduced.

---

## User Stories

1. **As a league coordinator**, I want all teams to play each opponent a roughly equal number of times, so that the schedule feels fair and no team has a lopsided competitive record due to repeated rematches.

2. **As a league coordinator**, I want the generator to automatically determine the right number of round-robin cycles for my target, so that I don't have to calculate or configure this manually.

3. **As a league coordinator**, I want the schedule generation output to tell me how many full and partial round-robin cycles were applied per division, so that I can understand how the matchup target was reached.

---

## Acceptance Criteria

1. For any division with N teams and a target of T games per team, every pair of teams plays each other either `floor(T / (N-1))` or `floor(T / (N-1)) + 1` times. No pair plays more than `floor(T / (N-1)) + 1` times.

2. Each team plays exactly T games per season. (Or T-1 if parity prevents exact completion for odd-N divisions — consistent with current behavior.)

3. When T is exactly divisible by (N-1), all pairs in the division play exactly `T / (N-1)` times. There are no matchup-count differences between any two pairs.

4. The schedule generation output replaces "Fill round K complete: ..." log lines with per-division cycle log lines using the format: "Cycle K complete: &lt;team counts&gt;" for full cycles, and "Partial cycle (R of N-1 rounds) complete: &lt;team counts&gt;" for the partial cycle.

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
