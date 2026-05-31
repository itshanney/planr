# `planr schedule view` Filter Parity with `planr calendar` and `--team-schedule` Removal

**Date:** 2026-05-31
**Status:** Final

---

## Problem Statement

`planr schedule view` has two behavioral problems relative to `planr calendar`. First, it
accepts `--division`, `--team`, and `--field` simultaneously and silently applies them as an AND
condition; `planr calendar` rejects any combination of more than one filter. Second, it exits 0
when a valid filter produces no results; `planr calendar` exits 1. Additionally, `planr schedule
view` carries a `--team-schedule` flag that has no distinct purpose: in `TEAM_SCHEDULE` state,
`view` already auto-routes to the matchup table without any flag, and in `DRAFT`/`FINALIZED`
state, regressing to a matchup-only display serves no identified use case. The flag adds surface
area that has to be kept consistent with filter behavior rules, creating extra complexity for no
benefit.

---

## Proposed Solution

Three changes to `planr schedule view` only:

1. Add a mutual-exclusion guard that rejects more than one of `--division`, `--team`, `--field`.
2. Exit 1 (not 0) when a valid filter produces an empty result set, moving the message to stderr.
3. Remove the `--team-schedule` flag entirely. State-based auto-routing (`TEAM_SCHEDULE` →
   matchup table, `DRAFT`/`FINALIZED` → full schedule) is unchanged and requires no flag.

`planr schedule export --team-schedule` is a separate flag on a separate command and is not
changed.

---

## User Stories

1. **As a league scheduler**, I want `planr schedule view --division Majors --team Yankees`
   to exit with an error, so that I get immediate feedback instead of silently receiving a
   confusing AND-filtered result.

2. **As a league scheduler**, I want `planr schedule view` to use the same one-filter-at-a-time
   rule as `planr calendar`, so that I don't need to remember different rules for two commands
   that do similar things.

3. **As a league scheduler**, I want a filter that matches no games to exit non-zero, so that
   scripts and interactive use both get a clear signal that nothing was found.

4. **As a league scheduler**, I want `planr schedule view` to have a simple, predictable
   interface — the right table for the current state, optionally narrowed by one filter — with
   no confusing override flags.

---

## Acceptance Criteria

### Mutual exclusion guard

1. `planr schedule view --division Majors --team Yankees` exits 1 with stderr containing
   `"At most one of --division, --team, --field may be specified."` (the same message used by
   `planr calendar`).

2. `planr schedule view --division Majors --field "Riverside Park"` exits 1 with stderr
   containing `"At most one of --division, --team, --field may be specified."`

3. `planr schedule view --team Yankees --field "Riverside Park"` exits 1 with stderr
   containing `"At most one of --division, --team, --field may be specified."`

4. `planr schedule view --division Majors --team Yankees --field "Riverside Park"` exits 1
   with the same error.

5. The mutual-exclusion check fires **before** entity validation — supplying two filters where
   one names a non-existent entity still exits 1 with the mutual-exclusion message, not an
   entity-not-found message.

6. Each filter continues to work individually: `--division Majors`, `--team Yankees`, and
   `--field "Riverside Park"` each accepted without error when supplied alone.

### Empty filtered result (exit 1)

7. `planr schedule view --division Majors` when a DRAFT or FINALIZED schedule exists but
   contains no games for division `Majors` exits 1 with stderr containing
   `"No games match the specified filter."` (exit code changes from 0 to 1; message moves from
   stdout to stderr).

8. `planr schedule view --team Yankees` when no assigned games involve team `Yankees` exits 1
   with the same message.

9. `planr schedule view --field "Riverside Park"` when no assigned games are at that field
   exits 1 with the same message.

10. `planr schedule view` with no filter and a non-empty DRAFT/FINALIZED schedule continues
    to exit 0 and display all games, unchanged.

### `--team-schedule` flag removed from `planr schedule view`

11. `planr schedule view --team-schedule` exits non-zero with an error from picocli indicating
    `--team-schedule` is not a recognized option. No schedule table is displayed.

12. `planr schedule view --team-schedule --division Majors` exits non-zero with the same
    unrecognized-option error (the flag is gone; no special handling needed).

13. `planr schedule view` in `TEAM_SCHEDULE` state continues to auto-display the matchup table
    (no flag required). Output is unchanged from the pre-removal behavior when no flag was
    supplied.

14. `planr schedule view` in `DRAFT` or `FINALIZED` state always displays the full schedule
    table with dates, times, and fields. There is no way to request the matchup-only view from
    `planr schedule view` in these states.

### Existing single-filter and state-routing behavior preserved

15. `planr schedule view --division Majors` in DRAFT/FINALIZED state returns only games where
    `divisionName` matches `Majors` (case-insensitive) and exits 0 when games are found.

16. `planr schedule view --team Yankees` returns games where `homeTeamName` or `awayTeamName`
    matches `Yankees` (case-insensitive) and exits 0 when games are found.

17. `planr schedule view --field "Riverside Park"` returns games where `fieldName` matches
    case-insensitively and exits 0 when games are found.

18. Entity-not-found validation (exits 1 with "not found" message for unknown division, team,
    or field) is preserved unchanged.

19. `planr schedule view` with no flags and no schedule at all continues to exit 1 with
    `"Error: No draft or finalized schedule exists."`, unchanged.

### `planr schedule export --team-schedule` is unaffected

20. `planr schedule export --team-schedule` continues to work exactly as before. This flag is
    on the export subcommand, not view, and is not part of this change.

---

## Out of Scope

- `planr schedule export --team-schedule` — distinct flag on a separate subcommand, not touched.
- Adding filters to `planr schedule export`.
- Changing the behavior of `planr schedule stats` or any other subcommand.
- Removing `--team-schedule` from `planr schedule export` (separate decision).

---

## Open Questions

None. All questions resolved.

---

## Dependencies

- `ScheduleCommand.ViewCmd` is the only type that changes:
  - Add filter-count guard at the top of `call()` (mirrors `CalendarCommand.call()`).
  - Delete the `teamScheduleView` `@Option` field and all code paths that reference it
    (the `if (teamScheduleView || state == ScheduleState.TEAM_SCHEDULE)` block simplifies to
    `if (state == ScheduleState.TEAM_SCHEDULE)`).
  - Change the empty-filtered-result branch: `return 0` → `return 1`; move message from
    `System.out` to `System.err`.
- No model or persistence changes required.
- No schema version bump required.
