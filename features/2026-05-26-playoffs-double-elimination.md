# Playoff Scheduling — Double Elimination

**Date:** 2026-05-26  
**Status:** Finalized

---

## Problem Statement

planr currently generates only regular-season schedules. Once the regular season ends, league organizers have no tool support for playoff scheduling — they must arrange brackets and field times manually. 

Playoffs have structurally different matchup logic (double elimination rather than round-robin) and operate in a distinct date window that does not overlap with the regular season. Without first-class playoff support, planr cannot serve as a complete prototype for the league management workflow.

---

## Proposed Solution

Add a `planr playoff` top-level command group with two phases mirroring the existing `planr schedule` workflow:

1. **Phase 1 — Bracket generation** (`planr playoff generate`): For a specified division, build a double elimination bracket and persist it as a new `Playoff` entity in `league.json`. The bracket accepts any team count from 2 to 16. When the team count is not a power of 2, the bracket is padded with Bye slots to reach the next power of 2; the top-seeded teams receive the Byes and advance automatically. All bracket slots are pre-generated so that field time slots can be assigned upfront before any games are played.

2. **Phase 2 — Field assignment** (`planr playoff assign`): Assign field/time slots to bracket game slots using the existing CP-SAT solver across **all divisions that have a generated playoff bracket simultaneously**. Because all divisions share the same playoff date window and compete for the same fields, field assignment is a single cross-division solve — not one solve per division. The playoff date range is assumed to be consistent across all active `Playoff` entities.

Supporting commands: `planr playoff status` to view the bracket, and `planr playoff clear` to reset a division's playoff back to uninitialized.

---

## User Stories

1. **As a league organizer**, I want to generate a double elimination bracket for a division, so that all potential playoff matchups are structured before the first game is played.

2. **As a league organizer**, I want to set a playoff-specific start and end date for a division, so that field time slots are assigned within the playoff window rather than the regular season window.

3. **As a league organizer**, I want to assign field and time slots to all bracket games across all divisions in a single command, so that the field inventory is shared fairly and facilities are reserved before the playoffs begin.

4. **As a league organizer**, I want to view the full bracket with assigned field/time slots (or unassigned status) at any time, so that I can share the playoff schedule with coaches and parents.

5. **As a league organizer**, I want to reset a division's playoff and regenerate it, so that I can correct a seeding mistake or recover from a bracket error before games begin.

---

## Acceptance Criteria

### Playoff Configuration — `planr playoff generate`

- **AC-1.** `planr playoff generate --division <name> --start <YYYY-MM-DD> --end <YYYY-MM-DD> --seeds <team1,team2,...>` generates a double elimination bracket for the named division. All four options are required. The `--seeds` value is a comma-separated, ordered list of team names representing seed 1 through seed N.
- **AC-2.** Division name is matched case-insensitively. If the name does not match an existing division, the command exits with code `1` and an error message.
- **AC-3.** `--end` must not be before `--start`; if it is, the command exits with code `1` and an error message.
- **AC-4.** The number of teams provided in `--seeds` must equal the number of teams actually in the named division. N must be between 2 and 16 inclusive. Any integer value in that range is accepted — there is no power-of-2 restriction. The system automatically computes B = (next power of 2 ≥ N) − N and introduces B Bye slots; no organizer action is needed. When N is already a power of 2, B = 0 and no Byes are added. If the `--seeds` count does not match the actual team count in the division, the command exits with code `1` and an error message stating both counts. If N is outside the 2–16 range, the command exits with code `1` and an error message.
- **AC-5.** Each name in `--seeds` is matched case-insensitively against the teams in the named division. If any name does not match a team in that division, the command exits with code `1` listing the unrecognized names. Duplicate names within `--seeds` also exit with code `1`.
- **AC-6.** If a playoff for the named division already exists in state `GENERATED` or `ASSIGNED`, the command exits with code `1` and an error message instructing the user to run `planr playoff clear --division <name>` first.
- **AC-7.** On success, the command prints a bracket summary table showing all slots in round order: slot ID, round name (e.g., `Winners R1`, `Losers R1`, `Championship`), and the two position labels per slot (team name or `BYE` for first-round slots; positional reference such as `W of G3` for subsequent rounds). It also prints a final line: `Playoff generated for <division>: <N> teams, <G> game slots, <B> bye(s)` where G counts schedulable real game slots only and B is the number of Bye slots added.
- **AC-8.** Seeds are placed into a bracket of size P = next power of 2 ≥ N, in positions 1 through N. Positions N+1 through P are filled with Byes. First-round Winners bracket pairings follow standard bracket ordering: position 1 vs position P, position 2 vs position P−1, and so on. This means seeds 1 through B are each paired against a Bye and automatically advance, while seeds B+1 through N are paired against each other. All pairings are deterministic given a fixed seed list.
- **AC-8a.** Each Bye slot appears in the Winners R1 section of the bracket summary with the paired seed's team name in position A and `BYE` in position B. Bye slots are visually distinguished (e.g., flagged in a dedicated column) so they are not confused with real game slots. When B = 0, no Bye slots appear.

### Bracket Structure

- **AC-9.** The bracket contains `(2N − 1)` real game slots plus 1 conditional championship re-match slot, totaling `2N` schedulable slots. Additionally, B = (next power of 2 ≥ N) − N Bye slots are stored in the bracket (B = 0 when N is already a power of 2). Bye slots are persisted with unique IDs but are never submitted to the field-assignment solver and are not counted in the `2N` schedulable total.
- **AC-10.** The bracket includes a Winners bracket, a Losers bracket, and a Championship game (and conditional re-match). Round names and slot count per round follow standard double elimination structure for the effective participant count.
- **AC-11.** Each bracket slot carries: a unique slot ID, round name, bracket side (`WINNERS` / `LOSERS` / `CHAMPIONSHIP`), two position references (team name or `BYE` for first-round slots; positional reference such as `W of G3` or `L of G2` for subsequent rounds), `isConditional` flag (`true` only for the championship re-match slot), and `isBye` flag (`true` only for the seed-1 bye slot when N is odd).

### Field Assignment — `planr playoff assign`

- **AC-12.** `planr playoff assign` (no division flag) collects every division that has a `Playoff` entity in any state (`GENERATED` or `ASSIGNED`), clears all existing field assignments across those divisions, and runs a single CP-SAT field/time slot assignment across all their game slots simultaneously. This is a full re-solve: prior assignment results are never preserved.
- **AC-13.** If no divisions have a playoff entity (no `Playoff` records exist at all), the command exits with code `1` and an error message.
- **AC-14.** Before solving, the command validates that all playoff entities (regardless of prior state) share the same `startDate` and `endDate`. If any two differ, the command exits with code `1` and lists each division's date range so the organizer can correct them via `planr playoff clear` and `planr playoff generate`.
- **AC-15.** Field availability, field blocks, field overrides, and field division locks are all applied identically to the regular-season solver.
- **AC-16.** The solver treats the playoff date range as fully independent from the regular-season window. No cross-period conflict checking against regular-season assigned games is performed; playoff dates are assumed non-overlapping with the regular season across all divisions.
- **AC-17.** The existing league-level scheduling constraints (`maxGamesPerWeek`, `restDays`) are applied per team across all playoff game assignments within the playoff date range.
- **AC-18.** Partial assignment results are acceptable. If the solver cannot assign all game slots within the playoff date range, unassigned slots are reported and the command exits with code `0`. All divisions with a playoff entity transition to (or remain in) `ASSIGNED` state after the command completes, regardless of whether every slot was filled.
- **AC-19.** The command outputs live progress lines and a constraint summary in the same format as `planr schedule assign`. The final status line reads: `Playoff field assignment complete: <G_assigned>/<G_total> game slots assigned across <D> divisions.`
- **AC-20.** The conditional championship re-match slot is included in field assignment alongside all other real game slots for each division. The organizer is responsible for releasing any unused reservation if the re-match is not needed.
- **AC-20a.** Bye slots are never submitted to the field-assignment solver, regardless of division or how many byes exist. They do not consume a field or time slot. The `G_total` in the final status line reflects real schedulable game slots only (summed across all divisions, Bye slots excluded).

### Viewing — `planr playoff status`

- **AC-21.** `planr playoff status --division <name>` prints the full bracket: round, slot ID, position labels, assigned date/time/field (or `UNASSIGNED` for real unassigned games, `BYE` for the bye slot). If no playoff exists for the division, it exits with code `1` and an error message.
- **AC-22.** `planr playoff status` (no division flag) prints a one-line summary per division showing playoff state: `NOT_STARTED`, `GENERATED`, or `ASSIGNED`.

### Reset — `planr playoff clear`

- **AC-23.** `planr playoff clear --division <name>` removes the playoff entity for the named division entirely, returning the division to `NOT_STARTED` state. The command prompts for confirmation before clearing.
- **AC-24.** If no playoff exists for the division, the command exits with code `1` and an error message.

### Data Model and Persistence

- **AC-25.** A new `Playoff` record is added with fields: `divisionId` (UUID), `startDate`, `endDate` (LocalDate), `state` (`PlayoffState` enum: `GENERATED`, `ASSIGNED`), and `games` (`List<PlayoffGame>`).
- **AC-26.** A new `PlayoffGame` record is added with fields: `gameId` (UUID), `round` (String), `bracketSide` (`BracketSide` enum: `WINNERS`, `LOSERS`, `CHAMPIONSHIP`), `positionA` (String), `positionB` (String), `assignedDate` (LocalDate, nullable), `assignedStartTime` (LocalTime, nullable), `assignedFieldId` (UUID, nullable), `isConditional` (boolean), `isBye` (boolean). When `isBye` is `true`, `positionB` is the literal string `"BYE"` and all three `assigned*` fields are always `null`.
- **AC-27.** `League` gains a `List<Playoff>` field. The compact constructor normalizes null to `List.of()`. `League` schema version increments. The `LeagueStore` migration is a no-op marker for the new version; existing files without the `playoffs` key deserialize safely.

### Error Handling

- **AC-28.** All I/O failures in any of the above commands exit with code `2`.

---

## Out of Scope

- Recording game results and advancing the bracket based on outcomes (who won/lost each game). Bracket advancement is a separate feature.
- Cross-division playoffs (e.g., tournament across all divisions).
- Per-division `maxGamesPerWeek` or `restDays` overrides for the playoff period.
- Automatically releasing conditional championship re-match field reservations if the slot is not needed.
- Integration with regular-season win/loss records to auto-compute seeding.
- Playoff bracket display in bracket-diagram format (tree layout). Tabular output only.
- Cross-period conflict checking between playoff and regular-season assigned games. Playoff and regular-season date windows are assumed to be non-overlapping.

---

## Open Questions

None — all questions resolved prior to implementation.

---

## Dependencies

- New `Playoff` and `PlayoffGame` model records; `BracketSide` and `PlayoffState` enums.
- `League` record gains `List<Playoff> playoffs`; `LeagueStore` adds a schema version migration marker and increments `CURRENT_VERSION`.
- New `PlayoffCommand` top-level command class with `GenerateCmd`, `AssignCmd`, `StatusCmd`, and `ClearCmd` inner classes registered under it.
- `SchedulerService` (or a thin wrapper) must be reusable for playoff assignment: it accepts a `Playoff` entity and its date range in place of the league-level config dates, and treats `PlayoffGame` slots as the fixture list. The solver logic itself is not duplicated.
- `FieldBlock`, `FieldDateOverride`, and `FieldDivisionLock` filtering logic must be reusable from the playoff solver path without modification to field model records.
