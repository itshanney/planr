# Tech Spec: Playoff Scheduling — Double Elimination

**Date:** 2026-05-26  
**Status:** Final  
**PRD:** `features/2026-05-26-playoffs-double-elimination.md`

---

## Overview

The playoff feature adds a `planr playoff` top-level command group that mirrors the two-phase pattern of `planr schedule`: Phase 1 generates a double-elimination bracket per division and persists it to `league.json`; Phase 2 runs the existing CP-SAT field-assignment solver across all active playoff brackets in a single cross-division solve using a playoff-specific date range.

The core scheduler (`SchedulerService.buildAndSolve`) is reused without modification. The integration point is a new `SchedulerService.assignPlayoffs()` method that converts `PlayoffGame` records into the `Fixture` / `Slot` inputs the solver already understands, then maps the solver output back into `PlayoffGame` field assignments. Bracket generation is isolated in a new `PlayoffBracketService`.

Schema version advances from 6 → 7 with a no-op migration. All new model types are immutable records following the existing pattern.

---

## Component Diagram

```
PlanrApp
└── PlayoffCommand (new top-level command)
    ├── GenerateCmd   ── PlayoffBracketService (new)
    │                       └── produces List<PlayoffGame>
    ├── AssignCmd     ── SchedulerService.assignPlayoffs() (new method, reuses buildAndSolve)
    │                       └── produces PlayoffScheduleResult (new sealed interface)
    ├── StatusCmd     ── reads League.playoffs()
    └── ClearCmd      ── removes Playoff from League

model/
  Playoff            record  — division's bracket entity; persisted in league.json
  PlayoffGame        record  — one bracket slot (real game or bye)
  PlayoffState       enum    — GENERATED | ASSIGNED
  BracketSide        enum    — WINNERS | LOSERS | CHAMPIONSHIP

scheduler/
  PlayoffBracketService   — generates bracket game list from seed list
  PlayoffScheduleResult   — sealed: Success(assignmentsByGameId, summaries) | Failure(msg)
  SchedulerService        — gains assignPlayoffs() public method; buildAndSolve unchanged

store/
  LeagueStore             — v6→v7 no-op migration; League gains playoffs field
```

**Responsibility statements:**
- `PlayoffBracketService` — pure function: given N seeds and a division, produce the ordered `List<PlayoffGame>` including bye slots. No I/O.
- `SchedulerService.assignPlayoffs()` — converts `List<Playoff>` to `Fixture`/`Slot` inputs, delegates to `buildAndSolve`, maps results to `Map<UUID, Slot>`.
- `PlayoffScheduleResult` — carries the solver output in a playoff-specific shape so `AssignCmd` can update `PlayoffGame` records without touching `ScheduleResult`.
- `Playoff` — source of truth for a division's bracket state and field assignments.
- `PlayoffGame` — one bracket slot; `isBye=true` slots are never submitted to the solver and always have null assigned fields.

---

## Data Model

### New records — `model` package

```
Playoff
  UUID           divisionId
  LocalDate      startDate
  LocalDate      endDate
  PlayoffState   state          // GENERATED | ASSIGNED
  List<PlayoffGame> games

PlayoffGame
  UUID           gameId
  String         round          // "Winners R1", "Losers R2", "Championship", etc.
  BracketSide    bracketSide    // WINNERS | LOSERS | CHAMPIONSHIP
  String         positionA      // team name (R1), "W of G3" / "L of G2" (later rounds)
  String         positionB      // team name, "BYE", or positional reference
  LocalDate      assignedDate   // null until assigned
  LocalTime      assignedStartTime  // null until assigned
  UUID           assignedFieldId    // null until assigned
  boolean        isConditional  // true only for the championship re-match
  boolean        isBye          // true when positionB == "BYE"
```

**Constraint:** When `isBye=true`, all three `assigned*` fields must always be `null`.

### New enums — `model` package

```
PlayoffState  { GENERATED, ASSIGNED }
BracketSide   { WINNERS, LOSERS, CHAMPIONSHIP }
```

### `League` record — changed

Add `List<Playoff> playoffs` as the last constructor parameter. Update the compact constructor to normalize `null → List.of()`. Update all existing `withX()` helpers to thread `playoffs` through (they currently pass every field explicitly, so each needs one additional argument).

Add new helpers:
```
League withPlayoffAdded(Playoff p)
League withPlayoffReplaced(UUID divisionId, Playoff replacement)
League withPlayoffRemoved(UUID divisionId)
Optional<Playoff> findPlayoff(UUID divisionId)
```

`CURRENT_VERSION` bumps from 6 → 7.

### New result type — `scheduler` package

```
sealed interface PlayoffScheduleResult {
  record Success(
    Map<UUID, Slot> assignmentsByGameId,   // key = PlayoffGame.gameId
    boolean optimal,
    boolean targetMet,
    List<DivisionSummary> divisionSummaries
  ) implements PlayoffScheduleResult {}

  record Failure(String message) implements PlayoffScheduleResult {}
}
```

`DivisionSummary` is reused as-is. `Slot` is reused as-is.

### `LeagueStore` — changed

Add v6→v7 migration block (no data transformation needed; absent `playoffs` key deserializes to `null`, normalized by compact constructor):

```java
if (league.version() < 7) {
    league = new League(7, league.config(), league.divisions(), league.fields(),
        league.teamSchedule(), league.schedule(), league.playoffs());
    save(league);
}
```

---

## API Contracts

These are CLI command signatures, not HTTP endpoints. Each follows the pattern: read → mutate → save.

### `planr playoff generate`

```
Options (all required):
  --division <name>
  --start    <YYYY-MM-DD>
  --end      <YYYY-MM-DD>
  --seeds    <team1,team2,...>   // ordered, seed 1 first

Preconditions checked in order:
  1. Division exists (case-insensitive)
  2. --end not before --start
  3. --seeds count matches actual team count in division; N in [2,16]
  4. Each seed name matches a team in the division (case-insensitive); no duplicates
  5. No playoff already exists for this division in GENERATED or ASSIGNED state

On success:
  - Calls PlayoffBracketService.generate(division, resolvedSeeds)
  - Persists new Playoff(divisionId, startDate, endDate, GENERATED, games)
    via league.withPlayoffAdded(playoff)
  - Prints bracket summary table + final line
  - Exit 0

On validation failure: stderr + exit 1
On I/O failure: stderr + exit 2
```

### `planr playoff assign`

```
Options: none

Preconditions:
  1. At least one Playoff record exists
  2. All Playoff records share identical startDate and endDate

On success:
  - Clears all assigned* fields on every PlayoffGame across all Playoff records
  - Calls SchedulerService.assignPlayoffs(league, league.playoffs())
  - Maps PlayoffScheduleResult.Success.assignmentsByGameId() back onto PlayoffGame records
  - Transitions all Playoff records to state ASSIGNED
  - Saves updated league
  - Prints constraint summary + status line
  - Exit 0 (even on partial result)

On validation failure: stderr + exit 1
On I/O failure: stderr + exit 2
```

### `planr playoff status`

```
Options:
  --division <name>   // optional

Without --division:
  - For every division: print one line showing state (NOT_STARTED | GENERATED | ASSIGNED)

With --division:
  - Prints full bracket table: round, gameId (short), posA, posB, DATE, TIME, FIELD
    (UNASSIGNED or BYE where applicable)

On missing division: stderr + exit 1
On I/O failure: stderr + exit 2
```

### `planr playoff clear`

```
Options:
  --division <name>   // required

Preconditions:
  1. Division exists
  2. A Playoff record exists for this division

Prompt: "Remove playoff for <division>? Type 'yes' to confirm: "
On confirmation:
  - Removes Playoff record via league.withPlayoffRemoved(divisionId)
  - Saves updated league
  - Exit 0

On no confirmation: print "Cancelled." + exit 0
On missing playoff or division: stderr + exit 1
On I/O failure: stderr + exit 2
```

---

## Critical Path Walkthrough

### Path 1: `planr playoff generate --division Majors --start 2026-06-14 --end 2026-06-28 --seeds "Red Sox,Yankees,Cubs,Dodgers,Mets"`

```
1. PlayoffCommand.GenerateCmd.call()
2. store.load() → League
3. Validate division exists → Division{id=X, gameDurationMinutes=90, teams=[5]}
4. Validate --end not before --start
5. Validate --seeds count (5) == division.teams().size() (5); 5 ∈ [2,16] ✓
6. Resolve each seed name to Team (case-insensitive); collect ordered List<Team>
7. PlayoffBracketService.generate(division, resolvedSeeds):
   a. N=5, P=8, B=3
   b. positions[0..4] = resolvedSeeds; positions[5..7] = BYE markers
   c. W-R1 pairings: (pos[0] vs pos[7]=BYE, isBye=true), (pos[1] vs pos[6]=BYE, isBye=true),
                     (pos[2] vs pos[5]=BYE, isBye=true), (pos[3] vs pos[4], isBye=false)
   d. Emit 3 bye games + 1 real game for W-R1 (4 bracket slots total)
   e. Subsequent rounds reference prior game outcomes ("W of G1", "L of G4", etc.)
   f. Total returned: 3 bye slots + (2*5-1) = 9 real slots + 1 conditional = 13 PlayoffGame objects
8. Construct Playoff(divisionId=X, start, end, GENERATED, games)
9. store.save(league.withPlayoffAdded(playoff))
10. Print bracket summary table; print "Playoff generated for Majors: 5 teams, 10 game slots, 3 bye(s)"
    (G = 2N = 10 schedulable slots including conditional; B = 3 byes)
11. Exit 0
```

**Error path:** If `--seeds` contains "RedSox" (no space) and no team matches, step 6 collects unrecognized names and exits 1 with the list.

### Path 2: `planr playoff assign` (cross-division, all brackets ready)

```
1. PlayoffCommand.AssignCmd.call()
2. store.load() → League
3. activePlayoffs = league.playoffs()  // e.g., [Majors(5 teams), Minors(4 teams), T-Ball(3 teams)]
4. Validate activePlayoffs not empty
5. Validate all share same startDate and endDate (e.g., all 2026-06-14 to 2026-06-28)
6. Clear all assigned* fields on every non-bye PlayoffGame (build updated leagues in memory)
7. SchedulerService.assignPlayoffs(league, activePlayoffs):
   a. Build fixturesByDiv:
      - For each Playoff p in activePlayoffs:
        - divId = p.divisionId()
        - duration = league.divisions().stream().filter(d→d.id()==divId).gameDurationMinutes()
        - For each PlayoffGame g in p.games() where !g.isBye():
          - Round 1 games: homeTeamId = resolveTeamByName(division, g.positionA())
                           awayTeamId = resolveTeamByName(division, g.positionB())
          - Later rounds:  homeTeamId = deterministicPseudoId("pA:" + g.gameId())
                           awayTeamId = deterministicPseudoId("pB:" + g.gameId())
          - emit Fixture(g.gameId(), homeTeamId, awayTeamId, divId, duration)
   b. Call enumerateAllSlots(league, playoffStart, playoffEnd, Set<divIds from activePlayoffs>)
      (uses existing resolveOpenWindow logic; field division locks applied)
   c. emitFeasibilityCheckLine(...)
   d. buildAndSolve(league, fixturesByDiv, slotsByDiv, slotCounts, startMs)
      (existing code, no modification needed)
   e. For each assigned GameVar, emit Map.Entry(fixture.gameId(), slot)
   f. Return PlayoffScheduleResult.Success(assignmentsByGameId, optimal, targetMet, summaries)
8. AssignCmd maps results back to PlayoffGame updates:
   - For each Playoff p: for each PlayoffGame g in p.games():
     - If assignmentsByGameId contains g.gameId():
       - g' = new PlayoffGame(g.gameId(), g.round(), ..., slot.date(), slot.startTime(), slot.fieldId(), g.isConditional(), g.isBye())
     - Else: g' = g (no assigned fields set)
   - p' = new Playoff(p.divisionId(), p.startDate(), p.endDate(), ASSIGNED, updatedGames)
9. store.save(league with all playoffs replaced by their ASSIGNED versions)
10. Print constraint summary + "Playoff field assignment complete: 19/20 game slots assigned across 3 divisions."
11. Exit 0
```

**Error path (date mismatch):** Step 5 finds Majors has start=2026-06-14 but Minors has start=2026-06-07. Exit 1 with table listing each division and its dates.

### Path 3: `planr playoff status --division Majors` (after assign, partial result)

```
1. StatusCmd.call()
2. store.load() → League
3. Validate division exists; find Playoff for this division
4. Print bracket table, one row per PlayoffGame (including bye slots):
   - Bye slots: posA=team name, posB="BYE", date/time/field="BYE"
   - Assigned real slots: show date, time, field name
   - Unassigned real slots: show "UNASSIGNED" in date/time/field columns
5. Exit 0
```

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| **Later-round team IDs in solver** | (A) Skip C3/C4/C5 constraints for later rounds. (B) Use deterministic pseudo-UUIDs for later-round "teams." | B — deterministic pseudo-UUIDs (`UUID.nameUUIDFromBytes("pA:"+gameId)`) | C3/C4/C5 constraints in `buildAndSolve` key on team IDs. With unique pseudo-UUIDs, those constraints fire per-game-slot and are trivially satisfied (one game = one "team" = never conflicts with itself). No solver code changes needed. | Later-round games have no rest-day or weekly-cap protection, which is correct since teams are unknown. |
| **Solver result type** | (A) Reuse `ScheduleResult` and carry `ScheduledGame`. (B) New `PlayoffScheduleResult` with `Map<UUID,Slot>`. | B | Mapping solver output back to `PlayoffGame` updates requires `gameId` → `Slot`. `ScheduledGame` gets a new UUID in `buildScheduledGame`, so gameId is lost. Rather than patching `buildScheduledGame`, define a thinner result type for the playoff path that carries exactly what `AssignCmd` needs. | One more sealed interface in the scheduler package. Acceptable for the separation of concerns. |
| **`enumerateAllSlots` parameterization** | (A) Pass date range + division ID filter as new parameters. (B) Build a second copy of the method. | A — add `Set<UUID> divisionIdFilter` parameter and pass the playoff date range | The existing `enumerateAllSlots(League, LocalDate, LocalDate)` already accepts a date range. Adding a division filter avoids enumerating slots for non-playoff divisions. Zero duplication. | The existing `assign()` call site must pass `null` or `Set.of()` to mean "all divisions." Use overloading: keep the existing 3-arg form calling the 4-arg form with an empty set (empty = no filter). |
| **`League` record constructor change** | (A) Add `playoffs` as last parameter. (B) Wrap playoffs in a separate container record. | A | Consistent with how all other collections (`divisions`, `fields`) are handled. All `withX()` helpers just need one extra passthrough argument. | Every existing `withX()` helper in `League` needs to be updated (14 sites). Straightforward but must be done completely — a missed site will silently drop all playoffs on that mutation path. |
| **Bracket generation placement** | (A) Static methods on `PlayoffCommand`. (B) New `PlayoffBracketService` in `scheduler/`. | B | Bracket generation is algorithmically independent of I/O and commands; isolating it in `scheduler/` makes it testable without picocli machinery. Follows the `TeamScheduleService` precedent. | One additional class file. |
| **Bye-slot losers-bracket handling** | (A) Only produce bye games in W-R1 and let the bracket auto-adjust. (B) Produce explicit bye games in the losers bracket too. | A | W-R1 bye games produce no losers, so the downstream losers-bracket slot simply has one fewer input. The bracket generator must handle this by detecting "no loser available" slots in L-R1 and either collapsing them or inserting losers-bracket byes. Total real game count invariant (2N-1) is the authoritative test. | The bracket generator is the most complex piece of this feature. Requires careful testing across all N in [2,16]. |

---

## Implementation Order

The following sequence minimizes integration risk. Each step is independently compilable and testable.

1. **Model types** — add `PlayoffState`, `BracketSide` enums; `PlayoffGame`, `Playoff` records; update `League` record and all `withX()` helpers; bump `CURRENT_VERSION` to 7; add v6→v7 migration no-op in `LeagueStore`.

2. **`PlayoffBracketService`** — implement `generate(Division, List<Team>)`. Write unit tests for all edge-case team counts: 2, 3, 4, 5, 6, 7, 8, 9, 15, 16. Assert: (a) real game count == 2N-1, (b) conditional game count == 1, (c) bye count == P-N, (d) round names are correct, (e) first-round pairings are deterministic.

3. **`SchedulerService.assignPlayoffs()`** — add the new public method and the `PlayoffScheduleResult` sealed interface. Refactor `enumerateAllSlots` to accept an optional division ID filter. Unit test that bye games are excluded from `fixturesByDiv` and that later-round pseudo-UUIDs are deterministic.

4. **`PlayoffCommand`** — implement `GenerateCmd`, `StatusCmd`, `ClearCmd` first (no solver dependency). Then implement `AssignCmd`. Register `PlayoffCommand` in `PlanrApp.subcommands`.

5. **Integration tests** — follow `ScheduleCommandTest` patterns in `CommandTestBase`. Cover: generate → status → assign → status cycle for 4-team (no byes), 5-team (3 byes), and two-division (cross-division assign) scenarios.

---

## Operational Concerns

**Test isolation:** `PlayoffCommand` tests must use `CommandTestBase` (which wipes `~/.planr/` between tests and runs serially). The solver is invoked during `planr playoff assign` tests — keep test division sizes small (2-4 teams per division) to stay well within the 300-second solver time limit.

**Schema migration:** The v6→v7 migration is a no-op. Old `league.json` files without a `playoffs` key deserialize cleanly because `FAIL_ON_UNKNOWN_PROPERTIES` is disabled and the `League` compact constructor normalizes `null → List.of()`. Forward compatibility (a v7 file read by a v6 binary) silently ignores the `playoffs` key, which is acceptable for a CLI prototype.

**Solver time limit:** The playoff solver reuses the existing 300-second cap in `buildAndSolve`. Playoff brackets are smaller than regular-season schedules (2N real games, max 31 for N=16 vs. potentially hundreds regular season), so the solver should complete far faster. No time-limit change is needed.

**Rollback:** There is no rollback mechanism beyond `planr playoff clear --division <name>` followed by `planr playoff generate`. This is consistent with how `planr schedule assign` works (no undo; re-run replaces previous result).

---

## Out of Scope / Future Work

- **Bracket advancement based on results.** Recording game results (who won/lost) and updating `positionA`/`positionB` in subsequent rounds. The data model fields exist to support this (`positionA`/`positionB` are strings, not team UUIDs, specifically so they can be updated as results come in), but the command logic is not part of this feature.
- **Per-division game duration override for playoffs.** Currently the playoff solver uses `division.gameDurationMinutes()`. A playoff-specific duration would require a new field on `Playoff`.
- **Parallel division solves.** The single cross-division CP-SAT solve is correct but does not parallelize. For future scale (>6 divisions with large brackets), per-division solves could run in parallel and then a conflict-resolution pass applied. Not needed at current scale.
- **`planr playoff export`.** CSV/JSON export of playoff game assignments. Deferred; the data is accessible via `planr playoff status`.

---

## Errata

### E-1: `--seeds` multi-word names and odd-N L-R1 crash

**Status:** Identified post-implementation. Both bugs live in `PlayoffBracketService` and `PlayoffCommand.GenerateCmd`.

#### Bug A — `--seeds` cannot parse multi-word team names

**Root cause (`PlayoffCommand.java:76-78`):** `--seeds` is declared as a single `String` field. The implementation splits the value on commas (`seedsStr.split(",", -1)`). When a team name contains spaces (e.g., "Red Sox"), the shell tokenizes unquoted input before picocli sees it: `--seeds Red Sox,Yankees` is delivered to picocli as `--seeds "Red"` with `"Sox,Yankees"` as a stray positional argument, causing a parse error. Even when quoted correctly (`--seeds "Red Sox,Yankees"`), the syntax is non-obvious and fragile.

**Fix:** Change `--seeds` from a single `String` to a repeatable `List<String>` option. Each invocation of `--seeds` supplies one seed:

```java
// Before
@Option(names = "--seeds", required = true, paramLabel = "<team1,team2,...>")
String seedsStr;

// After
@Option(names = "--seeds", required = true, paramLabel = "<team>",
        description = "Team name in seed order. Repeat once per team: "
                    + "--seeds \"Red Sox\" --seeds Yankees --seeds Cubs")
List<String> seeds;
```

Remove the `split(",", -1)` processing from `GenerateCmd.call()` — `seeds` arrives already as a correctly-split list. Update `paramLabel` and `description` in the spec's API contract table accordingly. Update `planr playoff generate` usage examples in the Critical Path Walkthrough to use the repeated-flag form.

**Updated CLI invocation example (Path 1):**
```
planr playoff generate --division Majors --start 2026-06-14 --end 2026-06-28 \
  --seeds "Red Sox" --seeds Yankees --seeds Cubs --seeds Dodgers --seeds Mets
```

#### Bug B — `IndexOutOfBoundsException` for N = 7, 11, 13, 15

**Root cause (`PlayoffBracketService.java:125-136`):** The L-R1 pairing loop iterates `i += 2` over `survivingLosers` (real Winners R1 losers). The count of real Winners R1 games is `n - p/2`. When this value is odd and greater than 1 — which occurs at N = 7 (count=3), N = 11 (count=3), N = 13 (count=5), and N = 15 (count=7) — the loop reaches the last index and calls `survivingLosers.get(i + 1)` on an index that does not exist.

| N  | P  | real W-R1 games | odd? | fails? |
|----|----|-----------------|------|--------|
| 5  | 8  | 1               | —    | no (handled by else branch) |
| 6  | 8  | 2               | no   | no |
| 7  | 8  | 3               | yes  | **crash** |
| 11 | 16 | 3               | yes  | **crash** |
| 13 | 16 | 5               | yes  | **crash** |
| 15 | 16 | 7               | yes  | **crash** |

**Fix:** Introduce a typed reference carrier so the L-R1 loop can handle both paired and unpaired losers uniformly. Define a private record inside `PlayoffBracketService`:

```java
private record GameRef(UUID gameId, String prefix) {
    String label(List<BracketSlot> slots) {
        return prefix + " " + slotLabel(slots, gameId);
    }
}
```

Rewrite the L-R1 block to produce `List<GameRef>` rather than `List<UUID>`. When `survivingLosers.size()` is odd, the last loser gets no L-R1 game — it is carried forward as `GameRef(loserId, "L of")` to be paired directly in the L-R2 feed round. Paired losers produce L-R1 games and are carried as `GameRef(l1GameId, "W of")`.

```java
// Build prevLosers as GameRef list
List<GameRef> prevLosers = new ArrayList<>();
int realCount = survivingLosers.size();
int pairedCount = (realCount / 2) * 2;  // floor to even

for (int i = 0; i < pairedCount; i += 2) {
    UUID gameId = UUID.randomUUID();
    slots.add(new BracketSlot(gameId, "Losers R" + losersRound, BracketSide.LOSERS,
        "L of " + slotLabel(slots, survivingLosers.get(i)),
        "L of " + slotLabel(slots, survivingLosers.get(i + 1)),
        false, false));
    prevLosers.add(new GameRef(gameId, "W of"));
    losersRound++;
}
if (realCount % 2 == 1) {
    // Orphan loser bypasses L-R1, enters L-R2 directly
    prevLosers.add(new GameRef(survivingLosers.get(pairedCount), "L of"));
}
// Also handle the realCount == 1 case (previously the else branch):
// the single loser just becomes one GameRef with "L of" prefix — same code path.
```

Downstream feed and elimination rounds use `ref.label(slots)` instead of `"W of " + slotLabel(...)` everywhere. The `winnersRoundDropIds` feed loop similarly produces `GameRef(feedGameId, "W of")` entries.

**Invariant check:** After this fix, `generateBracket` must satisfy for all N in [2, 16]:
- real game count (non-bye, non-conditional) == `2*N - 1`
- conditional game count == `1`
- bye count == `nextPowerOfTwo(N) - N`

Add test cases for N = 7, 11, 13, 15 to `PlayoffBracketServiceTest` asserting these invariants and verifying no exception is thrown.
