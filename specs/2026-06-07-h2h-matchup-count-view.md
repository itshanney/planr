# Tech Spec: Head-to-Head Matchup Count View (v0.14.1)

**PRD:** `features/2026-06-07-h2h-matchup-count-view.md`  
**Date:** 2026-06-07

## Overview

This is a pure rendering change. Two static methods in `ScheduleCommand.java` — `printHeadToHeadBlock` and `printScheduledHeadToHeadBlock` — currently build a directional N×N matrix where `matrix[homeRow][awayCol]` counts how many times the home team hosted the away team. The change collapses that into a symmetric matrix where every cell holds the total games between the two teams regardless of home/away role. No data model, no persistence layer, and no CLI surface area changes. The only artifacts affected are those two methods and their unit tests in `ScheduleCommandStatsTest` plus the integration assertion in `ScheduleCommandTest`.

## Component Diagram

```
ScheduleCommand.java
├── ViewCmd.run()                        ← unchanged; calls printXxx helpers in sequence
├── printBalanceBlock()                  ← UNCHANGED
├── printScheduledBalanceBlock()         ← UNCHANGED
├── printHeadToHeadBlock()               ← MODIFIED (TeamGame list, DRAFT state)
└── printScheduledHeadToHeadBlock()      ← MODIFIED (ScheduledGame list, FINALIZED state)

ScheduleCommandStatsTest.java
└── HeadToHeadBlock (nested class)       ← TESTS UPDATED (see test impact section)

ScheduleCommandTest.java
└── "head-to-head section appears..."    ← ASSERTION UPDATED (subtitle text)
```

## Data Model

No model changes. The methods receive game lists already loaded from `league.json`:

| Input type | State | Source fields used |
|---|---|---|
| `List<TeamGame>` | DRAFT (`TEAM_SCHEDULE`) | `homeTeamName()`, `awayTeamName()` |
| `List<ScheduledGame>` | FINALIZED (`SCHEDULED`) | `homeTeamName()`, `awayTeamName()` |

Both inputs carry the same semantic information needed: who the two participants were. Home/away direction is intentionally discarded.

## Algorithm Change

### Current (directional)

```
for each game g:
    matrix[homeIdx][awayIdx]++

for each row r:
    rowMode[r] = most-common value in matrix[r][*] excluding diagonal
    flag cell if matrix[r][c] != rowMode[r]
```

### New (symmetric, global mode)

**Step 1 — Build symmetric pair counts**
```
for each game g:
    i = teamIndex[g.homeTeamName()]
    j = teamIndex[g.awayTeamName()]
    matrix[i][j]++
    matrix[j][i]++          ← new: mirror into the other triangle
```

**Step 2 — Compute global mode across all pairs**

Collect the upper-triangle values only (to avoid double-counting each pair):
```
collect all matrix[i][j] where j > i   ← upper triangle = one entry per pair
globalMode = most-frequent value in that collection
             tie-break: choose the lower value (preserves existing tie-break semantics)
```

**Step 3 — Render cells**
```
for each cell (row, col):
    if row == col: cell = "—"
    else:          cell = String.valueOf(matrix[row][col])
                         + (matrix[row][col] != globalMode ? "*" : "")
```

**Step 4 — Header subtitle**

Change from:
```
HEAD-TO-HEAD — %s (row = home team, column = away team)
```
To:
```
HEAD-TO-HEAD — %s (total games between each pair)
```

The same four-step algorithm applies identically to both `printHeadToHeadBlock` and `printScheduledHeadToHeadBlock`. The only difference between the two methods is the list element type (`TeamGame` vs `ScheduledGame`); the logic is otherwise identical.

## Critical Path Walkthrough

### Flow: `planr schedule view` (DRAFT state)

1. `ViewCmd.run()` loads `League` via `store.load()`.
2. Determines state is `TEAM_SCHEDULE` → collects `TeamGame` list from `league.teamSchedule()`.
3. Groups games by division name into `Map<String, List<TeamGame>>`.
4. For each division: calls `printBalanceBlock(games, divName)` (unchanged), then `printHeadToHeadBlock(games, divName)` (modified).
5. In `printHeadToHeadBlock`:
   - Builds sorted team list (alphabetical, unchanged).
   - Iterates games: increments `matrix[i][j]` **and** `matrix[j][i]`.
   - Collects upper-triangle values into a list; computes global mode with existing tie-break logic (lower value on tie).
   - Renders each cell: value + optional `*`; diagonal = `"—"`.
   - Prints header with new subtitle, column headers, separator, and data rows.
6. Output goes to `stdout`. Exit 0.

### Flow: `planr schedule view` (FINALIZED state)

Identical path but `ViewCmd.run()` operates on `List<ScheduledGame>` from `league.schedule()` and calls `printScheduledHeadToHeadBlock`. Symmetric matrix construction and global-mode logic are the same.

## Test Impact

### `ScheduleCommandStatsTest` — `HeadToHeadBlock` nested class

| Test | Action | Reason |
|---|---|---|
| `sectionHeaderIncludesDivisionNameAndAnnotation` | **Update assertions** | Subtitle changes from "row = home team, column = away team" to "total games between each pair" |
| `matrixOrientationIsHomeRowAwayColumn` | **Delete test** | Directly asserts old directional behavior (Alpha hosts Beta 3× → row Alpha = 3, row Beta = 0). New behavior: both cells = 3. This test documents what is intentionally being removed. |
| `deviatingCellFlaggedWithAsteriskDirectlyAppended` | **Update setup + assertions** | Test uses per-row mode reasoning. Rewrite to use a game set where the global mode is unambiguous and one pair has a different count. |
| `modeTieBreakChoosesLowerValue` | **Update setup + assertions** | Currently crafted around per-row mode in row A. Rewrite to produce a global distribution where the tie exists across all pairs, not within a single row. |
| `nonZeroMatchupDisplaysAsInteger` | **Update assertions** | Current fixture: Alpha hosts Beta 2×, Beta hosts Alpha 1× → expected "2" in Alpha row. New: total = 3 for both cells. Update expected value to "3". |
| `zeroMatchupDisplaysAsZeroNotBlank` | **Update comment + verify** | Current fixture uses a cycle (A→B, B→C, C→A) so directionally `matrix[A][C]=0`. Symmetrically: A vs C = 1 (C→A), so no zero exists. Replace with a fixture that genuinely has a pair with zero total games (e.g., 4 teams, not all pairs scheduled). |
| `uniformRowValuesProduceNoFlags` | **Verify passes as-is** | Fixture has every pair playing exactly once in each direction → total per pair = 2 → global mode = 2 → no flags. Logic unchanged; should pass. |
| `columnHeadersAreSortedAlphabetically` | **No change needed** | Tests column ordering, not counts. |
| `rowLabelsAreSortedAlphabetically` | **No change needed** | Tests row ordering, not counts. |
| `diagonalCellsContainEmDash` | **No change needed** | Diagonal rendering unchanged. |
| `columnHeaderRowIsIndentedByRowLabelWidth` | **No change needed** | Layout logic unchanged. |
| `columnSeparatorWidthMeetsTeamNameLength` | **No change needed** | Column width calculation unchanged. |

### `ScheduleCommandTest` — integration tests

| Test | Action |
|---|---|
| `headToHeadSectionAppearsInTeamScheduleState` (line 882) | Assert `stdout().contains("HEAD-TO-HEAD")` — still passes; no change. |
| `"head-to-head section appears..."` subtitle assertion (line 887) | If it asserts the old subtitle text, **update** to new subtitle. |
| Any assertion matching "row = home team" or "column = away team" | **Update** to match new subtitle. |

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| Symmetric matrix via double-increment | (a) Double-increment both triangles in the game loop; (b) build half-matrix and mirror at render time | Double-increment (a) | Simplest; render loop stays unchanged; both cells are always equal | None — matrix is small (≤ ~20 teams) |
| Global mode vs per-row mode | Per-row (current), global across all pairs | Global | Detects division-wide imbalance; per-row could flag a team as outlier even when the pair count is the division norm | A division where two separate sub-groups have different but internally consistent counts will produce more flags |
| Upper-triangle-only for mode collection | Collect all non-diagonal cells (double-counts each pair), collect upper triangle only | Upper triangle | Each pair should contribute one vote to the mode frequency, not two; double-counting would not change the mode value in most cases but is semantically wrong | None |
| Tie-break on mode (lower value wins) | Tie-break lower vs higher | Lower value (preserve existing) | Existing behavior and test expectations already specify lower-value tie-break | None |
| Deleting `matrixOrientationIsHomeRowAwayColumn` test | Delete vs rewrite to test new symmetric property | Delete | The test title and body document the old directional contract; repurposing it would be misleading. A new test verifying symmetric equality can be added separately if desired. | Slight reduction in test coverage; mitigated by the updated `nonZeroMatchupDisplaysAsInteger` test which will implicitly verify symmetry |

## Operational Concerns

- **No deployment concerns.** This is a CLI tool; the change ships with the next `gradle installDist`.
- **No migration.** `league.json` is not touched.
- **No backward compatibility concern.** Output format is for human consumption; no downstream tooling parses it.
- **Rollback:** revert the two method bodies and restore the deleted test. One commit.

## Out of Scope / Future Work

- Adding a flag to toggle between symmetric (matchup count) and directional (home/away) views — deferred per PRD.
- Applying the same symmetric treatment to the calendar view — not requested.
- Extracting the shared matrix-building logic into a shared helper (both methods are ~70 lines and identical except for the list element type) — a worthwhile cleanup but outside this ticket's scope.
