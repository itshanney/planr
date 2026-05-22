# Tech Spec: Phase 1 — Team Schedule Generation & Review

* **Date:** 2026-05-17
* **Status:** Ready for Implementation
* **Scope:** "Phase 1: Team Schedule Generation" and "Team Schedule Review" acceptance criteria from `features/2026-05-17-league-planner-core-scheduling-v2.md`
* **Supersedes / Coordinates with:** `specs/2026-05-16-schedule-generation-cli.md` — Phase 2 (`SchedulerService`) is modified in this slice to consume `TeamSchedule` as input instead of generating its own fixtures; those changes are detailed here.
* **Phase:** CLI prototype (follows v2 entity management slice)

---

## Overview

This slice splits the existing single-step schedule generation into two explicit user-initiated phases:
* **Phase 1** (new `planr schedule generate`) produces a **team schedule** — a full list of matchups with home/away assignments — using a round-robin algorithm plus fill games. It is deterministic, near-instant, and requires no constraint solver. 
* **Phase 2** (renamed `planr schedule assign`, was `planr schedule generate`) accepts the confirmed team schedule as input and runs OR-Tools CP-SAT to assign each game a date, start time, and field. 

A new review step sits between the two phases: the organizer inspects the team schedule, optionally edits home/away assignments, and explicitly confirms before field assignment begins.

Two new model records are introduced (`TeamGame`, `TeamSchedule`) and the `League` record gains a `teamSchedule` field distinct from the existing `schedule` field. The schedule lifecycle expands from two states (Draft, Finalized) to three (Team Schedule, Draft, Finalized). No schema version bump is required — `FAIL_ON_UNKNOWN_PROPERTIES=false` absorbs the additive `teamSchedule` field transparently for any existing v4 files.

The `SchedulerService` is refactored to remove internal fixture generation; it now receives the confirmed `TeamSchedule` from `League` and converts it into `Fixture` objects for the solver. All other `SchedulerService` behavior (slot enumeration, CP-SAT model, solve, result assembly) is unchanged.

---

## Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  planr (CLI entry point)                                                    │
│  PlanrApp — root @Command; wires subcommands, injects store                 │
└──────────────────────┬──────────────────────────────────────────────────────┘
                       │ dispatches to
    ┌──────────────────┼──────────────────────┐
    ▼                  ▼                       ▼
┌──────────┐    ┌──────────┐    ┌────────────────────────────────────────────┐
│ Division │    │ Field/   │    │ ScheduleCommand                            │
│ /Team/   │    │ Block/   │    │ generate / assign / game-edit / game-      │
│ Config   │    │ Override │    │ override / status / finalize / view /      │
│(unchanged│    │(unchanged│    │ export                                     │
└──────────┘    └──────────┘    └────────────────────┬───────────────────────┘
                                                     │
                          ┌──────────────────────────┼──────────────────┐
                          ▼                          ▼                  ▼
              ┌────────────────────┐   ┌─────────────────────┐  ┌─────────────┐
              │ TeamScheduleService│   │ SchedulerService    │  │ LeagueStore │
              │ Phase 1: round-    │   │ Phase 2: slot enum  │  │ (unchanged  │
              │ robin + fill games │   │ → CP-SAT solve →    │  │ persistence │
              │ → TeamSchedule     │   │ ScheduledGame list  │  │ layer)      │
              └────────────────────┘   └────────────┬────────┘  └──────┬──────┘
                                                    │                  │
                                                    ▼                  ▼
                                       ┌────────────────────┐  ┌─────────────────────┐
                                       │ OR-Tools CP-SAT    │  │ ~/.planr/league.json│
                                       │ Solver             │  └─────────────────────┘
                                       └────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|---|---|
| `ScheduleCommand` | Picocli command group; dispatches Phase 1 and Phase 2; owns the review/confirmation prompt; delegates to `TeamScheduleService` or `SchedulerService`; persists via `LeagueStore` |
| `TeamScheduleService` | Deterministic Phase 1 computation: round-robin generation (circle method), fill game extension to target, home/away balance, per-fill-round progress logging |
| `SchedulerService` | Phase 2 constraint solve: converts `TeamGame` list → `Fixture` list, enumerates slots, builds and solves CP-SAT model, assembles `ScheduledGame` list |
| `TeamSchedule` (record) | Immutable value: ordered list of `TeamGame` records produced by Phase 1 |
| `TeamGame` (record) | Immutable value: one game in the team schedule — game number, home/away team IDs and names, division ID and name, game duration |
| `LeagueStore` | Unchanged — `League` serialization already handles nullable fields transparently |

---

## Data Model

### New Records

```
TeamGame
  ├── id: UUID                     (stable identity; survives home/away edits)
  ├── gameNumber: int              (1-based display index; stable after Phase 1)
  ├── homeTeamId: UUID
  ├── homeTeamName: String         (denormalized at Phase 1 time)
  ├── awayTeamId: UUID
  ├── awayTeamName: String         (denormalized at Phase 1 time)
  ├── divisionId: UUID
  ├── divisionName: String         (denormalized at Phase 1 time)
  └── gameDurationMinutes: int     (denormalized from division at Phase 1 time)

TeamSchedule
  └── List<TeamGame> games         (ordered by gameNumber)
```

**Key methods on `TeamGame`:**
- `withSwappedHomeAway()` → returns new `TeamGame` with home and away swapped; preserves `id` and `gameNumber`.

**Key methods on `TeamSchedule`:**
- `withGameReplaced(int gameNumber, TeamGame replacement)` → returns new `TeamSchedule` with the identified game replaced.
- `findGame(int gameNumber)` → `Optional<TeamGame>`.

### Changes to Existing Records

**`League` (no version bump — additive field):**

```
League (v4, unchanged version)
  ├── version: int           (4, unchanged)
  ├── config: LeagueConfig   (unchanged)
  ├── divisions: List<Division>  (unchanged)
  ├── fields: List<Field>    (unchanged)
  ├── teamSchedule: TeamSchedule   ← NEW nullable field (null = Phase 1 not yet run)
  └── schedule: Schedule     (unchanged — null = Phase 2 not yet run)
```

**New methods on `League`:**
- `withTeamSchedule(TeamSchedule ts)` → returns new `League` with `teamSchedule` set.
- `withTeamScheduleCleared()` → returns new `League` with `teamSchedule = null` and `schedule = null` (used when Phase 1 is re-run to discard both).

**Schedule lifecycle state — derived from `League` fields:**

```java
enum ScheduleState { NONE, TEAM_SCHEDULE, DRAFT, FINALIZED }

static ScheduleState of(League league) {
    if (league.schedule() != null) {
        return league.schedule().status() == ScheduleStatus.FINALIZED
            ? FINALIZED : DRAFT;
    }
    if (league.teamSchedule() != null) return TEAM_SCHEDULE;
    return NONE;
}
```

No new enum type is required; `ScheduleState` is a package-private helper computed on demand. (A separate `ScheduleState.java` file is fine if the logic is reused across multiple commands.)

### JSON Shape Changes

The `teamSchedule` field is added to the root `League` object. Existing v4 files that lack this key deserialize with `teamSchedule = null` (Jackson's `FAIL_ON_UNKNOWN_PROPERTIES=false` already handles missing fields). No migration guard is needed.

```json
{
  "version": 4,
  "config": { ... },
  "divisions": [ ... ],
  "fields": [ ... ],
  "teamSchedule": {
    "games": [
      {
        "id":                  "a1b2c3d4-...",
        "gameNumber":          1,
        "homeTeamId":          "t1...",
        "homeTeamName":        "Blue Jays",
        "awayTeamId":          "t2...",
        "awayTeamName":        "Cardinals",
        "divisionId":          "d1...",
        "divisionName":        "Majors",
        "gameDurationMinutes": 120
      }
    ]
  },
  "schedule": null
}
```

`teamSchedule` is `null` (JSON `null`) when Phase 1 has not been run.

---

## Phase 1 Algorithm — `TeamScheduleService`

### Method Signature

```java
public sealed interface TeamScheduleResult {
    record Success(TeamSchedule schedule, List<String> fillRoundLogs) implements TeamScheduleResult {}
    record Failure(String message) implements TeamScheduleResult {}
}

public TeamScheduleResult generate(League league)
```

### Precondition Validation (fail fast, in order)

1. `league.config().seasonStart()` and `league.config().seasonEnd()` are both non-null. If not: `"Error: Season start and end dates must be configured before generating a team schedule. Run 'planr config set --start ... --end ...'."` → `Failure`.
2. At least one division with ≥ 2 teams exists. If not: `"Error: At least one division with 2 or more teams is required."` → `Failure`.
3. For each division with ≥ 2 teams: if `division.targetGamesPerTeam() < (N - 1)` where `N = division.teams().size()`: `"Error: Division \"Majors\" target of 2 games per team is less than the minimum of 3 required for a single round-robin with 4 teams. Minimum target is N−1 = 3."` → `Failure`. Check all divisions and report all failures.

### Step 1 — Round-Robin Generation (circle method)

For each division with ≥ 2 teams and N = team count:

**If N is even:** run the circle method directly.

**If N is odd:** add a synthetic bye entry at index N; run the circle method on N+1 positions; discard any game involving the bye. The resulting game set is identical to an even team count — N*(N-1)/2 total games.

**Circle method for the first direction (pass A):**

```
teams = division.teams() as array, indices 0..N-1 (or 0..N for odd with bye)
M = N if N even, else N+1  (must be even)
fixed = teams[0]
rotating = teams[1..M-1]  (M-1 entries)

For round r = 0 to M-2:
  circle = [fixed] + rotate(rotating, r)   // rotate right by r positions
  For i = 0 to M/2 - 1:
    left  = circle[i]
    right = circle[M - 1 - i]
    home  = left  if (i + r) % 2 == 0 else right
    away  = right if (i + r) % 2 == 0 else left
    emit (home, away) if neither is the bye
```

This assignment rule ensures no team is home for all games in the first half of the round-robin schedule.

**Result:** N*(N-1)/2 total games. Each team plays exactly N-1 games. Home/away balance is approximately even per team; exact per-team home and away counts vary by position and round and must be tracked from actual output (not derived from N).

### Step 2 — Fill Games

Initialize per-team counters from the round-robin output (accumulated while emitting games in Step 1):
```
homeCount[team] = actual home games for this team emitted in Step 1
awayCount[team] = actual away games for this team emitted in Step 1
gameCount[team] = N - 1         (each team plays exactly N-1 games in a single round-robin)
```

**Fill round loop:**

```
fillRound = 0
while any team in any division has gameCount < target:
    fillRound++
    eligible = [teams where gameCount < target], sorted by (gameCount ASC, UUID ASC)
    paired = []
    for i = 0 to eligible.size() - 2 step 2:
        a = eligible[i], b = eligible[i+1]
        // assign home to team with greater relative away imbalance
        aImbalance = awayCount[a] - homeCount[a]   // positive = more away than home
        bImbalance = awayCount[b] - homeCount[b]
        if aImbalance >= bImbalance: home=a, away=b
        else:                         home=b, away=a
        emit game(home=a_or_b, away=b_or_a)
        homeCount[home]++, awayCount[away]++
        gameCount[a]++, gameCount[b]++
    // if eligible.size() is odd, last team sits out this round (gets no fill game)
    
    log: "Fill round {fillRound} complete: {team1} {count1}, {team2} {count2}, ..."
         (all teams in the division, not just eligible ones)
```

**Game numbering:** Games are numbered globally across all divisions in a single sequential series starting at 1. Round-robin games for all divisions come first (ordered by division index, then round, then pair position), followed by fill games in fill-round order. This gives stable, predictable numbering.

**Imbalance tiebreak:** When `aImbalance == bImbalance`, team with lexicographically smaller UUID becomes home. Deterministic; produces consistent output on re-runs.

### Step 3 — Assemble TeamSchedule

Collect all games from all divisions in the order they were emitted (round-robin games then fill games, all divisions interleaved by round). Assign game numbers 1..total sequentially. Wrap in `TeamSchedule(games)` and return `TeamScheduleResult.Success`.

---

## CLI Command Contracts

All commands: `stdout` on success, `stderr` on error. Exit codes: `0` = success, `1` = validation error, `2` = I/O error.

---

### `planr schedule generate` ← NEW (Phase 1)

**Preconditions:**
- Season start and end dates are configured in `LeagueConfig`.
- At least one division has ≥ 2 teams.
- Per-division: `targetGamesPerTeam >= (N - 1)` where N = team count.
- Schedule is not in FINALIZED state (a DRAFT or TEAM_SCHEDULE is silently replaced after confirmation).

**Behavior:**

1. Load league. Determine current `ScheduleState`.
2. If `FINALIZED`: print error and exit 1.
3. If `DRAFT` or `TEAM_SCHEDULE`: print `"Warning: Re-running Phase 1 will discard the existing team schedule and any draft schedule. Type 'yes' to continue: "`. Read stdin; if not `yes`, print `"Cancelled."` and exit 0.
4. Call `TeamScheduleService.generate(league)`.
5. On `Failure`: print error to stderr, exit 1.
6. On `Success`:
   - Print fill round logs to stdout (if any fill rounds ran).
   - Print full team schedule table to stdout (see format below).
   - Save `league.withTeamScheduleCleared().withTeamSchedule(result.schedule())` — this clears both the old team schedule and any existing draft before saving.
   - Print summary line.
   - Exit 0.

**Team schedule table format:**

```
Team schedule generated: 48 games across 3 divisions.

#    HOME                AWAY                DIVISION
---  ------------------  ------------------  --------
1    Blue Jays           Cardinals           Majors
2    Red Sox             Yankees             Majors
3    Cardinals           Blue Jays           Majors
...
48   Tigers              Braves              T-Ball

Review the matchups. Run 'planr schedule game edit <#> --home <team>' to adjust home/away.
Run 'planr schedule assign' when ready to assign dates, times, and fields.
```

Games are printed in game-number order. Each row: `#`, home team, away team, division name. Column widths padded to the longest value in each column (minimum widths: 3, 18, 18, 8).

**Error messages:**

| Condition | Message (stderr) | Exit |
|---|---|---|
| FINALIZED schedule exists | `Error: A finalized schedule exists. Phase 1 cannot be re-run. Use 'planr schedule game override' for individual adjustments.` | 1 |
| No season dates configured | `Error: Season start and end dates must be configured. Run 'planr config set --start <date> --end <date>'.` | 1 |
| No division with ≥ 2 teams | `Error: At least one division with 2 or more teams is required to generate a team schedule.` | 1 |
| Target too low | `Error: Division "Majors" target of 2 games per team is less than the minimum of 3 required for a single round-robin with 4 teams. Minimum target is 3.` | 1 |

---

### `planr schedule assign` ← RENAMED from `planr schedule generate` (Phase 2)

**Breaking change from v1:** The old `--start` and `--end` arguments are removed. Season dates now come from `LeagueConfig`. The command name changes from `generate` to `assign`.

**Preconditions:**
- A `teamSchedule` exists (Phase 1 must have been run).
- At least one field is configured.
- Season start and end dates are in `LeagueConfig`.

**Behavior:**

1. Load league. Determine `ScheduleState`.
2. If `NONE`: error — Phase 1 required.
3. If `FINALIZED`: error — cannot re-assign a finalized schedule.
4. Display the current team schedule in condensed form (game count per division, totals).
5. Compute per-division feasibility estimate (see below). If any division is estimated to have fewer available slots than games, print a warning (non-blocking).
6. Print confirmation prompt: `"Confirm this team schedule and begin field assignment? This may take up to 5 minutes. Type 'yes' to continue: "`. Read stdin; if not `yes`, print `"Cancelled."` and exit 0.
7. Call `SchedulerService.assign(league)` (reads `league.teamSchedule()` and `league.config()` for season dates).
8. On `Failure`: print error to stderr, exit 1. Do not write any file on failure.
9. On `Success`: save draft schedule; print result summary; exit 0.

**Pre-Phase-2 feasibility warning format (non-blocking):**

```
Warning: AAA division has 24 games but only ~18 slots estimated in the season window.
         Field assignment may produce a partial schedule.
```

The slot estimate is a fast computation: for each field, walk each date in the season and compute `floor(effective_open_minutes_for_division / (gameDurationMinutes + 15))`, summing across all fields and dates for the division. This reuses the slot enumeration logic in `SchedulerService` without invoking the solver.

**Error messages:**

| Condition | Message (stderr) | Exit |
|---|---|---|
| No team schedule | `Error: No team schedule found. Run 'planr schedule generate' first.` | 1 |
| No fields configured | `Error: At least one field must be configured before field assignment. Run 'planr field add'.` | 1 |
| No season dates | `Error: Season start and end dates must be configured. Run 'planr config set --start <date> --end <date>'.` | 1 |
| FINALIZED schedule | `Error: A finalized schedule exists. Run 'planr schedule game override' for individual adjustments.` | 1 |

---

### `planr schedule game edit <gameNumber> --home <teamName>` ← NEW

Registered as a subcommand under `ScheduleGameCommand` alongside the existing `override` subcommand. Available in `TEAM_SCHEDULE` and `DRAFT` states.

**Parameters:**
- `<gameNumber>`: positional, 1-based index from `planr schedule view` team schedule output.
- `--home <teamName>`: the team that should become the home team. The other team in the game becomes the away team.

**Behavior:**

1. Load league. Determine `ScheduleState`.
2. Validate state is `TEAM_SCHEDULE` or `DRAFT`. If not: error.
3. Find `teamGame` by `gameNumber` in `league.teamSchedule().games()`. If not found: error.
4. Resolve `teamName` against `teamGame.homeTeamName()` and `teamGame.awayTeamName()` (case-insensitive). If it matches neither: error.
5. If `teamName` already matches `teamGame.homeTeamName()`: print `"No change — <teamName> is already the home team for game #N."` and exit 0.
6. Build `updatedGame = teamGame.withSwappedHomeAway()`.
7. Build updated `teamSchedule` with `updatedGame` at position `gameNumber`.
8. Save `league.withTeamSchedule(updatedTeamSchedule)`. Note: if in DRAFT state, the existing `schedule` (field assignment) is NOT cleared — the organizer must re-run `planr schedule assign` to apply the edit to the field assignment.
9. Print: `"Game #N updated: <newHomeName> (home) vs <newAwayName> (away)."` and exit 0.

**Error messages:**

| Condition | Message (stderr) | Exit |
|---|---|---|
| NONE or FINALIZED state | `Error: 'game edit' requires a team schedule. Run 'planr schedule generate' first.` or `Error: 'game edit' is not available for a finalized schedule. Use 'planr schedule game override'.` | 1 |
| Invalid game number | `Error: Game #99 not found. Valid range is 1–48.` | 1 |
| Team name not in game | `Error: "Grizzlies" is not a team in game #5. Valid options: "Blue Jays", "Cardinals".` | 1 |

---

### `planr schedule view` — MODIFIED

**Added behavior for `TEAM_SCHEDULE` state:** when no `schedule` exists but `teamSchedule` does, display the team schedule table (same format as the output of `planr schedule generate`).

```
Schedule status: TEAM_SCHEDULE

#    HOME                AWAY                DIVISION
---  ------------------  ------------------  --------
1    Blue Jays           Cardinals           Majors
...
```

**Existing Draft/Finalized behavior:** unchanged. Filter flags (`--division`, `--team`, `--field`) apply only to Draft/Finalized views; they are ignored (with a notice) in TEAM_SCHEDULE state since no field assignments exist yet.

**No schedule:** existing message unchanged.

---

### `planr schedule status` — MODIFIED

Extended to display `TEAM_SCHEDULE` state:

```
Status:       TEAM_SCHEDULE
Total games:  48
  Majors:     20 games (10 target per team, 4 teams)
  AAA:        12 games (6 target per team, 4 teams)
  T-Ball:      6 games (6 target per team, 3 teams)
Phase 2 not yet run. Run 'planr schedule assign' to assign dates, times, and fields.
```

For NONE state: `"No schedule generated yet. Run 'planr schedule generate' to start."` (unchanged).

---

### `planr schedule export` — MODIFIED

**Added behavior for `TEAM_SCHEDULE` state:** exports the team schedule JSON per the spec.

Team schedule JSON format:

```json
[
  {
    "game_number":    1,
    "home_team":      "Blue Jays",
    "away_team":      "Cardinals",
    "division_name":  "Majors"
  }
]
```

Games are exported in game-number order. No `date`, `start_time`, or `field_name` fields are included.

**Behavior:** print JSON array to stdout; print `"Exported 48 games (team schedule)."` to stderr.

**Draft/Finalized export:** unchanged (full schedule JSON format).

---

## SchedulerService Refactoring

The existing `SchedulerService.generate(League, LocalDate, LocalDate)` method is replaced by `assign(League)`. All internal behavior is preserved except fixture generation, which is removed.

**New method signature:**

```java
public ScheduleResult assign(League league)
```

**Changes:**
1. **Remove** the internal `generateFixtures(Division)` method (circle method + home/away table).
2. **Add** `List<Fixture> toFixtures(TeamSchedule ts)` — converts each `TeamGame` to a `Fixture(homeTeamId, awayTeamId, divisionId, gameDurationMinutes)`. One-to-one mapping; order preserved.
3. **Read season dates** from `league.config().seasonStart()` and `league.config().seasonEnd()` instead of method parameters.
4. All CP-SAT model construction, slot enumeration, solve, and result assembly logic is unchanged.

**Slot estimator for feasibility warning:**

Extract a package-private method from `SchedulerService`:

```java
int estimateAvailableSlots(League league, UUID divisionId, int gameDurationMinutes)
```

Walks each date in the season, computes effective open window per field per date (respecting blocks and overrides), sums `floor(effectiveMinutes / (gameDurationMinutes + 15))` across all fields, and returns the total. Called by `ScheduleCommand.Assign` before the confirmation prompt.

---

## Critical Path Walkthroughs

### 1. First-Time Phase 1 Run

```
User: planr schedule generate

1. Load league (NONE state — no teamSchedule, no schedule).
2. Precondition checks: season dates set ✓, ≥1 division with ≥2 teams ✓, targets sufficient ✓.
3. TeamScheduleService.generate(league):
   a. For each division (e.g., Majors: 4 teams, target=10):
      - Round-robin: circle method, M=4 (even), 3 rounds (single direction — pass A only)
      - Pass A: 6 games; each team plays 3 (≈1–2 home, ≈1–2 away depending on position)
      - homeCount/awayCount/gameCount accumulated from actual round-robin output
      - Target=10, current=3 per team → fill needed: 7 more per team
      - Fill round 1: eligible=[all 4 teams], pairs=(Blue Jays, Cardinals), (Red Sox, Yankees)
        - home/away assigned by away imbalance (tracked from round-robin)
        - Log: "Fill round 1 complete: Blue Jays 4, Cardinals 4, Red Sox 4, Yankees 4"
      - Fill rounds 2..7: similar logic; each team reaches 10 games (14 fill games total)
      - Total: 20 games for Majors division
   b. Repeat for other divisions.
   c. Assign global game numbers 1..total.
4. Print fill round logs: "Fill round 1 complete: ..."
5. Print team schedule table.
6. Save league with new teamSchedule (schedule remains null).
7. Print summary: "Team schedule generated: 48 games across 3 divisions."
8. Exit 0.
```

---

### 2. Phase 2 — Field Assignment (the `assign` command)

```
User: planr schedule assign

1. Load league (TEAM_SCHEDULE state — teamSchedule exists, schedule is null).
2. Precondition checks: teamSchedule ≠ null ✓, fields ≠ empty ✓, season dates set ✓.
3. Display team schedule summary:
   "Team schedule: 48 games across 3 divisions (Majors 20, AAA 12, T-Ball 6)."
4. Compute feasibility estimates per division:
   - Majors: ~24 estimated slots vs 20 games → OK
   - AAA: ~10 estimated slots vs 12 games → WARNING printed
5. Print warning: "Warning: AAA division has 12 games but only ~10 slots estimated..."
6. Print confirmation prompt. User types "yes".
7. SchedulerService.assign(league):
   a. toFixtures(league.teamSchedule()) → 48 Fixture objects
   b. Slot enumeration using league.config() season dates → Map<divisionId, List<Slot>>
   c. Pre-solve check: AAA 12 fixtures > 10 slots → ScheduleResult.Failure(diagnostic)
   (Alternatively, solve proceeds and CP-SAT returns INFEASIBLE)
8. ScheduleCommand receives Failure:
   - Print: "Error: Cannot generate a valid schedule. ..." to stderr
   - Exit 1. No file write.
```

---

### 3. Edit Home/Away on Team Schedule

```
User: planr schedule game edit 3 --home Cardinals

1. Load league (TEAM_SCHEDULE state).
2. Find game #3 in teamSchedule.games() → {homeTeamName="Blue Jays", awayTeamName="Cardinals"}
3. Resolve "Cardinals" → matches awayTeamName (case-insensitive).
4. Build swapped game: {homeTeamName="Cardinals", awayTeamName="Blue Jays"}.
5. Build updated teamSchedule with replacement at gameNumber=3.
6. Save league with updated teamSchedule.
7. Print: "Game #3 updated: Cardinals (home) vs Blue Jays (away)."
8. Exit 0.
```

---

### 4. Re-run Phase 1 While in Draft State

```
User: planr schedule generate

1. Load league (DRAFT state — teamSchedule ≠ null, schedule ≠ null).
2. Precondition: not FINALIZED ✓.
3. State is DRAFT → print warning: "Warning: Re-running Phase 1 will discard the existing
   team schedule and any draft schedule. Type 'yes' to continue: "
4. User types "yes".
5. Run TeamScheduleService.generate(league) → Success(newTeamSchedule, logs).
6. Save: league.withTeamScheduleCleared().withTeamSchedule(newTeamSchedule)
   → teamSchedule = newTeamSchedule, schedule = null (draft discarded).
7. Print fill logs + team schedule table + summary.
8. Exit 0.
```

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| Confirmation step for Phase 2 | Separate `planr schedule confirm` command, inline prompt in `assign`, no confirmation | **Inline prompt in `assign`** | The spec requires explicit confirmation but three separate commands (generate → confirm → assign) adds friction with no benefit in a single-user CLI. The inline prompt in `assign` mirrors the existing `planr schedule finalize` pattern already in the codebase. | Confirmation cannot be scripted without stdin piping (`echo yes | planr schedule assign`). Acceptable for a CLI prototype; a `--yes` flag can be added later. |
| Schema versioning | Bump to v5 with migration, stay at v4 | **Stay at v4, additive field** | `FAIL_ON_UNKNOWN_PROPERTIES=false` already handles missing fields. Existing v4 files that lack `teamSchedule` will deserialize with `null`, which is the correct initial state. Adding a v5 migration would be a no-op except for the version number bump — pure overhead. | If a future v5 migration needs to set a non-null default for `teamSchedule`, there's no way to distinguish "file was v4 with no team schedule" from "file was v5 with no team schedule." Acceptable — the correct initial state in both cases is `null`. |
| Game numbering scope | Per-division (1-based within division), global (1-based across all divisions) | **Global sequential** | Game numbers in the display output must be unique for `planr schedule game edit <N>` to work unambiguously. Requiring the organizer to specify both division and game number adds complexity and doesn't match the spec. | Game numbers > total / division count are not intuitive for multi-division leagues, but the table display makes it clear. |
| Fill game pairing | Sort by deficit only, sort by (deficit, UUID) | **Sort by (deficit DESC, UUID ASC)** | Deficit-first ensures teams furthest from target get matched early each round. UUID tiebreak makes output deterministic across re-runs (same inputs → same team schedule). | UUID ordering is arbitrary and not semantically meaningful. Acceptable — fill games are interchangeable for scheduling purposes. |
| Minimum target enforcement | At division creation time, at Phase 1 time | **Phase 1 time only** | Team count is not fixed at division creation — teams can be added later. Enforcing at division creation would produce spurious errors or require re-validation on every team add. | A division with target=1 can be created and stored; it will fail Phase 1 if team count is ≥ 2. The list command already warns on target=0; the Phase 1 error message provides the actionable minimum. |
| Season dates in `assign` | Command args (`--start`, `--end`), read from `LeagueConfig` | **Read from `LeagueConfig`** | v2 feature spec explicitly moves season dates to the league-level config. Keeping them as command args would require duplicating them on every `assign` invocation and introduces divergence risk. | **Breaking change** from v1 `planr schedule generate --start ... --end ...`. Organizers must set dates via `planr config set` before running `assign`. This is clearly documented in the error message. |
| `game edit` home assignment | `--swap` flag (flip current), `--home <teamName>` (set explicitly) | **`--home <teamName>`** | "Swap" is ambiguous when the organizer doesn't remember the current assignment. Specifying the team that should be home is unambiguous, self-documenting, and consistent with how `game override` accepts team names. | Slightly more typing than `--swap`. Acceptable for a low-frequency edit operation. |
| Partial Phase 2 schedule | Block Phase 2 if feasibility estimate shows deficit, allow but warn | **Warn, don't block** | Slot estimation is an upper bound — the real slot count depends on inter-division slot sharing, game duration staggering, and buffer interactions. An estimated deficit doesn't guarantee a real failure. Blocking on an estimate would frustrate users with valid configurations. The actual CP-SAT failure gives precise diagnostics. | If the estimate shows a deficit and the user proceeds, they may spend 60 seconds waiting for a predictable failure. Acceptable — the warning signals the risk. |

---

## Operational Concerns

### Testing Strategy

**`TeamScheduleServiceTest`** — unit tests, no file I/O:

| Test | Assertion |
|---|---|
| Round-robin: 2 teams | 1 game; each team plays 1 game; fill games extend to target |
| Round-robin: 3 teams (odd) | 3 games; each team plays 2 games; no bye games in output |
| Round-robin: 4 teams | 6 games; each pair appears exactly once; home/away balance ≈ even per team |
| Round-robin: 6 teams | 15 games; each team plays 5 games; home/away balance ≈ even per team |
| Fill games: 4 teams, target=8 | 16 games total (6 round-robin + 10 fill); each team exactly 8 games; max |home - away| ≤ 1 |
| Fill games: 3 teams, target=8 | One team per round sits out; all teams reach ≥ 7 games (some 8, some 7 depending on pairing) |
| Fill round logging | `Success.fillRoundLogs()` has one entry per fill round; format matches spec |
| Target too low | `Failure` returned with correct division name and minimum value in message |
| No season dates | `Failure` returned |
| All divisions pass validation | `Success` returned; total game count = sum of N*(N-1)/2 + fill games per division |
| Determinism | Same league input → same `TeamSchedule` output on repeated calls |

**`ScheduleCommandTest`** — integration tests via `CommandTestBase`:

| Test | Assertion |
|---|---|
| `generate` — no season dates | Exit 1; stderr contains "config set" |
| `generate` — no divisions with ≥ 2 teams | Exit 1 |
| `generate` — target too low | Exit 1; stderr names failing division |
| `generate` — success | Exit 0; stdout contains table header + at least 1 game row; `league.json` has `teamSchedule` field |
| `generate` — re-run in TEAM_SCHEDULE state | Prompts for confirmation; "yes" → success, new teamSchedule; "no" → cancelled, old teamSchedule preserved |
| `generate` — re-run in DRAFT state | Prompts; "yes" → success, old schedule cleared |
| `generate` — FINALIZED state | Exit 1; error message references `game override` |
| `game edit` — success (swap) | Exit 0; stdout shows new home/away; `league.json` updated |
| `game edit` — already home team | Exit 0; "no change" message |
| `game edit` — wrong team name | Exit 1; names both valid options |
| `game edit` — invalid game number | Exit 1 |
| `game edit` — in NONE state | Exit 1 |
| `assign` — no teamSchedule | Exit 1 |
| `assign` — no fields | Exit 1 |
| `assign` — user types "no" | Exit 0; no schedule written |
| `assign` — success | Exit 0; `league.json` has `schedule` field in DRAFT status |
| `view` — TEAM_SCHEDULE state | Exit 0; stdout shows team schedule table with header |
| `view` — NONE state | Exit 1 |
| `status` — TEAM_SCHEDULE state | Exit 0; shows game counts per division |
| `export` — TEAM_SCHEDULE state | Exit 0; stdout is valid JSON array; contains `game_number`, `home_team`, `away_team`, `division_name`; no `date`, `start_time`, `field_name` |

**`LeagueStoreTest`** — JSON round-trip:
- Serialize and deserialize a `League` containing a `TeamSchedule` with multiple `TeamGame` records; verify all fields survive.
- Deserialize a v4 `league.json` without `teamSchedule` key; verify `league.teamSchedule() == null`.

---

## Out of Scope / Future Work

- **Phase 2 changes beyond the SchedulerService refactor** — the CP-SAT model, progress callbacks, partial schedule output, and constraint summary are Phase 2 concerns covered in `specs/2026-05-16-schedule-generation-cli.md` and its forthcoming v2 update.
- **Adding or removing games from the team schedule** — organizers may edit home/away assignments only. Game count is determined by Phase 1 configuration. Explicitly out of scope per the PRD.
- **Recurring fill game patterns** — fill games are distributed for balance only; per-matchup preferences are out of scope.
- **`planr schedule game edit --away <teamName>` form** — the `--home <teamName>` form is sufficient since the two teams in a game are fixed. Accepting `--away` as an alias can be added without design changes.
- **Web UI confirmation flow** — the inline stdin prompt is a CLI prototype pattern. The web layer will use a modal or a dedicated confirmation endpoint instead.
- **Export of fill-round constraint log** — logs go to stdout during generation only; file export is out of scope per the PRD.

---

## Implementation Plan

The following tasks are ordered by dependency. Tasks within the same milestone can be worked in parallel.

### Milestone 1 — Model Layer (no behavior changes)

**Task 1.1 — Add `TeamGame` record**
- File: `src/main/java/org/leagueplan/planr/model/TeamGame.java`
- Fields: `UUID id`, `int gameNumber`, `UUID homeTeamId`, `String homeTeamName`, `UUID awayTeamId`, `String awayTeamName`, `UUID divisionId`, `String divisionName`, `int gameDurationMinutes`
- Methods: `withSwappedHomeAway()` (returns new `TeamGame` with home/away swapped, same `id` and `gameNumber`)
- Compact constructor: no normalization needed

**Task 1.2 — Add `TeamSchedule` record**
- File: `src/main/java/org/leagueplan/planr/model/TeamSchedule.java`
- Field: `List<TeamGame> games` (compact constructor null-coalesces to `List.of()`)
- Methods:
  - `withGameReplaced(int gameNumber, TeamGame replacement)` → returns new `TeamSchedule`
  - `Optional<TeamGame> findGame(int gameNumber)`

**Task 1.3 — Extend `League` record**
- File: `src/main/java/org/leagueplan/planr/model/League.java`
- Add field: `TeamSchedule teamSchedule` (after `fields`, before `schedule`)
- Update compact constructor: null-coalesce `teamSchedule` to `null` (not a list — null means "not generated")
- Update `League.empty()`: `teamSchedule = null`
- Add methods:
  - `withTeamSchedule(TeamSchedule ts)` → returns new `League` with `teamSchedule` set
  - `withTeamScheduleCleared()` → returns new `League` with both `teamSchedule = null` and `schedule = null`

**Task 1.4 — Add `ScheduleState` helper**
- File: `src/main/java/org/leagueplan/planr/model/ScheduleState.java`
- Enum values: `NONE`, `TEAM_SCHEDULE`, `DRAFT`, `FINALIZED`
- Static factory: `ScheduleState of(League league)` implementing the three-field logic described in the data model section

**Task 1.5 — Write model layer tests**
- `TeamGame`: verify `withSwappedHomeAway()` preserves `id`, `gameNumber`, `divisionId`, `gameDurationMinutes` while swapping home/away fields
- `TeamSchedule`: verify `withGameReplaced` returns new record with target game replaced; verify `findGame` returns empty for unknown number
- `League`: verify `withTeamScheduleCleared()` nulls both `teamSchedule` and `schedule`; verify `League.empty()` has null `teamSchedule`
- `ScheduleState.of()`: test all four state transitions
- Jackson round-trip: `TeamGame`, `TeamSchedule`, `League` with `teamSchedule` set; `League` without `teamSchedule` key in JSON deserializes with `null`

---

### Milestone 2 — Phase 1 Service

**Task 2.1 — Add `TeamScheduleResult` sealed interface**
- File: `src/main/java/org/leagueplan/planr/scheduler/TeamScheduleResult.java`
- Records: `Success(TeamSchedule schedule, List<String> fillRoundLogs)`, `Failure(String message)`

**Task 2.2 — Implement `TeamScheduleService`**
- File: `src/main/java/org/leagueplan/planr/scheduler/TeamScheduleService.java`
- Method: `public TeamScheduleResult generate(League league)`
- Implement precondition validation (in order): season dates present, ≥1 division with ≥2 teams, per-division target ≥ (N-1). Collect all target violations before returning Failure.
- Implement circle method for single round-robin (pass A only); accumulate homeCount/awayCount per team while emitting
- Handle odd team count: add synthetic bye, discard bye games
- Implement fill game loop with home/away balance tracking
- Collect fill round log strings during generation; include in `Success`
- Assign global game numbers after collecting all games from all divisions

**Task 2.3 — Write `TeamScheduleServiceTest`**
- Cover all test cases listed in the Testing Strategy section above
- Tests must be deterministic: same league, same output on repeated calls
- Run without file I/O (no `CommandTestBase` required)

---

### Milestone 3 — SchedulerService Refactoring

**Task 3.1 — Refactor `SchedulerService`**
- File: `src/main/java/org/leagueplan/planr/scheduler/SchedulerService.java`
- Remove: internal fixture generation methods (circle method, `generateFixtures(Division)`)
- Remove: `--start`/`--end` parameters from `generate` method signature
- Rename method: `generate(League, LocalDate, LocalDate)` → `assign(League)`
- Add: `List<Fixture> toFixtures(TeamSchedule ts)` — maps `TeamGame → Fixture` one-to-one
- Add: `int estimateAvailableSlots(League league, UUID divisionId, int gameDurationMinutes)` — walks dates in `league.config()` season window, computes `floor(effectiveOpenMinutes / (gameDuration + 15))` per field per date, sums total
- Update: `assign` reads `league.teamSchedule()` → calls `toFixtures()` to get fixture list; reads `league.config().seasonStart/End()` for season dates; all downstream solver logic unchanged

**Task 3.2 — Update `SchedulerServiceTest`**
- Update existing tests to use `assign(league)` signature instead of `generate(league, start, end)`
- Add test: `assign` with league that has `teamSchedule = null` → graceful failure (or assert precondition enforcement in command layer, not service layer — document which layer owns this check)
- Verify `estimateAvailableSlots` returns 0 for a division with no compatible field windows

---

### Milestone 4 — Command Layer

**Task 4.1 — Add `ScheduleCommand.GenerateMatchups` inner class**
- In file: `src/main/java/org/leagueplan/planr/command/ScheduleCommand.java`
- Command name: `generate`
- Implement behavior as described in the CLI contracts section:
  - FINALIZED guard
  - DRAFT/TEAM_SCHEDULE discard confirmation prompt
  - Call `TeamScheduleService.generate(league)` → handle Failure (stderr, exit 1) and Success
  - Print fill round logs from `result.fillRoundLogs()` before the table
  - Print team schedule table
  - Save with `league.withTeamScheduleCleared().withTeamSchedule(result.schedule())`
  - Print summary line

**Task 4.2 — Rename and update `ScheduleCommand.Generate` → `ScheduleCommand.Assign`**
- Rename inner class from `Generate` to `Assign`, command name from `generate` to `assign`
- Remove `@Option` parameters `--start` and `--end`
- Add pre-confirmation display of team schedule summary (game count per division)
- Add feasibility warning: call `SchedulerService.estimateAvailableSlots()` per division; print warning if estimate < game count
- Add confirmation prompt (same stdin pattern as `planr schedule finalize`)
- Update service call to `schedulerService.assign(league)` (new signature)
- Precondition: check `league.teamSchedule() != null` before proceeding

**Task 4.3 — Add `ScheduleGameCommand.EditHomeAway` inner class**
- In file: `src/main/java/org/leagueplan/planr/command/ScheduleGameCommand.java`
- Command name: `edit`
- Parameter: `@Parameters(index="0") int gameNumber`
- Option: `@Option(names="--home", required=true) String teamName`
- Implement behavior as described in the CLI contracts section

**Task 4.4 — Update `ScheduleCommand.View`**
- Add TEAM_SCHEDULE branch: if `ScheduleState.of(league) == TEAM_SCHEDULE`, render team schedule table (same format as `generate` output)
- Print state header: `"Schedule status: TEAM_SCHEDULE"`
- Keep existing DRAFT/FINALIZED rendering unchanged
- If `--division`/`--team`/`--field` filters are provided in TEAM_SCHEDULE state, print: `"Filters are not applicable in team schedule view (no dates or fields assigned yet)."` — then display full team schedule

**Task 4.5 — Update `ScheduleCommand.Status`**
- Add TEAM_SCHEDULE output format as described in the CLI contracts section
- Replace "No schedule" case with `ScheduleState.NONE` check

**Task 4.6 — Update `ScheduleCommand.Export`**
- Add TEAM_SCHEDULE export branch: map `teamSchedule.games()` to team schedule JSON shape
- Maintain existing DRAFT/FINALIZED full schedule export unchanged
- Print `"Exported N games (team schedule)."` to stderr in TEAM_SCHEDULE state

**Task 4.7 — Write `ScheduleCommandTest` integration tests**
- Cover all integration test cases listed in the Testing Strategy section above
- Re-use `CommandTestBase` test harness
- For tests that require Phase 2 to complete (e.g., `assign` success), configure a minimal league (2 teams, 1 field, generous availability) to keep test runtime under 30 seconds

---

### Milestone 5 — Cleanup and Verification

**Task 5.1 — Run full test suite**
```bash
gradle test
```
All tests must pass. Pay attention to any existing `ScheduleCommandTest` that references the old `generate --start ... --end ...` signature — update those tests to use the new two-phase flow.

**Task 5.2 — Update `SchedulerServiceTest`**
- The existing `SchedulerServiceTest` may call `generate(league, seasonStart, seasonEnd)` directly. Update all call sites to `assign(league)` with a league that has a populated `teamSchedule` and `config`.

**Task 5.3 — End-to-end smoke test**
```bash
gradle installDist
./build/install/planr/bin/planr config set --sunrise 09:00 --sunset 18:00 --start 2026-06-01 --end 2026-08-31
./build/install/planr/bin/planr division add Majors --duration 120 --target 10
./build/install/planr/bin/planr team add Majors "Blue Jays"
./build/install/planr/bin/planr team add Majors "Cardinals"
./build/install/planr/bin/planr team add Majors "Red Sox"
./build/install/planr/bin/planr team add Majors "Yankees"
./build/install/planr/bin/planr field add "Riverside Park"
./build/install/planr/bin/planr schedule generate        # Phase 1
./build/install/planr/bin/planr schedule view            # team schedule
./build/install/planr/bin/planr schedule game edit 1 --home Cardinals
./build/install/planr/bin/planr schedule view            # verify edit
echo yes | ./build/install/planr/bin/planr schedule assign  # Phase 2
./build/install/planr/bin/planr schedule view            # full schedule
./build/install/planr/bin/planr schedule status
./build/install/planr/bin/planr schedule export
```

**Task 5.4 — Verify backward compatibility**
- Write a v4 `league.json` fixture (without `teamSchedule` key) and call `LeagueStore.load()`. Assert `league.teamSchedule() == null` and no exception is thrown.
- Confirm all existing v4 migration logic in `LeagueStore` is unaffected (the additive field requires no new migration guard).

---

## Errata

### E1 — Minimum target games formula incorrect (discovered 2026-05-21)

**Location:** Precondition Validation, point 3; `planr schedule generate` Preconditions; error message table for "Target too low"; Implementation Plan Task 2.2.

**Original text (incorrect):** The minimum target games per team for a single round-robin with N teams was stated as `2 * (N - 1)`.

**Correction:** For a single round-robin, each team plays every other team exactly once, producing `N - 1` games per team. The correct minimum is `N - 1`, not `2 * (N - 1)`.

**Affected locations updated in this document:**
- Precondition Validation point 3: threshold changed from `< 2 * (N - 1)` to `< (N - 1)`; example error message updated to reflect minimum of 3 (not 6) for a 4-team division.
- `planr schedule generate` Preconditions: condition updated from `>= 2 * (N - 1)` to `>= (N - 1)`.
- Error message table: example corrected to show target of 2 (less than minimum of 3) for a 4-team division.
- Implementation Plan Task 2.2: threshold description updated from `≥ 2*(N-1)` to `≥ (N-1)`.

**Note:** The Phase 1 algorithm (circle method) generates a double round-robin via Pass A + Pass B, producing `2 * (N - 1)` games per team in the base round-robin before any fill games. The precondition minimum of `N - 1` permits a target that is below the double round-robin output; targets in the range `[N-1, 2*(N-1))` may produce more round-robin games than the target requests. Implementers should clip or condition Pass B on whether the target requires it, or accept that the effective minimum is driven by the algorithm's output. This discrepancy is resolved by E2 below.

---

### E2 — Algorithm incorrectly specifies double round-robin; correct design is single round-robin plus fill games (discovered 2026-05-21)

**Location:** Step 1 — Round-Robin Generation; Step 2 — Fill Games (initial counter values); Critical Path Walkthrough §1; Testing Strategy (`TeamScheduleServiceTest` round-robin test cases).

**Problem:** The spec's Phase 1 algorithm generates a *double* round-robin (Pass A + Pass B), producing `N*(N-1)` games per division before fill games begin. The correct design is a *single* round-robin (Pass A only), producing `N*(N-1)/2` games per division, with fill games extending to the target. This aligns with the stated goal ("round-robin plus fill games") and with the minimum target of `N-1` corrected in E1 — a single round-robin produces exactly `N-1` games per team, which is the minimum meaningful target.

**Corrections by location:**

**Step 1 — Round-Robin Generation:**
- Remove Pass B (reverse direction) entirely. The round-robin portion produces only Pass A: `N*(N-1)/2` total games per division. Each team plays exactly `N-1` games.
- Remove the "Interleaving A and B" paragraph (there is no Pass B to interleave).
- Updated result statement: `N*(N-1)/2` total games. Home/away balance is approximately even per team; exact per-team counts vary by position and round under the `(i + r) % 2` assignment rule and must be tracked from actual output.

**Step 2 — Fill Games, initial counter values:**

Replace the hardcoded initialization comment block:
```
homeCount[team] = N - 1         (each team has N-1 home games after round-robin)
awayCount[team] = N - 1         (each team has N-1 away games after round-robin)
gameCount[team] = 2 * (N - 1)
```
With counters accumulated from actual round-robin output:
```
homeCount[team] = actual home games for this team emitted in Step 1
awayCount[team] = actual away games for this team emitted in Step 1
gameCount[team] = N - 1         (all teams play exactly N-1 games in a single round-robin)
```
Implementers must accumulate `homeCount` and `awayCount` while emitting games in Step 1, not derive them from `N`.

**Critical Path Walkthrough §1 (First-Time Phase 1 Run):**

Replace:
```
- Pass A: 6 games (pairs in round-robin order, home/away by position+round formula)
- Pass B: 6 games (reversed direction)
- Interleaved: 12 games total; each team has 3 home, 3 away
- Target=10, current=6 per team → fill needed: 4 more per team
- Fill rounds 2..4: similar logic; each team reaches 10 games
```
With:
```
- Pass A (single round-robin): 6 games total; each team plays 3 (home/away balance ≈ 1–2 home, 1–2 away per team)
- Target=10, current=3 per team → fill needed: 7 more per team
- Fill rounds produce pairs until all teams reach 10 games
```
Note: the total game count for Majors (20 games) is unchanged — `N*(N-1)/2 + fill = N*target/2` regardless of how the split falls.

**Testing Strategy — `TeamScheduleServiceTest` round-robin test case assertions:**

| Original (double round-robin) | Corrected (single round-robin) |
|---|---|
| Round-robin: 2 teams → 2 games (A@B, B@A); each team 1 home, 1 away | Round-robin: 2 teams → 1 game; fill games extend to target |
| Round-robin: 3 teams (odd) → 6 games; each team 2 home, 2 away | Round-robin: 3 teams (odd) → 3 games; each team plays 2 games; no bye games in output |
| Round-robin: 4 teams → 12 games; each pair appears exactly twice (once each direction) | Round-robin: 4 teams → 6 games; each pair appears exactly once |
| Round-robin: 6 teams → 30 games; balance verified | Round-robin: 6 teams → 15 games; balance verified |

Fill game tests (e.g., "4 teams, target=8 → 16 games total") remain valid — total game counts are unchanged; only the round-robin vs. fill split changes.

**Resolution of E1 discrepancy:** The note at the end of E1 ("targets in the range `[N-1, 2*(N-1))` may produce more round-robin games than the target requests") is resolved. With a single round-robin the base output is exactly `N-1` games per team — equal to the minimum target. No over-generation can occur.
