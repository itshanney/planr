# Test Plan — planr

## Overview

`planr` is a Java 25 CLI for little-league schedule management. All state lives in a single JSON file. The test suite validates two distinct concerns: (1) **business logic** in model types and scheduler services, and (2) **command behavior** — the full input/output/exit-code contract of every CLI command.

**Total: 936 test invocations across 31 test classes.**

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
| `LeagueTest.java` | 14 | `League` compact constructor (null normalization), version assertion (`CURRENT_VERSION = 10`), `withX(...)` builder round-trips |
| `DivisionTest.java` | 13 | `Division` record construction, practice field accessors, `withTeam*` builder methods |
| `LeagueConfigTest.java` | 25 | `LeagueConfig` defaults, null normalization, DOW window and blocked-day builders, `withFieldBufferMinutes`/`withGridMinutes` — value, null sentinel, immutability, field threading |

### Unit tests — scheduler layer

Tests for stateless service classes that contain algorithm logic.

| File | Tests | What it covers |
|---|---|---|
| `PlayoffBracketServiceTest.java` | 88 | `generateBracket()` for all N in [2,16]: no crash, exactly 1 conditional, bye count = `nextPowerOfTwo(N) - N`, unique game IDs; per-N slot count assertions; `toPlayoffGame()` field preservation; determinism |
| `TeamScheduleServiceTest.java` | 36 | Multi-cycle circle-method round-robin; head-to-head balance invariant (each pair meets floor(T/(N-1)) or floor+1 times); home/away reversal across cycles (globalRound property); partial-cycle generation; cycle log format and cumulative game-count content; odd-N coverage (N=3, N=5); game UUID uniqueness |
| `SchedulerServiceTest.java` | 36 | CP-SAT field/time assignment, `estimateAvailableSlots()`, multi-division solves, constraint satisfaction (field non-overlap, rest days, week cap, division locking, pinning) |
| `SchedulerServiceDowTest.java` | 10 | Day-of-week availability and blocked-day constraints in `estimateAvailableSlots()` |
| `SchedulerServiceBufferGridTest.java` | 23 | Configurable `fieldBufferMinutes` and `gridMinutes` in `estimateAvailableSlots()` and `assign()` — see detail below |
| `SchedulerServiceCurfewTest.java` | 14 | Division curfew time enforcement in `estimateAvailableSlots()`, pre-solve zero-slot hard fail, full `assign()` boundary behavior — see detail below |
| `SchedulerServiceFieldPriorityTest.java` | 15 | Playoff field priority in `assignPlayoffs()`: `PlayoffFieldSummary` content, ranked-field preference, fallback to unranked, equal-rank equivalence, regular-season unchanged — see detail below |

#### `SchedulerServiceBufferGridTest` detail

| Nested class | Tests | What it covers |
|---|---|---|
| `FieldBuffer` | 7 | null = default 0; buffer=0 and buffer=30 slot counts match formula; exact boundary (duration + buffer == window); oversized buffer → 0 slots; multi-day consistency |
| `GridMinutes` | 8 | null = default 30; grid=30/15/60/1 slot counts match formula; coarser grid → fewer slots; multi-day consistency |
| `BufferAndGridCombined` | 3 | combined formula (buffer=30 + grid=60); 2-hour window + buffer=60 → 1 slot; 2-hour window + buffer=0 → 3 slots |
| `AssignFieldNonOverlap` | 2 | buffer=0 allows two 60-min games back-to-back (solver); buffer=60 prevents second game in same 2-hour window (solver) |
| `AssignGridAlignment` | 3 | grid=60 → all start times on the hour; grid=30 → :00/:30 only; grid=15 → :15/:45 permitted (solver) |

#### `SchedulerServiceCurfewTest` detail

| Nested class | Tests | What it covers |
|---|---|---|
| `EstimateAvailableSlots` | 8 | null curfew = no change; curfew exactly at last valid start time includes that slot (AC 6 boundary); one minute before excludes it; curfew reduces count vs. no curfew; exact slot count formula for curfew=13:00; curfew before window open → 0 slots; curfew at window open → 1 slot; per-division independence |
| `AssignCurfewHardFail` | 3 | Midnight curfew eliminates all slots → `ScheduleResult.Failure` naming the division; failure message includes the curfew time; unconstrained division does not trigger curfew error path |
| `AssignWithActiveCurfew` | 3 | No assigned game starts after curfew (full CP-SAT run); game starting exactly at curfew is assigned (inclusive boundary); uncurfewed division in same solve is not constrained by another division's curfew |

#### `SchedulerServiceFieldPriorityTest` detail

| Nested class | Tests | What it covers |
|---|---|---|
| `FieldSummaryPresence` | 5 | Non-empty summary when games assigned; field name and count; null priority for unranked field; configured priority value for ranked field; summary sorted by rank ascending, nulls last |
| `RankedFieldPreferred` | 3 | All games go to ranked field when it has capacity (AC 17); unranked field absent from summary; rank 1 preferred over rank 2 |
| `FallbackToUnranked` | 2 | Games on unranked field when ranked is at capacity (AC 18); solver succeeds (no Failure) during fallback |
| `EqualRankTreatment` | 2 | Two rank-1 fields used without error (AC 20); unranked field absent from summary when two equal-rank fields have capacity |
| `NoRankedFields` | 2 | Solver succeeds when all fields unranked; field summary still populated |
| `RegularSeasonUnaffected` | 1 | `assign()` game count unchanged whether or not fields have playoff priority (AC 21) |

### Store tests

| File | Tests | What it covers |
|---|---|---|
| `LeagueStoreTest.java` | 19 | Atomic write (`ATOMIC_MOVE`), schema migration v1→v10, round-trip serialization of all field types, corrupt-file error handling, `LocalTime` serialized as `"HH:mm"`, `DayOfWeek` as string |

### Command tests — CLI end-to-end

Each command test class runs the full picocli dispatch stack, reads/writes real files, and asserts on stdout, stderr, and exit codes.

| File | Tests | Commands covered |
|---|---|---|
| `DivisionCommandTest.java` | 36 | `division add/edit/list/delete` — name uniqueness, duration/target validation, case-insensitive lookup |
| `DivisionCommandPracticeTest.java` | 19 | `division edit --practice-*` — count, duration, start, end; date validation; cross-field validation; `division list` practice columns |
| `DivisionCommandCurfewTest.java` | 17 | `division edit --curfew-time/--no-curfew-time` — time format validation (STRICT mode, rejects 24:00 and seconds), mutual exclusion, persistence, `division list` CURFEW column, no-args guard |
| `TeamCommandTest.java` | 27 | `team add/edit/list/delete` — division scoping, name uniqueness per division |
| `FieldCommandTest.java` | 33 | `field add/edit/list/delete` — name uniqueness, address, case-insensitive lookup |
| `FieldCommandPlayoffPriorityTest.java` | 15 | `field edit --playoff-priority/--no-playoff-priority` — positive-integer validation, mutual exclusion, persistence, `field list` PLAYOFF_PRI column, no-args guard |
| `FieldBlockCommandTest.java` | 28 | `field block add/edit/list/delete` — block periods, overlap detection |
| `FieldOverrideCommandTest.java` | 28 | `field override add/edit/list/delete` — date-specific overrides |
| `FieldLockCommandTest.java` | 27 | `field lock add/list/delete` — division-to-field locking for CP-SAT |
| `ConfigCommandTest.java` | 56 | `config set/show` — season start/end, sunrise/sunset, rest days, max games per week, `--field-buffer-minutes` and `--grid-minutes` (valid ranges, invalid inputs, persistence, `config show` display) |
| `ConfigDowCommandTest.java` | 24 | `config dow add/list/delete` — day-of-week scheduling availability |
| `ConfigBlockdayCommandTest.java` | 19 | `config blockday add/list/delete` — league-wide date blocks |
| `ConfigShowDowBlockdayTest.java` | 11 | `config show` output for dow and blockday configuration |
| `ScheduleCommandTest.java` | 98 | `schedule generate/assign/status/clear` — full scheduling lifecycle, phase 1 matchup table, phase 2 CP-SAT assignment, per-division summaries |
| `ScheduleCommandStatsTest.java` | 23 | `schedule stats` — per-team game counts, home/away balance display |
| `PlayoffCommandTest.java` | 36 | `playoff generate/status/clear` — bracket generation for all N in [2,16], BYE display, conditional marker, lifecycle, two-division independence |
| `PracticeCommandTest.java` | 37 | `practice generate/view/clear` — per-division practice slots, skip logic, NOT_CONFIGURED/NOT_STARTED/GENERATED states; sort-order: unassigned by team name, alpha order, within-team date ASC |
| `CalendarCommandTest.java` | 39 | `calendar` — argument validation (exclusive flags, month format), entity validation, weekly/monthly views, filter by division/team/field, no-events error |
| `CalendarRendererTest.java` | 41 | `CalendarRenderer` unit tests — weekly view layout, monthly grid structure, monthly event listing, `toSundayOnOrBefore` helper, `formatEventLine` helper |
| `DayParserTest.java` | 29 | `DayParser` — `"Mon"/"Monday"/"MONDAY"` parsing, invalid input, full-week vs. single-day parsing |

---

## Coverage by Feature Area

| Feature | Command tests | Unit/service tests | Notes |
|---|---|---|---|
| Division CRUD | DivisionCommandTest, DivisionCommandPracticeTest | DivisionTest | Full coverage |
| Division curfew time | DivisionCommandCurfewTest | SchedulerServiceCurfewTest | Full coverage — CLI options, validation, STRICT time parsing, slot filtering, hard fail, boundary behavior |
| Team CRUD | TeamCommandTest | — | Full coverage |
| Field CRUD + blocks/overrides/locks | FieldCommandTest, FieldBlockCommandTest, FieldOverrideCommandTest, FieldLockCommandTest | — | Full coverage |
| Field playoff priority | FieldCommandPlayoffPriorityTest | SchedulerServiceFieldPriorityTest | Full coverage — CLI options, validation, solver preference, fallback, field summary output |
| Config (season, DOW, blockdays) | ConfigCommandTest, ConfigDowCommandTest, ConfigBlockdayCommandTest, ConfigShowDowBlockdayTest | LeagueConfigTest | Full coverage |
| Config (field buffer, grid interval) | ConfigCommandTest | LeagueConfigTest, SchedulerServiceBufferGridTest | Full coverage — set/validate/show/persist; solver behavior verified |
| Schedule generation (phase 1) | ScheduleCommandTest | TeamScheduleServiceTest | Full coverage |
| Schedule assignment (phase 2, CP-SAT) | ScheduleCommandTest | SchedulerServiceTest, SchedulerServiceDowTest, SchedulerServiceBufferGridTest, SchedulerServiceCurfewTest | Core paths covered; see gaps |
| Schedule stats | ScheduleCommandStatsTest | — | Full coverage |
| Playoff bracket structure | PlayoffCommandTest | PlayoffBracketServiceTest | Full coverage of structure invariants |
| Playoff field assignment (CP-SAT) | — | SchedulerServiceFieldPriorityTest | Service-level only; command-level assign excluded (stdin + CP-SAT) |
| Practice scheduling | PracticeCommandTest, DivisionCommandPracticeTest | — | generate/view/clear covered; assign excluded |
| Calendar view | CalendarCommandTest, CalendarRendererTest | — | Full coverage of argument validation, view modes, renderer logic |
| Persistence / migration | — | LeagueStoreTest, LeagueTest | Schema versions v1→v10 tested |

---

## Known Gaps

### `practice assign` and `playoff assign` (CP-SAT + stdin, command level)

Both commands involve an interactive confirmation prompt followed by a CP-SAT solve. They are excluded from command tests. The CP-SAT assignment path for **regular-season** is covered end-to-end by `ScheduleCommandTest`. The **playoff** CP-SAT path is covered at the service level by `SchedulerServiceFieldPriorityTest` (which calls `assignPlayoffs()` directly). The **practice** CP-SAT path is covered by the same solver code exercised in `SchedulerServiceTest`.

**Risk:** If `PracticeCommand.Assign` or `PlayoffCommand.Assign` have command-wiring bugs (wrong parent reference, wrong store call), they will not be caught. The field utilization table printed by `PlayoffCommand.Assign` is verified at the service level (field summaries in `SchedulerServiceFieldPriorityTest`) but not through the command output path.

### Practice curfew (service level)

`SchedulerServiceCurfewTest` covers the regular-season `assign()` path. The practice curfew filter uses the same `divisionCurfew()` helper and the same `break` in `enumeratePracticeSlots()`, but no dedicated service-level test exercises `assignPractices()` with a curfew active. The zero-slot guard for practice is also untested.

### W-bracket Final loser drop (structural bracket limitation)

`PlayoffBracketServiceTest` documents a known structural issue: the W-bracket Final loser does not enter the L bracket for most N values. The "2N-1 non-conditional real games" invariant stated in the spec does not hold for most bracket sizes. Actual counts are verified and tested, but the structural behavior diverges from the double-elimination ideal for N ≠ 2, 3, 7, 11, 15.

### Concurrent access

`LeagueStore` performs an atomic file swap (`Files.move` with `ATOMIC_MOVE`) but has no locking against concurrent readers. No concurrency tests exist. For the current use case (single-user CLI), this is an acceptable gap.

### Native binary

`picocli-codegen` is included for a future GraalVM native image. No tests verify the native binary.

### CP-SAT solver timeout / infeasibility edge cases

`SchedulerServiceTest` covers some infeasibility scenarios but not timeout behavior or partial-solve degradation under extreme constraint combinations (many divisions, many fields with conflicting locks).

### `schedule game override` conflict warning with custom buffer

`ScheduleGameCommand.gamesConflict()` reads `fieldBufferMinutes` from the loaded league config. The warning path is tested manually but not covered by automated command tests (would require generating, finalizing, then overriding a game into a known conflict).

---

## Test Count Summary

| Category | Files | Tests |
|---|---|---|
| Model unit tests | 3 | 52 |
| Scheduler unit tests | 7 | 222 |
| Store tests | 1 | 19 |
| Command end-to-end tests | 20 | 643 |
| **Total** | **31** | **936** |
