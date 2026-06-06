# planr

A command-line tool for little league organizers to configure divisions, teams, and fields, then generate and manage game schedules.

All data is stored locally in `~/.planr/league.json`.

---

## Installation

Requires Java 25 and Gradle 9.4.1.

```
gradle installDist
```

This produces a runnable script at `build/install/planr/bin/planr`. Add it to your PATH or invoke it directly.

---

## Quick start

```bash
# 1. Configure league-wide schedule parameters
planr config set --sunrise 09:00 --sunset 18:00 --start 2026-06-01 --end 2026-08-31

# 2. Add a division and its teams
planr division add "Majors" --duration 120 --target 10
planr team add "Majors" "Blue Jays"
planr team add "Majors" "Cardinals"
planr team add "Majors" "Red Sox"
planr team add "Majors" "Yankees"

# (optional) set a curfew for a division so games can't start after a certain time
planr division edit "Majors" --curfew-time 19:30

# 3. Add a field
planr field add "Riverside Park" --address "100 River Rd"

# (optional) restrict availability by day of the week for all fields
planr config dow set --day wednesday --start 16:00 --end 21:00
planr config blockday add --day sunday

# (optional) rank fields for playoff scheduling — lower number = higher preference
planr field edit "Riverside Park" --playoff-priority 1

# 4. (optional) configure and assign pre-season practices
planr division edit "Majors" --practice-count 2 --practice-duration-minutes 60 \
  --practice-start 2026-05-15 --practice-end 2026-05-30
planr practice generate
echo yes | planr practice assign
planr practice view --division Majors

# 5. Phase 1 — generate the team schedule (matchups only, no dates yet)
planr schedule generate

# 6. Review and optionally adjust home/away assignments
planr schedule view
planr schedule game edit 3 --home Cardinals

# 7. Phase 2 — assign dates, times, and fields
echo yes | planr schedule assign

# 8. Review, finalize, and export
planr schedule view
planr schedule finalize
planr schedule export

# 9. (optional) run playoffs after the regular season
planr playoff generate --division Majors --start 2026-09-06 --end 2026-09-20 \
  --seeds Yankees --seeds "Red Sox" --seeds Cardinals --seeds "Blue Jays"
planr playoff status --division Majors
echo yes | planr playoff assign
planr playoff status --division Majors
```

---

## Commands

### League configuration

League configuration sets parameters used by schedule generation. Sunrise, sunset, and season dates are required before Phase 2 (`planr schedule assign`) can run.

```
planr config set [--sunrise <HH:mm>] [--sunset <HH:mm>] [--start <YYYY-MM-DD>] [--end <YYYY-MM-DD>]
                 [--max-games-per-week <N>] [--rest-days <N>]
                 [--field-buffer-minutes <N>] [--grid-minutes <N>]
planr config show
```

- `--sunrise` and `--sunset` define the default open window applied to every field on every calendar day
- `--start` and `--end` define the season date range
- `--max-games-per-week` sets a hard cap on games any team may be scheduled in a single ISO calendar week (default 2)
- `--rest-days` sets the minimum calendar days between any two games for the same team (default 1; 0 disables)
- `--field-buffer-minutes` sets the minimum turnover gap between consecutive games on the same field (default 0; 0 means back-to-back games are allowed; must be ≥ 0)
- `--grid-minutes` sets the start-time grid interval for slot enumeration; must evenly divide 60 (default 30; valid values: 1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30, 60)
- Each option is independent; `config set` merges with existing values rather than replacing them

`config show` also displays any configured day-of-week windows and blocked days (see below).

**Example**

```
$ planr config set --sunrise 09:00 --sunset 18:00 --start 2026-06-01 --end 2026-08-31
League config updated.

$ planr config show
League Configuration
--------------------
Sunrise:        09:00
Sunset:         18:00
Season start:   2026-06-01
Season end:     2026-08-31
Max games/week: 2 (default)
Min rest days:  1 (default)
Field buffer:   0 min (default)
Grid interval:  30 min (default)

Day-of-week windows:
  Wednesday: 16:00 – 21:00

Blocked days of week:
  Sunday
```

---

### Day-of-week windows

Day-of-week windows narrow the effective open window for **all fields** on a specific day of the week. For example, if fields generally open at 09:00 but Wednesday evenings start at 16:00, set a Wednesday window instead of adding identical blocks to every field. A day-of-week window takes precedence over the global sunrise/sunset but is overridden by any field-level `FieldDateOverride` on a specific date.

```
planr config dow set   --day <DAY> --start <HH:mm> --end <HH:mm>
planr config dow clear --day <DAY>
planr config dow list
```

- `<DAY>` accepts full names (`wednesday`) or 3-letter abbreviations (`wed`), case-insensitively
- Setting a window for a day that already has one replaces it
- If field-level blocks or overrides already exist on matching dates within the season, a warning is printed with the count

**Example**

```
$ planr config dow set --day wednesday --start 16:00 --end 21:00
Day-of-week window set: Wednesday 16:00–21:00.

$ planr config dow list
DAY          OPEN   CLOSE
-----------  -----  -----
Wednesday    16:00  21:00
```

---

### Blocked days of the week

Blocked days mark a day of the week as unavailable for **all fields** throughout the season (e.g., no games on Sundays). A blocked day removes all slots on matching dates. A `FieldDateOverride` on a specific field and date still takes precedence, allowing individual rescued dates (e.g., a rescheduled game on an otherwise-blocked Sunday).

```
planr config blockday add    --day <DAY>
planr config blockday remove --day <DAY>
planr config blockday list
```

- `<DAY>` accepts full names or 3-letter abbreviations, case-insensitively
- Adding a day that is already blocked exits with an error
- When existing field-level entries fall on matching dates within the season, a warning is printed noting that `FieldDateOverride` entries still take precedence

**Example**

```
$ planr config blockday add --day sunday
Sunday added to blocked days.

$ planr config blockday list
Blocked days of week:
  Sunday
```

---

### Divisions

Divisions group teams by age or skill level. Each division carries a game duration (used to pack the field schedule) and a season target (games per team).

```
planr division add <name> --duration <minutes> --target <n>
planr division edit <name> [--name <new-name>] [--duration <minutes>] [--target <n>]
                           [--curfew-time <HH:mm>] [--no-curfew-time]
planr division delete <name>
planr division list
```

The minimum valid target is `N−1` where N is the number of teams (enough for one full round-robin). A division can only be deleted when it has no teams.

**Example**

```
$ planr division add "Majors" --duration 120 --target 10
Division "Majors" added (120 min/game).

$ planr division list
DIVISION    DURATION    TARGET    TEAMS    ...    CURFEW
--------    --------    ------    -----    ---    ------
Majors      120 min     10        0               --
```

#### Curfew time

```
planr division edit <name> --curfew-time <HH:mm>
planr division edit <name> --no-curfew-time
```

Sets the latest time at which any game or practice for this division may **start**. A start exactly at the curfew time is valid; any later start is excluded from the solver's candidate slots. Use this to protect younger players from late-evening events.

- `--curfew-time` accepts `HH:mm` in 24-hour format, validated strictly (24:00 and out-of-range values are rejected)
- `--no-curfew-time` removes the constraint from the division; idempotent on divisions with no curfew
- The two flags are mutually exclusive
- When configured, the curfew applies to regular-season games, playoff games, and practices for that division
- If the curfew eliminates all available slots, `planr schedule assign` (and `playoff assign`, `practice assign`) exits 1 with a message naming the division

The curfew is shown in `planr division list` as `HH:mm` when set, `--` when not configured.

**Example**

```
$ planr division edit "6U" --curfew-time 19:30
Division "6U" updated.

$ planr division edit "6U" --no-curfew-time
Division "6U" updated.
```

---

### Teams

Teams belong to a division. Team names must be unique within their division (case-insensitive).

```
planr team add <division> <team>
planr team edit <division> <team> --name <new-name>
planr team delete <division> <team>
planr team list <division>
```

**Example**

```
$ planr team add "Majors" "Blue Jays"
Team "Blue Jays" added to division "Majors".

$ planr team list "Majors"
Blue Jays
Cardinals
Red Sox
Yankees
```

---

### Fields

Fields are the physical locations where games are played. By default, every field is available during the league-wide sunrise-to-sunset window on every day of the season. Use blocks and overrides to record exceptions.

```
planr field add <name> [--address <address>]
planr field edit <name> [--name <new-name>] [--address <address>]
                        [--playoff-priority <n>] [--no-playoff-priority]
planr field delete <name>
planr field list
```

Deleting a field also removes all its blocks and date overrides.

**Example**

```
$ planr field add "Riverside Park" --address "100 River Rd"
Field "Riverside Park" added.

$ planr field list
NAME               ADDRESS        BLOCKS    OVERRIDES    LOCKS    PLAYOFF_PRI
---------------    -----------    ------    ---------    -----    -----------
Riverside Park     100 River Rd   0         0            0        --
```

#### Playoff field priority

```
planr field edit <name> --playoff-priority <n>
planr field edit <name> --no-playoff-priority
```

Assigns a preference rank to a field that the playoff CP-SAT solver uses when choosing where to schedule games. Lower numbers mean higher preference (1 = most preferred). The solver treats this as a soft objective — it fills ranked fields first, but falls back to lower-priority or unranked fields rather than leaving games unassigned.

- `--playoff-priority` must be a positive integer (≥ 1); 0 and negative values are rejected
- `--no-playoff-priority` removes the rank; idempotent on unranked fields
- The two flags are mutually exclusive
- Fields with no rank are used only after all ranked fields are exhausted
- Playoff priority has **no effect** on regular-season or practice scheduling
- After `planr playoff assign`, a `Field Utilization` table is printed showing each field used, its rank (`--` if unranked), and the number of games assigned to it

**Example**

```
$ planr field edit "Riverside Park" --playoff-priority 1
Field "Riverside Park" updated.

$ planr field edit "Community Park" --playoff-priority 2
Field "Community Park" updated.

$ planr field edit "Backup Field" --no-playoff-priority
Field "Backup Field" updated.
```

---

### Field blocks

Blocks mark specific date/time ranges when a field is unavailable (e.g., a holiday, a maintenance window). They are subtracted from the effective open window on that date.

```
planr field block add <field> --date <YYYY-MM-DD> --start <HH:mm> --end <HH:mm>
planr field block edit <field> <number> [--date <YYYY-MM-DD>] [--start <HH:mm>] [--end <HH:mm>]
planr field block delete <field> <number>
planr field block list <field>
```

**Example**

```
$ planr field block add "Riverside Park" --date 2026-07-04 --start 00:00 --end 23:59
Block #1 added to field "Riverside Park" (2026-07-04 00:00–23:59).

$ planr field block list "Riverside Park"
#    DATE        START  END
-    ----------  -----  -----
1    2026-07-04  00:00  23:59
```

---

### Field date overrides

Overrides replace the league-level sunrise/sunset with a custom open window on a specific field and date (e.g., a field with extended evening lighting, or an earlier close for a school night).

```
planr field override add <field> --date <YYYY-MM-DD> --start <HH:mm> --end <HH:mm>
planr field override edit <field> <number> [--date <YYYY-MM-DD>] [--start <HH:mm>] [--end <HH:mm>]
planr field override delete <field> <number>
planr field override list <field>
```

At most one override per field per date. Blocks are applied on top of the override window.

---

### Practices

Pre-season practice scheduling uses the same two-phase pattern as regular-season scheduling. Configure a practice window and per-team count on each division, then generate and assign.

#### Configure practice settings

```
planr division edit <division> [--practice-count <n>] [--practice-duration-minutes <n>]
                               [--practice-start <YYYY-MM-DD>] [--practice-end <YYYY-MM-DD>]
```

All four flags are optional and independent; omitting one leaves its current value unchanged. Validation rules:

- `--practice-count` and `--practice-duration-minutes` must be ≥ 1
- `--practice-end` must not be before `--practice-start`
- Both dates must be strictly before `config.seasonStart` (when the season is configured)

The practice window appears in `planr division list` as `PRAC. START`, `PRAC. END`, `PRACTICE COUNT`, and `PRAC. DURATION`. Fields not yet set display as `--`.

#### Phase 1 — generate practice slots

```
planr practice generate
```

Generates one practice slot stub per team per `practiceCount` for every division that has all four practice fields configured. Divisions missing any field, or that already have a practice schedule, are skipped with a warning. Exits 1 if no division qualifies.

**Preconditions:** at least one division with full practice configuration and at least one team.

#### Phase 2 — assign field slots to practices

```
planr practice assign
```

Clears any prior assignments and runs the CP-SAT solver across all divisions with a practice schedule. Each division's `[practiceStart, practiceEnd]` window and `practiceDurationMinutes` are used independently. Because the practice window ends before `seasonStart`, practice and game weeks never overlap. `maxGamesPerWeek` and `minRestDays` apply within the practice window only.

**Preconditions:** at least one practice schedule exists; at least one field configured; sunrise/sunset configured.

#### View and manage

```
planr practice view [--division <name>]
planr practice clear --division <name>
```

- **`view`** — without `--division`: one-line summary per division showing state (`NOT_CONFIGURED`, `NOT_STARTED`, `GENERATED`, `ASSIGNED`) with assigned/total slot counts. With `--division`: per-slot table sorted by assigned date, then time, then team name; assigned slots appear first, unassigned slots trail at the bottom (`UNASSIGNED` when not yet assigned).
- **`clear`** — removes a division's practice schedule after interactive confirmation, returning it to `NOT_STARTED`. Does not affect other divisions.

**Example**

```
$ planr division edit "Majors" --practice-count 2 --practice-duration-minutes 60 \
    --practice-start 2026-05-15 --practice-end 2026-05-30
Division "Majors" updated.

$ planr practice generate
Generated 8 practice slots for Majors (4 teams × 2 practices).
Practice generation complete: 1 division(s) processed, 8 total slots created.

$ planr practice view --division Majors
Division: Majors | State: GENERATED | Period: 2026-05-15 to 2026-05-30

TEAM        PRACTICE  DATE        TIME   FIELD
----------  --------  ----------  -----  -----
Blue Jays   1 of 2    UNASSIGNED  --     --
Blue Jays   2 of 2    UNASSIGNED  --     --
...
```

---

### Schedule

Schedule generation is split into two phases, with a review step in between.

#### Phase 1 — generate team schedule

```
planr schedule generate
```

Generates matchups for each eligible division using a multi-cycle round-robin. The number of complete round-robin cycles is determined from the division's `targetGamesPerTeam`; any remainder becomes a partial cycle. Every team pair plays either `floor(T/(N−1))` or `floor(T/(N−1)) + 1` times — head-to-head imbalance is at most 1.

Prints cycle-progress logs and a full matchup table. Re-running while a team schedule or draft already exists prompts for confirmation; blocked if a finalized schedule exists.

**Preconditions:** season dates configured; at least one division with ≥ 2 teams; per-division target ≥ N−1.

#### Review and edit home/away

```
planr schedule game edit <number> --home <team>
```

Makes the named team the home team for the specified game (and the other team away). Available in `TEAM_SCHEDULE` and `DRAFT` states. No-op if the team is already home.

#### Phase 2 — assign dates, times, and fields

```
planr schedule assign
```

Reads the confirmed team schedule and runs the OR-Tools CP-SAT solver to assign each game a date, start time, and field. Displays a feasibility estimate per division before prompting for confirmation.

**Preconditions:** team schedule exists; at least one field configured; season dates configured.

**Constraints enforced:**
- **C1** — each game assigned exactly once
- **C2** — no two games on the same field overlap (including the configured buffer between games; default 0, allowing back-to-back games)
- **C3** — no team plays more than once on the same calendar day

**Objective:** minimise the maximum number of games any team plays in a single ISO calendar week (spreads the season evenly).

#### View, status, export, finalize

```
planr schedule view [--division <name>] [--team <name>] [--field <name>]
planr schedule status
planr schedule export
planr schedule finalize
```

- **`view`** — in `TEAM_SCHEDULE` state, shows the matchup-only table (`#`, `HOME`, `AWAY`, `DIVISION`) plus HOME/AWAY BALANCE and HEAD-TO-HEAD stat blocks. `--division` and `--team` filter both the table and the stat blocks; `--field` is rejected in this state (no field assignments exist yet). In `DRAFT`/`FINALIZED`, shows the full table with date, time, and field, followed by per-division HOME/AWAY BALANCE and HEAD-TO-HEAD blocks when no filter or a `--division` filter is active. The stat blocks are suppressed when `--team` or `--field` is specified.
- **`status`** — shows the current state (`TEAM_SCHEDULE`, `DRAFT`, or `FINALIZED`) with per-division game counts, targets, and team counts.
- **`export`** — writes JSON to stdout. In `TEAM_SCHEDULE` state: `game_number`, `home_team`, `away_team`, `division_name`. In `DRAFT`/`FINALIZED`: adds `date`, `start_time`, `field_name`, and `status`.
- **`finalize`** — promotes a draft to `FINALIZED` after interactive confirmation. Irreversible.

#### Override individual games

```
planr schedule game override <number> [--date <date>] [--start <HH:mm>] [--field <field>] [--home <team>] [--away <team>]
```

Adjusts one game on a finalized schedule. Any combination of fields may be changed. A non-blocking warning is printed to stderr if the change creates a field conflict. Overridden games are marked with `*` in `planr schedule view`.

---

### Playoffs

Playoff brackets use the same two-phase pattern as the regular-season schedule. Phase 1 generates a double-elimination bracket for a division; Phase 2 runs the CP-SAT field-assignment solver across all brackets simultaneously.

#### Phase 1 — generate a bracket

```
planr playoff generate --division <name> --start <YYYY-MM-DD> --end <YYYY-MM-DD>
                       --seeds <team> [--seeds <team> ...]
```

Generates a double-elimination bracket for the specified division. `--seeds` is a repeatable flag — supply it once per team in seed order (seed 1 first). Bracket structure: the N teams are padded to the next power of two P; top-seeded teams receive byes in Winners R1. Subsequent rounds use positional references (`W of G3`, `L of G2`). The final two slots are the Championship and an optional conditional re-match.

Validation: division must exist; end not before start; seed count must match the division's team count (2–16); all seed names must match division teams (case-insensitive); no duplicate seeds; no existing playoff for the division.

**Preconditions:** division exists with 2–16 teams; no existing playoff for the division.

#### Phase 2 — assign field slots to playoff games

```
planr playoff assign
```

Clears any prior assignments and runs the CP-SAT solver across all divisions that have a playoff bracket. All brackets must share the same `startDate` and `endDate` (validated before the solve starts). Bye slots and the conditional re-match slot are never submitted to the solver. Later-round games use deterministic pseudo-team IDs so rest-day and weekly-cap constraints fire per slot without affecting solver correctness.

If any fields have a `--playoff-priority` rank set (see [Playoff field priority](#playoff-field-priority)), the solver will prefer higher-ranked fields as a secondary objective, falling back to lower-priority or unranked fields as needed.

After the solve, a `Field Utilization` table is printed listing every field that received playoff games, its priority rank, and its game count — ranked fields first, then unranked:

```
Field Utilization
-----------------
FIELD           PLAYOFF_PRI  GAMES
--------------  -----------  -----
Riverside Park  1            6
Eastside Field  1            5
Community Park  2            2
Backup Field    --           1
```

**Preconditions:** at least one playoff bracket exists; all brackets share the same date range; at least one field configured; sunrise/sunset configured.

#### View, clear

```
planr playoff status [--division <name>]
planr playoff clear --division <name>
```

- **`status`** — without `--division`: one-line summary per division showing state (`NOT_STARTED` | `GENERATED` | `ASSIGNED`). With `--division`: full bracket table (`SLOT`, `ROUND`, `POSITION A`, `POSITION B`, `ASSIGNED`). Bye slots show `BYE`; unassigned real games show `UNASSIGNED`. The conditional re-match slot is marked `*`.
- **`clear`** — removes a division's bracket after interactive confirmation, returning it to `NOT_STARTED`. Does not affect other divisions.

**Example**

```
$ planr playoff generate --division Majors --start 2026-09-06 --end 2026-09-20 \
    --seeds Yankees --seeds "Red Sox" --seeds Cardinals --seeds "Blue Jays"

SLOT  ROUND       POSITION A  POSITION B  BYE
----  ----------  ----------  ----------  ---
G1    Winners R1  Yankees     Blue Jays
G2    Winners R1  Red Sox     Cardinals
...
G8 *  Championship  W of L-Final  W of Champ

Playoff generated for Majors: 4 teams, 7 game slots, 0 bye(s).

$ planr playoff status --division Majors
Division: Majors | State: GENERATED | Period: 2026-09-06 to 2026-09-20

SLOT    ROUND         POSITION A   POSITION B   ASSIGNED
------  ------------  -----------  -----------  ----------
G1      Winners R1    Yankees      Blue Jays    UNASSIGNED
G2      Winners R1    Red Sox      Cardinals    UNASSIGNED
...
G8 *    Championship  W of G6      W of G7      UNASSIGNED

* = conditional (championship re-match slot)
```

---

## Phase 1 algorithm: how team schedules are built

`planr schedule generate` uses a multi-cycle circle-method round-robin to produce a balanced matchup list. For a division with **N** teams and a target of **T** games per team, the generator computes:

- `fullCycles = floor(T / (N−1))` — the number of complete round-robin passes
- `remainder = T mod (N−1)` — extra rounds needed after the full cycles

**Invariant:** any two teams play each other either `fullCycles` or `fullCycles + 1` times. The maximum head-to-head difference between any two pairs in the same division is 1.

### Circle-method round generation

All rounds — across full cycles and any partial cycle — are produced by the same stateful circle method:

1. One team is fixed in position; the remaining `N−1` teams form a rotating list.
2. If N is odd, a null bye-slot is appended so the count is always even.
3. In each round, `N/2` pairs are read from the circle:
   - Pair 0: fixed team vs. last position in the rotating list.
   - Pairs 1…N/2−1: `rotating[i−1]` vs. `rotating[N−2−i]` (symmetric about the centre).
   - Any pairing involving the bye-slot is discarded.
4. After each round, the rotating list advances by moving its last element to the front.

One full cycle runs `N−1` rounds and produces exactly `N*(N-1)/2` games — one for every distinct team pair.

### Full cycles and partial cycle

`fullCycles` complete passes are run through the circle method. After each pass, the rotation continues from where it left off — **the rotation is never reset between cycles**. Continuing the rotation ensures a different pairing sequence in each cycle, which produces better home/away balance across the full schedule.

If `remainder > 0`, a partial cycle runs exactly `remainder` more rounds from the current rotation state. Within the partial cycle each team pair appears at most once, so no pair exceeds `fullCycles + 1` total meetings. For odd-N divisions, some teams draw a bye in the partial cycle and receive one fewer game; this ±1 per-team variation is expected and acceptable.

### Home/away assignment

Home/away is determined by a **global round index** (`globalRound`) that increments continuously from 0 across all cycles and never resets:

```
leftIsHome = ((specI + globalRound) % 2 == 0)
```

`specI` is the column index in the circle table (0 for the fixed-team row, 1 for the first rotating pair, etc.). Because `globalRound` never resets at cycle boundaries, home advantage alternates correctly between cycles. For an even-N division with an exact multiple-cycle target (e.g., double round-robin), this produces **perfect home/away balance** — each team plays exactly half its games at home.

### Game number assignment

Game numbers are assigned after all rounds are collected. A single pass assigns stable 1-based integers in order: all of division A's games appear before division B's; within each division, games are ordered cycle by cycle, round by round.

### Example: 4 teams, target 6 (double round-robin)

N = 4, N−1 = 3 → `fullCycles = 2`, `remainder = 0`.

| Cycle | Round | Pair 1 (home–away) | Pair 2 (home–away) |
|---|---|---|---|
| 1 | 1 | A–D | C–B |
| 1 | 2 | C–A | D–B |
| 1 | 3 | A–B | D–C |
| 2 | 4 | D–A | B–C |
| 2 | 5 | A–C | B–D |
| 2 | 6 | B–A | C–D |

Every team plays 6 games, 3 home and 3 away. Cycle 2 exactly reverses home/away from cycle 1 for each pair.

**Partial cycle example:** 4 teams, target 8 → `fullCycles = 2`, `remainder = 2`. After the 12 games above, 2 more rounds run from the current rotation state (globalRounds 6–7), adding 2 games per team to reach the target of 8. Every pair meets 2 or 3 times (head-to-head imbalance ≤ 1).

---

## Phase 2 algorithm: how field assignment works

`planr schedule assign` takes the confirmed matchup list from Phase 1 and finds a legal assignment of dates, times, and fields using [OR-Tools CP-SAT](https://developers.google.com/optimization/reference/python/sat/python/cp_model), Google's constraint-programming solver.

### Step 1 — enumerate valid slots

Before the solver runs, the scheduler enumerates every valid start time across the entire season:

1. Walk every calendar date from `--start` to `--end`.
2. For each date and field, determine the open window using a four-level precedence rule:
   1. **`FieldDateOverride`** — if a date-specific override exists for this field, use its `openStart`→`openEnd`. This wins over everything, including blocked days, allowing individual rescued dates on an otherwise-blocked day.
   2. **Blocked day** — if the day of week is in the league's blocked-days list and no override applies, the date produces no slots.
   3. **Day-of-week window** — if a league-wide day-of-week window is configured for this day (e.g., Wednesdays open at 16:00), use its `openStart`→`openEnd` in place of the global sunrise/sunset.
   4. **Global sunrise/sunset** — fall back to the league-wide `sunrise`→`sunset` window.
3. Subtract any field-level blocks (`planr field block`) that fall on that date. This may fragment the open window into multiple sub-ranges.
4. Within each sub-range, advance a cursor in `gridMinutes`-minute increments (default 30). Each position where a game of the division's duration fits before the window closes becomes one **slot** `(date, field, startTime)`.
5. If the division has a **curfew time** configured (`planr division edit --curfew-time`), any slot whose start time is strictly after the curfew is discarded at this stage — before the CP-SAT model is even built. A start exactly at the curfew time is kept.

Slots are enumerated separately for each division because divisions have different game durations (a 90-minute division gets more slots per day than a 120-minute one). The total slot count — after any curfew filtering — is printed in the feasibility check line before the solver starts. If curfew filtering reduces a division's slot count to zero, the assign command exits with an error naming the division and its curfew before running the solver.

### Step 2 — build the constraint model

The solver works on a matrix of **boolean decision variables**: one `BoolVar` per `(fixture, slot)` pair. Setting a variable to `true` means "assign this game to this slot." Three constraints restrict which assignments are legal:

**C1 — each game assigned at most once**

For each fixture, at most one of its slot variables may be `true`. This is expressed as a single `addAtMostOne` over all slot variables for that fixture. An auxiliary boolean `isAssigned[f]` equals the sum, recording whether the fixture received a slot at all.

`addAtMostOne` (not `addExactlyOne`) is intentional: if there are fewer slots than games, the solver assigns as many as it can and saves a partial Draft rather than failing.

**C2 — no two games overlap on the same field**

Rather than checking every pair of games on the same field for time overlap (which grows as O(N²)), each variable is registered under every grid-sized tick it would occupy:

```
ticks covered = [slotStartMinute, slotStartMinute + gameDuration + fieldBufferMinutes)
                in gridMinutes steps
```

For each `(field, date, tick)` bucket, `addAtMostOne` ensures at most one game is active at that tick. This bounds the number of C2 constraints to `numFields × numDays × ticksPerDay` regardless of how many fixtures exist. The configured buffer (`fieldBufferMinutes`, default 0) guarantees turnover time between consecutive games on a field; with the default of 0, back-to-back games on the same field are allowed.

**C3 — no team plays twice on the same calendar day**

For each `(team, date)` pair, `addAtMostOne` is applied across all games where that team appears (home or away) on that date.

### Step 3 — define the objective

The solver optimises a two-level objective encoded as a single weighted sum:

```
maximise:  bigM × totalAssigned  −  maxWeekLoad
where  bigM = totalFixtures + 1
```

- **Primary goal (totalAssigned):** assign as many games as possible. The `bigM` coefficient guarantees lexicographic dominance — gaining one more assigned game always outweighs any reduction in `maxWeekLoad`.
- **Secondary goal (maxWeekLoad):** minimise the maximum number of games any team plays in a single ISO calendar week. `maxWeekLoad` is tracked by adding one constraint per `(team, ISO week)` pair: `sum(vars for that team-week) ≤ maxWeekLoad`. When the season has enough capacity for all games, the solver uses its remaining freedom to spread games evenly across the calendar.

### Step 4 — solve and collect results

The CP-SAT solver runs for up to 300 seconds. Progress is streamed to stdout at the 25%, 50%, and 75% time marks. The solver may finish early if it proves the solution is optimal.

After the solver returns:

- **OPTIMAL or FEASIBLE** — every variable where `booleanValue == true` becomes a `ScheduledGame`. Games are sorted by date, then start time, then field name. A Draft is saved regardless of whether all fixtures were assigned.
- **UNKNOWN** — the solver timed out without finding any feasible solution (returned before placing a single game). This indicates the season window or field availability is too constrained. A failure message is returned and no Draft is saved.
- **INFEASIBLE** — theoretically unreachable with `addAtMostOne` constraints; treated as an internal error.

---

## Notes

- Division and field names are matched case-insensitively in all commands
- Exit code `0` = success, `1` = validation error, `2` = data file I/O error
- Run `planr --help` or append `--help` to any subcommand for usage details
