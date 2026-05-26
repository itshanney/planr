# Test Plan — planr

## Overview

`planr` is a Java 25 CLI for little-league schedule management. All state lives in a single JSON file. The test suite validates two distinct concerns: (1) **business logic** in model types and scheduler services, and (2) **command behavior** — the full input/output/exit-code contract of every CLI command.

**Total: ~750 test methods across 23 test classes.**

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
| `LeagueConfigTest.java` | 16 | `LeagueConfig` defaults, day-of-week availability, field lock constraints |

### Unit tests — scheduler layer

Tests for stateless service classes that contain algorithm logic.

| File | Tests | What it covers |
|---|---|---|
| `PlayoffBracketServiceTest.java` | 29 | `generateBracket()` for all N in [2,16]: no crash, exactly 1 conditional, bye count = `nextPowerOfTwo(N) - N`, unique game IDs; per-N slot count assertions; `toPlayoffGame()` field preservation; determinism |
| `TeamScheduleServiceTest.java` | 20 | Circle-method round-robin, fill-round generation, home/away balance, `TeamScheduleResult` sealed type |
| `SchedulerServiceTest.java` | 36 | CP-SAT field/time assignment, `estimateAvailableSlots()`, multi-division solves, constraint satisfaction |
| `SchedulerServiceDowTest.java` | 10 | Day-of-week availability constraints in CP-SAT model |
| `DayParserTest.java` | 29 | `"Mon"/"Monday"/"MONDAY"` parsing, invalid input, full-week vs. single-day parsing |

### Store tests

| File | Tests | What it covers |
|---|---|---|
| `LeagueStoreTest.java` | 19 | Atomic write (`ATOMIC_MOVE`), schema migration v1→v8, round-trip serialization of all field types, corrupt-file error handling, `LocalTime` serialized as `"HH:mm"`, `DayOfWeek` as string |

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
| `ConfigCommandTest.java` | 31 | `config set/show` — season start/end, sunrise/sunset, rest days, max games per week |
| `ConfigDowCommandTest.java` | 24 | `config dow` — day-of-week scheduling availability |
| `ConfigBlockdayCommandTest.java` | 19 | `config blockday add/list/delete` — league-wide date blocks |
| `ConfigShowDowBlockdayTest.java` | 11 | `config show` output for dow and blockday configuration |
| `ScheduleCommandTest.java` | 98 | `schedule generate/assign/status/clear` — full scheduling lifecycle, phase 1 matchup table, phase 2 CP-SAT assignment, per-division summaries |
| `ScheduleCommandStatsTest.java` | 23 | `schedule stats` — per-team game counts, home/away balance display |
| `PlayoffCommandTest.java` | 36 | `playoff generate/status/clear` — bracket generation for all N in [2,16], BYE display, conditional marker, lifecycle, two-division independence |
| `PracticeCommandTest.java` | 30 | `practice generate/status/clear` — per-division practice slots, skip logic, NOT_CONFIGURED/NOT_STARTED/GENERATED states, confirmation cancel/accept |

---

## Coverage by Feature Area

| Feature | Command tests | Unit/service tests | Notes |
|---|---|---|---|
| Division CRUD | DivisionCommandTest, DivisionCommandPracticeTest | DivisionTest | Full coverage |
| Team CRUD | TeamCommandTest | — | Full coverage |
| Field CRUD + blocks/overrides/locks | FieldCommandTest, FieldBlockCommandTest, FieldOverrideCommandTest, FieldLockCommandTest | — | Full coverage |
| Config (season, DOW, blockdays) | ConfigCommandTest, ConfigDowCommandTest, ConfigBlockdayCommandTest, ConfigShowDowBlockdayTest | LeagueConfigTest | Full coverage |
| Schedule generation (phase 1) | ScheduleCommandTest | TeamScheduleServiceTest | Full coverage |
| Schedule assignment (phase 2, CP-SAT) | ScheduleCommandTest | SchedulerServiceTest, SchedulerServiceDowTest | Core paths covered; see gaps |
| Schedule stats | ScheduleCommandStatsTest | — | Full coverage |
| Playoff bracket | PlayoffCommandTest | PlayoffBracketServiceTest | Full coverage of structure invariants |
| Practice scheduling | PracticeCommandTest, DivisionCommandPracticeTest | — | generate/status/clear covered; assign excluded |
| Persistence / migration | — | LeagueStoreTest, LeagueTest | All 8 schema versions tested |

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

---

## Test Count Summary

| Category | Files | Approx. tests |
|---|---|---|
| Model unit tests | 3 | 43 |
| Scheduler unit tests | 5 | 124 |
| Store tests | 1 | 19 |
| Command end-to-end tests | 15 | ~570 |
| **Total** | **24** | **~756** |
