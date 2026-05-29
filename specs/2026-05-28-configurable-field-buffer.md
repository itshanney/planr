# Tech Spec: Configurable Field Buffer and Game Start Grid

**Date:** 2026-05-28
**PRD:** `features/2026-05-28-configurable-field-buffer.md`
**Status:** Final

---

## Overview

This is a configuration-layer change with no new persistence entities. Two new nullable
`Integer` fields are added to the existing `LeagueConfig` record - `fieldBufferMinutes` and
`gridMinutes` — and surfaced via `planr config set` and `planr config show`. 

The two hardcoded constants `BUFFER_MINUTES = 15` and `GRID_MINUTES = 15` in `SchedulerService` are replaced by
runtime reads from `LeagueConfig`, with null treated as the new defaults (0 and 30
respectively). The same pattern already exists in the codebase for `maxGamesPerWeek` and
`minRestDays`. `LeagueStore` advances to schema v9 with a version-stamp-only migration block.
No stored game times are touched.

---

## Component Diagram

```
planr config set
  --field-buffer-minutes <n>       validates ≥ 0; persists to LeagueConfig.fieldBufferMinutes
  --grid-minutes <n>               validates > 0 and 60 % n == 0; persists to LeagueConfig.gridMinutes

planr config show                  reads and displays both new fields with "(default)" label when null

LeagueConfig  ──────────────────── record: source of truth for both new settings
      │
      ├── SchedulerService         reads both fields at solve time; drives slot enumeration,
      │       assign()             tick-occupancy map, and estimateAvailableSlots()
      │       estimateAvailableSlots()
      │
      └── ScheduleGameCommand      reads fieldBufferMinutes at override validation time
              gamesConflict()
```

---

## Data Model

### `LeagueConfig` (modified record)

Two new nullable `Integer` fields appended after the existing eight:

| Field | Type | Null means | Effective default |
|---|---|---|---|
| `fieldBufferMinutes` | `Integer` | not configured | `0` |
| `gridMinutes` | `Integer` | not configured | `30` |

**No migration transformation required.** Absence of either field in an existing `league.json`
deserializes as `null` (Jackson's `FAIL_ON_UNKNOWN_PROPERTIES` is disabled and unknown fields
are already ignored in the reverse direction). The compact constructor normalizes nothing for
these two fields — null is the valid "unset" sentinel, identical to `maxGamesPerWeek` and
`minRestDays`.

`LeagueConfig.empty()` continues to pass `null` for both new fields.

All existing `withX(...)` builder methods must be updated to thread the new fields through
unchanged. Two new builder methods are added:

```
LeagueConfig withFieldBufferMinutes(Integer n)
LeagueConfig withGridMinutes(Integer n)
```

### `LeagueStore` schema version

Current: `8` → New: `9`

Migration block is a version-stamp-only entry (same pattern as v5, v6, v7, v8):

```java
if (league.version() < 9) {
  league = new League(9, league.config(), league.divisions(), league.fields(),
      league.teamSchedule(), league.schedule(), league.playoffs(),
      league.practiceSchedules());
  save(league);
}
```

`League.CURRENT_VERSION` constant updated from `8` to `9`.

---

## API Contracts

### `planr config set` (modified command)

Two new options added to `ConfigCommand.SetCmd`:

```
--field-buffer-minutes <N>
    Minutes of dead time added after each game/practice before the field is
    re-available. Must be a non-negative integer (≥ 0).
    Error: "Error: --field-buffer-minutes must be a non-negative integer (got \"<N>\")."
    Exit 1 on invalid input.

--grid-minutes <N>
    Interval in minutes between generated game/practice start times. Must be a
    positive integer that evenly divides 60.
    Error: "Error: --grid-minutes must be a positive integer that evenly divides 60 (got \"<N>\")."
    Exit 1 on invalid input.
```

The no-op guard that requires at least one option be provided must be extended to include both
new option strings.

The `new LeagueConfig(...)` construction inside `SetCmd.call()` must pass the two new fields
through (using the existing pattern: `(fieldBufferMinutes != null) ? fieldBufferMinutes :
existing.fieldBufferMinutes()`).

### `planr config show` (modified command)

Two new lines added to `ShowCmd.call()` output, placed immediately after "Min rest days",
before the blank line that precedes "Day-of-week windows":

```
Field buffer:     0 min (default)       ← when fieldBufferMinutes is null
Field buffer:     15 min                ← when fieldBufferMinutes is set
Grid interval:    30 min (default)      ← when gridMinutes is null
Grid interval:    15 min                ← when gridMinutes is set
```

---

## Critical Path Walkthrough

### Path 1: Operator sets buffer to 0 and grid to 30, then runs schedule assign

1. `planr config set --field-buffer-minutes 0 --grid-minutes 30`
2. `SetCmd.call()` parses both strings. Buffer `0` ≥ 0 → valid. Grid `30` > 0 and
   `60 % 30 == 0` → valid.
3. Constructs new `LeagueConfig` with `fieldBufferMinutes = 0`, `gridMinutes = 30`; all
   existing fields preserved.
4. `store.save(league.withConfig(updated))` → persists to `league.json`.
5. `planr schedule assign` → `SchedulerService.assign(league)` reads `league.config()`.
6. `effectiveBuffer = 0`, `effectiveGrid = 30`.
7. Slot enumeration: `slotStart` advances by `30` minutes per step → start times are
   `:00` and `:30` only.
8. Tick-occupancy map: each game occupies ticks from `gameStart` to
   `gameStart + gameDurationMinutes + 0` (exclusive), stepping by `30`. With buffer 0 and
   duration 60, a game at 9:00 occupies ticks {540}. A game at 10:00 occupies ticks {600}.
   No overlap → back-to-back allowed.
9. C2 (field non-overlap) enforced: solver can pack a 60-min game ending at 10:00 immediately
   followed by another starting at 10:00 on the same field.

### Path 2: Invalid `--grid-minutes` input

1. `planr config set --grid-minutes 7`
2. `SetCmd.call()` parses `"7"` → `Integer.parseInt` succeeds → `7 > 0` passes the positive
   check → `60 % 7 = 4 ≠ 0` → fails the divisibility check.
3. `System.err.printf("Error: --grid-minutes must be a positive integer that evenly divides 60
   (got \"7\").%n")`
4. Returns exit code `1`. Nothing is written to disk.

### Path 3: Manual game override with non-default buffer

1. `planr schedule game override --game 3 ...`
2. `ScheduleGameCommand.OverrideCmd.call()` loads the league, resolves the new game, then
   calls `gamesConflict(updated, other)`.
3. `gamesConflict` now accepts a third `int bufferMinutes` parameter derived from
   `league.config().fieldBufferMinutes()` (null → 0).
4. Overlap check: `aEnd = aStart + a.gameDurationMinutes() + bufferMinutes`.
5. Warning printed only if the configured buffer would be violated.

---

## Implementation Checklist

All changes are isolated to four files plus the store migration. In dependency order:

### 1. `LeagueConfig.java`
- Add `Integer fieldBufferMinutes` and `Integer gridMinutes` as the 9th and 10th record
  components.
- `empty()`: pass `null, null` for the two new fields.
- Update all eight existing `withX(...)` methods to thread new fields through unchanged.
- Add `withFieldBufferMinutes(Integer n)` and `withGridMinutes(Integer n)`.
- Compact constructor: leave both new fields as-is (null is the valid sentinel; do not
  default them here).

### 2. `League.java`
- No record changes. Update `CURRENT_VERSION` to `9`.

### 3. `LeagueStore.java`
- Add v8→v9 migration stamp block (see Data Model section).

### 4. `ConfigCommand.java` (`SetCmd` and `ShowCmd`)
- Add `String fieldBufferMinutesStr` and `String gridMinutesStr` option fields.
- Extend the no-op guard to include both new strings.
- Add validation blocks (non-negative for buffer; positive + divides-60 for grid).
- Update `new LeagueConfig(...)` to pass all ten fields.
- Add display lines in `ShowCmd`.

### 5. `SchedulerService.java`
- Replace `private static final int BUFFER_MINUTES = 15` with
  `static final int DEFAULT_FIELD_BUFFER_MINUTES = 0`.
- Replace `private static final int GRID_MINUTES = 15` with
  `static final int DEFAULT_GRID_MINUTES = 30`.
- Add private helper (or inline resolution) at the top of `assign()`,
  `assignPractices()`, and `estimateAvailableSlots()`:
  ```
  int bufferMinutes = (config.fieldBufferMinutes() != null)
      ? config.fieldBufferMinutes() : DEFAULT_FIELD_BUFFER_MINUTES;
  int gridMinutes = (config.gridMinutes() != null)
      ? config.gridMinutes() : DEFAULT_GRID_MINUTES;
  ```
- Replace all uses of `BUFFER_MINUTES` and `GRID_MINUTES` with the local variables.
- In `estimateAvailableSlots`: update the fit-check condition from
  `slotStart.plusMinutes(gameDurationMinutes)` to
  `slotStart.plusMinutes(gameDurationMinutes + bufferMinutes)`; update the step from
  `GRID_MINUTES` to `gridMinutes`.

### 6. `ScheduleGameCommand.java`
- Change `gamesConflict(ScheduledGame a, ScheduledGame b)` signature to accept
  `int bufferMinutes` as a third parameter.
- Replace both hardcoded `+ 15` with `+ bufferMinutes`.
- Update caller to derive `bufferMinutes` from the loaded league config (null → 0).
- Update the warning message string that references "15-minute buffer" to use the
  configured value or remove the specific number.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk |
|---|---|---|---|---|
| Where to resolve null → default | In `LeagueConfig` (accessor methods) vs. in `SchedulerService` (constants) | `SchedulerService` constants + local resolution at call site | Matches existing `maxGamesPerWeek`/`minRestDays` pattern; keeps `LeagueConfig` a plain data record with no behavior | Constants in `SchedulerService` must stay in sync with the intent of the config field |
| Default for `gridMinutes` | 15 (preserve current behavior) vs. 30 (new cleaner default) | 30 | User explicitly requested :00/:30 starts as the new default; any existing league.json without this field gets 30, which is the intended steady state | Existing leagues mid-season that had 15-min slots re-running schedule assign will get 30-min slots; operator must explicitly set `--grid-minutes 15` to restore old behavior |
| Default for `fieldBufferMinutes` | 15 (preserve current) vs. 0 (maximize utilization) | 0 | User explicitly chose 0; absence means no buffer, consistent with the feature's stated goal | Same as above: operators who relied on the 15-min buffer behavior must explicitly set it |
| Migration strategy | Transform existing config to stamp in defaults (15 and 15) vs. version-stamp only with null = new defaults | Version-stamp only | Buffer and grid only affect future scheduling runs, not stored game times; no in-flight data is corrupted by the new defaults | A league mid-season re-running assign will silently pick up 0/30 defaults. This is documented behavior, not a silent regression, but operator communication is needed |
| `gamesConflict` buffer parameter | Pass buffer as arg vs. inject league into method | Pass as `int` arg | Method stays a pure function; already only called from one site | Minimal; caller has the loaded league |

---

## Operational Concerns

**Backward compatibility:** `league.json` files at v1–v8 that lack `fieldBufferMinutes` and
`gridMinutes` deserialize both as `null` and receive the new defaults (0 and 30) on first
access by the solver. This is intentional and documented in the PRD.

**Forward compatibility:** A v9 file read by a hypothetical v8 binary will silently ignore
both new fields (Jackson's `FAIL_ON_UNKNOWN_PROPERTIES` is already disabled). They will be
dropped on the next save. This is acceptable for the CLI prototype context.

**Rollback:** Decrement `CURRENT_VERSION` back to `8`, remove the new fields from
`LeagueConfig`, and redeploy. Any v9 files on disk are readable by v8 with field loss on
next save.

---

## Out of Scope / Future Work

- Per-division or per-field buffer/grid overrides.
- Separate buffer values for games vs. practices.
- Sub-minute grid resolution.
- Surfacing buffer/grid values in `schedule status` output or per-game detail views.
- Making `SOLVER_TIME_LIMIT_SECONDS` configurable (separate concern, separate PRD).
