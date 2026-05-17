# Planr — Core Scheduling Feature (v2)

* **Date:** 2026-05-17
* **Status:** Ready for Architecture
* **Supersedes:** `features/2026-05-15-league-planner-core-scheduling.md`

---

## Problem Statement

Little league baseball organizers currently have no dedicated tool for managing the complex scheduling constraints of a multi-division season. Manually balancing teams across shared fields with varying availability leads to conflicts, inequitable field time distribution, and significant administrative burden. Planr will eliminate this by providing a structured workflow to configure league entities and generate a conflict-free, balanced schedule.

The v1 scheduling model has three constraints that limit real-world usability:
1. A hard-coded double round-robin produces a fixed game count that organizers cannot control — leagues of different sizes end up with wildly different season lengths.
1. The availability-window model requires organizers to explicitly describe every time block a field is open, which is tedious for fields that are open most of the time; a single blocked date (e.g., a holiday tournament) forces editing every surrounding window.
1. The scheduler runs silently with no feedback, leaving the organizer with no way to understand why a schedule failed or which constraints are tightest.

---

## Proposed Solution

A command-line application, named **planr**, that allows a league organizer to define the core entities of their league:
* Divisions (with per-division game durations and target games per team)
* Teams within each Division
* Fields and Field Availability via **field blocks** (fields are open by default; organizers block only the exceptions)

Schedule generation is split into two explicit user-initiated phases with a review step between them:

* **Phase 1 — Team Schedule Generation:** Generate the complete list of matchups (who plays whom, home/away assignment) for each division based on a single round-robin plus fill games to reach the target. 
  * This is deterministic and completes near-instantly. 
  * The organizer-facing name for this output is the **team schedule**. 
  * The organizer reviews the team schedule, may edit home/away assignments, and explicitly confirms it before proceeding. 
  * The confirmed team schedule is persisted to `league.json`. 
  * Internally, the application translates the team schedule into a fixture representation for the constraint solver.

* **Phase 2 — Field Assignment:** Assign each game from the confirmed team schedule to a specific date, start time, and field using a constraint solver. 
  * This is the computationally hard step. 
  * The solver streams progress to stdout and produces a constraint summary on completion. 
  * The result is saved as a **Draft** schedule.

The schedule exists in one of three states — **Team Schedule** (Phase 1 complete, Phase 2 not yet run), **Draft** (Phase 2 complete), or **Finalized**. 
  * In Draft state the organizer may re-run Phase 2 as many times as needed, and may also edit home/away assignments on the team schedule in place before re-running. 
  * Once finalized, the schedule is locked and only individual game overrides are permitted. 
  * The organizer can view and export both the team schedule and the full draft/finalized schedule as JSON.

This spec covers an **MVP** scope: entity management, schedule generation, draft/finalize lifecycle, and JSON export. Multi-organizer support, team/parent-facing views, and post-finalization bulk edits are out of scope.

---

## User Stories

1. **As a league organizer**, I want to define Divisions with a game duration, a target games per team, and assign teams to them, so that the scheduler knows how long each game runs and how many games each team should play.

2. **As a league organizer**, I want to register baseball fields and have them be assumed available during all daylight hours by default, so that I only have to record the exceptions rather than building a full availability schedule from scratch.

3. **As a league organizer**, I want to generate a team schedule showing every matchup for the season — who plays whom and which team is home — so that I can review and adjust home/away assignments before any field assignment begins.

4. **As a league organizer**, I want to confirm the team schedule (or edit it) and then trigger field assignment, so that the computationally hard scheduling work only runs against a matchup list I have already approved.

5. **As a league organizer**, I want to see live progress while field assignment is running and receive a post-assignment constraint summary, so that I can make targeted adjustments if the resulting schedule is unsatisfactory.

6. **As a league organizer**, I want to finalize a draft schedule and then override individual games one at a time, so that the schedule is stable while still allowing exception handling.

7. **As a league organizer**, I want to export the team schedule or the full schedule as a JSON file, so that I can sanity-check matchups before field assignment and share the final schedule with others.

---

## Acceptance Criteria

### Division & Team Management

- [ ] An organizer can create a division with a unique name (e.g., "T-Ball", "Coast", "AAA", "Majors") and a game duration in minutes.
- [ ] An organizer can edit a division's name or game duration.
- [ ] An organizer can delete a division only if it has no teams assigned to it.
- [ ] An organizer can add one or more teams to a division; each team name must be unique within its division.
- [ ] An organizer can edit or delete a team without affecting other teams in the division.
- [ ] The system rejects creation of a division or team with an empty name.
- [ ] The system rejects a game duration that is not a positive integer number of minutes.

### Field Management

- [ ] An organizer can create a field with a name and an optional address/location description.
- [ ] An organizer can delete a field; deleting a field removes all of its associated field blocks and per-date open window overrides.

### Field Availability Configuration

- [ ] Sunrise and sunset times are set at the **league level** (`HH:mm`) and define the default open window applied to every field on every calendar day in the season.
- [ ] A field can override the league-level defaults with its own **per-date open window**: for a specific calendar date, a field-level start time and end time replace the league defaults for that field on that date only.
- [ ] An organizer can add one or more **field blocks** to a field; each window specifies: a specific calendar date, a start time, and an end time.
- [ ] An field block blocks the field for the specified date and time range only — no other dates are affected.
- [ ] The effective open window for a field on a given date is determined in priority order: (1) per-date field override if defined, (2) league-level defaults otherwise. Unavailability windows are then subtracted from whichever open window applies.
- [ ] The scheduler treats any game slot that overlaps an field block as unavailable (partial overlap is sufficient: a slot that starts within or ends within an field block is excluded).
- [ ] If no field blocks are defined for a date, the field's full effective open window is available for scheduling.
- [ ] An organizer can edit or delete an field block or per-date open window override without affecting any other window or the field's defaults.
- [ ] The system rejects an field block where end time ≤ start time on the same date.
- [ ] The system rejects a per-date open window override where end time ≤ start time.
- [ ] The system warns the organizer at input time if an field block's date falls outside the configured season date range, but accepts and saves the window. Generation is not blocked.

### Phase 1: Team Schedule Generation

- [ ] An organizer can initiate Phase 1 only after at least one division with two or more teams has been configured and a season start and end date have been specified.
- [ ] Each division has a configurable **target games per team** (positive integer, minimum 1).
- [ ] Phase 1 first generates a complete **single round-robin** for each division: every team plays every other team exactly once in each direction (Team A hosts Team B, Team B hosts Team A).
- [ ] If the target is less than the number of games produced by a single round-robin, Phase 1 rejects the configuration at input time with the message: `Error: Target of N games is less than the N*(N-1) games required for a single round-robin with M teams. Minimum target is N*(N-1).`
- [ ] After the single round-robin is complete, Phase 1 adds **fill games** in rounds: in each round, every team that has not yet reached the target receives at most one additional fill game before any team receives a second fill game in the same round.
- [ ] Phase 1 logs status to stdout after completing each fill round, showing the current game count per team (e.g., `Fill round 2 complete: Blue Jays 8, Red Sox 8, Yankees 7`).
- [ ] Home/away assignments across all games (round-robin and fill) are distributed to minimize imbalance: Phase 1 attempts to ensure each team has as close to an equal number of home and away games as possible. Fill game home/away assignments alternate to bring each team's home-to-away ratio closer to 1:1.
- [ ] Phase 1 completes and displays the full team schedule before any field assignment begins.
- [ ] The team schedule output shows, for each game: a game number, home team, away team, and division. No dates, times, or fields are assigned at this stage.
- [ ] The confirmed team schedule is saved to `league.json` so the organizer can exit and return without losing their work.
- [ ] Phase 1 can be re-run at any time, including while in Draft state; re-running Phase 1 discards the current team schedule and any existing Draft and replaces both with a freshly generated team schedule.

### Team Schedule Review

- [ ] After Phase 1 completes, the organizer is presented with the full team schedule and must explicitly confirm it before Phase 2 begins.
- [ ] Before the organizer confirms, the system checks whether the total number of games in the team schedule is likely achievable given the available field slots over the season. If the estimated available slots for any division are fewer than the games generated for that division, the system displays a pre-Phase-2 warning (e.g., `Warning: AAA division has 24 games but only ~18 slots estimated in the season window. Field assignment may produce a partial schedule.`). Phase 2 is not blocked.
- [ ] The organizer can edit the home/away assignment of any individual game in the team schedule before confirming. Edited team schedules are saved to `league.json`.
- [ ] The organizer can edit the home/away assignment of any game in the team schedule while in **Draft** state (after Phase 2 has already run), then re-run Phase 2 without re-running Phase 1. The updated team schedule is saved to `league.json` before Phase 2 begins.

### Phase 2: Field Assignment

- [ ] Phase 2 can only be initiated after the organizer has confirmed a team schedule.
- [ ] The system requires at least one field to be configured before Phase 2 can begin.
- [ ] Phase 2 assigns each game from the confirmed team schedule to a specific date, start time, and field.
- [ ] The assigned schedule contains no two games at the same field with overlapping times, where overlap includes the 15-minute buffer following each game.
- [ ] Every assigned game falls within the effective open window for its field on its assigned date and does not overlap any field block for that field on that date.
- [ ] A game's scheduled time slot is exactly equal to the game duration of its division; the game slot does not consume the subsequent 15-minute buffer.
- [ ] No team plays more than one game on the same calendar day.
- [ ] All assigned games fall within the configured season date range.
- [ ] If Phase 2 cannot assign all games due to insufficient field slots, it assigns as many as possible, saves the result as a **Draft**, and reports which teams fell short of the target and by how many games.
- [ ] The schedule summary output includes actual games assigned per team alongside the target, e.g. `Blue Jays: 8/10 games assigned`.
- [ ] A newly completed Phase 2 run is saved as a **Draft** schedule and reports whether it is `target-met` or `partial`.
- [ ] Phase 2 attempts to complete within 60 seconds for a league with up to 10 divisions, 100 teams total, and 10 fields, but may continue up to 300 seconds (5 minutes) in an attempt to produce an ideal assignment.
- [ ] Phase 2 can be re-run against the current team schedule (with or without home/away edits) without re-running Phase 1; each re-run replaces the previous Draft.

### Scheduler Progress and Logging

- [ ] During Phase 2, the scheduler emits progress lines to stdout at regular intervals (at minimum: start, feasibility check complete, solver progress at 25%/50%/75% of elapsed time budget, and completion).
- [ ] Each progress line is timestamped and human-readable, e.g.: `[0:05] Feasibility check passed. Solver started. 48 games across 3 divisions.`
- [ ] After Phase 2 completes (success, partial, or failure), the system prints a **constraint summary** that includes, for each division:
  - Total games to assign (from confirmed team schedule)
  - Total available field slots in the season
  - Slots used / slots available ratio
  - Whether the division's target was met
- [ ] If Phase 2 fails to assign all round-robin games, the constraint summary identifies which division(s) caused the failure and specifically names the binding constraint (e.g., `Insufficient field availability for AAA division — 12 games cannot be assigned within the season window`).
- [ ] The progress log and constraint summary are always printed to stdout and are not suppressible in v2.
- [ ] The constraint summary is printed even when all games are successfully assigned, so the organizer can see how much scheduling headroom remains.

### Schedule Lifecycle

- [ ] The league schedule exists in one of three states: **Team Schedule** (Phase 1 complete, Phase 2 not yet run), **Draft** (Phase 2 complete), or **Finalized**.
- [ ] In **Team Schedule** state, the organizer can: edit home/away assignments on any game, re-run Phase 1 (which replaces the team schedule), or confirm and run Phase 2 (which produces a Draft).
- [ ] In **Draft** state, the organizer can: edit home/away assignments on any game in the team schedule and re-run Phase 2 (replacing the previous Draft), or re-run Phase 1 from scratch (which discards both the Draft and the current team schedule and produces a new team schedule).
- [ ] The organizer can promote a Draft to **Finalized** state via an explicit finalize action.
- [ ] Before finalizing, the system presents a confirmation warning that finalization is irreversible and the schedule will be locked.
- [ ] Finalization is one-way; a Finalized schedule cannot be reverted to Draft, Team Schedule, or regenerated.
- [ ] Once Finalized, the organizer can override an individual game by editing any combination of: date, start time, field assignment, home team, and away team.
- [ ] Individual game overrides on a Finalized schedule are saved without re-validating the full schedule against constraints.
- [ ] The system displays a non-blocking warning if an individual game override creates a field conflict (same field, overlapping time including buffer) with another game on the same date.

### Schedule Viewing

- [ ] The organizer can view the team schedule as a list showing game number, home team, away team, and division.
- [ ] The organizer can view the draft or finalized schedule in a list view, filterable by division, team, and field.
- [ ] Each draft/finalized schedule entry displays: date, start time, field name, home team, away team, and division name.
- [ ] The schedule view indicates the current state: Team Schedule, Draft, or Finalized.

### Schedule Export

- [ ] The organizer can export the team schedule as a JSON file while in **Team Schedule** or **Draft** state.
- [ ] The exported team schedule JSON is an array of game objects; each object contains: `game_number` (integer), `home_team`, `away_team`, `division_name`. No date, time, or field is included.
- [ ] The organizer can export the full schedule as a JSON file while in **Draft** or **Finalized** state.
- [ ] The exported full schedule JSON is an array of game objects; each object contains: `date` (ISO 8601 date), `start_time` (ISO 8601 time), `field_name`, `home_team`, `away_team`, `division_name`, `status` ("draft" or "finalized").

### Backward Compatibility

- [ ] Existing `league.json` files from v1, v2, and v3 are migrated silently on first load: all prior availability windows are deleted and a one-time warning is printed: `Warning: Field availability windows from a previous version have been removed. Please configure field field blocks for the new season.`
- [ ] The `version` field in `league.json` advances to `4`.

---

## Out of Scope

- Multi-organizer accounts and access control (single organizer for MVP).
- Team manager or parent-facing views.
- Push notifications or email distribution of schedules.
- Playoff or tournament bracket scheduling.
- Rainout/reschedule workflows.
- Bulk editing or reverting a finalized schedule.
- Configurable buffer time (fixed at 15 minutes league-wide).
- Team home-field preferences or constraints.
- Travel time or geographic constraints between fields.
- Player-level roster management.
- Mobile native applications.
- CSV, PDF, or iCal export formats.
- **Astronomically calculated sunrise/sunset** — times are fixed configuration values, not automatically derived from GPS coordinates or date.
- **Recurring field blocks** (e.g., "every Tuesday afternoon") — field blocks are date-specific only. Recurring blocks require adding individual entries.
- **Per-game target** (e.g., team A plays team B exactly 3 times) — target is per-team per-season, not per-matchup.
- **Weighted fill game distribution** (e.g., prefer rivals) — fill games are distributed for balance only.
- **Exporting the constraint log** to a file — log goes to stdout only.
- **Interruptible generation** — the organizer cannot pause or cancel a running Phase 2 in v2.
- **Adding or removing games from the team schedule** — the organizer may edit home/away assignments but may not change the number of games. Game count is determined by Phase 1 configuration only.

---

## Open Questions

None — all questions resolved.

---

## Dependencies

- **Scheduling algorithm redesign** — the OR-Tools CP-SAT model operates only on Phase 2 (field assignment) and receives the persisted team schedule as input. The model must: (a) assign date/time/field to each game, (b) enforce field-conflict and 15-minute buffer constraints, (c) enforce the one-game-per-team-per-day constraint, (d) respect effective per-date field open windows and field blocks, and (e) maximize total games assigned when full assignment is not feasible.
- **Phase 1 team schedule generation** — the round-robin and fill-game logic is a standalone deterministic computation, separate from the constraint solver. It must be implemented and tested independently of the OR-Tools model.
- **Solver progress API** — OR-Tools CP-SAT supports a `SolutionCallback` interface. Time-based progress milestones (25%/50%/75% of elapsed wall-clock budget) are sufficient. Confirm that wall-clock elapsed time is accessible from the callback context in the `ortools-java` 9.10 artifact.
- **League-level configuration storage** — sunrise/sunset defaults require a new top-level config object in `league.json`. The `League` record must be extended.
- **`league.json` schema for Team Schedule state** — the schema must support a `teamSchedule` field (persisted after Phase 1) distinct from the `schedule` field (persisted after Phase 2). Both fields must be defined in the version `4` schema.
- **v1/v2/v3 migration chain** — the existing three-step migration chain must be extended to v4. All prior availability windows are discarded on migration; no conversion is required.
- **No external API dependencies identified for Phase 1.**
