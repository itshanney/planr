# Tech Spec: Division Curfew Times and Playoff Field Priority

**Date:** 2026-05-31
**Status:** Final
**PRD:** `features/2026-05-31-scheduler-division-curfew-field-priority.md`

---

## Overview

Two independent scheduler constraints are added: a per-division curfew that caps the latest
game/practice start time, and a per-field playoff priority that steers the CP-SAT solver toward
preferred fields for playoff assignments.

Both constraints are stored as nullable fields on their respective records (`Division.curfewTime`,
`Field.playoffPriority`). Null means the constraint is absent — backward-compatible with existing
data, requiring only a schema version bump. The curfew is enforced by **pre-filtering** candidate
slots before the model is built (smaller model, cleaner feasibility path). Field priority is
enforced as a **tiered CP-SAT objective** term (soft preference that yields to the primary
maximize-assignments goal). Schema advances from version 9 to version 10.

---

## Component Diagram

```
CLI layer
├── DivisionCommand.EditCmd     adds --curfew-time / --no-curfew-time options
├── DivisionCommand.ListCmd     adds CURFEW column to output table
├── FieldCommand.EditCmd        adds --playoff-priority / --no-playoff-priority options
├── FieldCommand.ListCmd        adds PLAYOFF_PRI column to output table
└── PlayoffCommand.AssignCmd    prints per-field field-utilization table after solve

Model layer (immutable records)
├── Division                    gains LocalTime curfewTime (nullable)
└── Field                       gains Integer playoffPriority (nullable)

Scheduler layer
├── SchedulerService
│   ├── enumerateAllSlots           filters slots against division.curfewTime()
│   ├── enumeratePlayoffSlots       filters slots against division.curfewTime()
│   ├── enumeratePracticeSlots      filters slots against division.curfewTime()
│   ├── estimateAvailableSlots      filters slots against division.curfewTime()
│   ├── buildAndSolve               adds pre-solve zero-slot curfew guard
│   ├── buildAndSolvePlayoffs       adds pre-solve zero-slot curfew guard
│   │                               adds field-priority term to CP-SAT objective
│   └── buildAndSolvePractices      adds pre-solve zero-slot curfew guard
└── PlayoffFieldSummary (new)   record(fieldId, fieldName, playoffPriority, gamesAssigned)

Persistence layer
└── LeagueStore                 version 9 → 10 migration (no-op stamp)
```

---

## Data Model

### `Division` record — new field

```
LocalTime curfewTime   // nullable; null = no constraint
```

Add after `practiceEnd` as the final component. The `Division` compact constructor does not
need to normalize this (null is the valid "unset" sentinel). All existing constructor call
sites must be updated to pass `null` (or forward the new field) as the last argument.

### `Field` record — new field

```
Integer playoffPriority   // nullable; null = unranked; positive integer; 1 = highest priority
```

Add after `divisionLocks` as the final component. The `Field` compact constructor already
handles null for `blocks`, `dateOverrides`, and `divisionLocks` — `playoffPriority` stays null
(no normalization needed). All existing constructor call sites must pass `null`.

### `LeagueStore` migration

- Increment `CURRENT_VERSION` on `League` from `9` to `10`.
- Add a `v9 → v10` migration block in `LeagueStore.load()` that stamps the version with no
  data transformation (null for both new fields reads correctly from old JSON since
  `FAIL_ON_UNKNOWN_PROPERTIES` is disabled and the fields default to null when absent).

### New record: `PlayoffFieldSummary`

```java
// scheduler package
public record PlayoffFieldSummary(
    UUID fieldId,
    String fieldName,
    Integer playoffPriority,   // null = unranked
    int gamesAssigned) {}
```

Added to `PlayoffScheduleResult.Success` alongside `divisionSummaries`:

```java
record Success(
    Map<UUID, Slot> assignmentsByGameId,
    boolean optimal,
    List<DivisionSummary> divisionSummaries,
    List<PlayoffFieldSummary> fieldSummaries)   // new
    implements PlayoffScheduleResult {}
```

`PlayoffScheduleResult.success(...)` factory method gains a `fieldSummaries` parameter.

---

## API Contracts (CLI)

### `planr division edit <name>`

New options added to `DivisionCommand.EditCmd`:

| Option | Type | Description |
|--------|------|-------------|
| `--curfew-time <HH:mm>` | `String` (parsed to `LocalTime`) | Latest start time for games/practices |
| `--no-curfew-time` | `boolean` flag | Clears any existing curfew |

**Validation:**
- Both `--curfew-time` and `--no-curfew-time` present → exit 1, stderr: `"Error: --curfew-time and --no-curfew-time are mutually exclusive."`
- `--curfew-time` value fails `LocalTime.parse(str, HH_mm_FORMATTER)` → exit 1, stderr: `"Error: Invalid time \"<value>\" for --curfew-time. Expected HH:mm."`
- The no-args guard must be updated to also accept `--curfew-time` or `--no-curfew-time` as sufficient non-null options.

**`applyEdits` logic for curfew:**
```
if clearCurfewTime → resolvedCurfewTime = null
else if newCurfewTimeStr != null → resolvedCurfewTime = parsed LocalTime
else → resolvedCurfewTime = division.curfewTime()   // unchanged
```

**`planr division list` output** — add a `CURFEW` column after `PRAC_END`:
- Value: `HH:mm` when set, `--` when null.

---

### `planr field edit <name>`

New options added to `FieldCommand.EditCmd`:

| Option | Type | Description |
|--------|------|-------------|
| `--playoff-priority <n>` | `Integer` | Positive integer rank (1 = highest) |
| `--no-playoff-priority` | `boolean` flag | Removes rank from field |

**Validation:**
- Both present → exit 1, stderr: `"Error: --playoff-priority and --no-playoff-priority are mutually exclusive."`
- `--playoff-priority` ≤ 0 → exit 1, stderr: `"Error: --playoff-priority must be a positive integer."`
- The no-args guard must be updated to accept the two new options.

**`applyEdits` logic for priority:**
```
if clearPlayoffPriority → resolvedPriority = null
else if newPlayoffPriority != null → resolvedPriority = newPlayoffPriority
else → resolvedPriority = field.playoffPriority()   // unchanged
```

**`planr field list` output** — add a `PLAYOFF_PRI` column after `LOCKS`:
- Value: integer rank when set, `--` when null.

---

### `planr playoff assign` — output addition

After the existing `ScheduleCommand.printConstraintSummary(...)` call, print a field
utilization table using `success.fieldSummaries()`:

```
Field Utilization
-----------------
FIELD           PLAYOFF_PRI  GAMES
--------------  -----------  -----
Westfield #1    1            6
Westfield #2    1            5
Eastfield       2            2
Community Park  --           1
```

Column order: `FIELD`, `PLAYOFF_PRI` (rank or `--`), `GAMES`. Sort by priority rank ascending
(nulls last), then by field name ascending within same rank.

---

## Critical Path Walkthrough

### Path 1 — Set and apply a division curfew (regular season)

1. `planr division edit 6U --curfew-time 19:30`
   - `DivisionCommand.EditCmd.call()` parses `"19:30"` → `LocalTime.of(19, 30)`.
   - Loads league, finds division `6U`, constructs updated `Division` with `curfewTime = 19:30`.
   - Saves. Exits 0.
2. `planr schedule assign`
   - `SchedulerService.assign()` calls `enumerateAllSlots(league, start, end)`.
   - Inside the slot loop for division `6U`: after generating `slotStart`, check
     `curfewTime != null && slotStart.isAfter(curfewTime)` → `break` out of the `while` loop
     for this window (all subsequent starts in this window also exceed curfew).
   - Other divisions (no curfew) are unaffected.
   - Pre-solve curfew guard: if `slotsByDiv.get(6U_id).isEmpty()`, return
     `ScheduleResult.failure("Error: Division \"6U\" has no available slots ...")`.
   - CP-SAT model built without 6U slots exceeding 19:30; solve proceeds normally.

### Path 2 — Assign playoffs with field priority

1. `planr field edit "Westfield #1" --playoff-priority 1`
   — persists `playoffPriority = 1` on that field.
2. `planr field edit "Community Park" --playoff-priority 2`
   — persists `playoffPriority = 2`.
3. `planr playoff assign`
   - `SchedulerService.assignPlayoffs()` calls `enumeratePlayoffSlots(...)` — all slots across
     all fields are included (curfew filter applies if divisions have curfews set).
   - `buildAndSolvePlayoffs()` computes a `fieldPriorityScore` map:
     - Collect all `playoffPriority` values from `league.fields()` that are non-null.
     - `maxRank` = max priority value among ranked fields (e.g. 2).
     - Score for rank `r` = `maxRank - r + 1` (so rank 1 → score 2, rank 2 → score 1).
     - Unranked fields → score 0.
   - For each `GameVar gv`, its priority contribution = `fieldPriorityScore[gv.slot().fieldId()]`.
   - Add `IntVar totalPriorityScore = model.newIntVar(0, maxScore * totalFixtures, "total_pri")`.
   - Add `model.addEquality(totalPriorityScore, LinearExpr.weightedSum(gameVars, priorityScores))`.
   - Modify objective (3-tier weighted sum):
     ```
     W1 = (maxPossiblePriorityScore * totalFixtures + weekCap + 2)
     W2 = weekCap + 1
     maximize(totalAssigned * W1 + totalPriorityScore * W2 - maxWeekLoad)
     ```
     This guarantees:
     - Maximizing assigned games always dominates (one extra assignment > any priority gain).
     - Among equal assignment counts, higher-priority field preference dominates (any priority
       gain > any week-load change within cap bounds).
     - Week-load balancing is the tiebreaker.
   - After solve, build `fieldSummaries` by grouping `assignmentsByGameId.values()` by
     `slot.fieldId()`, looking up `Field.playoffPriority()` for each field.
   - `PlayoffCommand.AssignCmd` prints the field utilization table from `success.fieldSummaries()`.

### Path 3 — Clear a curfew

1. `planr division edit 6U --no-curfew-time`
   - `clearCurfewTime = true` → `resolvedCurfewTime = null`.
   - Saves division with `curfewTime = null`. Exits 0.
   - Subsequent `planr schedule assign` runs produce no curfew filtering for this division.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risks Accepted |
|----------|--------------------|--------|-----------|----------------|
| Curfew enforcement mechanism | CP-SAT hard constraint vs. slot pre-filter | Slot pre-filter | Smaller model, simpler feasibility detection, no new CP-SAT variable; break in while loop is O(1) | None — pre-filter is strictly equivalent since CP-SAT would exclude those vars anyway |
| Curfew on 0-slot detection | Return Failure from scheduler vs. check in command | Failure from scheduler | Keeps command code thin; consistent with existing error-return contract | Must ensure null-safe curfew lookup does not throw on divisions with 0 teams |
| Field priority in regular-season / practices | Share objective code vs. no-op | No-op (playoff solver only) | PRD explicitly excludes non-playoff use; adds complexity for no benefit | Separate `buildAndSolve` and `buildAndSolvePlayoffs` remain divergent but intentionally so |
| `PlayoffFieldSummary` placement | Compute in command vs. carry in result | Carry in result (`PlayoffScheduleResult.Success`) | Keeps `PlayoffCommand.AssignCmd` thin; testable in scheduler unit tests | Adds one more field to `PlayoffScheduleResult.Success`; all call sites must pass it |
| Field priority scoring formula | Raw rank as weight vs. normalized inversion | Normalized inversion `(maxRank - rank + 1)` | Ensures rank 1 always scores higher than rank 2 regardless of how many tiers exist; unranked = 0 is a clean floor | If ranks are non-contiguous (e.g. 1, 5, 100) the relative weight differences are large; acceptable since the solver optimizes to prefer rank 1 regardless of exact gap |
| Curfew time semantics | Hard start time vs. hard end time | Hard start time (PRD decision) | More intuitive: "no games starting after 19:30"; aligns with how sports leagues communicate curfews | Games can end after 19:30; this is accepted per the spec |

---

## Operational Concerns

- **Schema migration:** v9 → v10 migration is a no-op stamp. Old `league.json` files missing
  `curfewTime` and `playoffPriority` deserialize those fields as `null` (Jackson ignores missing
  properties when `FAIL_ON_UNKNOWN_PROPERTIES` is disabled). No data transformation, no backup
  needed.
- **Solver model size:** Curfew filtering can only reduce the slot count, making the CP-SAT
  model smaller or equal. No performance regression possible.
- **Objective weight overflow:** `W1 = maxPriorityScore * totalFixtures * (weekCap + 1) + weekCap + 2`.
  With a league of 50 games, maxRank 10, weekCap 2, this is `10 * 50 * 3 + 4 = 1504`. CP-SAT
  uses 64-bit integers; no overflow concern.
- **Test isolation:** Existing `CommandTestBase` wipes `~/.planr/` before each test. No new
  isolation machinery needed. New fields default to null in `Division.AddCmd` and `Field.AddCmd`
  so existing tests that construct divisions and fields do not break.

---

## Out of Scope / Future Work

Per the PRD:
- Per-date or per-day curfew overrides — deferred.
- League-wide curfew default — deferred.
- Curfew enforcement on `planr schedule game override` — explicitly excluded.
- Per-division field priority — excluded.
- Playoff priority on regular-season or practice scheduling — excluded.

---

## Implementation Checklist

### Model changes
- [ ] `Division.java`: add `LocalTime curfewTime` parameter (last position); update all
      `with*` factory methods that construct a new `Division` to thread `curfewTime` through.
- [ ] `Field.java`: add `Integer playoffPriority` parameter (last position); update all
      `with*` factory methods to thread `playoffPriority` through.
- [ ] `League.java`: bump `CURRENT_VERSION` to `10`; update `empty()` and any test helpers
      that construct `League` directly.
- [ ] `PlayoffFieldSummary.java`: new record in `scheduler` package.
- [ ] `PlayoffScheduleResult.java`: add `List<PlayoffFieldSummary> fieldSummaries` to
      `Success` record; update `success(...)` factory.

### Persistence
- [ ] `LeagueStore.java`: add `if (league.version() < 10)` no-op stamp block.

### Command changes
- [ ] `DivisionCommand.java`:
  - `EditCmd`: add `--curfew-time` / `--no-curfew-time` options; update validation,
    `applyEdits`, and no-args guard.
  - `ListCmd.printTable`: add `CURFEW` column.
- [ ] `FieldCommand.java`:
  - `EditCmd`: add `--playoff-priority` / `--no-playoff-priority` options; update validation,
    `applyEdits`, and no-args guard.
  - `ListCmd.printTable`: add `PLAYOFF_PRI` column.
- [ ] `PlayoffCommand.java`:
  - `AssignCmd.call()`: print field utilization table from `success.fieldSummaries()`.

### Scheduler changes
- [ ] `SchedulerService.java`:
  - `enumerateAllSlots`: add curfew `break` in slot-generation while loop.
  - `enumeratePlayoffSlots`: same.
  - `enumeratePracticeSlots`: same.
  - `estimateAvailableSlots`: add curfew filter using division lookup.
  - `buildAndSolve` / `buildAndSolvePlayoffs` / `buildAndSolvePractices`: add pre-solve
    zero-slot guard that returns `Failure` when a curfewed division has no slots.
  - `buildAndSolvePlayoffs`: add `totalPriorityScore` IntVar, compute `fieldPriorityScore`
    map, extend objective to 3-tier weighted sum, build `fieldSummaries` after solve.

### Test changes
- [ ] All existing test constructors for `Division` must pass an additional `null` argument.
- [ ] All existing test constructors for `Field` must pass an additional `null` argument.
- [ ] `DivisionCommandTest`: add cases for `--curfew-time` validation and persistence.
- [ ] `FieldCommandTest`: add cases for `--playoff-priority` validation and persistence.
- [ ] `SchedulerServiceTest` (or new `SchedulerServiceCurfewTest`): verify curfew slot
      filtering for regular season, playoffs, and practices; verify 0-slot hard fail.
- [ ] `PlayoffCommandTest`: verify field utilization table appears in `playoff assign` output.
- [ ] `LeagueStoreTest`: add v10 migration round-trip test.
