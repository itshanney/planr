# Changelog

All notable changes to `planr` are documented here. Each entry references the product requirements document (PRD) in `features/` and the technical design document in `specs/` that drove it.

---

## [0.6.0] — Phase 2 Partial Schedules, Solver Progress & Constraint Summary

**PRD:** `features/2026-05-17-league-planner-core-scheduling-v2.md`  
**Spec:** `specs/2026-05-23-phase2-field-assignment.md`

Upgrades Phase 2 field assignment in three areas: the solver now saves a partial draft instead of failing when slots are insufficient; live `[M:SS]` progress lines stream to stdout during the solve; and a per-division constraint summary is printed after every run. The solver time budget extends from 60 to 300 seconds.

### Added

- **Partial schedule support** — `planr schedule assign` no longer returns a hard failure when any division has fewer available field slots than games. The CP-SAT model is updated from a hard `addExactlyOne` per fixture to a soft `addAtMostOne` plus an `isAssigned` BoolVar. The objective is changed to a weighted maximize: `bigM × totalAssigned − maxWeekLoad`, where `bigM = totalFixtures + 1` guarantees lexicographic dominance (more assigned games always beats better week-load balance). The solver assigns as many games as possible and saves a Draft regardless. Exit code remains 0 for partial schedules.

- **Live solver progress output** — Four timestamped `[M:SS]` lines are streamed to stdout during Phase 2:
  - `[0:00] Phase 2 started. N games across D division(s).` — emitted by `AssignCmd` before calling the service.
  - `[0:XX] Feasibility check passed. Solver started.` or `[0:XX] Feasibility check: <division> deficit (N games, M slots). Solver started.` — emitted after slot enumeration.
  - `[M:SS] Solver progress: ~N% of time budget used.` — emitted by `ProgressCallback.onSolutionCallback()` at 25 %/50 %/75 % of the time budget using `wallTime()`.
  - `[M:SS] Solver complete. N of T games assigned (target-met|partial[, optimal]).` — emitted after `solver.solve()` returns.

- **Constraint summary** — Always printed after every Phase 2 run. Tabular per-division output showing games requested, slots available, used/available ratio, and status (`target-met` or `partial (N unassigned)`). Footer line reads `"All targets met."` or `"Warning: N game(s) could not be assigned."`.

- **Per-team shortfall** — Printed only when `targetMet == false`. For each division with unassigned games, lists every team that fell short with its `assigned/requested` game count (e.g., `Cardinals: 4/6 games assigned`). Computed in `AssignCmd` by diffing `league.teamSchedule()` against `result.games()`.

- **`DivisionSummary` record** — New transient record in the `scheduler` package: `divisionName`, `gamesRequested`, `gamesAssigned`, `slotsAvailable`. Derived methods: `targetMet()` (`gamesAssigned == gamesRequested`), `usedAvailRatio()`. Not persisted to `league.json`.

### Changed

- **`ScheduleResult.Success`** — Gains two new fields: `boolean targetMet` and `List<DivisionSummary> divisionSummaries`. The `ScheduleResult.success(...)` factory method updated to accept all four parameters.

- **Solver time limit** — `SOLVER_TIME_LIMIT_SECONDS` extended from 60 to 300. The solver terminates early when CP-SAT proves OPTIMAL; the extended budget provides headroom for larger leagues.

- **C2 (field non-overlap) constraint** — Rewritten from a pairwise O(N²) loop over conflicting `(field, date)` game pairs to a time-tick-bucket approach. Each `BoolVar` is registered under every 15-minute tick it occupies; one `addAtMostOne` is added per `(fieldId, date, tick)` bucket. This bounds C2 to at most `numFields × numDays × ticksPerDay` constraints regardless of fixture count, eliminating the OOM error that occurred at scale.

- **Final status line** — Updated to a three-case format:
  - Target met, optimal: `Draft schedule saved: N games assigned (target-met, optimal distribution).`
  - Target met, not optimal: `Draft schedule saved: N games assigned (target-met, good distribution — optimizer ran up to 300s).`
  - Partial: `Draft schedule saved: N of M games assigned (partial).`

- **Hard failure cases** — `UNKNOWN` solver status (timed out before finding any feasible solution) now returns `ScheduleResult.Failure` with a diagnostic message and does not save a draft. `INFEASIBLE` status (theoretically impossible with `addAtMostOne`) also returns `Failure` with an internal-error message.

### Tests

- **`SchedulerServiceTest`** — 8 new tests in the new `DivisionSummary accuracy` section: `targetMet` correctness, per-division summary presence, `gamesRequested` fixture count accuracy, `gamesAssigned` vs actual game count consistency, `slotsAvailable` vs `estimateAvailableSlots()` agreement, single-slot partial schedule (1 game assigned from 12 fixtures), zero-slot division (90-min game in 60-min window), and alphabetical summary ordering.

- **`ScheduleCommandTest`** — New `@Nested` class `ProgressAndConstraintSummary` with 11 tests covering: Phase 2 start and solver-complete progress lines, constraint summary presence, `target-met` and `All targets met` footer on success, `partial` status and `Teams that fell short` section on partial schedules, `N/M games assigned` fraction format, absence of shortfall output on full assignment, and `Draft schedule saved` / `(partial)` final status line variants.

---

## [0.5.1] — Phase 1 Algorithm Corrections

**Spec:** `specs/2026-05-18-phase1-team-schedule.md` (Errata E1 and E2)

Corrects two errors in the Phase 1 team schedule algorithm that shipped in 0.5.0.

### Fixed

- **Minimum target validation (E1):** `planr schedule generate` now correctly rejects a per-division target below `N−1` (the minimum for one full round-robin), not `2*(N−1)` as previously enforced. For a 4-team division the minimum is now 3, not 6.

- **Single round-robin, not double (E2):** Phase 1 now generates a *single* round-robin (Pass A only, `N*(N-1)/2` games per division) and relies on fill rounds to reach the target. The former implementation generated a double round-robin (Pass A + Pass B, `N*(N-1)` base games), silently over-generating games for any target below `2*(N−1)`.

- **Fixture identity in the Phase 2 solver (E2 follow-on):** `Fixture` gains a `gameId: UUID` field (carried from `TeamGame.id()`). The CP-SAT model now keys per-fixture constraints on `gameId` rather than structural equality. Previously, fill games with the same matchup direction as an earlier round-robin game collapsed into a single solver constraint, causing the solver to under-schedule fill-heavy leagues.

---

## [0.5.0] — Two-Phase Schedule Generation

**PRD:** `features/2026-05-17-league-planner-core-scheduling-v2.md`  
**Spec:** `specs/2026-05-18-phase1-team-schedule.md`

Splits schedule generation into two explicit user-controlled phases. Phase 1 produces a reviewable team schedule (matchups with home/away assignments) using a deterministic single round-robin plus fill rounds algorithm. Phase 2 consumes that schedule and runs the OR-Tools CP-SAT solver to assign dates, times, and fields. Organizers can inspect and edit home/away assignments between phases.

### Added

**`planr schedule generate`** (Phase 1 — replaces the former single-step `generate`)
- Generates a team schedule for every eligible division using the circle-method single round-robin algorithm (`N*(N-1)/2` games per division). Additional fill rounds bring each team's game count up to the division's `targetGamesPerTeam`, with home/away assignments balanced by tracking each team's running imbalance.
- Requires season start/end dates in `planr config` and at least one division with ≥ 2 teams. Per-division target must be ≥ N−1 (minimum for one full round-robin).
- Prints fill-round progress logs, then a tabular team schedule (`#`, `HOME`, `AWAY`, `DIVISION`), then guidance for the next steps.
- Re-running when a team schedule or draft already exists prompts for confirmation before discarding the existing work. Blocked if a finalized schedule exists.
- Status after Phase 1: `TEAM_SCHEDULE`.

**`planr schedule assign`** (Phase 2 — field/time assignment)
- Reads the confirmed team schedule and runs the CP-SAT solver to assign each fixture to a date, time slot, and field. Season dates and sunrise/sunset hours are read from `planr config`.
- Displays a condensed team schedule summary and per-division feasibility estimates before prompting for confirmation.
- On success, saves a **Draft** schedule. Status after Phase 2: `DRAFT`.
- Enforces the same three constraints as before (C1 exact assignment, C2 no field overlap with 15-minute buffer, C3 no team double-booked per day) and the same weekly-load-balancing objective.

**`planr schedule game edit <number> --home <team>`**
- Available in `TEAM_SCHEDULE` and `DRAFT` states.
- Swaps home and away for the specified game, designating the named team as home. No-op if the team is already home. Error if the team is not participating in that game.

**`planr schedule view --team-schedule`**
- Available in `TEAM_SCHEDULE` and `DRAFT` states. Shows the matchup-only table (no dates or fields) even after Phase 2 has run.

**`planr schedule export --team-schedule`**
- Available in `TEAM_SCHEDULE` and `DRAFT` states. Exports a JSON array with `game_number`, `home_team`, `away_team`, `division_name` fields.

### Changed

- **`planr schedule status`** — new `TEAM_SCHEDULE` state shows per-division game counts alongside the division's target and team count. The `DRAFT` / `FINALIZED` output is unchanged.
- **`planr schedule view`** — when state is `TEAM_SCHEDULE`, shows the matchup-only table automatically (no date/time/field columns).
- **`planr schedule export`** — when state is `TEAM_SCHEDULE`, automatically exports the team schedule JSON (no `--team-schedule` flag required).

### New model types
- `TeamGame` record — a single fixture in the team schedule: `id`, `gameNumber`, `homeTeamId`, `homeTeamName`, `awayTeamId`, `awayTeamName`, `divisionId`, `divisionName`, `gameDurationMinutes`. Includes `withSwappedHomeAway()`.
- `TeamSchedule` record — an ordered list of `TeamGame` records. Includes `withGameReplaced(gameNumber, replacement)` and `findGame(gameNumber)`.
- `ScheduleState` enum — `NONE`, `TEAM_SCHEDULE`, `DRAFT`, `FINALIZED`; derived from `(league.teamSchedule(), league.schedule())` nullability.
- `TeamScheduleResult` sealed interface — `Success(schedule, fillRoundLogs)` or `Failure(message)`.
- `TeamScheduleService` — encapsulates the Phase 1 algorithm (circle-method single round-robin + fill rounds).

### Changed (model / persistence)
- `League` record: gains `teamSchedule: TeamSchedule` (nullable, position 4, between `fields` and `schedule`). All `withX(...)` helpers forward it. New helpers: `withTeamSchedule(TeamSchedule)`, `withTeamScheduleCleared()` (nulls both `teamSchedule` and `schedule`).
- `SchedulerService`: `generate(League, LocalDate, LocalDate)` replaced by `assign(League)` (reads season dates from `league.config()`, reads fixtures from `league.teamSchedule()`). New public method `estimateAvailableSlots(League, UUID, int)` for pre-Phase-2 feasibility warnings.
- `LeagueStore` migration constructors updated to pass `null` for `teamSchedule`; no version bump required (existing v4 files load with `teamSchedule = null` via `FAIL_ON_UNKNOWN_PROPERTIES=false`).

---

## [0.4.0] — v2 Entity Management

**PRD:** `features/2026-05-17-league-planner-core-scheduling-v2.md`  
**Spec:** `specs/2026-05-17-v2-entity-management-cli.md`

This slice upgrades the entity management layer to satisfy the v2 product requirements. The availability model is redesigned from the ground up: fields are now open by default during configurable league-wide daylight hours, and organizers record only the exceptions. The schema advances from version 3 to version 4; prior availability windows are discarded on migration.

### Added

**League config (`planr config`)**
- `planr config set` — set league-level sunrise and sunset times (`HH:mm`) and optional season start/end dates (`YYYY-MM-DD`). Each option is independent; calling `set` a second time merges with the existing config rather than replacing it. Validates that sunset is after sunrise (when both are provided in the same call) and that season end is after season start.
- `planr config show` — display the current league configuration; shows `(not set)` for any field not yet configured.
- Sunrise and sunset times define the default open window applied to every field on every calendar day in the season. They are required prerequisites for schedule generation.

**Field blocks (`planr field block`)**
- `planr field block add <field> --date --start --end` — block a specific date/time range on a field. Multiple blocks per field are allowed. Each block is a date-specific exception that subtracts time from the field's effective open window.
- `planr field block edit <field> <number> [--date] [--start] [--end]` — edit a block by 1-based index; unspecified fields are preserved from the existing block.
- `planr field block delete <field> <number>` — delete a block by 1-based index.
- `planr field block list <field>` — list all blocks for a field in a tabular `#`, `DATE`, `START`, `END` format.

**Per-date open window overrides (`planr field override`)**
- `planr field override add <field> --date --start --end` — override the league-level sunrise/sunset for a specific field on a specific date. At most one override per field per date; adding a second override for the same date is rejected.
- `planr field override edit <field> <number> [--date] [--start] [--end]` — edit an override by 1-based index. If `--date` is changed, validates uniqueness against the remaining overrides.
- `planr field override delete <field> <number>` — delete an override by 1-based index.
- `planr field override list <field>` — list all per-date overrides for a field: `#`, `DATE`, `OPEN START`, `OPEN END`.

**New model records**
- `LeagueConfig` — holds `sunriseTime`, `sunsetTime`, `seasonStart`, `seasonEnd`; top-level on `League`.
- `FieldBlock` — date-specific blocked time range on a field.
- `FieldDateOverride` — per-date replacement for the league-level open window on a specific field.

### Changed

**Division management**
- `planr division add` now requires `--target <n>` (positive integer): the target number of games per team for the season. Exit code 1 if omitted or ≤ 0.
- `planr division edit` now accepts `--target <n>` to update the target; the error message for no options provided now lists `--target` alongside `--name` and `--duration`.
- `planr division list` now shows a `TARGET` column. Divisions migrated from earlier schema versions have `targetGamesPerTeam = 0`, displayed as `0*` with a trailing warning directing the organizer to configure a target.

**Field management**
- `planr field delete` — cascade message now reports blocks and overrides removed (e.g., `Field "X" deleted (2 block(s), 1 override(s) removed).`).
- `planr field list` — `WINDOWS` column replaced by `BLOCKS` and `OVERRIDES` columns.
- `planr field window` subcommand group retired entirely. All day-of-week recurring windows (`AvailabilityWindow`) are removed.

**Schedule generation**
- Precondition for `planr schedule generate` changed: a field with availability windows is no longer required. Instead, the league config must have `sunriseTime` and `sunsetTime` set. Specific error: `"Error: Schedule generation requires league config with sunrise and sunset times. Run 'planr config set --sunrise HH:mm --sunset HH:mm' first."`
- The scheduler's slot enumeration algorithm is rewritten: for each date in the season range, for each field, the effective open window is determined by (1) the field's `FieldDateOverride` for that date if present, otherwise (2) the league-level sunrise/sunset. `FieldBlock` entries for that date are subtracted from the open window to produce available sub-windows; slots are enumerated within each sub-window at 15-minute grid intervals.

**Data model**
- `Division` record gains `targetGamesPerTeam: int` (position 3, before `teams`).
- `Field` record: `List<AvailabilityWindow> windows` replaced by `List<FieldBlock> blocks` and `List<FieldDateOverride> dateOverrides`.
- `League` record gains `config: LeagueConfig` (position 1, after `version`). All `withX(...)` helpers forward the config field.
- `League.CURRENT_VERSION` advanced from `3` to `4`.
- `League.empty()` initializes `config` to `LeagueConfig.empty()` (all fields null).

**Persistence**
- `LeagueStore` migration chain extended: v3 → v4 strips all `AvailabilityWindow` data, initializes `LeagueConfig.empty()`, bumps the version field, writes the migrated file atomically, and prints a one-time warning to stderr: `Warning: Field availability windows from a previous version have been removed. Please configure field blocks for the new season.`
- v1 → v2 and v2 → v3 migration guards updated to pass `null` for the new `config` parameter; the subsequent v3 → v4 guard normalizes it.

### Removed
- `AvailabilityWindow` record — deleted.
- `FieldWindowCommand` — deleted. All `planr field window` subcommands no longer exist.

---

## [0.3.0] — Schedule Generation — 2026-05-16

**PRD:** `features/2026-05-15-league-planner-core-scheduling.md` (Schedule Generation, Lifecycle, Viewing, Export sections)  
**Spec:** `specs/2026-05-16-schedule-generation-cli.md`

Adds the full schedule workflow to the `planr` CLI, delegating all constraint satisfaction to the Google OR-Tools CP-SAT solver. The `League` record gains a nullable `Schedule` field and the JSON schema advances to version 3.

### Added

**`planr schedule generate --start <date> --end <date>`**
- Generates a double round-robin (every ordered team pair plays exactly once) for each eligible division.
- Assigns each fixture to a date, time slot, and field using the OR-Tools CP-SAT solver with a 60-second wall-clock budget. If OPTIMAL is proven within the budget, the summary notes it; otherwise it reports "good distribution."
- Enforces three hard constraints:
  - **C1** — each fixture assigned exactly once.
  - **C2** — no two games on the same field overlap (including the 15-minute buffer after each game).
  - **C3** — no team plays more than once on the same calendar day.
- Objective: minimise the maximum number of games any team plays in a single ISO calendar week (spreads the season evenly).
- Pre-solve feasibility check: counts available slots per division and reports infeasibility (with division-level detail) before invoking the solver if slots < fixtures.
- Requires: at least one division with ≥ 2 teams, at least one field with at least one availability window, and a valid season date range.
- Produces a **Draft** schedule; replaces any existing draft silently.
- Prints: `Draft schedule generated: N games across D division(s) (qualifier).`
- Exit codes: 0 (success), 1 (validation or infeasibility), 2 (I/O error).

**`planr schedule status`**
- Shows: schedule status (DRAFT / FINALIZED), season date range, total game count, and per-division game counts.

**`planr schedule finalize`**
- Promotes a Draft to FINALIZED after an interactive `yes` confirmation.
- Prints a warning that finalization is irreversible. A finalized schedule cannot be regenerated.

**`planr schedule view [--division <name>] [--team <name>] [--field <name>]`**
- Tabular view of the schedule: `#`, `DATE`, `START`, `FIELD`, `HOME`, `AWAY`, `DIVISION`.
- Supports optional filters by division, team, or field; validates that filter values refer to known entities.
- Games overridden after finalization are marked with `*` in the `#` column.

**`planr schedule export`**
- Writes a JSON array to stdout; each element contains `date`, `start_time`, `field_name`, `home_team`, `away_team`, `division_name`, and `status` (`"draft"` or `"finalized"`).
- Game count printed to stderr.

**`planr schedule game override <number> [--date] [--start] [--field] [--home] [--away]`**
- Overrides an individual game on a **finalized** schedule. Any combination of fields may be changed. Teams are resolved within the game's original division first, then league-wide.
- Non-blocking field-conflict warning printed to stderr if the overridden game now overlaps another game at the same field on the same date (including the 15-minute buffer).
- Marks the game as `overridden = true`, surfaced in `schedule view` as the `*` suffix.

**New model types**
- `Schedule` record — holds status, season dates, and the ordered list of `ScheduledGame` records.
- `ScheduleStatus` enum — `DRAFT`, `FINALIZED`.
- `ScheduledGame` record — a fully denormalised game: UUID, date, start time, field id/name, home team id/name, away team id/name, division id/name, game duration, and overridden flag. Includes `withOverride(...)` for partial mutation.
- `SchedulerService` — encapsulates fixture generation (circle-method round-robin), slot enumeration (per-field, per-day, respecting availability windows), and the CP-SAT model build/solve loop.
- `Fixture` record — a (gameId, homeTeamId, awayTeamId, divisionId, gameDurationMinutes) tuple used internally by the solver. `gameId` carries the source `TeamGame.id()` and is used as the per-fixture constraint key so repeated matchup directions (fill games) remain distinct.
- `Slot` record — a (date, fieldId, fieldName, startTime) tuple used internally by the solver.
- `ScheduleResult` sealed interface — `Success(games, optimal)` or `Failure(message)`.

**Build**
- `com.google.ortools:ortools-java:9.10.4067` added to `dependencies`.

### Changed
- `League` record: gains `schedule: Schedule` (nullable, position 4). All `withX(...)` helpers forward it.
- `League.CURRENT_VERSION` advanced from `2` to `3`.
- `LeagueStore`: adds `Schedule` / `ScheduledGame` round-trip support; v2 → v3 migration adds `null` schedule to existing files.

---

## [0.2.0] — Field Management — 2026-05-16

**PRD:** `features/2026-05-15-league-planner-core-scheduling.md` (Field Management section)  
**Spec:** `specs/2026-05-16-field-management-cli.md`

Adds field management (`planr field`) and recurring availability windows (`planr field window`) to the CLI. The `League` record gains a `fields` list and the JSON schema advances to version 2.

Also added in this commit: `CLAUDE.md` (project guidance for Claude Code), `README.md` (user-facing documentation), and a development `league.json` artifact (not committed going forward).

### Added

**`planr field add <name> [--address <address>]`**
- Creates a field with a unique name (case-insensitive) and optional address.

**`planr field edit <name> [--name <new-name>] [--address <address>]`**
- Renames a field or updates its address. Pass `""` (empty string) to clear the address.

**`planr field delete <name>`**
- Deletes a field and all associated availability windows.

**`planr field list`**
- Tabular list of all fields: `NAME`, `ADDRESS`, `WINDOWS`.

**`planr field window add <field> --day <day> --start <HH:mm> --end <HH:mm> [--division <name>]`**
- Adds a recurring day-of-week availability window to a field. Accepts full day names or 3-letter abbreviations, case-insensitively. An optional `--division` restriction limits the window to one division's games.

**`planr field window edit <field> <number> [--day] [--start] [--end] [--division] [--clear-division]`**
- Edits a window by 1-based index; unspecified fields are preserved. `--clear-division` and `--division` are mutually exclusive.

**`planr field window delete <field> <number>`**
- Deletes a window by 1-based index.

**`planr field window list <field>`**
- Tabular list of windows: `#`, `DAY`, `START`, `END`, `DIVISION` (shows "All divisions" or the division name; shows "[deleted]" with a trailing warning for orphaned division references).

**New model types**
- `Field` record — UUID, name, optional address, list of `AvailabilityWindow` records.
- `AvailabilityWindow` record — UUID, day-of-week, start time, end time, optional division UUID.

### Changed
- `League` record: gains `fields: List<Field>` (position 2). All `withX(...)` helpers forward it.
- `League.CURRENT_VERSION` advanced from `1` to `2`.
- `League.empty()` returns `new League(2, List.of(), List.of(), null)`.
- `LeagueStore`: adds `jackson-datatype-jsr310` (`JavaTimeModule`) for `LocalTime` / `DayOfWeek` support; registers a custom `HH:mm` serializer for `LocalTime` (overriding the jsr310 default of `HH:mm:ss`); v1 → v2 migration adds an empty `fields` list.
- `build.gradle`: adds `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2`.

---

## [0.1.1] — CI Pipeline — 2026-05-15

Adds a `Jenkinsfile` defining a basic declarative pipeline with Checkout, Build (`gradle assemble`), and Test (`gradle test`) stages, using an `openjdk:25` Docker agent.

---

## [0.1.0] — Division & Team Management — 2026-05-15

**PRD:** `features/2026-05-15-league-planner-core-scheduling.md` (Division & Team Management section)  
**Spec:** `specs/2026-05-15-division-team-management-cli.md`

Initial working release of `planr`. Establishes the core CLI skeleton, persistence layer, and immutable model pattern. All state lives in `~/.planr/league.json`; every mutating operation writes the file atomically (temp file + `Files.move(ATOMIC_MOVE)`).

### Added

**`planr division add <name> --duration <minutes>`**
- Creates a division with a unique name (case-insensitive) and a positive game duration.

**`planr division edit <name> [--name <new-name>] [--duration <minutes>]`**
- Renames a division or changes its game duration. At least one option required.

**`planr division delete <name>`**
- Deletes a division. Rejected if the division has any teams; the error message names the team count and instructs removing them first.

**`planr division list`**
- Tabular list of all divisions: `DIVISION`, `DURATION`, `TEAMS`.

**`planr team add <division> <name>`**
- Adds a team to a division. Name must be unique within the division (case-insensitive).

**`planr team edit <division> <name> --name <new-name>`**
- Renames a team within a division. Case-insensitive match for both division and existing team name.

**`planr team delete <division> <name>`**
- Removes a team from its division.

**`planr team list <division>`**
- Lists all teams in a division, sorted alphabetically (case-insensitive).

**Infrastructure**
- `PlanrApp` — root Picocli `@Command`; owns the `LeagueStore` singleton and wires subcommands via `@ParentCommand`.
- `LeagueStore` — reads and writes `~/.planr/league.json` atomically. Initialises the file with `League.empty()` on first run.
- `League` record — `version: int`, `divisions: List<Division>`, immutable; exposes `findDivision`, `hasDivision`, `withDivisionAdded`, `withDivisionReplaced`, `withDivisionRemoved`.
- `Division` record — `id: UUID`, `name: String`, `gameDurationMinutes: int`, `teams: List<Team>`; exposes team mutation helpers.
- `Team` record — `id: UUID`, `name: String`.
- Exit code conventions: `0` success, `1` validation error, `2` I/O error. All success output to stdout, all errors to stderr.
- Full JUnit 5 test suite: `DivisionCommandTest`, `TeamCommandTest`, `DivisionTest`, `LeagueTest`, `LeagueStoreTest`. Tests isolated via `user.home` redirect to `build/test-home`; serial execution to avoid file contention.
- `build.gradle`: Java 25 toolchain, Picocli 4.7.6, Jackson 2.17.2, JUnit Jupiter 5.10.2, `picocli-codegen` annotation processor (for future GraalVM native image), `-parameters` compiler flag (for Jackson record parameter name binding without `@JsonCreator`).

---

## [0.0.1] — Repository Initialisation — 2026-05-15

- Added `.gitignore` and MIT `LICENSE`.
