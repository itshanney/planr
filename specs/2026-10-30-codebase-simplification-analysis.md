# Codebase Simplification Analysis

**Date:** 2026-10-30
**Scope:** `src/main/java` (all packages), `build.gradle`, test suite as evidence. Reviewed at `main` @ `5351384` (v0.14.1).
**Type:** Analysis only. No code was changed.

---

## 1. What this application does

`planr` is a single-user CLI for little league organizers. It stores divisions, teams, fields, and availability rules in one JSON file (`~/.planr/league.json`), then builds three kinds of schedules with the same two-phase pattern:

- **Regular season:** a round-robin matchup generator (`TeamScheduleService`) followed by an OR-Tools CP-SAT field/time assignment (`SchedulerService.assign`).
- **Practices:** slot stubs per team, then CP-SAT assignment (`assignPractices`).
- **Playoffs:** a double-elimination bracket (`PlayoffBracketService`), then CP-SAT assignment (`assignPlayoffs`).

Every mutating command does the same thing: `store.load()`, build a new immutable `League` with a `withX(...)` helper, then `store.save()` (temp file plus atomic move).

**Scale profile.** One user, one process, one small file, no network, no concurrency. A league has tens of teams and a few fields. The only CPU-heavy work is the CP-SAT solve, which is capped at 300 s. CLAUDE.md describes the CLI as a prototype that validates the data model before a web application is built. **Assumption:** the scheduling logic and model will be carried into that web app, and the command layer will not.

The scale profile justifies almost no infrastructure complexity, and the code mostly has none. The excess here is **duplication**, not over-engineering.

## 2. Shape of the code

| Metric | Value |
|---|---|
| Main source | 9,252 LOC in 52 files |
| Test source | 14,684 LOC in 32 files (≈ 1.6 : 1 test-to-main) |
| `command/` | 6,152 LOC (66 % of main) |
| `scheduler/` | 1,980 LOC |
| `model/` | 860 LOC (all records/enums) |
| `store/` | 212 LOC |
| Largest files | `SchedulerService` 1,350 · `ScheduleCommand` 1,254 · `PlayoffCommand` 657 · `DivisionCommand` 541 |
| Interfaces | 4, all sealed `Result` types with exactly two record implementations (Success/Failure). No single-implementation service interfaces, no DI container. |
| Runtime dependencies | picocli, Jackson databind + jsr310, OR-Tools. All are used substantially. |
| Tests | 673 command tests, ~98 `SchedulerService*` tests, 39 `TeamScheduleService`, 29 bracket, 19 store |

Duplication measured with a line-level sequence match (blank lines ignored):

| Pair | Lines | Identical lines |
|---|---|---|
| `buildAndSolve` vs `buildAndSolvePlayoffs` | 206 / 238 | 164 |
| `buildAndSolve` vs `buildAndSolvePractices` | 206 / 183 | 125 |
| `enumerateAllSlots` vs `enumeratePlayoffSlots` | 39 / 37 | 32 |

Repeated idioms in `command/`:

| Idiom | Occurrences |
|---|---|
| Identical `catch (IOException e)` block printing "Failed to access league data" and returning `2` | 48 (every command) |
| `Scanner(System.in)` "type yes" confirmation block | 7 |
| Hand-rolled table blocks (`"-".repeat(width)` header rules) | 64 rule cells across 6 files |
| Inline `divisions().stream().filter(d -> d.id().equals(...))` lookups by ID | 11, plus 3 for teams and 3 for fields |
| Private `parseDate` / `parseTime` copies | 6, plus inline parsing in `ConfigCommand`, `DivisionCommand`, `PlayoffCommand` |

## 3. Recommendations

The recommendations are ordered by payoff divided by risk. The first five are mechanical, low-risk wins covered by existing tests.

### R1. Handle `IOException` once, not 48 times

- **Where:** every `Callable` in `command/*.java` (48 sites); `PlanrApp.main` (`PlanrApp.java:45`); `CommandTestBase.execute` (`CommandTestBase.java:50`).
- **What's there now:** every `call()` wraps its whole body in the same handler:
  ```java
  } catch (IOException e) {
    System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
    return 2;
  }
  ```
- **Proposed simplification:** add one factory that both `main` and the tests use, with a picocli execution-exception handler that maps `IOException` to exit 2:
  ```java
  public static CommandLine commandLine() {
    return new CommandLine(new PlanrApp())
        .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
          if (!(ex instanceof IOException io)) throw ex;
          cmd.getErr().printf("Error: Failed to access league data: %s%n", io.getMessage());
          return 2;
        });
  }
  ```
  Commands then declare `call() throws IOException` and drop the `try`. The body loses one indentation level.
- **Estimated impact:** about 190 lines removed. Every command becomes easier to read.
- **Risk:** low. `exitsOnCorruptedData`-style tests already check exit 2 and the message. A Jackson parse failure is an `IOException` subclass, so it is still covered. Note that `cmd.getErr()` is picocli's writer: make sure the tests' stderr capture still sees it, or keep `System.err`.

### R2. One confirmation helper, which also fixes a crash

- **Where:** `ScheduleCommand.java:93, 238, 406`, `PlayoffCommand.java:335, 629`, and `PracticeCommand.java` (×2).
- **What's there now:** seven copies of `try (Scanner sc = new Scanner(System.in)) { input = sc.nextLine().trim(); } if (!input.equals("yes")) { … "Cancelled." … }`.
- **Bug found while checking:** if stdin is empty or closed (for example `planr schedule finalize < /dev/null` in a script), `nextLine()` throws `NoSuchElementException`. The user sees a picocli stack trace and exit 1 instead of a clean "Cancelled.".
- **Proposed simplification:** add `static boolean confirmed()` (for example in a small `Prompts` class next to `DayParser`). It returns `false` when no line is available. Each site becomes `if (!Prompts.confirmed()) { System.out.println("Cancelled."); return 0; }`. `finalize` keeps its own message ("Finalization cancelled.").
- **Estimated impact:** about 45 lines removed, and one crash fixed.
- **Risk:** low. The existing confirmation tests drive stdin through `provideStdinAndExecute`. Add one test with empty stdin.

### R3. Collapse the version-only schema migrations

- **Where:** `LeagueStore.load()`, `LeagueStore.java:≈105–200`. `League.CURRENT_VERSION` is private (`League.java:18`), and the store repeats the literal `10`.
- **What's there now:** six blocks (v4→5 … v9→10). Each rebuilds the whole `League` with its 8-argument constructor and **writes the file to disk**, only to change `version`. The comments in each block say no data changes, because the compact constructors already normalize `null` to defaults. A v4 file is therefore rewritten six times in one load.
- **Proposed simplification:** keep the real migrations (v1→2, v2→3, v3→4, which do change data and print a warning). Replace the rest with one step:
  ```java
  if (league.version() < League.CURRENT_VERSION) {
    league = league.withVersion(League.CURRENT_VERSION); // later fields default via compact ctors
    save(league);
  }
  ```
  Make `CURRENT_VERSION` package-visible or public, and add `withVersion`. Each future no-op schema bump is then a one-line constant change instead of another 15-line block. The table in CLAUDE.md still documents the history.
- **Estimated impact:** about 85 lines removed, and five unnecessary disk writes per upgrade avoided.
- **Risk:** low. `LeagueStoreTest` checks results ("loaded version == 10", "migrated file written to disk", "v1→current in a single load"), not the intermediate steps. Several test names still say "ToV6" and could be renamed while you're there.

### R4. One home for date/time parsing

- **Where:** `parseDate`/`parseTime` in `FieldBlockCommand.java:44–60`, `FieldOverrideCommand.java:44–60` (identical copies), `FieldLockCommand.java:43`, `ConfigDowCommand.java:43`, plus inline `LocalDate.parse`/`LocalTime.parse` with `DateTimeParseException` handling in `ConfigCommand`, `DivisionCommand`, `PlayoffCommand`, and `ScheduleGameCommand` (17 catch sites in total).
- **Proposed simplification:** move `parseDate`, `parseTime`, and `parseNonNegativeInt` into one package-private helper next to the existing `DayParser`, using the same strict `HH:mm` formatter everywhere. Delete the per-class copies.
- **Estimated impact:** about 70 lines removed. It also guarantees every command accepts the same time syntax; today each copy can drift on its own (for example in how `24:00` is treated).
- **Risk:** low. Error-message tests should be checked, because a few call sites word their errors differently ("Invalid date format … Expected YYYY-MM-DD" vs "Invalid date … Use YYYY-MM-DD format"). Pick one wording.
- **See also:** open question Q1. A bigger option is to let picocli type the options directly.

### R5. Add the missing lookups-by-ID to `League`

- **Where:** `League` already has `findDivision(name)`, `findField(name)`, and `findPlayoff(id)`. There is no `findDivisionById`, `findFieldById`, or `teamName(id)`, so these are re-implemented inline 17 times: `CalendarCommand.java:220, 244, 289`, `PlayoffCommand.java:652`, `FieldLockCommand.java:318`, `PracticeCommand.java:404`, and `SchedulerService.java:602, 619`, among others. `resolveTeamName` exists in three classes.
- **Proposed simplification:** add `Optional<Division> findDivision(UUID)`, `Optional<Field> findField(UUID)`, and `String teamName(UUID)` (returning a placeholder for unknown IDs) to `League`, and replace the inline streams.
- **Estimated impact:** about 60 lines removed, with clearer intent at each call site.
- **Risk:** low. Pure refactor. The default for an unknown ID must match today's per-site behavior (some sites return `"?"`, others `""`).

### R6. Read the CLI version from the build

- **Where:** `PlanrApp.java:31` (`version = "planr 0.14.1"`) and `build.gradle` (`version = '0.14.1'`).
- **What's there now:** the version is maintained in two places and bumped by hand each release.
- **Proposed simplification:** add `'Implementation-Version': project.version` to the `jar` manifest. Replace `version = …` with a `versionProvider` that returns `"planr " + PlanrApp.class.getPackage().getImplementationVersion()`.
- **Estimated impact:** a few lines, and one source of truth. The new docs build already reads the version from `build.gradle`.
- **Risk:** low. `gradle run` without a jar may show `null`, so fall back to `"dev"`.

### R7. Merge the three CP-SAT pipelines in `SchedulerService`

- **Where:** `buildAndSolve` (`SchedulerService.java:351–584`), `buildAndSolvePlayoffs` (`722–988`), `buildAndSolvePractices` (`1107–1314`); `enumerateAllSlots` / `enumeratePlayoffSlots` / `enumeratePracticeSlots`; `GameVar` vs `PracticeVar`; `Fixture` vs `PracticeFixture`.
- **What's there now:** three solver pipelines that share 125–164 identical lines each. Each builds its own `CpModel`, has its own copies of constraints C1–C5, the bigM objective, the solver setup, progress callbacks, and the code that collects results and per-division summaries. They differ in a few places: which date window is used, how the "teams" of a fixture are found (two real teams, one team, or playoff pseudo-teams), and an extra objective term for playoff field priority.
- **Proposed simplification:**
  1. Introduce one internal fixture shape, for example `record Job(UUID id, UUID divisionId, List<UUID> participants, int durationMinutes)`, and map games, practices, and playoff slots to it.
  2. Extract one `solve(List<Job>, Map<UUID, List<Slot>> slotsByDiv, ObjectiveExtras extras)` that builds C1–C5, the objective, solves, and returns assignments plus `DivisionSummary` rows. Playoffs pass the field-priority term as the extra objective.
  3. Give slot enumeration a single `enumerateSlots(league, start, end, divisionFilter, durationFn)`.
  4. Keep `assign`, `assignPlayoffs`, and `assignPractices` as thin public adapters, so callers and result types don't change.
- **Estimated impact:** about 350–450 lines removed from a 1,350-line file. Any future constraint change, such as the playoff round-order fix described in the open playoff-bracket issue, is then made once instead of in three near-copies.
- **Risk:** **medium.** This is the core algorithm. Mitigations: ~98 `SchedulerService*` tests (buffer/grid, curfew, day-of-week, field priority, the core suite), deterministic solver parameters, and doing the work in steps: enumeration first, then the shared constraint builder, then the objective. Run the full suite after each step. One trade-off: if the three pipelines are expected to diverge a lot later (for example practices getting very different constraints), a shared core with hooks can become harder to read than three copies. Nothing in the specs suggests that.

### R8. A small table printer for list and status output

- **Where:** 6 command files. `ScheduleCommand` has 27 rule cells, `PlayoffCommand` 12, `DivisionCommand` 9, and so on. Example: `PlayoffCommand.java:225–242, 405–423, 470–487`.
- **What's there now:** each report computes column widths, builds a `%-Ns` format string, prints a header, a row of dashes, and then the rows. Separators differ between tables (two spaces in most, four in `division list` and `field list`), which shows in the docs.
- **Proposed simplification:** add a ~40-line `TextTable` helper: `new TextTable("SLOT", "ROUND", …).rightAlign(2).row(…).print(out)`. Migrate tables one at a time.
- **Estimated impact:** about 150 lines removed, and consistent output.
- **Risk:** **medium, mostly because the tests won't catch it.** No command test asserts exact column layout, so a change in spacing will pass silently. Users or scripts may parse this output. Decide whether to keep each table's current separators or standardize them (a visible change), and add a few golden-output tests before migrating.

### R9 (minor). Drop the static factory methods on the `Result` types

- **Where:** `PlayoffScheduleResult`, `PracticeScheduleResult`, `ScheduleResult`.
- **What's there now:** `static Success success(…) { return new Success(…); }` and `static Failure failure(…)` duplicate the record constructors.
- **Proposed simplification:** call `new Success(…)` / `new Failure(…)` directly.
- **Estimated impact:** about 25 lines.
- **Risk:** very low. Do it only alongside R7, since R7 touches these call sites anyway.

## 4. Leave alone

| Area | Why it stays |
|---|---|
| Immutable records + `withX` helpers in `model/` | Cheap, safe, and the reason the commands have no state bugs. The model is also the part most likely to move into the web app. |
| Atomic temp-file + `ATOMIC_MOVE` save in `LeagueStore` | Protects the only copy of the user's data. Real data-integrity value for a few lines. |
| `FAIL_ON_UNKNOWN_PROPERTIES` disabled, plus the custom `LocalTime` serializer | Deliberate forward compatibility and file format. Both are documented. |
| The v1→v4 migrations | They change data and warn the user. Only the version-only steps go (R3). |
| The four sealed `Result` interfaces | Small, and a Success/Failure split at the solver boundary is the right shape. Each success case carries different data, so one generic `Result<T>` would add generics without removing much. |
| CP-SAT itself and the tick-bucket C2 constraint | Real optimization problem, real solver. Tick bucketing keeps the constraint count bounded by fields × days × ticks rather than games². It earns its keep. |
| Picocli nested subcommand classes | The verbosity is mostly annotations that double as help text. Flattening would cost more readability than it saves. |
| `CalendarRenderer` separate from `CalendarCommand` | A justified seam: pure rendering from normalized events, unit-testable without a league file. |
| `PlayoffBracketService` structure | Needs a **correctness** fix (see the open playoff-bracket issue), not simplification. Fix it first; don't refactor around a known bug. |

## 5. Open questions

- **Q1. What should the exit-code contract be?** The docs say `1` = validation error, `2` = I/O error. But picocli's own usage errors already exit **2**: missing required option, unknown subcommand (verified: `planr division add` → 2, `planr bogus` → 2). Much of the hand-written string parsing (R4, and `String` options such as `ConfigCommand.maxGamesPerWeekStr`) seems to exist so that *bad values* exit 1. If you set `CommandLine.setParameterExceptionHandler` to return 1 for all parse errors, options could be typed directly (`LocalDate`, `LocalTime`, `int`) with one shared converter for strict `HH:mm`. That would delete most of R4's remaining code and every `*Str` field. Is there a script or a test harness that depends on usage errors returning 2?
- **Q2. Is the GraalVM native image still planned?** `picocli-codegen` is configured only to generate reflection config for "a future native image" (CLAUDE.md, `TESTPLAN.md`), and nothing builds or tests one. It's cheap to keep, but if the web app replaces the CLI first, it's dead weight to delete.
- **Q3. Are both distribution forms needed?** `build.gradle` makes `jar` a fat jar (all of OR-Tools' native libraries bundled) and also uses `application`/`installDist`, which ships the dependencies separately. Jenkins archives only `build/libs/*.jar`. If installed users only run `installDist`, the fat-jar `from { … zipTree … }` block can go. If the archived jar is the real artifact, `installDist` duplicates it.
- **Q4. Before or after the playoff fix?** R7 and the playoff-bracket bug touch the same code. The recommendation is to fix the bracket and add the round-order constraint first, with tests, and then do R7, so the merge is checked against correct behavior. The alternative is to do R7 first so the precedence constraint is written once. Pick based on how soon playoffs need to be usable.

## 6. Suggested order of attack

1. **R1 + R6** in one PR: a shared `PlanrApp.commandLine()` factory, the IOException handler, and the version from the manifest. Mechanical. Run `gradle test`.
2. **R2**: confirmation helper plus an empty-stdin test (fixes a real crash).
3. **R3**: collapse the version-only migrations; rename the `…ToV6` tests.
4. **R4 + R5**: parsing helper and lookups by ID. Settle Q1 first, because typed picocli options would replace most of R4.
5. **The playoff-bracket bug fix**, which is a prerequisite for the next step (see Q4).
6. **R7** in three reviewable steps: slot enumeration → shared constraint builder → shared objective and result collection. Full test run after each.
7. **R8**, only after adding golden-output tests for the main tables. **R9** along with R7.

Expected total: roughly **1,000–1,100 lines removed** from 9,252 (≈ 11–12 %), with no behavior change except the empty-stdin crash fix. That includes both medium-risk steps. The five low-risk steps alone account for about 450 lines.

### Not covered

The test suite was used as evidence but not reviewed for simplification itself (14.7k LOC, larger than the main code). The tests probably have the same duplication as the commands, especially in league fixture setup. That is a reasonable follow-up review.
