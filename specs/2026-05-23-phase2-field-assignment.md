# Tech Spec: Phase 2 — Field Assignment & Scheduler Progress/Logging

* **Date:** 2026-05-20
* **Status:** Ready for Implementation
* **Scope:** "Phase 2: Field Assignment" and "Scheduler Progress and Logging" acceptance criteria from `features/2026-05-17-league-planner-core-scheduling-v2.md`
* **Coordinates with:** `specs/2026-05-18-phase1-team-schedule.md` — Phase 1 output (`TeamSchedule`) is the input consumed by Phase 2 described here.

---

## Overview

This slice upgrades `SchedulerService` (the existing CP-SAT field-assignment engine) and `ScheduleCommand.Assign` to meet the v2 acceptance criteria in three areas:

1. **Partial schedule support** — The solver no longer blocks on infeasibility when there are fewer available field slots than games. Instead it assigns as many games as possible by converting the hard `addExactlyOne` fixture constraint to a soft `addAtMostOne`, introducing a per-fixture `isAssigned` BoolVar, and maximizing total games assigned. A Draft is saved regardless of whether all games were placed.

2. **Live solver progress** — A `ProgressCallback extends CpSolverSolutionCallback` streams timestamped `[M:SS]` progress lines to stdout during the solve at start (emitted by `AssignCmd`), feasibility-check completion, wall-clock milestones at 25 %/50 %/75 % of the time budget (emitted by the callback on each new solution via `wallTime()`), and solver completion.

3. **Constraint summary** — After every Phase 2 run, a per-division table is printed showing total games requested, available slots, used/available ratio, and target-met status. For partial schedules, the summary additionally names which teams fell short and by how many games.

The solver time budget extends from 60 seconds to a maximum of 300 seconds; it stops early when an optimal solution is proven. The existing CP-SAT model structure (field-conflict constraint C2, team-per-day constraint C3, ISO-week load objective) is preserved; the changes are additive.

No `league.json` schema changes are needed — `DivisionSummary` data is transient and not persisted.

---

## Component Diagram

No new top-level components are introduced. Changes are confined to `SchedulerService`, `ScheduleResult`, a new `DivisionSummary` record, and `ScheduleCommand.Assign`.

```
ScheduleCommand.Assign
  │
  ├── [unchanged] pre-confirmation feasibility estimate + warning
  ├── receives "yes" confirmation
  ├── emits "[0:00] Phase 2 started. N games across D division(s)."
  ├── records phase2StartMs = System.currentTimeMillis()
  ├── calls SchedulerService.assign(league)
  │          │
  │          │  SchedulerService.assign()
  │          │    1. precondition validation
  │          │    2. toFixturesByDiv(teamSchedule)
  │          │    3. enumerateAllSlots(league, start, end)
  │          │    4. computeSlotCounts() → Map<UUID, Integer>
  │          │    5. emit "[0:XX] Feasibility check ... Solver started."
  │          │    6. buildAndSolve(league, fixturesByDiv, slotsByDiv, slotCounts)
  │          │         ├── addAtMostOne per fixture (replaces addExactlyOne)
  │          │         ├── isAssigned BoolVar per fixture
  │          │         ├── objective: maximize(bigM * totalAssigned - maxWeekLoad)
  │          │         ├── solver time limit: 300s
  │          │         ├── ProgressCallback (inner class)
  │          │         │     └── onSolutionCallback() prints 25/50/75% milestones
  │          │         │         using wallTime()
  │          │         └── assembles DivisionSummary list from solve result
  │          │    7. emit "[M:SS] Solver complete. N of T games assigned (status)."
  │          │    8. return ScheduleResult.Success(games, optimal, targetMet,
  │          │                                     divisionSummaries)
  │          │
  ├── receives ScheduleResult
  ├── prints constraint summary table  ← NEW
  ├── prints per-team shortfall (if partial)  ← NEW
  ├── saves Schedule(DRAFT, games) via LeagueStore
  └── prints final status line (updated format)
```

### Component Responsibilities (changes only)

| Component | Change |
|---|---|
| `SchedulerService` | Remove hard infeasibility block; replace `addExactlyOne` with `addAtMostOne` + `isAssigned` BoolVar; update objective to weighted maximize; add `computeSlotCounts()`; extend time limit to 300 s; add `ProgressCallback` inner class; extend `ScheduleResult.Success` with `targetMet` and `divisionSummaries`; emit feasibility-check and completion progress lines |
| `ProgressCallback` | New `static` inner class in `SchedulerService`; extends `CpSolverSolutionCallback`; prints `[M:SS]` lines at 25 %/50 %/75 % of time budget using `wallTime()` |
| `ScheduleResult.Success` | Add `boolean targetMet` and `List<DivisionSummary> divisionSummaries` fields |
| `DivisionSummary` | New record in `scheduler` package: division name, games requested, games assigned, slots available |
| `ScheduleCommand.Assign` | Emit start progress line before calling service; after receiving result: print constraint summary, optionally per-team shortfall; update final status line |

---

## Data Model

### New Record: `DivisionSummary`

```
DivisionSummary                                           (scheduler package)
  ├── divisionName: String
  ├── gamesRequested: int       (total fixtures for this division in the team schedule)
  ├── gamesAssigned: int        (games actually placed by the solver)
  └── slotsAvailable: int       (total enumerated slots for this division across all
                                 fields and season dates, before game assignment)
```

Derived at call site:
- `targetMet()` → `gamesAssigned == gamesRequested`
- `usedAvailRatio()` → `gamesAssigned + "/" + slotsAvailable`

`DivisionSummary` is a package-level record (not nested in `ScheduleResult`) because `ScheduleCommand.Assign` references it directly for constraint summary rendering.

### Modified Sealed Interface: `ScheduleResult.Success`

```java
// Before:
record Success(List<ScheduledGame> games, boolean optimal) implements ScheduleResult {}

// After:
record Success(
    List<ScheduledGame> games,
    boolean optimal,                      // solver proved optimality (CP-SAT OPTIMAL status)
    boolean targetMet,                    // gamesAssigned == total fixtures
    List<DivisionSummary> divisionSummaries
) implements ScheduleResult {}
```

Update the factory method `ScheduleResult.success(...)` to accept the two new parameters.

### Per-team Shortfall (computed in `AssignCmd`, not persisted)

```
For each team T in league.teamSchedule():
  requested[T] = count of TeamGame records in teamSchedule where T is home or away
  assigned[T]  = count of ScheduledGame records in result.games() where T is home or away
  shortfall[T] = requested[T] - assigned[T]    (> 0 means fell short)
```

Only teams with `shortfall > 0` appear in the per-team shortfall output.

---

## Solver Design Changes

### C1: Fixture Constraint — `addExactlyOne` → `addAtMostOne` + `isAssigned`

**Current:**
```java
Literal[] lits = ...;
model.addExactlyOne(lits);   // hard constraint: game must be assigned
```

**New:**
```java
BoolVar[] boolVars = vars.stream().map(GameVar::var).toArray(BoolVar[]::new);
model.addAtMostOne(boolVars);                           // at most one slot selected

BoolVar assigned = model.newBoolVar("assigned_" + fixtureId);
model.addEquality(assigned, LinearExpr.sum(boolVars)); // assigned == (0 or 1)
isAssigned.put(fixtureId, assigned);
```

`LinearExpr.sum(BoolVar[])` — verify against OR-Tools 9.10 Javadoc; `BoolVar` extends `IntVar` so `LinearExpr.sum(IntVar[])` is the expected overload.

### Objective: Maximize assigned games, then minimize week-load imbalance

```java
// totalFixtures is a safe upper bound for both assigned count and maxWeekLoad.
int totalFixtures = fixturesByDiv.values().stream().mapToInt(List::size).sum();
long bigM = (long) totalFixtures + 1;

IntVar totalAssigned = model.newIntVar(0, totalFixtures, "total_assigned");
model.addEquality(totalAssigned, LinearExpr.sum(
    isAssigned.values().toArray(new IntVar[0])));

// Existing maxWeekLoad IntVar and per-team-week constraints: unchanged.

// Lexicographic: maximize assigned games first, then minimize week-load imbalance.
// bigM > max(maxWeekLoad) guarantees dominance ordering.
model.maximize(LinearExpr.weightedSum(
    new IntVar[]{totalAssigned, maxWeekLoad},
    new long[]{bigM, -1L}
));
```

`bigM = totalFixtures + 1` is a valid dominance bound because `maxWeekLoad` ≤ total games per team ≤ `totalFixtures`.

### Time Limit: 60 s → 300 s

```java
private static final int SOLVER_TIME_LIMIT_SECONDS = 300;
```

The solver stops immediately when CP-SAT proves OPTIMAL. For leagues within the target scale (≤ 100 teams, ≤ 10 fields), optimal is typically found in under 60 seconds. The extended budget provides headroom for larger configurations.

### `ProgressCallback` Inner Class

```java
private static class ProgressCallback extends CpSolverSolutionCallback {

    private final int timeLimitSeconds;
    private int lastMilestonePct = 0;

    ProgressCallback(int timeLimitSeconds) {
        this.timeLimitSeconds = timeLimitSeconds;
    }

    @Override
    public void onSolutionCallback() {
        if (timeLimitSeconds <= 0) return;
        int elapsedSec = (int) wallTime();
        int pct = (int)(wallTime() / timeLimitSeconds * 100);
        for (int milestone : new int[]{25, 50, 75}) {
            if (pct >= milestone && lastMilestonePct < milestone) {
                System.out.printf("[%d:%02d] Solver progress: ~%d%% of time budget used.%n",
                    elapsedSec / 60, elapsedSec % 60, milestone);
                System.out.flush();
                lastMilestonePct = milestone;
            }
        }
    }
}
```

`wallTime()` is a method on `CpSolverSolutionCallback` returning elapsed wall-clock seconds as `double`. Confirmed available in `ortools-java:9.10`.

Wire into `buildAndSolve()`:
```java
ProgressCallback callback = new ProgressCallback(SOLVER_TIME_LIMIT_SECONDS);
CpSolverStatus status = solver.solve(model, callback);
```

### Removing the Hard Infeasibility Block

The call to `checkSimpleFeasibility(...)` currently returns `ScheduleResult.Failure` when any division has fewer slots than games. This block is removed. The slot count information is retained as a `Map<UUID, Integer>` and used exclusively to populate `DivisionSummary.slotsAvailable`.

---

## CLI Command Contract — `planr schedule assign` (modified)

### Phase 1: Pre-confirmation display (unchanged)

```
Team schedule: 48 games across 3 divisions (AAA 12, Majors 20, T-Ball 6).
Warning: AAA division has 12 games but only ~10 slots estimated in the season window.
         Field assignment may produce a partial schedule.
Confirm this team schedule and begin field assignment? This may take up to 5 minutes. Type 'yes' to continue:
```

### Phase 2: Live progress (new — streamed to stdout during solve)

```
[0:00] Phase 2 started. 48 games across 3 divisions.
[0:01] Feasibility check passed. Solver started. 48 games across 3 divisions.
[1:15] Solver progress: ~25% of time budget used.
[2:30] Solver progress: ~50% of time budget used.
[3:45] Solver progress: ~75% of time budget used.
[5:00] Solver complete. 48 of 48 games assigned (target-met, optimal).
```

**Progress line format rules:**

| Line | Emitted by | When |
|---|---|---|
| `[0:00] Phase 2 started.` | `AssignCmd` | Immediately before calling `SchedulerService.assign()` |
| `[0:XX] Feasibility check passed. Solver started.` | `SchedulerService.assign()` | After `enumerateAllSlots()` completes, before `buildAndSolve()` |
| `[M:SS] Solver progress: ~N% ...` | `ProgressCallback.onSolutionCallback()` | When a new improving solution is found and `wallTime()` crosses a milestone |
| `[M:SS] Solver complete. N of T games ...` | `SchedulerService.assign()` | Immediately after `solver.solve()` returns |

The `[M:SS]` timer for the service-emitted lines is wall-clock elapsed since the start of `assign()` (`System.currentTimeMillis()` at method entry). All progress lines go to stdout. `System.out.flush()` is called after each line.

**Infeasibility note in feasibility-check line:** If any division shows a slot deficit, the feasibility check line reads:
```
[0:01] Feasibility check: AAA deficit (12 games, 10 slots). Solver started.
```
Otherwise: `Feasibility check passed. Solver started.`

**Completion line format:**

| Condition | Completion line |
|---|---|
| All assigned, OPTIMAL | `[M:SS] Solver complete. 48 of 48 games assigned (target-met, optimal).` |
| All assigned, FEASIBLE | `[M:SS] Solver complete. 48 of 48 games assigned (target-met).` |
| Partial, OPTIMAL | `[M:SS] Solver complete. 36 of 38 games assigned (partial, optimal).` |
| Partial, FEASIBLE | `[M:SS] Solver complete. 36 of 38 games assigned (partial).` |

### Constraint summary (new — always printed after solve)

**Target-met example:**
```
Constraint Summary
------------------
DIVISION    GAMES  SLOTS  USED/AVAIL  STATUS
----------  -----  -----  ----------  ----------
AAA            12     54   12/54       target-met
Majors         20     54   20/54       target-met
T-Ball          6     54    6/54       target-met

All targets met. 38 of 38 games assigned.
```

**Partial example:**
```
Constraint Summary
------------------
DIVISION    GAMES  SLOTS  USED/AVAIL  STATUS
----------  -----  -----  ----------  ----------
AAA            12     10   10/10       partial (2 unassigned)
Majors         20     54   20/54       target-met
T-Ball          6     54    6/54       target-met

Warning: 2 game(s) could not be assigned. Draft saved with 36 of 38 games.
Teams that fell short of target (AAA):
  Cardinals: 4/6 games assigned
  Blue Jays: 4/6 games assigned
```

**Formatting rules:**
- Divisions are sorted alphabetically.
- Column widths are padded to the widest value in each column.
- The per-team shortfall block is printed only when `targetMet == false` and only for divisions with unassigned games.
- Per-team shortfall is computed in `AssignCmd` by diffing `league.teamSchedule()` against `result.games()`.

### Final status line (updated format)

After saving the draft:

| Condition | Message (stdout) |
|---|---|
| Target met, optimal | `Draft schedule saved: 38 games assigned (target-met, optimal distribution). Run 'planr schedule view' to review.` |
| Target met, not optimal | `Draft schedule saved: 38 games assigned (target-met, good distribution — optimizer ran up to 300s). Run 'planr schedule view' to review.` |
| Partial | `Draft schedule saved: 36 of 38 games assigned (partial). Run 'planr schedule view' to review.` |

### Hard failure cases (return Failure, exit 1, no draft saved)

| Condition | Message (stderr) |
|---|---|
| No team schedule | (precondition check, unchanged) |
| No fields | (precondition check, unchanged) |
| No season dates | (precondition check, unchanged) |
| FINALIZED schedule | (precondition check, unchanged) |
| Solver returns UNKNOWN (timeout, no feasible solution found) | `Error: Solver timed out without assigning any games. Try extending the season or adding more field availability.` |
| Solver returns INFEASIBLE unexpectedly | `Error: Solver returned an unexpected result. Please report this bug.` |

`UNKNOWN` and `INFEASIBLE` from the solver are not saved as drafts. All other outcomes (including partial schedules) are saved.

---

## Critical Path Walkthroughs

### 1. Phase 2 — All games assigned (target-met)

```
User: planr schedule assign
(preconditions pass, user sees team schedule summary, types "yes")

1. AssignCmd emits: "[0:00] Phase 2 started. 48 games across 3 divisions."
2. SchedulerService.assign(league):
   a. toFixturesByDiv: 48 fixtures (Majors 20, AAA 12, T-Ball 6)
   b. enumerateAllSlots: Map<divId, List<Slot>> — Majors: 540 slots, AAA: 540, T-Ball: 540
   c. computeSlotCounts: {Majors→540, AAA→540, T-Ball→540}
   d. Emits: "[0:01] Feasibility check passed. Solver started. 48 games across 3 divisions."
   e. buildAndSolve():
      - 48 addAtMostOne constraints + 48 isAssigned BoolVars
      - C2 (field conflict), C3 (team-per-day): unchanged
      - Objective: maximize(49 * totalAssigned - maxWeekLoad)
      - solver.solve(model, ProgressCallback(300))
      - ProgressCallback fires at 25%/50%/75% if solutions are found at those wall-clock marks
      - solver returns OPTIMAL; all 48 isAssigned vars = 1
   f. Assembles List<ScheduledGame> (48 games, sorted by date/time/field)
   g. DivisionSummary list:
      {AAA: req=12, assigned=12, slots=540}
      {Majors: req=20, assigned=20, slots=540}
      {T-Ball: req=6, assigned=6, slots=540}
   h. Emits: "[0:45] Solver complete. 48 of 48 games assigned (target-met, optimal)."
   i. Returns Success(games=48, optimal=true, targetMet=true, summaries)

3. AssignCmd:
   a. Prints constraint summary table (all target-met)
   b. targetMet=true → no per-team shortfall printed
   c. Saves Schedule(DRAFT, seasonStart, seasonEnd, games=48) via LeagueStore
   d. Prints: "Draft schedule saved: 48 games assigned (target-met, optimal distribution)."
   e. Exit 0.
```

### 2. Phase 2 — Partial schedule

```
User: planr schedule assign
(pre-confirm warning was shown: AAA has 12 games, ~10 slots. User types "yes".)

1. AssignCmd emits: "[0:00] Phase 2 started. 38 games across 3 divisions."
2. SchedulerService.assign(league):
   a. toFixturesByDiv: 38 fixtures (Majors 20, AAA 12, T-Ball 6)
   b. enumerateAllSlots: AAA has 10 slots; Majors: 540, T-Ball: 54
   c. computeSlotCounts: {Majors→540, AAA→10, T-Ball→54}
   d. Emits: "[0:01] Feasibility check: AAA deficit (12 games, 10 slots). Solver started."
   e. buildAndSolve():
      - Solver assigns all 20 Majors + all 6 T-Ball + 10 of 12 AAA games
      - status = OPTIMAL
      - 2 AAA fixtures have isAssigned = 0
   f. Assembles List<ScheduledGame> (36 games)
   g. DivisionSummary list:
      {AAA: req=12, assigned=10, slots=10}
      {Majors: req=20, assigned=20, slots=540}
      {T-Ball: req=6, assigned=6, slots=54}
   h. Emits: "[1:05] Solver complete. 36 of 38 games assigned (partial, optimal)."
   i. Returns Success(games=36, optimal=true, targetMet=false, summaries)

3. AssignCmd:
   a. Prints constraint summary (AAA shows "partial (2 unassigned)")
   b. targetMet=false → computes per-team shortfall:
      - Walk teamSchedule.games() for AAA: Cardinals 6 req, Blue Jays 6 req
      - Walk result.games() for AAA: Cardinals 4 assigned, Blue Jays 4 assigned
      - Prints: "Teams that fell short of target (AAA):"
               "  Cardinals: 4/6 games assigned"
               "  Blue Jays: 4/6 games assigned"
   c. Saves Schedule(DRAFT, seasonStart, seasonEnd, games=36)
   d. Prints: "Draft schedule saved: 36 of 38 games assigned (partial)."
   e. Exit 0.
```

### 3. Phase 2 — Solver timeout without any feasible solution

```
(Extremely unusual: would require a model that cannot assign even 0 games,
 which is impossible with addAtMostOne. Defensive handling.)

solver.solve() returns UNKNOWN.
SchedulerService returns ScheduleResult.Failure("Error: Solver timed out without assigning any games...")
AssignCmd prints failure to stderr. Exit 1. No draft saved.
```

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| Partial schedule solver design | (A) `addAtMostOne` + maximize assigned; (B) two-phase (try full solve, fall back to partial if INFEASIBLE); (C) block on infeasibility (current) | **(A) `addAtMostOne` + maximize** | Single solver run; no special-casing; CP-SAT handles partial assignment natively. The `addAtMostOne` change is minimal (one line replaced). | Adds one `BoolVar` per fixture. At ≤ 1,000 fixtures (target scale), model size increase is negligible. |
| Weighted objective for partial + balance | (A) `bigM * assigned - weekLoad`; (B) multi-objective / AddAssumptions; (C) drop week-load objective entirely | **(A) Weighted sum with bigM** | Single objective is simpler than multi-objective CP-SAT API. `bigM = totalFixtures + 1` guarantees dominance: any solution with more assigned games wins over all solutions with fewer games, regardless of week-load. | If `maxWeekLoad` could theoretically exceed `totalFixtures`, the ordering breaks. In practice, week-load per team is bounded by games per team, which is much less than `totalFixtures`. The risk is theoretical only. |
| Progress output channel | stdout, stderr, Java Logger | **stdout** | PRD: "always printed to stdout and are not suppressible." stdout is correct. | Progress lines and schedule JSON (from `export`) both go to stdout. Piped invocations that capture both must strip progress lines manually. Acceptable for CLI prototype scope. |
| Progress milestone trigger | (A) `SolutionCallback.onSolutionCallback()` + `wallTime()`; (B) dedicated background timer thread; (C) periodic sleep loop | **(A) Solution callback + `wallTime()`** | CP-SAT-idiomatic; no threading complexity. `wallTime()` is confirmed available on `CpSolverSolutionCallback` in `ortools-java:9.10`. | Milestones only fire when the solver finds a new solution. If the solver finds its first (and only) optimal solution at second 5 of a 300 s budget, no 25 %/50 %/75 % lines appear. A dedicated timer thread is the correct fix; deferred as it requires cross-thread stdout coordination and is not required by the PRD. |
| Pre-solve infeasibility check | (A) Block Phase 2 with `Failure` (current); (B) Log warning, let solver proceed; (C) Suppress and let solver handle it | **(B) Log warning, proceed** | PRD: Phase 2 "assigns as many as possible" — blocking is wrong. Slot counts are still computed for the constraint summary. The feasibility check line informs the organizer before the long solve. | Organizer may see a deficit warning, wait 5 minutes, and receive a partial schedule. The pre-confirmation warning (unchanged in `AssignCmd`) already communicates this risk before the solve starts. |
| Constraint summary placement | (A) Printed inside `SchedulerService`; (B) Printed in `AssignCmd` from `DivisionSummary` data | **(B) Printed in `AssignCmd`** | Keeps `SchedulerService` free of presentation logic. Consistent with all other output formatting being in the command layer. | `ScheduleResult.Success` carries more data (two new fields). Acceptable: it is a sealed interface with a small number of implementations. |
| Per-team shortfall computation | (A) Track in solver (which fixtures weren't assigned); (B) Diff `teamSchedule` vs `result.games()` in `AssignCmd` | **(B) Diff in `AssignCmd`** | The solver has no "target" concept; tracking shortfall inside it would be out of scope. The diff is O(games) and trivial for the target scale. | None. |
| Solver time limit 300 s vs 60 s | Keep 60 s; extend to 300 s; make configurable via `--timeout` | **300 s fixed** | PRD specifies "may continue up to 300 seconds." For the target scale, optimal is almost always found in < 60 s. 300 s provides headroom for larger leagues. A `--timeout` flag can be added later without design changes. | Worst-case user wait increases from 60 s to 5 minutes. Progress output mitigates perceived wait. |
| UNKNOWN solver status | Save empty draft; return Failure | **Return Failure** | An empty draft provides no value to the organizer. UNKNOWN in a model with `addAtMostOne` is theoretically impossible (0 games is always feasible); defensive handling is correct. | If UNKNOWN occurs on a large league with a very short time limit, the user sees a hard failure rather than a partial result. Acceptable — the scenario is pathological. |

---

## Operational Concerns

### Testing Strategy

**`SchedulerServiceTest` additions:**

| Test | Assertion |
|---|---|
| Partial: 4-team div, season has only 2 days × 1 slot/day (60-min game, 09:00–10:00 window) | `Success.targetMet == false`; `games.size() < totalFixtures`; `DivisionSummary.gamesAssigned < gamesRequested` |
| `DivisionSummary.slotsAvailable` accuracy | equals `estimateAvailableSlots()` for the same league and division |
| Target-met: all games assigned | `Success.targetMet == true`; `games.size() == totalFixtures` |
| One division fully blocked (field block covers entire season) | That division's `DivisionSummary.gamesAssigned == 0`; other divisions fully assigned; `Success.targetMet == false` |
| Multi-division partial: one division has insufficient slots, others do not | Only the deficit division shows `partial` in its `DivisionSummary`; others show `targetMet` |
| `ScheduleResult.success(...)` factory: new 4-arg signature compiles | Compile-time only; no runtime assertion needed |

**`ScheduleCommandTest` additions:**

| Test | Assertion |
|---|---|
| `assign` success — progress lines present | stdout contains `[0:00]` start line and a solver-complete line |
| `assign` success — constraint summary present | stdout contains "Constraint Summary" and "target-met" |
| `assign` partial — constraint summary shows "partial" | stdout contains "partial" in constraint summary STATUS column |
| `assign` partial — per-team shortfall printed | stdout contains "Teams that fell short" and game counts in `N/M` format |
| `assign` partial — exit code 0 | partial is not a failure; `exitCode == 0` |
| `assign` partial — draft saved | `league.json` contains `schedule.games` with assigned count > 0 and < total fixtures |

For integration tests that invoke the CP-SAT solver, use a minimal league (2 teams, 1 field, 3-day season) to keep per-test runtime under 30 seconds. Partial-schedule tests use a 1-day × 1-slot season against a 4-team division (12 fixtures → guaranteed partial).

### Failure Mode Summary

| Failure | Behavior |
|---|---|
| Solver returns `INFEASIBLE` with `addAtMostOne` | Impossible in theory (0 games is always feasible). Return `Failure("Internal error: solver returned INFEASIBLE unexpectedly. Please report this bug.")`. No draft saved. |
| Solver returns `UNKNOWN` (no solution found before timeout) | Return `Failure("Error: Solver timed out without assigning any games.")`. No draft saved. |
| `ProgressCallback.onSolutionCallback()` throws | OR-Tools native bridge swallows exceptions from callbacks. Progress lines may be missing; solve continues. No user-visible error. |
| `System.out` closed / piped to broken pipe | `PrintStream.flush()` is a no-op on a closed stream. Subsequent progress lines may be silently dropped. Solve continues; result is unaffected. |

### Deployment / Rollback

- Changes are confined to four files: `SchedulerService.java`, `ScheduleResult.java`, `ScheduleCommand.java`, and the new `DivisionSummary.java`.
- No `league.json` schema changes. `DivisionSummary` is transient, not persisted.
- Existing DRAFT and FINALIZED schedules are unaffected — `Schedule.games` is stored as-is.
- Rollback: revert the four files listed above. No data corruption risk.

---

## Out of Scope / Future Work

- **`--timeout <seconds>` flag** — can be added without design changes by passing the value into `SchedulerService.assign()`.
- **Exporting the constraint log** — progress lines go to stdout only; file export is explicitly out of scope per PRD.
- **Interruptible generation (Ctrl+C)** — the organizer cannot pause or cancel a running Phase 2 in v2 per PRD.
- **Guaranteed progress milestone timing** — milestones are triggered by solution callbacks, not a wall-clock timer. A dedicated background timer thread is the correct fix for strict timing; deferred because it requires cross-thread stdout coordination.
- **Configurable buffer time** — fixed at 15 minutes per PRD.
- **Week-load balance for unassigned games** — the week-load objective minimizes imbalance across assigned games only. In a deeply partial schedule, the optimal balance for assigned games may not reflect the ideal for the full schedule. Acceptable for MVP.

---

## Implementation Plan

Tasks are ordered by dependency. Tasks within the same milestone can be worked in parallel.

### Milestone 1 — Data Model

**Task 1.1 — Add `DivisionSummary` record**
- File: `src/main/java/org/leagueplan/planr/scheduler/DivisionSummary.java`
- Fields: `String divisionName`, `int gamesRequested`, `int gamesAssigned`, `int slotsAvailable`
- No compact constructor logic needed

**Task 1.2 — Extend `ScheduleResult.Success`**
- File: `src/main/java/org/leagueplan/planr/scheduler/ScheduleResult.java`
- Add `boolean targetMet` and `List<DivisionSummary> divisionSummaries` to `Success` record
- Update static factory `success(...)` to accept all four parameters

**Task 1.3 — Update call sites of `ScheduleResult.success()`**
- `SchedulerService.buildAndSolve()`: pass `targetMet` and `divisionSummaries` (Task 2.6 provides the values)
- `ScheduleCommand.Assign`: update pattern-match destructuring to read new fields
- `SchedulerServiceTest`: update `ScheduleResult.success(games, optimal)` call sites to 4-arg form

---

### Milestone 2 — `SchedulerService` Core Changes

**Task 2.1 — Replace `addExactlyOne` with `addAtMostOne` + `isAssigned`**

In `buildAndSolve()`:
1. Add `Map<UUID, BoolVar> isAssigned = new HashMap<>()` before the fixture loop.
2. Replace `model.addExactlyOne(lits)` with `model.addAtMostOne(boolVars)` (where `boolVars` is `BoolVar[]` cast from the existing `Literal[]`).
3. After `addAtMostOne`: create `BoolVar assigned = model.newBoolVar("assigned_" + fixtureId)`, add `model.addEquality(assigned, LinearExpr.sum(boolVars))`, store in `isAssigned`.

**Task 2.2 — Add `totalAssigned` IntVar and update objective**

After the fixture loop:
1. Create `IntVar totalAssigned = model.newIntVar(0, totalFixtures, "total_assigned")`.
2. Add `model.addEquality(totalAssigned, LinearExpr.sum(isAssigned.values().toArray(new IntVar[0])))`.
3. Replace `model.minimize(maxWeekLoad)` with the weighted maximize expression (see Solver Design section).

**Task 2.3 — Extend time limit**

Change `SOLVER_TIME_LIMIT_SECONDS = 60` to `SOLVER_TIME_LIMIT_SECONDS = 300`.

**Task 2.4 — Remove hard infeasibility block**

Remove the call to `checkSimpleFeasibility(...)` and its `if (infeasibilityMsg != null) return ScheduleResult.failure(...)` guard. The slot count data from `slotsByDiv` is still used; only the blocking behavior is removed.

**Task 2.5 — Add `computeSlotCounts()` method**

```java
private Map<UUID, Integer> computeSlotCounts(Map<UUID, List<Slot>> slotsByDiv) {
    Map<UUID, Integer> counts = new HashMap<>();
    slotsByDiv.forEach((divId, slots) -> counts.put(divId, slots.size()));
    return counts;
}
```

Call from `assign()` after `enumerateAllSlots()`. Pass `slotCounts` into `buildAndSolve()`.

**Task 2.6 — Assemble `DivisionSummary` list in `buildAndSolve()`**

After collecting assigned `ScheduledGame` list from solver vars:

```java
Map<UUID, Long> assignedByDiv = allGameVars.stream()
    .filter(gv -> solver.booleanValue(gv.var()))
    .collect(Collectors.groupingBy(gv -> gv.fixture().divisionId(), Collectors.counting()));

List<DivisionSummary> summaries = fixturesByDiv.entrySet().stream()
    .map(e -> {
        UUID divId = e.getKey();
        int requested = e.getValue().size();
        int assigned = assignedByDiv.getOrDefault(divId, 0L).intValue();
        int slots = slotCounts.getOrDefault(divId, 0);
        return new DivisionSummary(divisionName(league, divId), requested, assigned, slots);
    })
    .sorted(Comparator.comparing(DivisionSummary::divisionName))
    .toList();

boolean targetMet = summaries.stream().allMatch(DivisionSummary::targetMet);
return ScheduleResult.success(games, status == CpSolverStatus.OPTIMAL, targetMet, summaries);
```

**Task 2.7 — Add `ProgressCallback` inner class**

Add the static inner class as specified in the Solver Design section. Instantiate and pass to `solver.solve(model, callback)`.

**Task 2.8 — Emit feasibility-check progress line from `assign()`**

After `enumerateAllSlots()` and `computeSlotCounts()`, and before `buildAndSolve()`:

```java
int elapsed = (int)((System.currentTimeMillis() - startMs) / 1000);
boolean anyDeficit = fixturesByDiv.entrySet().stream()
    .anyMatch(e -> slotCounts.getOrDefault(e.getKey(), 0) < e.getValue().size());
if (anyDeficit) {
    // Build a short deficit description (e.g., "AAA deficit (12 games, 10 slots)")
    System.out.printf("[%d:%02d] Feasibility check: %s. Solver started.%n", ...);
} else {
    System.out.printf("[%d:%02d] Feasibility check passed. Solver started. %d games across %d division(s).%n",
        elapsed / 60, elapsed % 60, totalFixtures, fixturesByDiv.size());
}
System.out.flush();
```

Track `startMs = System.currentTimeMillis()` at the top of `assign()`.

**Task 2.9 — Emit solver-complete progress line**

Immediately after `solver.solve()` returns (before assembling games):

```java
int elapsed = (int)((System.currentTimeMillis() - startMs) / 1000);
long assignedCount = isAssigned.values().stream()
    .filter(v -> solver.booleanValue(v)).count();
String statusStr = (assignedCount == totalFixtures ? "target-met" : "partial")
    + (status == CpSolverStatus.OPTIMAL ? ", optimal" : "");
System.out.printf("[%d:%02d] Solver complete. %d of %d games assigned (%s).%n",
    elapsed / 60, elapsed % 60, assignedCount, totalFixtures, statusStr);
System.out.flush();
```

**Task 2.10 — Handle UNKNOWN solver status**

```java
if (status == CpSolverStatus.UNKNOWN) {
    return ScheduleResult.failure(
        "Error: Solver timed out without assigning any games. "
        + "Try extending the season or adding more field availability.");
}
if (status == CpSolverStatus.INFEASIBLE) {
    return ScheduleResult.failure(
        "Error: Solver returned an unexpected result. Please report this bug.");
}
```

---

### Milestone 3 — `ScheduleCommand.Assign` Changes

**Task 3.1 — Emit Phase 2 start line before calling `SchedulerService.assign()`**

```java
long totalFixtures = league.teamSchedule().games().size();
long divCount = byDiv.size();
System.out.printf("[0:00] Phase 2 started. %d games across %d division(s).%n",
    totalFixtures, divCount);
System.out.flush();
```

Remove the existing `"Generating schedule, this may take up to 60 seconds..."` line.

**Task 3.2 — Add `printConstraintSummary()` static method**

Called after receiving `ScheduleResult.Success`. Renders the constraint summary table using `success.divisionSummaries()`. Column widths computed dynamically. After the table, prints the "All targets met." or "Warning: N game(s) could not be assigned." summary line.

**Task 3.3 — Add `printTeamShortfall()` static method**

Called only when `!success.targetMet()`. Receives `league.teamSchedule()` and `success.games()`. Computes per-team shortfall as described in the data model section. Groups output by division (only divisions with unassigned games appear).

**Task 3.4 — Update final status line**

Replace:
```java
String qualifier = success.optimal() ? "optimal distribution" : "good distribution — optimizer ran for 60s";
System.out.printf("Draft schedule generated: %d games across %d division(s) (%s).%n...", ...);
```

With the three-case format described in the CLI contract section.

**Task 3.5 — Write new integration tests in `ScheduleCommandTest`**

Cover all test cases listed in the Testing Strategy section. For partial-schedule tests, configure a 4-team division with a 2-day × 1-slot season (narrow 09:00–10:00 window) so the solver runs in < 5 seconds and produces a guaranteed partial result.

---

### Milestone 4 — Cleanup and Verification

**Task 4.1 — Update `SchedulerServiceTest`**

- Add partial-schedule test cases (see Testing Strategy section).
- Update existing `ScheduleResult.success(games, optimal)` call sites to the new 4-arg form.

**Task 4.2 — Run full test suite**

```bash
gradle test
```

All tests must pass. Pay special attention to:
- Existing `SchedulerServiceTest` tests that check `Success.optimal()` — verify they still pass with the new objective.
- Existing `ScheduleCommandTest` tests that assert on the "Draft schedule generated" output — update to match the new "Draft schedule saved" format.

**Task 4.3 — End-to-end smoke test**

```bash
gradle installDist
# Setup
./build/install/planr/bin/planr config set --sunrise 09:00 --sunset 18:00 --start 2026-06-01 --end 2026-08-31
./build/install/planr/bin/planr division add Majors --duration 90 --target 6
./build/install/planr/bin/planr team add Majors "Blue Jays"
./build/install/planr/bin/planr team add Majors "Cardinals"
./build/install/planr/bin/planr team add Majors "Red Sox"
./build/install/planr/bin/planr field add "Riverside Park"

# Phase 1
./build/install/planr/bin/planr schedule generate

# Phase 2 (target-met path)
echo yes | ./build/install/planr/bin/planr schedule assign
# Expected: progress lines, constraint summary (all target-met), draft saved

# Verify
./build/install/planr/bin/planr schedule status
./build/install/planr/bin/planr schedule view
```

**Partial-schedule smoke test:**

```bash
# Override season to 3 days (3 slots × 1 field < 6 games for 3 teams)
./build/install/planr/bin/planr config set --start 2026-06-01 --end 2026-06-03
echo yes | ./build/install/planr/bin/planr schedule assign
# Expected: partial constraint summary, per-team shortfall, draft saved with < 6 games
./build/install/planr/bin/planr schedule view   # shows partial draft
```
