# Head-to-Head Matchup Count View

**Version:** v0.14.1  
**Date:** 2026-06-07

## Problem Statement

The current `planr schedule view` HEAD-TO-HEAD matrix tracks *directional* game counts — rows represent the home team and columns represent the away team. This makes the matrix asymmetric and optimized for auditing home/away balance, which duplicates the HOME/AWAY BALANCE table already shown above it. The matrix is not useful for detecting whether two teams are scheduled to play each other an unexpected number of times relative to other pairs, which is the more actionable imbalance signal for a league administrator.

## Proposed Solution

Replace the directional home/away count in each HEAD-TO-HEAD matrix cell with the **total number of games played between the two teams** (regardless of who is home or away). The matrix becomes symmetric. A `*` flag marks any pair whose game count differs from the most common count across all pairs in the division, surfacing matchup imbalances at a glance. The HOME/AWAY BALANCE table is unchanged.

This change applies to both the DRAFT view (`planr schedule view` before assignment) and the FINALIZED view (`planr schedule view` after assignment).

## User Stories

1. As a league administrator reviewing a generated schedule, I want to see how many times each pair of teams plays each other, so that I can quickly identify pairs that are over- or under-scheduled relative to the rest of the division.

2. As a league administrator, I want outlier team pairs flagged with a `*` marker in the HEAD-TO-HEAD matrix, so that I do not have to mentally compare every cell to find imbalances.

3. As a league administrator, I want the matrix subtitle to accurately describe what the cells mean, so that I do not misread the data.

## Acceptance Criteria

1. Each cell `[A][B]` in the HEAD-TO-HEAD matrix displays the total number of games between team A and team B (home + away combined), not the directional home-game count.
2. The matrix is symmetric: `cell[A][B]` equals `cell[B][A]` for all pairs.
3. The diagonal cells still display `—`.
4. A `*` suffix is appended to a cell's value when that pair's total game count differs from the **global mode** — the most common pairwise game count across all non-diagonal pairs in the division.
5. A pair with zero games displays `0*` (not suppressed) when zero is not the global mode, making scheduling gaps visible.
6. The matrix header subtitle no longer reads "row = home team, column = away team"; it reads something that accurately reflects pair totals (e.g., "total games between each pair").
7. The HOME/AWAY BALANCE table above the matrix is unchanged.
8. The change applies consistently to both the DRAFT state view and the FINALIZED state view.
9. `planr schedule view --division <name>` displays the updated matrix filtered to that division.
10. Existing tests for `printHeadToHeadBlock` and `printScheduledHeadToHeadBlock` are updated to assert the new symmetric output.

## Out of Scope

- Changing the HOME/AWAY BALANCE table in any way.
- Adding new CLI flags or options to toggle between the old directional view and the new symmetric view.
- Modifying the `planr schedule stats` output.
- Changing the calendar view (`planr schedule calendar`).
- Any changes to how game data is stored in `league.json`.

## Open Questions

None — all questions resolved.

## Dependencies

- None. This is a pure rendering change within `ScheduleCommand.java` (`printHeadToHeadBlock` and `printScheduledHeadToHeadBlock` static methods) and their corresponding tests in `ScheduleCommandTest` / `ScheduleCommandStatsTest`.
