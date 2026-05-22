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

# 3. Add a field
planr field add "Riverside Park" --address "100 River Rd"

# 4. Phase 1 — generate the team schedule (matchups only, no dates yet)
planr schedule generate

# 5. Review and optionally adjust home/away assignments
planr schedule view
planr schedule game edit 3 --home Cardinals

# 6. Phase 2 — assign dates, times, and fields
echo yes | planr schedule assign

# 7. Review, finalize, and export
planr schedule view
planr schedule finalize
planr schedule export
```

---

## Commands

### League configuration

League configuration sets parameters used by schedule generation. All four values are required before Phase 2 (`planr schedule assign`) can run.

```
planr config set [--sunrise <HH:mm>] [--sunset <HH:mm>] [--start <YYYY-MM-DD>] [--end <YYYY-MM-DD>]
planr config show
```

- `--sunrise` and `--sunset` define the default open window applied to every field on every calendar day
- `--start` and `--end` define the season date range
- Each option is independent; `config set` merges with existing values rather than replacing them

**Example**

```
$ planr config set --sunrise 09:00 --sunset 18:00 --start 2026-06-01 --end 2026-08-31
League config updated.

$ planr config show
Sunrise:      09:00
Sunset:       18:00
Season start: 2026-06-01
Season end:   2026-08-31
```

---

### Divisions

Divisions group teams by age or skill level. Each division carries a game duration (used to pack the field schedule) and a season target (games per team).

```
planr division add <name> --duration <minutes> --target <n>
planr division edit <name> [--name <new-name>] [--duration <minutes>] [--target <n>]
planr division delete <name>
planr division list
```

The minimum valid target is `N−1` where N is the number of teams (enough for one full round-robin). A division can only be deleted when it has no teams.

**Example**

```
$ planr division add "Majors" --duration 120 --target 10
Division "Majors" added (120 min/game).

$ planr division list
DIVISION    DURATION    TARGET    TEAMS
--------    --------    ------    -----
Majors      120 min     10        0
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
planr field delete <name>
planr field list
```

Deleting a field also removes all its blocks and date overrides.

**Example**

```
$ planr field add "Riverside Park" --address "100 River Rd"
Field "Riverside Park" added.

$ planr field list
NAME               ADDRESS        BLOCKS    OVERRIDES
---------------    -----------    ------    ---------
Riverside Park     100 River Rd   0         0
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

### Schedule

Schedule generation is split into two phases, with a review step in between.

#### Phase 1 — generate team schedule

```
planr schedule generate
```

Generates a single round-robin for each eligible division (`N*(N-1)/2` matchups, one per team pair), then runs fill rounds until each team reaches the division's `targetGamesPerTeam`. Home/away assignments are balanced by tracking each team's running imbalance.

Prints fill-round progress logs and a full matchup table. Re-running while a team schedule or draft already exists prompts for confirmation; blocked if a finalized schedule exists.

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
- **C2** — no two games on the same field overlap (including a 15-minute buffer between games)
- **C3** — no team plays more than once on the same calendar day

**Objective:** minimise the maximum number of games any team plays in a single ISO calendar week (spreads the season evenly).

#### View, status, export, finalize

```
planr schedule view [--division <name>] [--team <name>] [--field <name>]
planr schedule status
planr schedule export
planr schedule finalize
```

- **`view`** — in `TEAM_SCHEDULE` state, shows the matchup-only table (`#`, `HOME`, `AWAY`, `DIVISION`). In `DRAFT`/`FINALIZED`, shows the full table with date, time, and field. Filters by division, team, or field apply in `DRAFT`/`FINALIZED` only.
- **`status`** — shows the current state (`TEAM_SCHEDULE`, `DRAFT`, or `FINALIZED`) with per-division game counts, targets, and team counts.
- **`export`** — writes JSON to stdout. In `TEAM_SCHEDULE` state: `game_number`, `home_team`, `away_team`, `division_name`. In `DRAFT`/`FINALIZED`: adds `date`, `start_time`, `field_name`, and `status`.
- **`finalize`** — promotes a draft to `FINALIZED` after interactive confirmation. Irreversible.

#### Override individual games

```
planr schedule game override <number> [--date <date>] [--start <HH:mm>] [--field <field>] [--home <team>] [--away <team>]
```

Adjusts one game on a finalized schedule. Any combination of fields may be changed. A non-blocking warning is printed to stderr if the change creates a field conflict. Overridden games are marked with `*` in `planr schedule view`.

---

## Notes

- Division and field names are matched case-insensitively in all commands
- Exit code `0` = success, `1` = validation error, `2` = data file I/O error
- Run `planr --help` or append `--help` to any subcommand for usage details
