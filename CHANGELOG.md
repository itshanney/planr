# Changelog

All notable changes to `planr` are documented here. Each entry references the product requirements document (PRD) in `features/` and the technical design document in `specs/` that drove it.

---

## [0.13.0] — `planr schedule view` Filter and Stats Parity

**PRD:** `features/2026-05-31-schedule-view-filter-parity.md`  
**Spec:** `specs/2026-05-31-schedule-view-filter-parity.md`

Closes two gaps in `planr schedule view`. First, the TEAM_SCHEDULE state path was ignoring all filter flags entirely — `--division` and `--team` now scope both the matchup table and the stat blocks, and `--field` is hard-rejected with a clear error (no field assignments exist in this state). Second, the DRAFT/FINALIZED path was rendering only the game table; it now appends per-division HOME/AWAY BALANCE and HEAD-TO-HEAD stat blocks after the table whenever the filter is unset or `--division`-only. The `--team` and `--field` filters suppress the stat blocks (a per-team or per-field stat summary carries no useful signal). No data model, persistence, or scheduler changes.

### Changed

- **`planr schedule view` — TEAM_SCHEDULE filter parity** — `--division` and `--team` now filter the matchup table and both stat blocks (HOME/AWAY BALANCE, HEAD-TO-HEAD). Previously all three filter flags were silently ignored in TEAM_SCHEDULE state; the full unfiltered game list was always passed to both rendering methods.

- **`planr schedule view --field` in TEAM_SCHEDULE state** — `--field` is now rejected with exit 1 before any entity lookup: `"Error: --field cannot be used when no field assignment exists."` The multi-filter-count guard still fires first, so passing two flags together still produces the usual "At most one of" error.

- **`planr schedule view` — stats blocks in DRAFT/FINALIZED** — The view now renders per-division HOME/AWAY BALANCE and HEAD-TO-HEAD blocks immediately after the game table when no filter or a `--division` filter is active. Previously these blocks appeared only in TEAM_SCHEDULE state. With `--team` or `--field`, only the (already-filtered) game table is shown. The analogy is the existing TEAM_SCHEDULE rendering: matchup table first, stats second.

- **`ScheduleCommand`** — Two new `ScheduledGame` overloads: `printScheduledBalanceBlock(List<ScheduledGame>, String)` and `printScheduledHeadToHeadBlock(List<ScheduledGame>, String)`. These mirror the existing `TeamGame` overloads (identical rendering logic, different source type). Java type erasure prevents true method overloading on `List<T>`, so the `ScheduledGame` variants carry distinct names.

### Tests

- **`ScheduleCommandTest`** — 1 updated test, 9 new tests across the existing `View` and `ViewStats` nested classes:
  - *Updated (`ViewStats`):* `fullViewInDraftStateDoesNotShowStats` → `fullViewInDraftStateShowsStats`; both `assertFalse` assertions inverted to `assertTrue`.
  - *New (`View`, 5):* `--division` filter scopes TEAM_SCHEDULE matchup table (Majors shown, AAA excluded); `--team` filter passes through in TEAM_SCHEDULE (exit 0, team name present); `--field` rejected in TEAM_SCHEDULE state (exit 1, error message); multi-filter guard fires before `--field` guard in TEAM_SCHEDULE; unknown `--division` exits 1 with entity-validation error (not empty-result error) in TEAM_SCHEDULE.
  - *New (`ViewStats`, 4):* `--division` filter in DRAFT scopes stats to that division only; `--team` filter in DRAFT suppresses stats entirely; `--field` filter in DRAFT suppresses stats entirely; FINALIZED view without filter shows stats blocks.

---

## [0.12.0] — Division Curfew Times and Playoff Field Priority

**PRD:** `features/2026-05-31-scheduler-division-curfew-field-priority.md`  
**Spec:** `specs/2026-05-31-scheduler-division-curfew-field-priority.md`

Adds two orthogonal scheduler constraints. A per-division curfew caps the latest allowable game or practice start time, protecting younger players from late-evening events. A per-field playoff priority rank steers the CP-SAT playoff solver toward preferred venues as a soft objective — ranked fields are used first, but the solver falls back to unranked fields rather than failing if ranked capacity is exhausted. Schema advances from v9 to v10.

### Added

- **`planr division edit --curfew-time <HH:mm>`** — Sets the latest time at which any game or practice for this division may **start**. A start exactly at the curfew time is valid; any start after it is excluded from the solver's candidate slots. Validated with `DateTimeFormatter` in `STRICT` mode (rejects `24:00`, seconds-included formats such as `HH:mm:ss`, out-of-range minutes, and non-numeric input). Stored as a nullable `LocalTime` on `Division`; null means no constraint.

- **`planr division edit --no-curfew-time`** — Clears the curfew from a division. Idempotent on divisions that have no curfew. Mutually exclusive with `--curfew-time`.

- **`planr field edit --playoff-priority <n>`** — Assigns a playoff preference rank to a field. Lower numbers are preferred (1 = highest priority). The CP-SAT playoff solver maximizes total priority score as a secondary objective, behind maximizing total assignments and ahead of minimizing week-load imbalance. Validated as a positive integer (≥ 1). Stored as a nullable `Integer` on `Field`; null means unranked. Ignored entirely by regular-season and practice solvers.

- **`planr field edit --no-playoff-priority`** — Removes the playoff priority rank from a field. Idempotent. Mutually exclusive with `--playoff-priority`.

- **`PlayoffFieldSummary` record** — New transient record in the `scheduler` package: `fieldId`, `fieldName`, `playoffPriority` (nullable), `gamesAssigned`. Carried in `PlayoffScheduleResult.Success`; not persisted to `league.json`.

### Changed

- **`planr division list`** — Adds a `CURFEW` column after `PRAC_END`. Displays the curfew as `HH:mm` when set, `--` when not configured.

- **`planr field list`** — Adds a `PLAYOFF_PRI` column after `LOCKS`. Displays the priority rank as an integer when set, `--` when not ranked.

- **`planr playoff assign`** — Prints a `Field Utilization` table after the constraint summary. Columns: `FIELD`, `PLAYOFF_PRI` (rank or `--`), `GAMES`. Only fields that received at least one game appear. Rows are sorted by priority rank ascending (nulls last), then by field name.

- **`Division` record** — Gains `LocalTime curfewTime` as the tenth constructor parameter (after `practiceEnd`). Null means no curfew constraint. All `withTeam*` and `withPracticeConfig` helpers thread the field through unchanged.

- **`Field` record** — Gains `Integer playoffPriority` as the seventh constructor parameter (after `divisionLocks`). Null means unranked. All `with*` helpers thread the field through unchanged.

- **`PlayoffScheduleResult.Success`** — Gains `List<PlayoffFieldSummary> fieldSummaries` as the fourth field. The `success(...)` factory method updated to accept all four parameters. `fieldSummaries` is sorted by priority rank ascending (nulls last), then field name, before being returned.

- **`SchedulerService`** — Three coordinated changes:
  - *Curfew slot pre-filter:* `enumerateAllSlots`, `enumeratePlayoffSlots`, `enumeratePracticeSlots`, and `estimateAvailableSlots` all call the new `divisionCurfew(League, UUID)` helper and break the start-time cursor when it exceeds the division's curfew. This keeps the CP-SAT model smaller by excluding ineligible slots before model construction.
  - *Zero-slot hard fail:* `buildAndSolve`, `buildAndSolvePlayoffs`, and `buildAndSolvePractices` each check for divisions with a configured curfew that produced zero slots after filtering. If found, the method returns a `Failure` naming the division and its curfew time before building the CP-SAT model.
  - *3-tier playoff objective:* `buildAndSolvePlayoffs` computes a `fieldPriorityScore` map for all fields using `buildFieldScoreMap` (`score = maxRank − rank + 1` for ranked fields; 0 for unranked). An `IntVar totalPriorityScore` is added to the model and constrained to the weighted sum of all game assignment variables against their field scores. The existing 2-tier `(totalAssigned × bigM − maxWeekLoad)` objective is extended to 3-tier `(totalAssigned × W1 + totalPriorityScore × W2 − maxWeekLoad)`, where `W1 = max(totalFixtures + 1, maxFieldRank × totalFixtures × W2 + weekCap + 1)` and `W2 = weekCap + 1`. This guarantees lexicographic dominance: one extra assigned game always beats any priority gain; any priority gain always beats week-load balancing. When no fields are ranked (all priorities null), the formula degenerates to the old 2-tier objective unchanged.

- **`LeagueStore`** — v9→v10 migration block: no data transformation required. `Division.curfewTime` and `Field.playoffPriority` absent from existing JSON deserialize as `null` via `FAIL_ON_UNKNOWN_PROPERTIES = false`. Version stamped and written back.

- **`League.CURRENT_VERSION`** — Advanced from `9` to `10`.

### Tests

- **`DivisionCommandCurfewTest`** (new file, 17 tests) across `CurfewTimeValidation`, `NoCurfewTime`, `ListCurfewColumn`, and `NoArgsGuard` nested classes: valid time persists, midnight (`00:00`) accepted, `25:00` rejected, `24:00` rejected (STRICT mode, would parse to midnight under SMART), `19:60` rejected, `HH:mm:ss` format rejected, non-numeric rejected, mutual exclusion error, `--no-curfew-time` removes curfew, idempotent clear, other fields preserved on clear, `CURFEW` header always present, `--` when unset, `HH:mm` when set, mixed table, `--curfew-time`/`--no-curfew-time` each accepted as sole options.

- **`FieldCommandPlayoffPriorityTest`** (new file, 15 tests) across `PlayoffPriorityValidation`, `NoPlayoffPriority`, `ListPlayoffPriorityColumn`, and `NoArgsGuard` nested classes: rank 1 persists, rank 5 persists, rank 0 rejected, negative rank rejected, mutual exclusion error, `--no-playoff-priority` removes rank, idempotent clear, other fields preserved on clear, `PLAYOFF_PRI` header always present, `--` when unset, integer rank when set, mixed table, both new options accepted as sole options, existing no-options guard still enforced.

- **`SchedulerServiceCurfewTest`** (new file, 14 tests) across `EstimateAvailableSlots`, `AssignCurfewHardFail`, and `AssignWithActiveCurfew` nested classes:
  - *EstimateAvailableSlots (8):* null curfew unchanged; curfew at last valid start time includes that slot (boundary inclusive per AC 6); one minute before excludes it; curfew reduces count vs. unconstrained; exact count for curfew=13:00; curfew before window open → 0 slots; curfew at window open → 1 slot; per-division independence.
  - *AssignCurfewHardFail (3):* midnight curfew → `ScheduleResult.Failure` naming the division; failure message contains the curfew time; unconstrained division does not trigger the curfew guard.
  - *AssignWithActiveCurfew (3):* no assigned game starts after curfew (full CP-SAT run); game starting exactly at curfew is assigned (inclusive boundary); another division in the same solve is not limited by a different division's curfew.

- **`SchedulerServiceFieldPriorityTest`** (new file, 15 tests) across `FieldSummaryPresence`, `RankedFieldPreferred`, `FallbackToUnranked`, `EqualRankTreatment`, `NoRankedFields`, and `RegularSeasonUnaffected` nested classes:
  - *FieldSummaryPresence (5):* non-empty summary when games assigned; field name and game count in summary entry; null priority for unranked field; configured priority value for ranked field; summary sorted by rank ascending, nulls last.
  - *RankedFieldPreferred (3):* all games go to ranked field when it has capacity (AC 17); unranked field absent from summary; rank 1 preferred over rank 2.
  - *FallbackToUnranked (2):* games on unranked field when ranked has only 1 slot (AC 18); solver returns Success (not Failure) during fallback.
  - *EqualRankTreatment (2):* two rank-1 fields used without error (AC 20); unranked field absent when two equal-rank fields have capacity.
  - *NoRankedFields (2):* solver succeeds when all fields unranked; field summary still populated.
  - *RegularSeasonUnaffected (1):* `assign()` game count unchanged whether or not fields have playoff priority (AC 21).

---

## [0.11.0] — Unified Calendar View

**PRD:** `features/2026-05-29-calendar-view.md`  
**Spec:** `specs/2026-05-29-calendar-view.md`

Adds `planr calendar`, a new top-level read-only command that renders all assigned schedule events — regular-season games, playoff games, and practice slots — in a unified ASCII calendar. Two layout modes are supported: a weekly day-by-day listing (default) and a monthly grid with a per-day event-count display followed by a full chronological event listing. The same `--division`, `--team`, and `--field` filters available on `planr schedule view` apply here. No data model changes, no JSON schema version bump.

### Added

- **`planr calendar [--weekly | --monthly]`** — Renders a unified calendar of all assigned events. `--weekly` (default) shows a 7-day Sunday-through-Saturday block with events listed under each day, sorted by start time then type (game → playoff → practice) then division and description. `--monthly` renders a traditional calendar grid followed by a full chronological event listing for the month. Defaults to the earliest week or month containing at least one assigned event when no date is specified.

- **`--week <YYYY-MM-DD>`** (weekly mode only) — Selects the target week; any date within the week may be supplied. The calendar always shows the Sunday-through-Saturday window containing that date.

- **`--month <YYYY-MM>`** (monthly mode only) — Selects the target month.

- **`--division <name>` / `--team <name>` / `--field <name>`** — At most one filter may be specified per invocation. All filters are case-insensitive and validated against known entities. When `--team` is used, playoff events are excluded because bracket references (`W of G1`) cannot be matched to a team name before the bracket resolves; use `--division` to include playoff games.

- **Weekly view layout** — Header line: `Week of YYYY-MM-DD (Sun) — YYYY-MM-DD (Sat)`. Each of the 7 days is a section with a `  DOW YYYY-MM-DD` header followed by indented event lines in the format `    HH:MM  [TYPE]  <description>  @  <field>  (<division>)`, or `    (no events)`. `[TYPE]` is `[G]` for regular-season games, `[PO]` for playoff games, `[P]` for practices. Practice event descriptions show only the team name (no "(practice)" suffix). Summary: `N events this week  (G: g  PO: po  P: p)`.

- **Monthly view layout** — Title centered at position 36 for an 80-char terminal. Grid uses Sun–Sat column order with a 5-char left margin and 10-char columns (75 chars total). Each week row is exactly 4 terminal lines: date numbers (right-aligned), game counts (`3G`), practice counts (`2P`), playoff counts (`1PO`) — blank, not `0G`, when a count is zero. All 4 lines are always emitted including rows where all counts are zero. After the grid and legend (`Legend: G = Game   PO = Playoff   P = Practice`), a chronological event listing groups events by date, omitting dates with no events. Summary: `N events in Month YYYY  (G: g  PO: po  P: p)`.

- **`CalendarCommand`** (new file: `src/main/java/org/leagueplan/planr/command/CalendarCommand.java`) — Top-level picocli command; handles argument parsing, entity validation, event collection from all three sources, filter application, and rendering dispatch. Contains two package-private nested types used by the renderer: `CalendarEvent` record (date, time, type, description, fieldName, divisionName, filterTeams) and `EventType` enum (GAME, PLAYOFF, PRACTICE). Event collection resolves field names and team names from the `League` entity via `league.fields()` and division team streams; `ScheduledGame.fieldName` and `ScheduledGame.divisionName` are already denormalized and used directly.

- **`CalendarRenderer`** (new file: `src/main/java/org/leagueplan/planr/command/CalendarRenderer.java`) — Package-private utility class with only static methods. Accepts a `PrintStream` parameter (not `System.out` directly) so rendering is testable without stream capture. Implements `renderWeekly()` and `renderMonthly()`, a shared `EVENT_ORDER` comparator, `toSundayOnOrBefore()` (Sunday-first week arithmetic), and `formatEventLine()`.

### Tests

- **`CalendarRendererTest`** (new file, 41 tests) across `WeeklyView`, `MonthlyGrid`, `MonthlyEventListing`, `ToSundayOnOrBefore`, and `FormatEventLine` nested classes:
  - **WeeklyView (10):** empty list shows `(no events)` on all 7 days; header uses title-case day abbreviations; day section headers are uppercase; game/playoff/practice event line formats; sort order (time ASC → game < playoff < practice → division → description); tiebreak by division then description; events appear only on their correct day; summary counts each type.
  - **MonthlyGrid (11):** title centered at column 36; column header positions for all 7 days; May 2026 produces 6 week rows of 4 lines each; fixed-height invariant for all-zero rows; day numbers right-aligned by column; game count in Sat column; practice count in Fri and Tue columns; playoff count in Sat column; zero count is blank not `0G`; out-of-month cells are blank; month-starting-on-Sunday has no leading blank cells.
  - **MonthlyEventListing (6):** dates in ascending order; dates with no events omitted; day headers are uppercase; events within a day sorted by time then type; event format matches weekly view; empty list renders cleanly with zero-count summary.
  - **ToSundayOnOrBefore (5):** Sunday maps to itself; Monday maps back 1; Saturday maps back 6; all 7 days in one week map to the same Sunday; month-boundary crossing.
  - **FormatEventLine (4):** game `[G]` format with 4-space indent; playoff `[PO]` format; practice `[P]` format with team name only; time zero-padded.

- **`CalendarCommandTest`** (new file, 39 tests) across `ArgumentValidation`, `EntityValidation`, `NoAssignedEvents`, `WeeklyView`, `MonthlyView`, `FilterByDivision`, `FilterByTeam`, `FilterByField`, and `CalendarCommand.toWeekStart` nested classes:
  - **ArgumentValidation (10):** `--weekly`/`--monthly` mutual exclusion; all three two-filter combinations; `--week` with `--monthly`; `--month` without `--monthly`; `--month` with explicit `--weekly`; invalid month format; impossible month value; non-numeric month.
  - **EntityValidation (3):** unknown division, team, and field each exit 1.
  - **NoAssignedEvents (3):** fresh league exits 1; team schedule only (no Phase 2) exits 1; corrupted data exits 2.
  - **WeeklyView (5):** exits 0 with week header after assign; `[G]` events appear; event lines contain home/away/field/division; `--week` outside the season shows all `(no events)`; summary is non-zero after assign.
  - **MonthlyView (3):** exits 0 with month title and legend; out-of-season month shows zero-count summary; summary line counts games.
  - **FilterByDivision (3):** only named division's events shown; case-insensitive; teamless division exits 1.
  - **FilterByTeam (3):** only named team's events shown; case-insensitive; playoff events excluded from team filter.
  - **FilterByField (3):** only named field's events shown; case-insensitive; post-assignment injected field exits 1.
  - **toWeekStart (4):** Sunday maps to itself; Saturday maps back 6; all 7 days map to same Sunday; `toWeekStart` and `CalendarRenderer.toSundayOnOrBefore` agree on all inputs.

---

## [0.10.2] — `planr practice view` Rename and Sort Fix

**Spec:** `specs/2026-05-29-practice-view-rename-and-sort.md`

Two targeted fixes to the practice display path. No data model changes, no schema version bump.

### Changed

- **`planr practice status` renamed to `planr practice view`** — Aligns the command name with `planr schedule view` for a consistent CLI surface. The no-arg form (division summary) and `--division <name>` form (slot detail table) are preserved unchanged; only the command token changes. `planr practice status` no longer exists.

- **`planr practice view --division <name>` sort order** — The slot detail table now sorts by assigned date ascending (unassigned slots last), then by assigned start time ascending, then by team name as a stable tiebreaker. Previously the table was sorted only by team name, which produced an unpredictable mix of assigned and unassigned rows.

### Tests

- **`PracticeCommandTest`** — All `execute("practice", "status", ...)` calls updated to `execute("practice", "view", ...)`. Nested classes `StatusSummary` and `StatusDetail` renamed to `ViewSummary` and `ViewDetail`.

---

## [0.10.1] — Configurable Field Buffer and Scheduling Grid

**PRD:** `features/2026-05-28-configurable-field-buffer.md`
**Spec:** `specs/2026-05-28-configurable-field-buffer.md`

Makes the field turnaround buffer and start-time grid interval configurable at the league level via `planr config set`. Previously both values were hardcoded in `SchedulerService` (buffer: 15 min, grid: 15 min). After this change the defaults are buffer = 0 (back-to-back games allowed) and grid = 30 minutes; existing leagues that do not configure explicit values will use the new defaults. Schema advances from v8 to v9.

### Added

- **`planr config set --field-buffer-minutes <N>`** — Sets the minimum turnover gap (in minutes) enforced between consecutive games on the same field. Validated as a non-negative integer (≥ 0; 0 means back-to-back games are allowed). Stored as a nullable `Integer` on `LeagueConfig`; null means "use the system default of 0." Merges with other config fields on each `set` call.

- **`planr config set --grid-minutes <N>`** — Sets the step size (in minutes) used to advance the start-time cursor when enumerating candidate slots. Validated as a positive integer that evenly divides 60 (valid values: 1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30, 60). Stored as a nullable `Integer` on `LeagueConfig`; null means "use the system default of 30." Merges with other config fields on each `set` call.

- **`SchedulerService.DEFAULT_FIELD_BUFFER_MINUTES = 0`** and **`DEFAULT_GRID_MINUTES = 30`** — Public constants used by the solver (as fallback defaults) and `ConfigCommand.ShowCmd` (to render `(default)` labels in `config show`).

### Changed

- **`planr config show`** — Renders two new lines after `Min rest days:`: `Field buffer: N min` and `Grid interval: N min`. Each line appends `(default)` when the value has not been explicitly configured; no suffix when explicitly set.

- **`LeagueConfig` record** — Gains two new nullable fields: `Integer fieldBufferMinutes` (position 9) and `Integer gridMinutes` (position 10). Compact constructor leaves both as `null` (does not normalize to defaults, so `config show` can distinguish "not set" from "explicitly set to the default value"). New mutation helpers: `withFieldBufferMinutes(Integer)` and `withGridMinutes(Integer)`.

- **`SchedulerService`** — Removes hardcoded `BUFFER_MINUTES = 15` and `GRID_MINUTES = 15` constants. Both values are now derived at call time from `league.config()`, falling back to `DEFAULT_FIELD_BUFFER_MINUTES` and `DEFAULT_GRID_MINUTES` when the config fields are null. Applies across `assign()`, `assignPlayoffs()`, `assignPractices()`, `estimateAvailableSlots()`, `buildAndSolve()`, and `enumerateAllSlots()`. **Behavior change:** leagues that do not configure explicit values now use buffer = 0 and grid = 30 min (previously both were hardcoded to 15 min).

- **`ScheduleGameCommand.gamesConflict()`** — Accepts `int bufferMinutes` as a parameter; derives the value from `league.config().fieldBufferMinutes()` at call time rather than using a hardcoded constant. The conflict-warning message reports the active buffer value.

- **`LeagueStore`** — v8→v9 migration block: no data transformation required; absent `fieldBufferMinutes` and `gridMinutes` keys deserialize to `null` via `FAIL_ON_UNKNOWN_PROPERTIES = false`. Version stamped and written back.

- **`League.CURRENT_VERSION`** — Advanced from `8` to `9`.

### Tests

- **`LeagueConfigTest`** additions — 9 new tests across `WithFieldBufferMinutes` (5) and `WithGridMinutes` (4) nested classes: set value, accept zero (buffer) and null, thread all other fields through unchanged, immutability.

- **`ConfigCommandTest`** additions — 25 new tests: `--field-buffer-minutes` valid (0, 15), invalid (−1, non-integer); `--grid-minutes` valid (30, 15, 60, 1), invalid (0, −1, 7, 120, non-integer); persistence to `config show`; merging without clearing other options; combined set in one call; `config show` `Field buffer` and `Grid interval` labels; `(default)` when unset; correct default values (0 min and 30 min); explicit values without `(default)` suffix; explicit zero for buffer distinguished from unset.

- **`SchedulerServiceBufferGridTest`** (new file, 23 tests) — 5 nested classes:
  - **`FieldBuffer`** (7): null = default 0; buffer=0 and buffer=30 counts match slot formula; exact boundary fit (duration + buffer == window); oversized buffer → 0 slots; multi-day consistency.
  - **`GridMinutes`** (8): null = default 30; grid=30/15/60/1 counts match slot formula; coarser grid → fewer slots; multi-day consistency.
  - **`BufferAndGridCombined`** (3): combined formula (buffer=30 + grid=60); 2-hour window with buffer=60 → 1 slot; 2-hour window with buffer=0 → 3 slots.
  - **`AssignFieldNonOverlap`** (2): buffer=0 allows two 60-min games back-to-back on the same field (solver); buffer=60 prevents the second game in the same 2-hour window (solver).
  - **`AssignGridAlignment`** (3): grid=60 → all start times on the hour; grid=30 → :00/:30 only; grid=15 → :15/:45 permitted (solver).

---

## [0.10.0] — Pre-Season Practice Scheduling

**PRD:** `features/2026-05-26-practice-scheduling.md`

Adds a `planr practice` command group that lets organizers configure and schedule pre-season field time for each division before regular-season games begin. Phase 1 generates practice slot stubs (one per team per requested count); Phase 2 runs the existing CP-SAT solver cross-division to assign field/time slots within each division's practice window. Because the practice window is enforced to end before `seasonStart`, practice and game weeks never overlap — the weekly cap and rest-day constraints apply to practice-only activity during the practice period. Schema advances from v7 to v8.

### Added

- **`planr practice generate`** — Generates practice slot stubs for all divisions that have `practiceCount`, `practiceDurationMinutes`, `practiceStart`, and `practiceEnd` configured. Divisions missing any of the four values are skipped with a per-division warning line; divisions with an existing `PracticeSchedule` are also skipped. Prints a per-division line (`Generated T×P practice slots for <division>`) and a final summary. Exits 1 if no divisions qualify.

- **`planr practice assign`** — Clears all prior field assignments and re-runs the CP-SAT solver across all divisions with a `PracticeSchedule` entity. Uses each division's `[practiceStart, practiceEnd]` window and `practiceDurationMinutes` for slot sizing. Field availability, blocks, overrides, and division locks are applied identically to the regular-season solver. `maxGamesPerWeek` caps weekly practices per team; `minRestDays` enforces rest gaps between consecutive practice assignments. Prints live `[M:SS]` progress lines, a constraint summary table, and a final status line. Partial assignments accepted (exit 0). Prompts for `yes` confirmation before starting.

- **`planr practice status [--division <name>]`** — Without `--division`: prints a summary table with `DIVISION`, `STATE`, `ASSIGNED`, and `TOTAL` columns. State values: `NOT_CONFIGURED` (practice fields not set), `NOT_STARTED` (configured but not yet generated), `GENERATED`, `ASSIGNED`. With `--division`: prints a per-slot detail table (`TEAM`, `PRACTICE`, `DATE`, `TIME`, `FIELD`) sorted by team name; unassigned slots show `UNASSIGNED`. Exits 1 if the division or its schedule is not found.

- **`planr practice clear --division <name>`** — Removes a division's `PracticeSchedule` entity after interactive confirmation, reverting it to `NOT_STARTED`. Prints the current state, slot count, and assigned count in the confirmation prompt. Exits 1 if no schedule exists for the division.

- **`PracticeSchedule` record** — `(UUID divisionId, PracticeState state, List<PracticeSlot> slots)`. Persisted in `league.json` under the `practiceSchedules` key on `League`. Mutation helpers: `withSlots(List<PracticeSlot>)` and `withState(PracticeState)`.

- **`PracticeSlot` record** — `(UUID slotId, UUID teamId, int slotNumber, LocalDate assignedDate, LocalTime assignedStartTime, UUID assignedFieldId)`. `slotNumber` is 1-based (1 of P, 2 of P, …). All three `assigned*` fields are `null` until Phase 2 runs. Mutation helpers: `withAssignment(date, time, fieldId)` and `withAssignmentCleared()`.

- **`PracticeState` enum** — `GENERATED`, `ASSIGNED`.

- **`PracticeScheduleResult` sealed interface** — `Success(Map<UUID,Slot> assignmentsBySlotId, boolean optimal, boolean targetMet, List<DivisionSummary> divisionSummaries)` and `Failure(String message)`. Mirrors `PlayoffScheduleResult` in shape; keyed on `slotId` rather than `gameId`.

- **`PracticeFixture` record** — Internal scheduler type: `(UUID slotId, UUID teamId, UUID divisionId, int practiceDurationMinutes)`. Passed to `buildAndSolve` in place of `Fixture`; the single `teamId` is used as both home and away to satisfy the solver's per-team constraint keys.

### Changed

- **`planr division edit`** — Gains four new optional flags: `--practice-count <n>` (positive integer, ≥ 1), `--practice-duration-minutes <n>` (positive integer, ≥ 1), `--practice-start <YYYY-MM-DD>`, `--practice-end <YYYY-MM-DD>`. All flags are independent; only the supplied fields are updated. Validates: count and duration ≥ 1; end not before start; both start and end must be strictly before `config.seasonStart` (when season is configured). Any violation exits 1 with a specific error message.

- **`planr division list`** — Adds four new columns: `PRACTICE COUNT`, `PRAC. DURATION`, `PRAC. START`, `PRAC. END`. Fields that have not been set are displayed as `--`.

- **`Division` record** — Gains four new nullable fields at positions 6–9: `Integer practiceCount`, `Integer practiceDurationMinutes`, `LocalDate practiceStart`, `LocalDate practiceEnd`. All default to `null` when absent in existing JSON. New derived method: `isPracticeConfigured()` (all four fields non-null). New mutation helper: `withPracticeConfig(count, durationMinutes, start, end)`.

- **`League` record** — Gains `List<PracticeSchedule> practiceSchedules` as the last constructor parameter. Compact constructor normalizes `null → List.of()`. New mutation helpers: `withPracticeScheduleAdded(PracticeSchedule)`, `withPracticeScheduleReplaced(UUID, PracticeSchedule)`, `withPracticeScheduleRemoved(UUID)`, `findPracticeSchedule(UUID)`.

- **`SchedulerService`** — Gains `assignPractices(League, List<PracticeSchedule>)` public method. Converts each `PracticeSlot` to an internal `PracticeFixture` (single-team fixture); enumerates slots using each division's `[practiceStart, practiceEnd]` window and `practiceDurationMinutes`; delegates to the existing `buildAndSolve` and maps results to a `PracticeScheduleResult`.

- **`PlanrApp`** — Registers `PracticeCommand` as a top-level subcommand alongside `PlayoffCommand`.

- **`LeagueStore`** — v7→v8 migration block: no data transformation required; absent `practiceSchedules` key deserializes to `null`, normalized by the compact constructor. Version is stamped and written back.

- **`League.CURRENT_VERSION`** — Advanced from `7` to `8`.

### Tests

- **`DivisionCommandPracticeTest`** — 20 tests across `PracticeCount`, `PracticeDurationMinutes`, `PracticeDates`, and `PracticeListColumns` nested classes: count persists and shows in list; count=0 exits 1; count negative exits 1; duration persists and shows; duration=0 exits 1; duration negative exits 1; dates persist and show; end-before-start exits 1; practice-start not before seasonStart exits 1; practice-end not before seasonStart exits 1; invalid start format exits 1; invalid end format exits 1; can set start alone when no end stored; validates new start against stored end; division list shows all four practice columns; `--` shown when fields unset.

- **`PracticeCommandTest`** — 28 tests across `Generate`, `StatusSummary`, `StatusDetail`, `Clear`, and `Lifecycle` nested classes: generate prints slot count, handles multiple divisions, skips incomplete config, skips existing schedule, skips division with no teams, exits 1 when none qualify, persists schedule (status shows GENERATED), exits 2 on corrupt data; status summary shows NOT_CONFIGURED/NOT_STARTED/GENERATED with assigned+total counts, empty-league message, exits 2 on I/O error; status detail prints slot table, period header, slot numbers as "N of P", case-insensitive division lookup, exits 1 when division not found, exits 1 when no schedule; clear removes on confirmation, status reverts to NOT_STARTED, cancelled on non-yes, confirmation includes slot count, exits 1 when no division, exits 1 when no schedule, exits 2 on I/O error; lifecycle cycle and two-division independence.

---

## [0.9.0] — Double-Elimination Playoff Brackets

**PRD:** `features/2026-05-26-playoffs-double-elimination.md`  
**Spec:** `specs/2026-05-26-playoffs-double-elimination.md`

Adds a `planr playoff` top-level command group for managing post-season double-elimination brackets. Phase 1 generates the bracket structure for a division given an ordered seed list; Phase 2 runs the existing CP-SAT field-assignment solver across all active brackets in a single cross-division solve using a playoff-specific date range. Bracket generation, field assignment, status viewing, and clearing are all independent commands. Schema advances from v6 to v7. Post-implementation Errata E-1 (see spec) is incorporated: `--seeds` was changed to a repeatable per-team flag to handle multi-word team names, and an `IndexOutOfBoundsException` in the L-R1 pairing loop for N = 7, 11, 13, 15 was corrected with a `GameRef` carrier.

### Added

- **`planr playoff generate`** — Validates the division, date range, and seed list (count, no duplicates, all names match division teams, no existing playoff for the division), then delegates to `PlayoffBracketService.generateBracket()` to produce the full double-elimination bracket. Persists a new `Playoff` record in `GENERATED` state. Prints a bracket summary table and a final summary line. `--seeds` accepts one team per flag invocation (`--seeds "Red Sox" --seeds Yankees …`), handling multi-word names without shell quoting issues.

- **`planr playoff assign`** — Validates that at least one playoff exists and that all playoffs share identical start/end dates. Clears all prior field assignments, then calls `SchedulerService.assignPlayoffs()` to run the CP-SAT solver. Maps solver output back into `PlayoffGame` field assignments, transitions all `Playoff` records to `ASSIGNED` state, and saves. Prints active field division locks, a constraint summary table, and a final status line. Prompts for `yes` confirmation before starting.

- **`planr playoff status [--division <name>]`** — Without `--division`: prints a one-line summary per division showing state (`NOT_STARTED` | `GENERATED` | `ASSIGNED`). With `--division`: prints the full bracket table with `SLOT`, `ROUND`, `POSITION A`, `POSITION B`, and `ASSIGNED` (date/time/field, `UNASSIGNED`, or `BYE`) columns. The conditional re-match slot is marked with `*`; a legend line is appended. Exits 1 when the division or its playoff is not found.

- **`planr playoff clear --division <name>`** — Removes a division's `Playoff` record after interactive confirmation, reverting it to `NOT_STARTED`. The confirmation prompt includes the current state, real game slot count, and assigned count. Exits 1 when the division or playoff is not found.

- **`Playoff` record** — `(UUID divisionId, LocalDate startDate, LocalDate endDate, PlayoffState state, List<PlayoffGame> games)`. Persisted in `league.json` under the `playoffs` key on `League`. Mutation helpers: `withGames(List<PlayoffGame>)`.

- **`PlayoffGame` record** — One bracket slot: `(UUID gameId, String round, BracketSide bracketSide, String positionA, String positionB, LocalDate assignedDate, LocalTime assignedStartTime, UUID assignedFieldId, boolean isConditional, boolean isBye)`. Position strings use team names in R1 and positional references (`"W of G3"`, `"L of G2"`) in subsequent rounds. Mutation helpers: `withAssignment(date, time, fieldId)` and `withAssignmentCleared()`.

- **`PlayoffState` enum** — `GENERATED`, `ASSIGNED`.

- **`BracketSide` enum** — `WINNERS`, `LOSERS`, `CHAMPIONSHIP`.

- **`PlayoffBracketService`** — Pure bracket generation: given an ordered list of N seed names (2 ≤ N ≤ 16), produces an ordered `List<BracketSlot>` for the full double-elimination structure. Pads to the next power of two P; top-seeded teams get byes in W-R1. Subsequent rounds use positional references. Invariants: real game count = 2N−1, bye count = P−N, exactly one conditional championship re-match slot. A private `GameRef(UUID gameId, String prefix)` carrier handles the L-R1 orphan case that occurs when the number of real W-R1 games is odd (N = 7, 11, 13, 15). Static helpers: `nextPowerOfTwo(int)`, `toPlayoffGame(BracketSlot)`.

- **`PlayoffScheduleResult` sealed interface** — `Success(Map<UUID,Slot> assignmentsByGameId, boolean optimal, boolean targetMet, List<DivisionSummary> divisionSummaries)` and `Failure(String message)`. Thinner than `ScheduleResult` — keyed on `PlayoffGame.gameId` so `AssignCmd` can map solver output back to individual game records without modifying `buildScheduledGame`.

### Changed

- **`SchedulerService`** — Gains `assignPlayoffs(League, List<Playoff>)` public method. Converts non-bye `PlayoffGame` records to `Fixture` objects (R1 games use resolved team IDs; later-round games use deterministic pseudo-UUIDs so existing C3/C4/C5 constraints fire trivially per slot). Enumerates slots using the playoff date range via the existing `enumerateAllSlots` overload. Delegates to `buildAndSolve` unchanged. `enumerateAllSlots` gains an overloaded 4-arg form accepting an optional `Set<UUID> divisionIdFilter`; the existing 3-arg form delegates to it with an empty set (no filter).

- **`League` record** — Gains `List<Playoff> playoffs` as the seventh constructor parameter. Compact constructor normalizes `null → List.of()`. New mutation helpers: `withPlayoffAdded(Playoff)`, `withPlayoffReplaced(UUID, Playoff)`, `withPlayoffRemoved(UUID)`, `findPlayoff(UUID)`. All existing `withX()` helpers updated to thread `playoffs` through.

- **`PlanrApp`** — Registers `PlayoffCommand` as a top-level subcommand.

- **`LeagueStore`** — v6→v7 migration block: no data transformation required; absent `playoffs` key deserializes to `null`, normalized by the compact constructor. Version is stamped and written back.

- **`League.CURRENT_VERSION`** — Advanced from `6` to `7`.

### Tests

- **`PlayoffBracketServiceTest`** — 5 parameterized test suites running across all N in [2, 16] (15 values each): real game count = 2N−1; exactly one conditional; bye count = P−N; all game IDs unique; no exception thrown. Dedicated regression suite for the odd-N L-R1 crash: N = 7, 11, 13, 15 each assert no exception, correct real count, conditional count, and bye count. Nested classes for N=2 (slot counts, seed positions, championship placement), N=4 (slot counts, W-R1 seeding 1v4/2v3), N=5 (slot counts, bye-flag consistency, top seeds in bye slots), N=8 (slot counts, four W-R1 games). Championship slots: both in `CHAMPIONSHIP` side, exactly one conditional, conditional is last. Bracket side assignments: W-R1 non-bye slots are `WINNERS`, losers-bracket slots are `LOSERS`. Determinism: two calls with identical seeds produce identical structure (ignoring random gameIds). `toPlayoffGame` conversion: all fields preserved, all `assigned*` fields null.

- **`PlayoffCommandTest`** — 34 tests across `Generate` (12), `StatusSummary` (5), `StatusDetail` (8), `Clear` (6), and `Lifecycle` (3) nested classes: generate exits 0 for 2/3/4-team brackets with correct bye counts; team names appear in bracket table; case-insensitive seed matching; persists to GENERATED state; exits 1 for unknown division, end-before-start, invalid date, seed count mismatch, unrecognized seed name, duplicate seeds, existing playoff; exits 2 on I/O error. Status summary NOT_STARTED/GENERATED states, all divisions shown even when partial, empty league; status detail full bracket table, period header, case-insensitive lookup, UNASSIGNED/BYE/conditional marker, exits 1 when not found. Clear: yes removes and reverts to NOT_STARTED, non-yes cancels, exits 1 for unknown division/missing playoff, exits 2 on I/O error. Lifecycle: full generate→status→clear cycle; can re-generate after clear; two divisions independent.

---

## [0.8.0] — Scheduling Constraints & Field Division Locks

**PRD:** `features/2026-05-25-scheduling-constraints.md`  
**Spec:** `specs/2026-05-25-scheduling-constraints.md`

Adds three new scheduling constraints: a per-team weekly game cap, a minimum rest-day gap between a team's games, and date-range field division locks. The first two are stored as nullable `Integer` fields on `LeagueConfig` and enforced as hard CP-SAT constraints inside `SchedulerService`. The third introduces a new `FieldDivisionLock` record on each `Field`; it is bidirectional — it excludes other divisions from the locked field and pins the owning division to it during the lock period (E1 errata). Schema advances from v5 to v6.

### Added

- **`planr config set --max-games-per-week <N>`** — Sets a hard cap on the number of games any team may be scheduled in a single ISO calendar week. Validated as a positive integer (≥ 1). Stored as a nullable `Integer` on `LeagueConfig`; null means "use the system default of 2." Merges with other config fields on each `set` call.

- **`planr config set --rest-days <N>`** — Sets the minimum number of calendar days that must separate any two games for the same team. Validated as a non-negative integer (≥ 0; 0 disables the rest constraint). Stored as a nullable `Integer` on `LeagueConfig`; null means "use the system default of 1."

- **`planr field lock add --field <name> --division <name> --start <YYYY-MM-DD> --end <YYYY-MM-DD>`** — Locks a field to a single division for an inclusive date range. Field and division names are resolved case-insensitively. Rejects malformed dates, end-before-start, unknown field or division, and any new range that overlaps an existing lock on the same field (lists conflicting lock indices in the error message). Bidirectional: during the lock period the locked field is unavailable to other divisions, and the owning division may only use its locked field (not unlocked fields).

- **`planr field lock delete --field <name> --index <N>`** — Deletes a lock by 1-based index. Confirms the deleted lock's field name, resolved division name, and date range. Errors if the field has no locks, or if the index is out of range.

- **`planr field lock list [--field <name>]`** — Lists all field division locks in a `FIELD`, `#`, `DIVISION`, `START`, `END` table sorted by field name then start date. Optional `--field` filter restricts output to one field. Shows a distinct empty-state message when no filter is applied vs. when the named field has no locks. Division names are resolved live; shows `[unknown]` if the division was deleted after the lock was created.

- **`FieldDivisionLock` record** — `(UUID divisionId, LocalDate startDate, LocalDate endDate)`. No `id` field; identified by field name + 1-based index within `field.divisionLocks()`, consistent with `FieldBlock`. Serialized by the existing `JavaTimeModule`.

- **`SchedulerService.DEFAULT_MAX_GAMES_PER_WEEK = 2`** and **`DEFAULT_MIN_REST_DAYS = 1`** — Public constants used by both the solver (as fallback defaults) and `ConfigCommand.ShowCmd` (to render the `(default)` label).

### Changed

- **`planr config show`** — Renders two new lines after `Season end:`: `Max games/week: <N>` and `Min rest days: <N>`. Each line appends `(default)` when the value has not been explicitly configured; no suffix when explicitly set.

- **`planr schedule assign`** — Prints active constraint config before the confirmation prompt: `Scheduling constraints: max N game(s)/week per team, min N rest day(s) between games.` After the constraint summary, prints any field division locks that overlap the season window.

- **`LeagueConfig` record** — Gains two new nullable fields: `Integer maxGamesPerWeek` (position 7) and `Integer minRestDays` (position 8). Compact constructor leaves both as `null` (does not normalize to defaults, so `config show` can distinguish "not set" from "explicitly set to the default value"). New mutation helpers: `withMaxGamesPerWeek(Integer)` and `withMinRestDays(Integer)`.

- **`Field` record** — Gains `List<FieldDivisionLock> divisionLocks` as the sixth parameter. Compact constructor normalizes `null` to `List.of()`. New mutation helpers: `withLockAdded(FieldDivisionLock)` and `withLockRemoved(int zeroBasedIndex)`.

- **`SchedulerService.buildAndSolve()`** — Two new hard CP-SAT constraint groups:
  - **C4 (max games per week):** for each `(team, ISO week)` pair, `sum(assigned vars) ≤ weekCap`. The existing soft `maxWeekLoad` minimization objective is preserved alongside the new hard cap.
  - **C5 (minimum rest days):** for each team and each pair of dates `(D, D+r)` where `r ∈ [1, restDays]`, adds `addAtMostOne` on the combined set of game vars for those two dates. Because C3 already limits each team to at most one game per day, each combined group has at most 2 literals — O(teams × dates × restDays) constraints, each trivial.

- **`SchedulerService.enumerateAllSlots()` and `estimateAvailableSlots()`** — Both methods now apply bidirectional field lock filtering. In addition to skipping fields locked to other divisions, they skip fields that are not locked to the current division when that division has an active lock on some other field for that date (i.e., the division is pinned to its own locked field).

- **`FieldCommand`** — Registers `FieldLockCommand` as a subcommand. `AddCmd` updated to pass an empty `divisionLocks` list to the `Field` constructor. `EditCmd.applyEdits()` threads `field.divisionLocks()` through unchanged. `ListCmd` adds a `LOCKS` column.

- **`LeagueStore`** — v<4 migration updated to pass an empty `divisionLocks` list to `Field` constructors. New v5→v6 migration block: no-op version stamp. `Field.divisionLocks` absent from older JSON is normalized to `List.of()` by the compact constructor; `LeagueConfig.maxGamesPerWeek` and `minRestDays` absent from older JSON deserialize as `null` (use default).

- **`League.CURRENT_VERSION`** — Advanced from `5` to `6`.

### Tests

- **`FieldLockCommandTest`** — 22 tests across `Add`, `Delete`, and `ListCmd` nested classes: success, lock number increment, field/division not found, invalid date formats, end-before-start, single-day lock accepted, overlap detection (reports conflicting index numbers), consecutive non-overlapping locks accepted, case-insensitive field and division lookup, empty-list error on delete, index out of range, field-filter on list, filter for nonexistent field, I/O errors throughout.

- **`ConfigCommandTest`** additions — 13 new tests: `--max-games-per-week` valid/zero/negative; `--rest-days` valid (including 0)/negative; both options together; persistence to `config show`; `config show` renders `Max games/week` and `Min rest days` fields; `(default)` label when unset; no `(default)` label when explicitly set for each field.

- **`SchedulerServiceTest`** additions — 13 new tests:
  - **C4:** configured week cap of 1 is enforced across all teams; default cap of 2 is respected.
  - **C5:** default 1-day rest is enforced (no back-to-back games); `restDays=0` produces a complete valid schedule.
  - **Field division lock (exclusion side):** locked field gives 0 slots to non-owning division; locked field gives same slots to owning division as an unlocked field; partial-period lock opens the field to other divisions outside the lock window; locked field's games belong only to the owning division.
  - **E1 (bidirectional pinning):** all owning division games assigned to the locked field only; adding an unlocked field does not increase slot count for the pinned division; pinning releases after the lock date range expires (slot count doubles in the post-lock period); division locked to two different fields in sequential periods receives slots from each field only during its respective period; two divisions each pinned to their own field use only their assigned field — no game lands on the shared open field.

---

## [0.7.0] — League-Wide Day-of-Week Availability Windows & Blocked Days

**PRD:** `features/2026-05-25-league-wide-availability-config.md`  
**Spec:** `specs/2026-05-25-league-wide-availability-config.md`

Adds two new league-wide availability controls that apply to all fields simultaneously: recurring day-of-week open windows (e.g., Wednesdays open at 16:00 instead of the global sunrise) and blocked days of the week (e.g., no availability on Sundays). Both are stored in `LeagueConfig` and are respected by the Phase 2 scheduler via a four-level precedence rule: `FieldDateOverride` → blocked day → day-of-week window → global sunrise/sunset. A `FieldDateOverride` can still rescue an individual date on an otherwise-blocked day. Schema advances from v4 to v5.

### Added

- **`planr config dow set --day <DAY> --start <HH:mm> --end <HH:mm>`** — Sets a recurring availability window for all fields on the specified day of the week. Replaces any existing window for that day. Accepts full day names or 3-letter abbreviations, case-insensitively. Rejects invalid day names, malformed times, and end ≤ start. Prints a conflict warning (with count) when field-level blocks or overrides exist on matching dates within the configured season; scans all entries when no season is set.

- **`planr config dow clear --day <DAY>`** — Removes the day-of-week window for the specified day. Exits 1 if no window exists for that day.

- **`planr config dow list`** — Lists all configured day-of-week windows in a `DAY / OPEN / CLOSE` table sorted Monday through Sunday. Shows an empty-state message when none are configured.

- **`planr config blockday add --day <DAY>`** — Marks a day of the week as unavailable for all fields. Exits 1 if the day is already blocked. Prints a conflict warning when field-level entries exist on matching dates within the season; the warning explicitly notes that `FieldDateOverride` entries on specific dates still take precedence over the block.

- **`planr config blockday remove --day <DAY>`** — Removes a day-of-week block. Exits 1 if the day is not currently blocked.

- **`planr config blockday list`** — Lists all blocked days sorted Monday through Sunday. Shows an empty-state message when none are configured.

- **`DayOfWeekWindow` record** — `(DayOfWeek day, LocalTime openStart, LocalTime openEnd)`. Stored in `LeagueConfig.dowWindows`.

- **`DayParser` utility** — Package-private helper shared by `ConfigDowCommand` and `ConfigBlockdayCommand`. `parse(String)` accepts full day names and 3-letter abbreviations case-insensitively, returning `Optional<DayOfWeek>`. `displayName(DayOfWeek)` returns title-case names (e.g., "Wednesday"). `hint()` returns the accepted-format description used in error messages.

### Changed

- **`planr config show`** — Now renders two additional sections after the four existing fields: "Day-of-week windows:" (sorted Mon→Sun, "(none)" when empty) and "Blocked days of week:" (sorted Mon→Sun, "(none)" when empty).

- **`LeagueConfig` record** — Gains two new fields: `List<DayOfWeekWindow> dowWindows` and `List<DayOfWeek> blockedDays`. The compact constructor normalizes both `null` to `List.of()`, ensuring safe deserialization of v4 files that omit these keys. New mutation helpers: `withDowWindowSet(DayOfWeekWindow)`, `withDowWindowRemoved(DayOfWeek)`, `withBlockedDayAdded(DayOfWeek)`, `withBlockedDayRemoved(DayOfWeek)`. `LeagueConfig.empty()` initializes both lists to `List.of()`.

- **`SchedulerService`** — The `resolveOpenWindow(LeagueConfig, Field, LocalDate)` private helper now applies the four-level precedence rule. Both `enumerateAllSlots()` (used by the solver) and `estimateAvailableSlots()` (used by pre-Phase-2 feasibility warnings) delegate to this helper. A blocked day with no field-level override yields `null` (no slots for that date); a day-of-week window replaces global sunrise/sunset when no override or block applies.

- **`planr config set`** — Preserves `dowWindows` and `blockedDays` when merging config values; previously-stored windows and blocks are not cleared by a `config set` call.

- **Schema v4→v5 migration** — `LeagueStore.load()` now migrates v4 files: version is stamped to 5 and the file is written back to disk. No structural data transformation is required because the compact constructor normalizes the missing keys to empty lists during deserialization. A version bump prevents re-running the migration on subsequent loads.

- **`League.CURRENT_VERSION`** — Advanced from `4` to `5`.

### Tests

- **`LeagueConfigTest`** — 12 tests: compact constructor null normalization for `dowWindows` and `blockedDays`; all four mutation helpers (`withDowWindowSet` add/replace/preserve-others/immutability, `withDowWindowRemoved` remove/no-op/preserve-others, `withBlockedDayAdded` add/append/immutability, `withBlockedDayRemoved` remove/no-op/preserve-others).

- **`DayParserTest`** — 22 tests: full names for all 7 days, abbreviations for all 7 days, case insensitivity (uppercase, title-case, mixed), whitespace trimming, invalid inputs (null, empty, garbage, partial, numeric), `hint()` content, `displayName()` title-case for all 7 days.

- **`ConfigDowCommandTest`** — 17 tests across `set`, `clear`, and `list` nested classes: success with full/abbreviated name, replace existing window, unrecognized day, invalid start/end time, end ≤ start, singular/plural conflict warning, no warning when no conflicts, season-scoped warning, all-entries warning when no season, I/O error (exit 2); clear success/not-found/persistence; list empty state/headers/sort order/times shown.

- **`ConfigBlockdayCommandTest`** — 17 tests across `add`, `remove`, and `list` nested classes: success with full/abbreviated name, duplicate rejection, unrecognized day, persistence, singular conflict warning, precedence note in warning, no-warning case, I/O error; remove success/persistence/not-blocked/unrecognized day/preserves others; list empty state/heading/sort order.

- **`ConfigShowDowBlockdayTest`** — 11 tests: DOW heading always present, `(none)` when empty, configured windows shown with times, sorted Mon→Sun; blocked-days heading, `(none)`, day names, sorted; both sections together when empty, both populated, coexistence with existing four config fields.

- **`SchedulerServiceDowTest`** — 10 tests using `estimateAvailableSlots()` (no CP-SAT invocation): blocked day → 0 slots, blocked day in multi-day season reduces count by exactly one day, all 7 days blocked → 0; `FieldDateOverride` rescues slots on a blocked day and uses the override window (not global sunrise/sunset); DOW window narrows slot count to configured hours, other-day window doesn't affect the tested day, window applied consistently across all matching days; full precedence chain (override beats DOW window, DOW window beats global).

- **`LeagueStoreTest`** — 2 new migration tests: v4 file with no `dowWindows`/`blockedDays` keys migrates to v5 with empty lists; migrated file is written back so a second load reads v5 without re-migrating.

---

## [0.6.1] — Schedule Lifecycle, Viewing, Export & Backward Compatibility

**PRD:** `features/2026-05-17-league-planner-core-scheduling-v2.md`  
**Spec:** `specs/2026-05-24-schedule-lifecycle-view-export-migration.md`

Closes out the v2 scheduling PRD with the remaining four sections: schedule lifecycle (finalize + game override/edit), schedule viewing (tabular `planr schedule view` with division/team filters), schedule export (`planr schedule export` JSON to stdout), and backward compatibility (v3→v4 schema migration that drops legacy field availability windows).

### Added

- **`planr schedule finalize`** — Promotes a Draft schedule to Finalized (irreversible). Prints a warning and requires the user to type `yes` at the prompt before writing. Exit code `1` if not in DRAFT state; exit code `1` if the user types anything other than `yes`.

- **`planr schedule game override`** — Available in FINALIZED state only. Accepts any combination of `--date`, `--start`, `--field`, `--home`, `--away` to mutate a single game. Non-blocking conflict check warns to stderr when the new time/field overlaps another game (15-minute buffer) but saves regardless. No constraint re-validation is performed.

- **`planr schedule game edit`** — Available in TEAM_SCHEDULE and DRAFT states. `--home <team>` or `--away <team>` swaps home/away for a given game ID. The `game edit` command is blocked in FINALIZED state (use `game override` instead).

- **`planr schedule view`** — Renders a tabular schedule to stdout. Routes automatically: shows the matchup table if state is TEAM_SCHEDULE, shows the full field/time schedule if state is DRAFT or FINALIZED. Optional `--division` and `--team` filters (case-insensitive). The current round or date range is shown in the header. Games where the filtered team is home are marked with `*` in the position column.

- **`planr schedule export`** — Serializes the schedule to a JSON array on stdout; game count annotation goes to stderr (redirectable with `>`). Routes automatically to team schedule format in TEAM_SCHEDULE state. Full schedule format includes `date`, `start_time`, `field_name`, `home_team`, `away_team`, `division_name`, and `status`. Team schedule format includes `game_number` (integer), `home_team`, `away_team`, and `division_name`; the `--team-schedule` flag forces this path in DRAFT/FINALIZED state. Export is blocked in FINALIZED state when `--team-schedule` is passed.

- **`ScheduleState` enum** — `NONE`, `TEAM_SCHEDULE`, `DRAFT`, `FINALIZED`. Derived from `(league.teamSchedule(), league.schedule())` nullability via `ScheduleState.of(league)`; never persisted.

- **`ScheduleStatus` enum** — `DRAFT`, `FINALIZED`. Persisted inside `Schedule.status`.

### Changed

- **Schema v3→v4 migration** — `LeagueStore.load()` now migrates v3 files: per-field `availabilityWindows` keys are dropped (silently ignored by Jackson), `Field.blocks` and `Field.dateOverrides` are initialized to empty lists, and `LeagueConfig.empty()` is added if absent. A one-time stderr warning ("Field availability windows…") is printed on the first load of a pre-v4 file; subsequent loads of the now-v4 file produce no warning. The migrated file is written back to disk immediately after migration.

- **`planr schedule assign`** — Blocked in FINALIZED state (exit code `1`).

### Tests

- **`ScheduleCommandTest`** — 20 new tests across `GameEdit`, `Assign`, `Finalize`, `View`, `Export`, and `GameOverride` nested classes:
  - `GameEdit`: fails when no team schedule exists, fails in FINALIZED state, fails when team not in game, no-op when team already home, swaps home/away in DRAFT, exits on corrupted data, succeeds in DRAFT state, DRAFT status preserved after edit
  - `Assign`: fails when schedule is FINALIZED
  - `Finalize`: prints irreversibility warning before the confirmation prompt
  - `View`: shows FINALIZED status in header, shows TEAM_SCHEDULE status in header, renders matchup table with `--team-schedule` flag in FINALIZED state, division filter is case-insensitive, team filter is case-insensitive
  - `Export`: auto-exports team schedule in TEAM_SCHEDULE state, team schedule JSON omits date/time/field, `game_number` is a numeric integer, stderr annotation mentions "team schedule"
  - `GameOverride`: succeeds with out-of-season date (no constraint re-validation), multiple option overrides in one call succeed

- **`LeagueStoreTest`** — 4 new migration and warning tests:
  - `load_migratesV3ToV4ClearingFieldCollections`: v3 JSON with `availabilityWindows` → v4 with empty `blocks` and `dateOverrides`
  - `load_printsMigrationWarningToStderrOnPreV4File`: stderr contains "Field availability windows" on first v3 load
  - `load_noPrintWarningForNativeV4File`: natively-created v4 file produces no migration warning
  - `load_doesNotPrintWarningOnSubsequentLoadOfV4File`: second load of an already-migrated v4 file produces no warning

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
