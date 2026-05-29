# Test Plan — planr

## Overview

`planr` is a Java 25 CLI for little-league schedule management. All state lives in a single JSON file. The test suite validates two distinct concerns: (1) **business logic** in model types and scheduler services, and (2) **command behavior** — the full input/output/exit-code contract of every CLI command.

**Total: 772 test methods across 25 test classes.**

---

## Test Infrastructure

### Test isolation

All command tests extend `CommandTestBase`, which:
- Redirects `System.out` and `System.err` to captured streams before each test
- Sets `user.home` to `build/test-home` so all file I/O goes to `build/test-home/.planr/`
- Wipes `build/test-home/.planr/` before and after every test method

Tests run **serially** (`maxParallelForks = 1`) because all tests share one file path.

### Running tests

```bash
# Full suite
gradle test

# Single class
gradle test --tests "org.leagueplan.planr.command.DivisionCommandTest"

# Single method
gradle test --tests "org.leagueplan.planr.command.DivisionCommandTest.Add.success"
```

### Test helpers

- `execute(String... args)` — runs picocli dispatch, returns exit code
- `stdout()` / `stderr()` — captured output from the last `execute()` call
- `corruptLeagueFile()` — writes invalid JSON to trigger I/O error paths (exit 2)

---

## Test Categories

### Unit tests — model layer

Tests for Java records that represent core domain entities.

| File | Tests | What it covers |
|---|---|---|
| `LeagueTest.java` | 14 | `League` compact constructor (null normalization), migration version assertion, `withX(...)` builder round-trips |
| `DivisionTest.java` | 13 | `Division` record construction, `practiceCount`/`practiceDurationMinutes`/`practiceStart`/`practiceEnd` field accessors |
| `LeagueConfigTest.java` | 25 | `LeagueConfig` defaults, null normalization, DOW window and blocked-day builders, `withFieldBufferMinutes`/`withGridMinutes` — value, null sentinel, immutability, field threading |

### Unit tests — scheduler layer

Tests for stateless service classes that contain algorithm logic.

| File | Tests | What it covers |
|---|---|---|
| `PlayoffBracketServiceTest.java` | 88 | `generateBracket()` for all N in [2,16]: no crash, exactly 1 conditional, bye count = `nextPowerOfTwo(N) - N`, unique game IDs; per-N slot count assertions; `toPlayoffGame()` field preservation; determinism |
| `TeamScheduleServiceTest.java` | 20 | Circle-method round-robin, fill-round generation, home/away balance, `TeamScheduleResult` sealed type |
| `SchedulerServiceTest.java` | 36 | CP-SAT field/time assignment, `estimateAvailableSlots()`, multi-division solves, constraint satisfaction (field non-overlap uses configured buffer constant) |
| `SchedulerServiceDowTest.java` | 10 | Day-of-week availability and blocked-day constraints in `estimateAvailableSlots()` |
| `SchedulerServiceBufferGridTest.java` | 23 | Configurable `fieldBufferMinutes` and `gridMinutes` in `estimateAvailableSlots()` and `assign()` — see detail below |
| `DayParserTest.java` | 29 | `"Mon"/"Monday"/"MONDAY"` parsing, invalid input, full-week vs. single-day parsing |

#### `SchedulerServiceBufferGridTest` detail

| Nested class | Tests | What it covers |
|---|---|---|
| `FieldBuffer` | 7 | null = default 0; buffer=0 and buffer=30 slot counts match formula; exact boundary (duration + buffer == window); oversized buffer → 0 slots; multi-day consistency |
| `GridMinutes` | 8 | null = default 30; grid=30/15/60/1 slot counts match formula; coarser grid → fewer slots; multi-day consistency |
| `BufferAndGridCombined` | 3 | combined formula (buffer=30 + grid=60); 2-hour window + buffer=60 → 1 slot; 2-hour window + buffer=0 → 3 slots |
| `AssignFieldNonOverlap` | 2 | buffer=0 allows two 60-min games back-to-back (solver); buffer=60 prevents second game in same 2-hour window (solver) |
| `AssignGridAlignment` | 3 | grid=60 → all start times on the hour; grid=30 → :00/:30 only; grid=15 → :15/:45 permitted (solver) |

### Store tests

| File | Tests | What it covers |
|---|---|---|
| `LeagueStoreTest.java` | 19 | Atomic write (`ATOMIC_MOVE`), schema migration v1→v9, round-trip serialization of all field types, corrupt-file error handling, `LocalTime` serialized as `"HH:mm"`, `DayOfWeek` as string |

### Command tests — CLI end-to-end

Each command test class runs the full picocli dispatch stack, reads/writes real files, and asserts on stdout, stderr, and exit codes.

| File | Tests | Commands covered |
|---|---|---|
| `DivisionCommandTest.java` | 36 | `division add/edit/list/remove` — name uniqueness, duration/target validation, case-insensitive lookup |
| `DivisionCommandPracticeTest.java` | 19 | `division edit --practice-*` — count, duration, start, end; date validation; cross-field validation; `division list` practice columns |
| `TeamCommandTest.java` | 27 | `team add/edit/list/remove` — division scoping, name uniqueness per division |
| `FieldCommandTest.java` | 33 | `field add/edit/list/remove` — name uniqueness, field-level properties |
| `FieldBlockCommandTest.java` | 28 | `field block add/edit/list/delete` — block periods, overlap detection |
| `FieldOverrideCommandTest.java` | 28 | `field override add/edit/list/delete` — date-specific overrides |
| `FieldLockCommandTest.java` | 27 | `field lock add/list/delete` — division-to-field locking for CP-SAT |
| `ConfigCommandTest.java` | 56 | `config set/show` — season start/end, sunrise/sunset, rest days, max games per week, `--field-buffer-minutes` (valid: 0/positive; invalid: negative/non-integer; persistence), `--grid-minutes` (valid divisors of 60; invalid: 0/negative/non-divisor/non-integer; persistence), `config show` default labels and explicit values for both new fields |
| `ConfigDowCommandTest.java` | 24 | `config dow` — day-of-week scheduling availability |
| `ConfigBlockdayCommandTest.java` | 19 | `config blockday add/list/delete` — league-wide date blocks |
| `ConfigShowDowBlockdayTest.java` | 11 | `config show` output for dow and blockday configuration |
| `ScheduleCommandTest.java` | 98 | `schedule generate/assign/status/clear` — full scheduling lifecycle, phase 1 matchup table, phase 2 CP-SAT assignment, per-division summaries |
| `ScheduleCommandStatsTest.java` | 23 | `schedule stats` — per-team game counts, home/away balance display |
| `PlayoffCommandTest.java` | 36 | `playoff generate/status/clear` — bracket generation for all N in [2,16], BYE display, conditional marker, lifecycle, two-division independence |
| `PracticeCommandTest.java` | 34 | `practice generate/view/clear` — per-division practice slots, skip logic, NOT_CONFIGURED/NOT_STARTED/GENERATED states, confirmation cancel/accept; sort-order: unassigned by team name, assigned before unassigned, assigned by date ASC, same-date by time ASC |

---

## Coverage by Feature Area

| Feature | Command tests | Unit/service tests | Notes |
|---|---|---|---|
| Division CRUD | DivisionCommandTest, DivisionCommandPracticeTest | DivisionTest | Full coverage |
| Team CRUD | TeamCommandTest | — | Full coverage |
| Field CRUD + blocks/overrides/locks | FieldCommandTest, FieldBlockCommandTest, FieldOverrideCommandTest, FieldLockCommandTest | — | Full coverage |
| Config (season, DOW, blockdays) | ConfigCommandTest, ConfigDowCommandTest, ConfigBlockdayCommandTest, ConfigShowDowBlockdayTest | LeagueConfigTest | Full coverage |
| Config (field buffer, grid interval) | ConfigCommandTest | LeagueConfigTest, SchedulerServiceBufferGridTest | Full coverage — set/validate/show/persist for both options; solver behavior verified |
| Schedule generation (phase 1) | ScheduleCommandTest | TeamScheduleServiceTest | Full coverage |
| Schedule assignment (phase 2, CP-SAT) | ScheduleCommandTest | SchedulerServiceTest, SchedulerServiceDowTest, SchedulerServiceBufferGridTest | Core paths covered; see gaps |
| Schedule stats | ScheduleCommandStatsTest | — | Full coverage |
| Playoff bracket | PlayoffCommandTest | PlayoffBracketServiceTest | Full coverage of structure invariants |
| Practice scheduling | PracticeCommandTest, DivisionCommandPracticeTest | — | generate/view/clear covered; assign excluded |
| Persistence / migration | — | LeagueStoreTest, LeagueTest | Schema versions v1–v9 tested |

---

## Known Gaps

### `practice assign` and `playoff assign` (CP-SAT + stdin)

Both commands involve an interactive confirmation prompt followed by a CP-SAT solve. They are excluded from all command tests. The CP-SAT assignment path is covered indirectly by `SchedulerServiceTest` (unit) and `ScheduleCommandTest` (end-to-end for `schedule assign`). Practice and playoff assignment uses the same solver; only the fixture/slot setup differs.

**Risk:** If `PracticeCommand.Assign` or `PlayoffCommand.Assign` have command-wiring bugs (wrong parent reference, wrong store call), they will not be caught.

### W-bracket Final loser drop (structural bracket limitation)

`PlayoffBracketServiceTest` documents a known structural issue: the W-bracket Final loser does not enter the L bracket for most N values. This means the "2N-1 non-conditional real games" invariant stated in the spec does not hold. Actual counts are verified and tested, but the structural behavior diverges from the double-elimination ideal for N ≠ 2, 3, 7, 11, 15.

### Concurrent access

`LeagueStore` performs an atomic file swap (`Files.move` with `ATOMIC_MOVE`) but has no locking against concurrent readers. No concurrency tests exist. For the current use case (single-user CLI), this is an acceptable gap.

### Native binary

`picocli-codegen` is included in `build.gradle` for a future GraalVM native image. No tests verify the native binary. `gradle installDist` produces JVM-based launcher scripts; those are not tested either.

### CP-SAT solver timeout / infeasibility edge cases

`SchedulerServiceTest` covers some infeasibility scenarios but not timeout behavior or partial-solve degradation under extreme constraint combinations (many divisions, many fields with conflicting locks).

### `schedule game override` conflict warning with custom buffer

`ScheduleGameCommand.gamesConflict()` reads `fieldBufferMinutes` from the loaded league config and warns when an override would create a conflict. The warning path is tested manually but not covered by automated command tests (would require generating, finalizing, then overriding a game into a known conflict). The core logic is covered at the unit level by `SchedulerServiceBufferGridTest`.

---

## Test Count Summary

| Category | Files | Tests |
|---|---|---|
| Model unit tests | 3 | 52 |
| Scheduler unit tests | 6 | 206 |
| Store tests | 1 | 19 |
| Command end-to-end tests | 15 | 499 |
| **Total** | **25** | **776** |
