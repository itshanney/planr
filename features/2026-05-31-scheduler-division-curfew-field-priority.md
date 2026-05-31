# Scheduler Constraints: Division Curfew Times and Playoff Field Priority

**Date:** 2026-05-31
**Status:** Final

---

## Problem Statement

The scheduler currently has no concept of age-appropriate start times for younger divisions. A
game assigned to a 6U division at 8:00 PM is technically valid but operationally impossible for
families. 

Separately, playoff games are assigned to fields without regard to field quality — but
for playoff games, where stakes and attendance are highest, the league wants its best fields used
first. 

Both gaps require new per-division and per-field configuration backed by scheduler
enforcement.

---

## Proposed Solution

### 1 — Division Curfew Time

Add an optional curfew time to each division. When set, the curfew is the latest time at which
a game or practice for that division may **start**. The constraint applies to the CP-SAT
regular-season solver, the playoff solver, and the practice solver. It is a hard constraint:
the scheduler will not produce a schedule that violates any division's curfew, even if that means
fewer games are assigned.

- Configured via `planr division edit <name> --curfew-time HH:mm` (and cleared with
  `--no-curfew-time`).
- Stored as a nullable `LocalTime` field on the `Division` record.
- A game or practice that starts exactly at the curfew time is valid; one that starts any later
  is not.

### 2 — Playoff Field Priority

Add an optional integer priority rank to each field. During playoff scheduling only, the
CP-SAT solver treats higher-priority fields as preferred over lower-priority ones. Priority is a
soft preference: if preferred fields cannot accommodate all games, the solver falls back to
lower-priority fields. Regular-season and practice scheduling ignore the rank entirely.

- Configured via `planr field edit <name> --playoff-priority <n>` (1 = highest; cleared with
  `--no-playoff-priority`).
- Stored as a nullable integer field on the `Field` record.
- Fields without a priority rank are treated as the lowest tier (scheduled only after all ranked
  fields are exhausted).
- `planr playoff assign` output includes a per-field summary showing the priority rank and game
  count for each field used.

---

## User Stories

1. **As a league scheduler**, I want to set a curfew time per division so that the solver never
   schedules a game or practice to start after that time, protecting younger players from late
   evenings.

2. **As a league scheduler**, I want to clear a division's curfew time so that I can remove the
   constraint without deleting the division.

3. **As a league scheduler**, I want the feasibility estimate shown before confirming assignment
   to account for the curfew, so that the reported slot count reflects only start times that
   satisfy the constraint.

4. **As a league scheduler**, I want to assign a playoff priority rank to a field so that the
   scheduler fills the best fields first when assigning playoff games.

5. **As a league scheduler**, I want fields without a playoff priority to still be used if
   ranked fields are insufficient, so that playoff scheduling never hard-fails due to priority
   configuration alone.

---

## Acceptance Criteria

### Division Curfew Time

1. `planr division edit <name> --curfew-time 19:30` exits 0 and persists the curfew as
   `"19:30"` on the named division.
2. `planr division edit <name> --curfew-time 25:00` exits 1 with stderr containing
   "invalid time".
3. `planr division edit <name> --curfew-time abc` exits 1 with stderr containing "invalid
   time".
4. `planr division edit <name> --no-curfew-time` exits 0 and removes the curfew from the
   named division, leaving all other division fields unchanged.
5. `planr division list` (or equivalent view command) displays each division's curfew time when
   set, and shows nothing (or a dash) when unset.
6. When a division has a curfew of `19:30`, no game for that division is assigned a start time
   later than `19:30` (a game starting at exactly `19:30` is valid; one starting at `19:31`
   is not).
7. When a division has a curfew of `19:30`, no practice slot for that division is assigned a
   start time later than `19:30`.
8. If curfew constraints reduce the available slots to zero for a division, `planr schedule
   assign` (and `planr playoff assign`, `planr practice assign`) exits 1 with stderr identifying
   the division and the curfew constraint rather than producing a partial schedule silently.
9. `estimateAvailableSlots` filters out any time slot whose start time exceeds the division's
   curfew; the returned count reflects only curfew-compliant slots.
10. A division with no curfew set behaves identically to current behavior — no new constraint is
    applied.
11. Changing a curfew after a schedule is assigned does not alter any already-stored game or
    practice times.

### Playoff Field Priority

12. `planr field edit <name> --playoff-priority 1` exits 0 and persists the rank on the named
    field.
13. `planr field edit <name> --playoff-priority 0` exits 1 with stderr containing "must be a
    positive integer".
14. `planr field edit <name> --playoff-priority -1` exits 1 with stderr containing "must be a
    positive integer".
15. `planr field edit <name> --no-playoff-priority` exits 0 and removes the rank from the named
    field, leaving all other field fields unchanged.
16. `planr field list` displays each field's playoff priority rank when set, and shows nothing
    (or a dash) when unset.
17. When playoff games are assigned and ranked fields have sufficient capacity, every playoff
    game is assigned to a ranked field (none land on an unranked field).
18. When ranked fields have insufficient capacity, the solver assigns remaining playoff games to
    unranked fields rather than failing.
19. `planr playoff assign` output includes a per-field table showing each field used, its
    playoff priority rank (or "unranked"), and the number of games assigned to it.
20. When multiple fields share the same priority rank, the solver distributes playoff games
    across them without preference — they are treated as equivalent.
21. `planr schedule assign` (regular season) and `planr practice assign` produce schedules that
    are identical whether or not playoff priority ranks are set — the rank has zero effect on
    non-playoff scheduling.

---

## Out of Scope

- Per-game or per-date curfew overrides (e.g. "no curfew on weekends"). Curfew is division-wide
  and always active.
- League-wide curfew as a fallback default. Each division must be configured independently.
- Curfew enforcement on manual game overrides (`planr schedule game override`). The override
  subcommand explicitly bypasses solver constraints.
- Per-division field priority (all divisions in a playoff share the same field ranking).
- Priority weighting for regular-season or practice scheduling. Hard out of scope.
- Named tiers ("premium", "standard") — the rank is a plain integer only.

---

## Open Questions

None. All questions resolved.

---

## Dependencies

- `Division` record gains a nullable `LocalTime curfewTime` field. Absent in existing JSON
  reads as `null` (no constraint). No migration needed.
- `Field` record gains a nullable `Integer playoffPriority` field. Absent in existing JSON reads
  as `null` (unranked). No migration needed.
- `LeagueStore` schema version must be incremented to record both additions.
- `SchedulerService.assignPlayoffs` must accept and apply the field priority ranking as a solver
  objective or constraint tier, and return per-field assignment counts for output.
- `SchedulerService.assign` and `SchedulerService.assignPractices` must apply the per-division
  curfew as a hard start-time constraint in the CP-SAT model.
- `estimateAvailableSlots` must filter slots against the division's curfew start-time limit
  before returning the count.
- `DivisionCommand.Edit` must accept `--curfew-time` and `--no-curfew-time` options.
- `FieldCommand.Edit` must accept `--playoff-priority` and `--no-playoff-priority` options.
- `PlayoffCommand.Assign` output must render the per-field priority/game-count table.
