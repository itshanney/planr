# Tech Spec: `planr practice view` — Rename and Sort Fix

**Date:** 2026-05-29

---

## Overview

Two targeted changes to the practice detail display path: 

* `planr practice status` is renamed to `planr practice view`, aligning it with `planr schedule view` so the CLI surface is consistent across both schedule types. The summary form (`planr practice view` with no flags) and the detail form (`planr practice view --division <name>`) are preserved as-is; only the command name changes. 
* The detail table in `planr practice view --division <name>` is re-sorted: currently it sorts only by team name, which produces an unpredictable mix of assigned and unassigned slots; the fix sorts by assigned date ascending (nulls/unassigned last), then by start time, then by team name as a stable tiebreaker.

No data model changes. No schema version bump. No new subcommands.

---

## Component Diagram

```
PracticeCommand (picocli @Command, name="practice")
  ├── GenerateCmd        — unchanged
  ├── AssignCmd          — unchanged
  ├── ViewCmd            — RENAMED from StatusCmd; name="view" (was "status")
  │     printSummary()   — unchanged output, unchanged logic
  │     printDetail()    — sort fix: date ASC (nulls last), startTime ASC, teamName ASC
  └── ClearCmd           — unchanged
```

---

## Data Model

No changes to any model record. `PracticeSlot` already carries `assignedDate: LocalDate` and `assignedStartTime: LocalTime` (both nullable until Phase 2 runs). The sort uses these fields directly.

---

## API Contracts

### Renamed command

| Before | After |
|---|---|
| `planr practice status` | `planr practice view` |
| `planr practice status --division <name>` | `planr practice view --division <name>` |

All flags, exit codes, stdout/stderr conventions, and output format are unchanged.

### Sort order for `planr practice view --division <name>`

Slots in the detail table are now ordered by:

1. **Assigned date, ascending** — slots with a `null` assignedDate (unassigned) sort last.
2. **Assigned start time, ascending** — within the same date; unassigned slots remain at the end.
3. **Team name, ascending (case-insensitive)** — stable tiebreaker.

The old sort (team name only) is removed.

**Comparator (pseudocode):**

```
Comparator.comparing(slot -> slot.assignedDate(),
    Comparator.nullsLast(Comparator.naturalOrder()))
  .thenComparing(slot -> slot.assignedStartTime(),
    Comparator.nullsLast(Comparator.naturalOrder()))
  .thenComparing(slot -> resolveTeamName(division, slot.teamId()),
    String.CASE_INSENSITIVE_ORDER)
```

---

## Critical Path Walkthrough

### `planr practice view` (summary)

1. Picocli routes `practice view` to `ViewCmd.call()`.
2. `divisionName == null` → `printSummary(league)`. Unchanged.
3. Output: same `DIVISION / STATE / ASSIGNED / TOTAL` table as before.

### `planr practice view --division Majors` (detail — after assign)

1. Picocli routes to `ViewCmd.call()` with `divisionName = "Majors"`.
2. Division and `PracticeSchedule` resolved as before.
3. `printDetail()` collects all slots and applies the new three-key comparator.
4. Assigned slots appear first, ordered by date then time. Multiple games on the same date appear time-ordered. Unassigned slots trail at the bottom. Within the unassigned group, team name is the tiebreaker.
5. Table printed. Exit 0.

### `planr practice view --division Majors` (detail — before assign)

All slots have `assignedDate = null`. The `nullsLast` comparator pushes all of them to the end of the sort; within the group they fall by team name (same as current behavior). Output is identical to today for the unassigned case.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risks Accepted |
|---|---|---|---|---|
| Sort key for unassigned slots | (a) team name, (b) slot number, (c) team name then slot number | Team name, then slot number (via existing `pracLabel`) | Consistent with the unassigned pre-assign view the user already sees; adding slot number as a secondary key avoids arbitrary ordering among a team's multiple practices | None — slot number is a stable integer |
| Rename scope | (a) rename command only, (b) rename + add a thin `planr practice status` alias, (c) rename + keep `status` as deprecated alias | Rename only — `status` is removed entirely | Keeps the surface clean; no alias pollution; all tests are updated in the same change | Any shell scripts using `practice status` break silently — acceptable for a CLI prototype |
| Summary form after rename | (a) keep `planr practice view` (no args) as summary, (b) split into separate `status` + `view` | Keep `view` no-arg as summary | `planr schedule view` (no-arg) also behaves differently based on state — same pattern | Slightly non-obvious that `view` without `--division` gives a summary rather than all detail |

---

## Operational Concerns

- No schema migration required.
- No state on disk is affected.
- `TESTPLAN.md` must be updated: rename `PracticeCommandTest.StatusSummary` → `ViewSummary` and `StatusDetail` → `ViewDetail`; update all command invocations from `practice status` to `practice view`; update test counts and descriptions.
- `README.md` must be updated: all `planr practice status` references in the Practices section.
- `CHANGELOG.md` should record the rename under a `[0.10.2]` patch entry.

---

## Implementation Checklist

### `PracticeCommand.java`

- [ ] Rename inner class `StatusCmd` → `ViewCmd`.
- [ ] Change `@Command(name = "status", ...)` → `@Command(name = "view", ...)`.
- [ ] Update `subcommands = { ..., PracticeCommand.StatusCmd.class, ... }` on the parent `@Command` → `PracticeCommand.ViewCmd.class`.
- [ ] In `printDetail()`, replace the single-key `Comparator.comparing(s -> resolveTeamName(...))` with the three-key comparator described above.
- [ ] Update the `description` string on `ViewCmd` to reference `planr practice view`.

### `PracticeCommandTest.java`

- [ ] Rename nested class `StatusSummary` → `ViewSummary`; update `@DisplayName`.
- [ ] Rename nested class `StatusDetail` → `ViewDetail`; update `@DisplayName`.
- [ ] Replace every `execute("practice", "status", ...)` call with `execute("practice", "view", ...)` throughout the file.
- [ ] Add sort-order tests to `ViewDetail`:
  - After assign, slots appear date-ordered, not team-name-ordered.
  - Two slots on the same date appear time-ordered.
  - Unassigned slots appear after all assigned slots.

### `README.md`

- [ ] In the Practices section, rename all `planr practice status` command references to `planr practice view`.
- [ ] Update the example output block heading and command line.
- [ ] Update the bullet description under `status` → `view`.

### `CHANGELOG.md`

- [ ] Add `[0.10.2]` entry documenting the rename and sort fix.

---

## Out of Scope / Future Work

- Adding `--team` or `--field` filter flags (matching `planr schedule view`): deferred; out of scope for this change.
- Paging or `--limit` for large practice lists: deferred; irrelevant at current scale.
- A standalone `planr practice status` that only prints state (no detail): not requested; the summary form of `view` fills this role.
