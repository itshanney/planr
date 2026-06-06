# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

`planr` is a Java 25 CLI tool for little league schedule management. It is a CLI prototype that validates the data model and business rules before a web application is built. All state lives in a single JSON file at `~/.planr/league.json`.

## Commands

There is no `./gradlew` wrapper — use `gradle` directly (requires Gradle 9.4.1 and JDK 25 on PATH).

```bash
gradle compileJava          # compile only
gradle test                 # run all tests
gradle assemble             # build JAR and distribution scripts
gradle installDist          # produce runnable scripts at build/install/planr/bin/planr
gradle run --args="division list"   # run the CLI via Gradle
```

**Run a single test class:**
```bash
gradle test --tests "org.leagueplan.planr.command.DivisionCommandTest"
```

**Run a single test method:**
```bash
gradle test --tests "org.leagueplan.planr.command.DivisionCommandTest.Add.success"
```

**Invoke the installed binary directly (after `installDist`):**
```bash
./build/install/planr/bin/planr division list
./build/install/planr/bin/planr schedule status
```

## Architecture

### Command dispatch chain

Picocli routes from `PlanrApp` to top-level command classes (`ConfigCommand`, `DivisionCommand`, `TeamCommand`, `FieldCommand`, `ScheduleCommand`, `PlayoffCommand`, `PracticeCommand`), each of which declares its CRUD operations as static inner classes. Several second-level nested subcommands are registered under their parents:

- `FieldBlockCommand` and `FieldOverrideCommand` under `FieldCommand` → `planr field block <add|edit|delete|list>` and `planr field override <add|edit|delete|list>`
- `FieldLockCommand` under `FieldCommand` → `planr field lock <add|delete|list>`
- `ScheduleGameCommand` under `ScheduleCommand` → `planr schedule game <edit|override>`
- `ConfigDowCommand` under `ConfigCommand` → `planr config dow <add|delete|list>`
- `ConfigBlockdayCommand` under `ConfigCommand` → `planr config blockday <add|delete|list>`

Commands access the store by traversing `@ParentCommand` references:
- Top-level commands (`PlayoffCommand`, `PracticeCommand`, etc.): `parent.app.store`
- `FieldBlockCommand`, `FieldOverrideCommand`, and `FieldLockCommand` inner classes: `parent.fieldCmd.app.store`
- `ScheduleGameCommand` inner classes: `parent.scheduleCmd.app.store`
- `ConfigDowCommand` and `ConfigBlockdayCommand` inner classes: `parent.configCmd.app.store`

### Immutable model + store pattern

All model types are Java records:

- **Core**: `League`, `Division`, `Team`, `Field`, `FieldBlock`, `FieldDateOverride`, `FieldDivisionLock`, `LeagueConfig`, `DayOfWeekWindow`
- **Regular season**: `TeamSchedule`, `TeamGame`, `Schedule`, `ScheduledGame`
- **Playoffs**: `Playoff`, `PlayoffGame`
- **Practices**: `PracticeSchedule`, `PracticeSlot`

Enums (not records): `BracketSide`, `PlayoffState`, `PracticeState`, `ScheduleState`, `ScheduleStatus`. `ScheduleState` is derived from `(league.teamSchedule(), league.schedule())` nullability via `ScheduleState.of(league)`. `PlayoffState` and `PracticeState` are derived similarly from the respective list entries.

The `League` compact constructor normalizes `null` to `List.of()` for `divisions`, `fields`, `playoffs`, and `practiceSchedules`. Mutations return new record instances — nothing is mutated in place. `LeagueStore` is the only layer that reads from or writes to disk. Every mutating operation goes:

1. `store.load()` → deserialize `league.json` into `League` record
2. Build a new `League` via `withX(...)` helper methods on the model records
3. `store.save(league)` → write to `.tmp` then `Files.move(ATOMIC_MOVE)`

### JSON persistence

`LeagueStore` owns the `ObjectMapper` configuration (initialized in its constructor):
- `JavaTimeModule` registered for `DayOfWeek` string serialization
- A `SimpleModule` registered after (wins over `JavaTimeModule`) to serialize `LocalTime` as `"HH:mm"` instead of the jsr310 default `"HH:mm:ss"`
- `WRITE_DATES_AS_TIMESTAMPS` disabled
- `FAIL_ON_UNKNOWN_PROPERTIES` disabled (forward-compatibility for future schema versions)

**Schema versioning:** The `League` record has a `version` field. Current version is `10`. `LeagueStore.load()` applies migrations in sequence:

| From | To | What changed |
|------|----|--------------|
| v1 | v2 | adds empty `fields` list |
| v2 | v3 | no-op marker |
| v3 | v4 | drops field availability windows; adds `LeagueConfig`; prints stderr warning |
| v4 | v5 | no-op — `LeagueConfig` gains `dowWindows`/`blockedDays` (compact constructor normalizes nulls) |
| v5 | v6 | no-op — `LeagueConfig` gains `maxGamesPerWeek`/`minRestDays`; `Field` gains `divisionLocks` |
| v6 | v7 | no-op — `League` gains `playoffs` list |
| v7 | v8 | no-op — `League` gains `practiceSchedules` list |
| v8 | v9 | no-op — `LeagueConfig` gains `fieldBufferMinutes`/`gridMinutes` |
| v9 | v10 | no-op — `Division` gains `curfewTime`; `Field` gains `playoffPriority` (compact constructor normalizes nulls) |

### Scheduler package

`src/main/java/org/leagueplan/planr/scheduler/` holds all scheduling logic and its supporting types. None of these are persisted to `league.json`.

- `TeamScheduleService` — Phase 1: multi-cycle circle-method round-robin (K full cycles + optional partial cycle), produces a `TeamSchedule`. Guarantees every team pair meets `floor(T/(N−1))` or `floor(T/(N−1)) + 1` times.
- `SchedulerService` — Phase 2: OR-Tools CP-SAT field/time assignment. Public methods: `assign(League)`, `assignPlayoffs(League, List<Playoff>)`, `assignPractices(League, List<PracticeSchedule>)`, `estimateAvailableSlots(League, UUID, int)`, and an overload of `enumerateAllSlots` that accepts a division filter.
- `PlayoffBracketService` — Generates double-elimination bracket slots for N teams (2 ≤ N ≤ 16). Uses power-of-2 bracket padding, seeded byes in W-R1, and positional references (`"W of G3"`, `"L of G2"`). Internal `GameRef(UUID, String prefix)` record carries the correct reference prefix for L-bracket survivors that bypass L-R1 when the real W-R1 game count is odd.
- `TeamScheduleResult` — sealed interface: `Success(schedule, cycleLogs)` or `Failure(message)`.
- `ScheduleResult` — sealed interface: `Success(games, optimal, targetMet, divisionSummaries)` or `Failure(message)`.
- `PlayoffScheduleResult` — sealed interface: `Success(games)` or `Failure(message)`.
- `PracticeScheduleResult` — sealed interface: `Success(slots)` or `Failure(message)`.
- `DivisionSummary` — record carrying per-division solve stats: `divisionName`, `gamesRequested`, `gamesAssigned`, `slotsAvailable`. Derived method `targetMet()`.
- `Fixture` — `(gameId, homeTeamId, awayTeamId, divisionId, gameDurationMinutes)` tuple used internally by the CP-SAT model for regular-season games.
- `PracticeFixture` — `(practiceId, teamId, divisionId, durationMinutes)` tuple used internally by the CP-SAT model for practice scheduling.
- `Slot` — `(date, fieldId, fieldName, startTime)` tuple used internally by the CP-SAT model.

### Exit codes and output conventions

Most commands: `stdout` on success, `stderr` on error. Exit `0` = success, `1` = validation error, `2` = I/O error. Field and division names are matched case-insensitively throughout.

**Multi-line output:** `planr schedule generate`, `planr schedule assign`, `planr playoff assign`, and `planr practice assign` emit multiple stdout lines. Phase 1 (`generate`) prints cycle logs (one line per full cycle, one for any partial cycle; suppressed when target == N−1), a matchup table, and a summary line. Phase 2 (`assign`) streams live `[M:SS]` progress lines during the solve, then a constraint summary table and a final status line. All progress output goes to `stdout`.

### Test isolation

Command tests extend `CommandTestBase`, which redirects `System.out`/`System.err` to captured streams and wipes `~/.planr/` (redirected to `build/test-home/.planr/` via `user.home` system property set in `build.gradle`) before and after every test. Tests must run serially (`maxParallelForks = 1`) because all tests share one file path. `LeagueStoreTest` mirrors the same `DATA_DIR` path to clean up between tests.

## Specs and feature docs

- `features/` — product requirements documents (PRDs) written before implementation
- `specs/` — technical design documents written before coding; one spec per feature slice

New feature work follows: PRD in `features/` → tech spec in `specs/` → implementation. Consult the relevant spec before modifying a feature area.

## Notes

- `league.json` and `league.json.bak` in the repo root are local development artifacts and should not be committed.
- The `-parameters` compiler flag is set in `build.gradle` so Jackson can match JSON property names to record constructor parameter names without `@JsonCreator` annotations.
- `picocli-codegen` annotation processor is included to generate GraalVM reflection config for a future native image build.
