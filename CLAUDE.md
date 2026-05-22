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

Picocli routes from `PlanrApp` to top-level command classes (`ConfigCommand`, `DivisionCommand`, `TeamCommand`, `FieldCommand`, `ScheduleCommand`), each of which declares its CRUD operations as static inner classes. `FieldBlockCommand` and `FieldOverrideCommand` are second-level nested subcommands registered under `FieldCommand`, making the invocations `planr field block <add|edit|delete|list>` and `planr field override <add|edit|delete|list>`. `ScheduleGameCommand` is similarly registered under `ScheduleCommand` for `planr schedule game <edit|override>`.

Commands access the store by traversing `@ParentCommand` references:
- Top-level commands: `parent.app.store`
- `FieldBlockCommand` and `FieldOverrideCommand` inner classes: `parent.fieldCmd.app.store`
- `ScheduleGameCommand` inner classes: `parent.scheduleCmd.app.store`

### Immutable model + store pattern

All model types (`League`, `Division`, `Team`, `Field`, `FieldBlock`, `FieldDateOverride`, `LeagueConfig`, `TeamSchedule`, `TeamGame`, `Schedule`, `ScheduledGame`, `ScheduleState`) are Java records. Mutations return new record instances — nothing is mutated in place. `LeagueStore` is the only layer that reads from or writes to disk. Every mutating operation goes:

1. `store.load()` → deserialize `league.json` into `League` record
2. Build a new `League` via `withX(...)` helper methods on the model records
3. `store.save(league)` → write to `.tmp` then `Files.move(ATOMIC_MOVE)`

### JSON persistence

`LeagueStore` owns the `ObjectMapper` configuration (initialized in its constructor):
- `JavaTimeModule` registered for `DayOfWeek` string serialization
- A `SimpleModule` registered after (wins over `JavaTimeModule`) to serialize `LocalTime` as `"HH:mm"` instead of the jsr310 default `"HH:mm:ss"`
- `WRITE_DATES_AS_TIMESTAMPS` disabled
- `FAIL_ON_UNKNOWN_PROPERTIES` disabled (forward-compatibility for future schema versions)

**Schema versioning:** The `League` record has a `version` field. Current version is `4`. `LeagueStore.load()` applies migrations in sequence: v1→adds empty `fields` list; v2→no-op marker; v3→drops field availability windows (replaced by the league-wide sunrise/sunset config and per-field blocks/overrides), printing a stderr warning. The compact constructor on `League` normalizes null `divisions`/`fields` to `List.of()` so old files without those keys deserialize safely.

### Exit codes and output conventions

All commands: `stdout` on success (one line), `stderr` on error (one line). Exit `0` = success, `1` = validation error, `2` = I/O error. Field and division names are matched case-insensitively throughout.

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
