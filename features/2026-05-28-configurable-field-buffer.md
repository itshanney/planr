# Configurable Field Buffer and Game Start Grid

**Date:** 2026-05-28
**Status:** Final

---

## Problem Statement

The scheduler uses two hardcoded constants that restrict field utilization: a 15-minute buffer
added after every game (preventing back-to-back scheduling), and a 15-minute time grid that
allows games to start at :00, :15, :30, and :45 past the hour. Both values should be
configurable. League operators need back-to-back games and want all start times to land on the
hour or half hour, neither of which is achievable today.

---

## Proposed Solution

Add two new league-wide settings to `planr config set`:

- **`--field-buffer-minutes <n>`** — minutes of dead time added after each game (and practice)
  before the field is considered available again. Defaults to `0`. Replaces the hardcoded
  `BUFFER_MINUTES = 15`.

- **`--grid-minutes <n>`** — the interval at which game (and practice) start times are
  generated. Must be a positive integer that evenly divides 60. Defaults to `30`, so all
  starts land on the hour or half hour. Replaces the hardcoded `GRID_MINUTES = 15`.

Both values are applied uniformly to:
- The CP-SAT field non-overlap constraint for game scheduling
- The CP-SAT field non-overlap constraint for practice scheduling
- The `estimateAvailableSlots` pre-confirm feasibility calculation
- The `gamesConflict()` overlap check in `schedule game override`

Absence of either field in an existing `league.json` file is treated as the default value (`0`
for buffer, `30` for grid). No data migration is required since both values only affect future
scheduling runs — stored game times are never altered.

---

## User Stories

1. **As a league scheduler**, I want games to be assigned back-to-back on a field, so that I
   can fit the maximum number of games into the season.

2. **As a league scheduler**, I want to configure a transition buffer between games (e.g. 15
   minutes), so that my league's specific field-turnover requirements are respected by the
   solver.

3. **As a league scheduler**, I want all game start times to land on the hour or half hour, so
   that the schedule is easy for parents and coaches to read.

4. **As a league scheduler**, I want the feasibility estimate shown before I confirm scheduling
   to reflect both the configured buffer and grid, so that the slot count is accurate.

5. **As a league scheduler**, I want manual game overrides to warn me about conflicts based on
   the configured buffer, so that the overlap check stays meaningful.

---

## Acceptance Criteria

### Field buffer (`--field-buffer-minutes`)

1. `planr config set --field-buffer-minutes 0` exits 0 and persists the value.
2. `planr config set --field-buffer-minutes 15` exits 0 and persists the value.
3. `planr config set --field-buffer-minutes -1` exits 1 with stderr containing
   "must be a non-negative integer".
4. `planr config show` displays the configured buffer value.
5. When `--field-buffer-minutes` has never been set, the effective value is `0`.
6. When buffer is `0`, the solver may assign a game starting at the exact minute a prior game
   on the same field ends (no forced gap).
7. When buffer is `15`, no game on the same field starts within 15 minutes of a prior game's
   end time (equivalent to prior behavior).
8. `estimateAvailableSlots` uses the configured buffer; with buffer `0` it returns a count ≥
   the count returned with buffer `15` for identical inputs.
9. `schedule game override` conflict detection uses the configured buffer value, not a
   hardcoded constant.
10. The buffer applies to practice scheduling under the same `--field-buffer-minutes` value.
11. Changing the buffer after a schedule is assigned does not alter any already-stored game
    times.

### Game start grid (`--grid-minutes`)

12. `planr config set --grid-minutes 30` exits 0 and persists the value.
13. `planr config set --grid-minutes 60` exits 0 and persists the value.
14. `planr config set --grid-minutes 0` exits 1 with stderr containing
    "must be a positive integer that evenly divides 60".
15. `planr config set --grid-minutes 7` exits 1 with stderr containing
    "must be a positive integer that evenly divides 60".
16. `planr config set --grid-minutes -5` exits 1 with stderr containing
    "must be a positive integer that evenly divides 60".
17. `planr config show` displays the configured grid value.
18. When `--grid-minutes` has never been set, the effective value is `30`.
19. When grid is `30`, all assigned game start times have a minute value of `:00` or `:30`.
20. When grid is `60`, all assigned game start times have a minute value of `:00`.
21. `estimateAvailableSlots` uses the configured grid; with grid `30` it returns a count ≤ the
    count returned with grid `15` for identical inputs.
22. The grid applies to practice scheduling under the same `--grid-minutes` value.

---

## Out of Scope

- Per-division or per-field buffer or grid values. League-wide settings only.
- Separate buffer values for practices vs. games.
- Separate grid values for practices vs. games.
- Surfacing buffer or grid values in `schedule status` output.
- Any UI or web-application changes.
- Sub-minute grid resolution.

---

## Open Questions

None. All questions resolved.

---

## Dependencies

- `LeagueConfig` record gains two nullable fields: `fieldBufferMinutes` (int, effective default
  `0`) and `gridMinutes` (int, effective default `30`). Absence in existing JSON files reads as
  the respective default — no migration entry needed.
- `LeagueStore` schema version must be incremented to record the addition.
- `SchedulerService.BUFFER_MINUTES` replaced by `league.config().fieldBufferMinutes()` (null →
  `0`).
- `SchedulerService.GRID_MINUTES` replaced by `league.config().gridMinutes()` (null → `30`).
- `ScheduleGameCommand.gamesConflict()` hardcoded `15` replaced by the configured buffer value.
- `estimateAvailableSlots` updated to read both configured values.
