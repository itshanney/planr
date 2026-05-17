# Tech Spec: v2 Entity Management — `planr` CLI

* **Date:** 2026-05-17
* **Status:** Ready for Implementation
* **Scope:** Division & Team Management, Field Management, and Field Availability Configuration sections from `features/2026-05-17-league-planner-core-scheduling-v2.md`
* **Supersedes:** Availability configuration portions of `specs/2026-05-16-field-management-cli.md`
* **Phase:** CLI prototype (precedes v2 Phase 1 and Phase 2 scheduling slices)

---

## Overview

This slice upgrades the entity management layer of `planr` to match the v2 product requirements. Three structural changes drive the work:

1. **Division** gains a `targetGamesPerTeam` field — a configurable positive integer added to `planr division add` and `planr division edit`, stored on the `Division` record, and displayed in `planr division list`.
2. **Field availability** replaces the recurring day-of-week `AvailabilityWindow` model entirely. Fields are now open by default; the league-level `sunriseTime`/`sunsetTime` defines the default open window. Organizers record only exceptions: **field blocks** (date-specific blocked ranges) and **per-date open window overrides** (date-specific replacements for the league defaults).
3. **League config** introduces a new top-level `LeagueConfig` record holding `sunriseTime`, `sunsetTime`, `seasonStart`, and `seasonEnd`. These are the configuration prerequisites for Phase 1 and Phase 2 scheduling.

Major changes:
* The `FieldWindowCommand` is retired. 
* Two new nested command groups replace it: `FieldBlockCommand` and `FieldOverrideCommand`. 
* A new top-level `ConfigCommand` manages league-level config. 
* The JSON schema advances from version 3 to version 4. All prior availability windows are discarded during migration. 
* No other components change.

---

## Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│  planr (CLI entry point)                                                │
│  PlanrApp — root @Command; wires subcommands, injects store             │
└────────────────┬────────────────────────────────────────────────────────┘
                 │ dispatches to
   ┌─────────────┼──────────────┬─────────────────────┬──────────────────┐
   ▼             ▼              ▼                     ▼                  ▼
DivisionCmd   TeamCmd      FieldCmd             ScheduleCmd          ConfigCmd
(modified)  (unchanged)  (modified)            (unchanged)            (NEW)
add--target              add/edit/delete/list                       set / show
edit--target             + nested:
delete                   FieldBlockCmd (NEW)
list(+TARGET)            add / edit / delete / list
                         FieldOverrideCmd (NEW)
                         add / edit / delete / list

All commands access state via:
┌─────────────────────────────────────────────────────────┐
│  LeagueStore                                            │
│  (extended: v3→v4 migration; LeagueConfig R/W)          │
└───────────────────────────┬─────────────────────────────┘
                            │ read / atomic write
                            ▼
              ┌─────────────────────────────┐
              │  ~/.planr/league.json (v4)  │
              └─────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|---|---|
| `DivisionCommand` | Extended: `add` and `edit` accept `--target`; `list` shows TARGET column |
| `TeamCommand` | Unchanged |
| `FieldCommand` | Unchanged except: `delete` cascade message reflects blocks/overrides; `list` shows BLOCKS and OVERRIDES columns |
| `FieldBlockCommand` | Validates and executes date-specific field block CRUD, nested under `FieldCommand` |
| `FieldOverrideCommand` | Validates and executes per-date open window override CRUD, nested under `FieldCommand` |
| `ConfigCommand` | Validates and executes league-level config set/show |
| `FieldBlock` (record) | Immutable value: date-specific blocked time range on a field |
| `FieldDateOverride` (record) | Immutable value: per-date replacement of league-level open window for a field |
| `LeagueConfig` (record) | Immutable value: league-level sunrise/sunset defaults and season date range |
| `LeagueStore` | Extended: v3→v4 migration; `LeagueConfig` serialization |

---

## Data Model

### Modified Records

**`Division`** — gains one new field:

```
Division
  ├── id: UUID
  ├── name: String
  ├── gameDurationMinutes: int
  ├── targetGamesPerTeam: int   ← NEW (positive integer; 0 = not configured / migration default)
  └── List<Team>
```

**`Field`** — replaces `List<AvailabilityWindow> windows` with two new lists:

```
Field
  ├── id: UUID
  ├── name: String
  ├── address: String (nullable)
  ├── blocks: List<FieldBlock>          ← NEW (was: windows: List<AvailabilityWindow>)
  └── dateOverrides: List<FieldDateOverride>  ← NEW
```

**`League`** — gains `config` field:

```
League (version 4)
  ├── version: int (4)
  ├── config: LeagueConfig              ← NEW (nullable; null if not yet configured)
  ├── List<Division>
  ├── List<Field>
  └── Schedule (nullable, unchanged)
```

### New Records

**`LeagueConfig`:**

```
LeagueConfig
  ├── sunriseTime: LocalTime   (HH:mm; nullable)
  ├── sunsetTime: LocalTime    (HH:mm; nullable)
  ├── seasonStart: LocalDate   (nullable)
  └── seasonEnd: LocalDate     (nullable)
```

All four fields are nullable — config is built incrementally. The scheduling slice will validate that all four are present before Phase 1 can run.

**`FieldBlock`:**

```
FieldBlock
  ├── id: UUID
  ├── date: LocalDate    (ISO 8601)
  ├── startTime: LocalTime (HH:mm)
  └── endTime: LocalTime   (HH:mm; must be strictly after startTime)
```

**`FieldDateOverride`:**

```
FieldDateOverride
  ├── id: UUID
  ├── date: LocalDate    (ISO 8601; unique within a field's dateOverrides list)
  ├── openStart: LocalTime (HH:mm)
  └── openEnd: LocalTime   (HH:mm; must be strictly after openStart)
```

### Retired Record

`AvailabilityWindow` — deleted. Not migrated; its data is discarded.

### JSON Schema (version 4)

```json
{
  "version": 4,
  "config": {
    "sunriseTime": "07:00",
    "sunsetTime": "20:00",
    "seasonStart": "2026-06-01",
    "seasonEnd": "2026-08-31"
  },
  "divisions": [
    {
      "id": "a1b2c3d4-...",
      "name": "Majors",
      "gameDurationMinutes": 120,
      "targetGamesPerTeam": 12,
      "teams": [
        { "id": "e5f6g7h8-...", "name": "Blue Jays" }
      ]
    }
  ],
  "fields": [
    {
      "id": "f1f2f3f4-...",
      "name": "Riverside Park",
      "address": "123 Main St",
      "blocks": [
        {
          "id": "b1b2b3b4-...",
          "date": "2026-06-15",
          "startTime": "09:00",
          "endTime": "12:00"
        }
      ],
      "dateOverrides": [
        {
          "id": "o1o2o3o4-...",
          "date": "2026-07-04",
          "openStart": "10:00",
          "openEnd": "16:00"
        }
      ]
    }
  ],
  "schedule": null
}
```

**Serialization notes:**
- `LocalDate` serializes as ISO 8601 string (`"2026-06-15"`) via `JavaTimeModule` with `WRITE_DATES_AS_TIMESTAMPS` disabled. No custom serializer needed — `LocalDate` default is already ISO 8601.
- `LocalTime` continues to use the existing `"HH:mm"` custom serializer registered in `LeagueStore`.
- `config` serializes as `null` when not yet configured. Jackson deserializes null → `null` reference on the `League` record; the `League` compact constructor normalizes `null config` to `null` (no further normalization needed — commands check for null before use).

### Schema Migration (v1/v2/v3 → v4)

`LeagueStore.load()` extends the existing migration chain. After the v1→v2 and v2→v3 guards, a single v→4 guard fires for any version below 4:

```
Strategy:
  Run existing v1→v2 and v2→v3 migration guards first.
  Then:

if (league.version() < 4) {
    // Strip all windows from fields; initialize empty blocks and dateOverrides
    List<Field> migratedFields = league.fields().stream()
        .map(f -> new Field(f.id(), f.name(), f.address(), List.of(), List.of()))
        .toList();
    // Initialize config with all nulls
    LeagueConfig config = new LeagueConfig(null, null, null, null);
    // Advance version to 4
    league = new League(4, league.divisions(), migratedFields, config, league.schedule());
    save(league);   // atomic write
    System.err.println("Warning: Field availability windows from a previous version "
        + "have been removed. Please configure field blocks for the new season.");
}
```

**Division.targetGamesPerTeam after migration:** Jackson deserializes `int` fields absent from JSON as `0`. Migrated divisions will have `targetGamesPerTeam = 0`, signaling "not configured." The Phase 1 scheduling spec will validate this is ≥ 1 before generation. Organizers must run `planr division edit <name> --target <n>` on each division before generating a schedule. This is acceptable — the entity management layer does not impose that constraint; the scheduling layer does.

**Idempotency:** A second invocation after migration sees `version = 4`, skips all guards, and returns the stored league. The migration warning is printed only once.

---

## Build Configuration Changes

One new Jackson module registration in `LeagueStore` for `LocalDate` support (which uses the same `JavaTimeModule` already registered for `LocalTime`, but requires `LocalDate` deserialization from ISO strings). Confirm that the existing `JavaTimeModule` + `WRITE_DATES_AS_TIMESTAMPS(false)` handles `LocalDate` correctly — it does, as `LocalDate` ISO 8601 is the `JavaTimeModule` default when timestamps are disabled.

No new Gradle dependencies required. All needed types (`LocalDate`, `LocalTime`) are already handled by `jackson-datatype-jsr310` (added in the field management slice).

---

## Project Structure Changes

```
src/main/java/org/leagueplan/planr/
├── PlanrApp.java                         # add ConfigCommand to subcommands list
├── command/
│   ├── DivisionCommand.java              # modified: --target on add/edit; TARGET col in list
│   ├── TeamCommand.java                  (unchanged)
│   ├── FieldCommand.java                 # modified: delete message; list columns
│   ├── FieldWindowCommand.java           # DELETED (replaced by FieldBlockCommand + FieldOverrideCommand)
│   ├── FieldBlockCommand.java            # NEW: add / edit / delete / list
│   ├── FieldOverrideCommand.java         # NEW: add / edit / delete / list
│   ├── ConfigCommand.java                # NEW: set / show
│   └── ScheduleCommand.java             (unchanged)
├── model/
│   ├── League.java                       # modified: add config field + helpers
│   ├── Division.java                     # modified: add targetGamesPerTeam field
│   ├── Team.java                         (unchanged)
│   ├── Field.java                        # modified: replace windows with blocks + dateOverrides
│   ├── AvailabilityWindow.java           # DELETED
│   ├── FieldBlock.java                   # NEW: record id/date/startTime/endTime
│   ├── FieldDateOverride.java            # NEW: record id/date/openStart/openEnd
│   ├── LeagueConfig.java                 # NEW: record sunriseTime/sunsetTime/seasonStart/seasonEnd
│   ├── Schedule.java                     (unchanged)
│   ├── ScheduledGame.java               (unchanged)
│   └── ScheduleStatus.java             (unchanged)
├── scheduler/                           (unchanged)
└── store/
    └── LeagueStore.java                  # modified: v3→v4 migration; LocalDate in ObjectMapper
```

---

## API Contracts

All commands: `stdout` on success, `stderr` on error. Exit codes: `0` = success, `1` = validation error, `2` = I/O error.

---

### Division Commands (modified)

#### `planr division add <name> --duration <minutes> --target <n>`

| | |
|---|---|
| New flag | `--target <n>` (required; positive integer ≥ 1) |
| Validation | All existing checks plus: `--target` is required and must be a positive integer ≥ 1 |
| On success | `Division "Majors" added (120 min/game, target 12 games/team).` |
| On error (missing --target) | `Error: --target is required. Specify the target number of games per team.` Exit 1 |
| On error (invalid target) | `Error: Target games per team must be a positive integer (got: 0).` Exit 1 |

#### `planr division edit <name> [--name <new-name>] [--duration <minutes>] [--target <n>]`

| | |
|---|---|
| New flag | `--target <n>` (optional; positive integer ≥ 1 if provided) |
| Validation | All existing checks plus: if `--target` provided, must be ≥ 1 |
| On success | `Division "Majors" updated.` |
| On error (invalid target) | `Error: Target games per team must be a positive integer (got: 0).` Exit 1 |

#### `planr division delete <name>` — unchanged

#### `planr division list` — adds TARGET column

```
DIVISION     DURATION    TARGET    TEAMS
---------    --------    ------    -----
Majors       120 min     12        4
AAA           90 min     10        6
Coast         90 min      0*       5
T-Ball        60 min      8        8
```

When `targetGamesPerTeam == 0` (not configured), display `0*` and print a trailing warning line:

```
Warning: 1 division(s) have no target configured. Set with 'planr division edit <name> --target <n>'.
```

---

### Team Commands — unchanged

---

### Config Commands (new)

#### `planr config set [--sunrise <HH:mm>] [--sunset <HH:mm>] [--start <YYYY-MM-DD>] [--end <YYYY-MM-DD>]`

| | |
|---|---|
| Validation | At least one flag required; `--sunrise` and `--sunset` parse as `HH:mm`; `--start` and `--end` parse as ISO 8601 dates; if both `--start` and `--end` are provided in the same invocation, `end` must be after `start`; if only one is provided, defer the cross-field validation to schedule generation |
| On success | Updates config (only the specified fields; others retain their current value), saves file. Prints: `League config updated.` |
| On error (no flags) | `Error: At least one of --sunrise, --sunset, --start, or --end must be provided.` Exit 1 |
| On error (parse failure) | `Error: Invalid time format "9:00". Expected HH:mm (e.g., 07:30).` Exit 1 |
| On error (sunset ≤ sunrise, both provided in same call) | `Error: Sunset time must be after sunrise time.` Exit 1 |
| On error (end before start, both provided in same call) | `Error: Season end date must be after season start date.` Exit 1 |

**Assumption:** Cross-field validation (sunset > sunrise; end > start) is only enforced when both fields of a pair are provided in the **same invocation**. If only one is set in a call, no cross-field validation runs — the scheduling slice enforces completeness before Phase 1.

#### `planr config show`

| | |
|---|---|
| Validation | None |
| On success (fully configured) | Tabular config display |
| On success (partially configured) | Same display; null fields show `(not set)` |

```
League Configuration
--------------------
Sunrise:        07:00
Sunset:         20:00
Season start:   2026-06-01
Season end:     2026-08-31
```

```
League Configuration
--------------------
Sunrise:        (not set)
Sunset:         (not set)
Season start:   (not set)
Season end:     (not set)
```

---

### Field Commands (modified)

#### `planr field add <name> [--address <address>]` — unchanged

#### `planr field edit <name> [--name <new-name>] [--address <address>]` — unchanged

#### `planr field delete <name>`

| | |
|---|---|
| Change | Cascade message reflects blocks and overrides instead of windows |
| On success | `Field "Riverside Park" deleted (3 block(s), 1 override(s) removed).` |

If blocks or overrides are zero: omit those clauses or show zero:
`Field "Riverside Park" deleted (0 blocks, 0 overrides removed).`

#### `planr field list`

| | |
|---|---|
| Change | WINDOWS column replaced by BLOCKS and OVERRIDES columns |

```
NAME              ADDRESS                  BLOCKS    OVERRIDES
--------------    ---------------------    ------    ---------
Riverside Park    123 Main St              3         1
Eastside Field    (none)                   0         0
```

---

### Field Block Commands (new — replaces `planr field window`)

All block commands are nested under `planr field block`.

Block numbers are **1-based display indices** corresponding to the order shown in `planr field block list`. Multiple blocks on the same date are allowed (each blocks a different time range on that date).

#### `planr field block add <field-name> --date <YYYY-MM-DD> --start <HH:mm> --end <HH:mm>`

| | |
|---|---|
| Validation | Field must exist; `--date` parses as ISO 8601; `--start` and `--end` parse as `HH:mm`; `end > start` |
| On success | Appends block, saves file. Prints: `Block #3 added to "Riverside Park" (2026-06-15, 09:00–12:00).` |
| Season warning | If `config.seasonStart` and `config.seasonEnd` are both set and the block date falls outside `[seasonStart, seasonEnd]`, print **after** the success line: `Warning: Block date 2026-06-15 is outside the configured season (2026-06-01 to 2026-08-31). Block saved.` |
| On error (field not found) | `Error: Field "Riverside Park" not found.` Exit 1 |
| On error (end ≤ start) | `Error: End time must be after start time.` Exit 1 |
| On error (invalid date) | `Error: Invalid date "2026-13-01". Expected YYYY-MM-DD.` Exit 1 |

#### `planr field block edit <field-name> <block-number> [--date <YYYY-MM-DD>] [--start <HH:mm>] [--end <HH:mm>]`

| | |
|---|---|
| Validation | Field must exist; `<block-number>` is a valid 1-based index; at least one option required; `end > start` after merging the existing and provided values (compute final start/end before checking) |
| On success | `Block #2 on "Riverside Park" updated.` |
| Season warning | Same season-range warning as `add` if the resulting date falls outside the season |
| On error (field not found) | `Error: Field "Riverside Park" not found.` Exit 1 |
| On error (invalid block number) | `Error: Block #5 not found for "Riverside Park" (1–3 are valid).` Exit 1 |
| On error (no options) | `Error: At least one of --date, --start, or --end must be provided.` Exit 1 |
| On error (end ≤ start after merge) | `Error: End time must be after start time.` Exit 1 |

**Merge semantics for end > start validation:** Apply the provided options to the existing block values first, then validate the resulting `(startTime, endTime)` pair. This allows `--end 11:00` alone on a block with `start=09:00, end=10:00` to succeed (result: 09:00–11:00 ✓).

#### `planr field block delete <field-name> <block-number>`

| | |
|---|---|
| Validation | Field must exist; `<block-number>` is a valid 1-based index |
| On success | Removes block at that index (remaining blocks shift up). Prints: `Block #2 on "Riverside Park" deleted.` |
| On error (field not found) | `Error: Field "Riverside Park" not found.` Exit 1 |
| On error (invalid block number) | `Error: Block #5 not found for "Riverside Park" (1–3 are valid).` Exit 1 |

#### `planr field block list <field-name>`

| | |
|---|---|
| Validation | Field must exist |
| On success (blocks exist) | Tabular output: `#`, `DATE`, `START`, `END` |
| On success (no blocks) | `No blocks for "Riverside Park". Use 'planr field block add' to create one.` |
| On error | `Error: Field "Riverside Park" not found.` Exit 1 |

```
#   DATE          START    END
-   ----------    -----    -----
1   2026-06-15    09:00    12:00
2   2026-07-04    08:00    20:00
3   2026-08-01    13:00    15:00
```

Blocks are displayed in insertion order (append-only list; no automatic sorting).

---

### Field Override Commands (new)

All override commands are nested under `planr field override`.

A **date override** replaces the league-level open window for a specific field on a specific date. At most one override may exist per date per field.

Override numbers are **1-based display indices** corresponding to the order shown in `planr field override list`.

#### `planr field override add <field-name> --date <YYYY-MM-DD> --start <HH:mm> --end <HH:mm>`

| | |
|---|---|
| Validation | Field must exist; `--date` parses as ISO 8601; `--start` and `--end` parse as `HH:mm`; `end > start`; no existing override for this date on this field |
| On success | Appends override, saves file. Prints: `Override #2 added to "Riverside Park" (2026-07-04, open 10:00–16:00).` |
| Season warning | Same out-of-season warning as field block add |
| On error (field not found) | `Error: Field "Riverside Park" not found.` Exit 1 |
| On error (duplicate date) | `Error: An override for 2026-07-04 already exists on "Riverside Park" (override #1). Use 'planr field override edit' to change it.` Exit 1 |
| On error (end ≤ start) | `Error: End time must be after start time.` Exit 1 |

#### `planr field override edit <field-name> <override-number> [--date <YYYY-MM-DD>] [--start <HH:mm>] [--end <HH:mm>]`

| | |
|---|---|
| Validation | Field must exist; `<override-number>` is a valid 1-based index; at least one option required; `end > start` after merging; if `--date` is provided and another override already exists for that date, reject |
| On success | `Override #1 on "Riverside Park" updated.` |
| On error (duplicate date via edit) | `Error: An override for 2026-07-05 already exists on "Riverside Park" (override #3). Dates must be unique per field.` Exit 1 |

#### `planr field override delete <field-name> <override-number>`

| | |
|---|---|
| Validation | Field must exist; `<override-number>` is a valid 1-based index |
| On success | `Override #1 on "Riverside Park" deleted.` |
| On error | `Error: Field "Riverside Park" not found.` / `Error: Override #5 not found for "Riverside Park" (1–2 are valid).` Exit 1 |

#### `planr field override list <field-name>`

| | |
|---|---|
| Validation | Field must exist |
| On success (overrides exist) | Tabular output: `#`, `DATE`, `OPEN START`, `OPEN END` |
| On success (no overrides) | `No date overrides for "Riverside Park". Use 'planr field override add' to create one.` |

```
#   DATE          OPEN START    OPEN END
-   ----------    ----------    --------
1   2026-07-04    10:00         16:00
2   2026-07-19    08:00         18:00
```

---

## Critical Path Walkthroughs

### 1. Migrate from v3 and Configure League

```
User upgrades binary; runs: planr config set --sunrise 07:00 --sunset 20:00

1. Picocli routes to ConfigCommand.SetCmd.call()
2. LeagueStore.load():
   a. Read league.json (version=3)
   b. v1→v2 guard: skipped (version >= 2)
   c. v2→v3 guard: skipped (version >= 3)
   d. v3→v4 guard: fires
      - Strip windows from all fields; initialize blocks=[], dateOverrides=[]
      - Build LeagueConfig(null, null, null, null)
      - Build League(4, divisions, migratedFields, config, schedule)
      - atomic save
      - Print to stderr: "Warning: Field availability windows from a previous version
        have been removed. Please configure field blocks for the new season."
   e. Return migrated League (version=4)
3. ConfigCommand.SetCmd.call():
   a. Parse: sunriseTime=07:00, sunsetTime=20:00
   b. Validate: sunsetTime.isAfter(sunriseTime) ✓
   c. Merge with existing config: update sunriseTime and sunsetTime;
      seasonStart and seasonEnd remain null
   d. Build new LeagueConfig(07:00, 20:00, null, null)
   e. league.withConfig(newConfig) → new League
   f. LeagueStore.save(league) — atomic write
4. Print: League config updated.
5. Exit 0
```

---

### 2. Add a Division with Target Games

```
User: planr division add "Majors" --duration 120 --target 12

1. Picocli parses: name="Majors", duration=120, target=12
2. DivisionCommand.Add.call():
   a. Validate: name non-empty ✓
   b. Validate: duration > 0 ✓
   c. Validate: target >= 1 ✓
3. LeagueStore.load() → League (v4)
4. league.hasDivision("Majors") → false ✓
5. Build Division: id=UUID.randomUUID(), name="Majors",
   gameDurationMinutes=120, targetGamesPerTeam=12, teams=[]
6. league.withDivisionAdded(division) → new League
7. LeagueStore.save(league) — atomic write
8. Print: Division "Majors" added (120 min/game, target 12 games/team).
9. Exit 0
```

**Error path (missing --target):**
- Picocli marks `--target` as required; missing flag causes Picocli to print usage and exit 2 before `call()` runs.

**Note:** `--target` is declared as a required Picocli option. No custom validation needed for "missing" — Picocli handles it.

---

### 3. Add a Field Block with Season Warning

```
User: planr field block add "Riverside Park" --date 2026-09-15 --start 09:00 --end 12:00
(season is configured as 2026-06-01 to 2026-08-31)

1. Picocli parses: fieldName="Riverside Park", date=2026-09-15,
   startTime=09:00, endTime=12:00
2. FieldBlockCommand.Add.call():
   a. Parse date: LocalDate.parse("2026-09-15") ✓
   b. Parse 09:00 → LocalTime.of(9,0) ✓
   c. Parse 12:00 → LocalTime.of(12,0) ✓
   d. Validate: endTime.isAfter(startTime) ✓
3. LeagueStore.load() → League (v4)
4. league.findField("Riverside Park") → Field (found) ✓
5. Build FieldBlock: id=UUID.randomUUID(), date=2026-09-15,
   startTime=09:00, endTime=12:00
6. field.withBlockAdded(block) → new Field
7. league.withFieldReplaced(field.id(), newField) → new League
8. LeagueStore.save(league) — atomic write
9. blockNumber = newField.blocks().size()  (e.g., 1)
10. Print: Block #1 added to "Riverside Park" (2026-09-15, 09:00–12:00).
11. Check season range:
    config.seasonStart() = 2026-06-01 (non-null)
    config.seasonEnd()   = 2026-08-31 (non-null)
    date 2026-09-15 is after seasonEnd → warn
12. Print: Warning: Block date 2026-09-15 is outside the configured season
           (2026-06-01 to 2026-08-31). Block saved.
13. Exit 0
```

---

### 4. Add a Per-Date Open Window Override

```
User: planr field override add "Riverside Park" --date 2026-07-04 --start 10:00 --end 16:00

1. Picocli parses: fieldName="Riverside Park", date=2026-07-04,
   openStart=10:00, openEnd=16:00
2. FieldOverrideCommand.Add.call():
   a. Validate end > start ✓
3. LeagueStore.load() → League (v4)
4. league.findField("Riverside Park") → Field (found) ✓
5. Check uniqueness: field.dateOverrides().stream()
   .anyMatch(o -> o.date().equals(LocalDate.of(2026,7,4))) → false ✓
6. Build FieldDateOverride: id=UUID.randomUUID(), date=2026-07-04,
   openStart=10:00, openEnd=16:00
7. field.withOverrideAdded(override) → new Field
8. league.withFieldReplaced(field.id(), newField) → new League
9. LeagueStore.save(league) — atomic write
10. overrideNumber = newField.dateOverrides().size()
11. Print: Override #1 added to "Riverside Park" (2026-07-04, open 10:00–16:00).
12. Exit 0
```

**Error path (duplicate date):**
- Step 5: `anyMatch` returns true → find the existing override's 1-based index
- Print to stderr: `Error: An override for 2026-07-04 already exists on "Riverside Park"
  (override #1). Use 'planr field override edit' to change it.`
- Exit 1 (no file write)

---

### 5. Schema Migration (v1 → v4) on a Standalone Old File

```
User has a v1 league.json (from the Division & Team Management slice only, no fields).

1. LeagueStore.load():
   a. Deserialize → League(version=1, divisions=[...], fields=null, schedule=null)
   b. Compact constructor normalizes: fields → List.of()
   c. v1→v2 guard fires:
      league = new League(2, divisions, List.of(), null, null)
      save() — atomic write
   d. v2→v3 guard fires:
      league = new League(3, divisions, List.of(), null, null)
      save() — atomic write
   e. v3→v4 guard fires:
      migratedFields = [] (already empty; map is a no-op)
      config = new LeagueConfig(null, null, null, null)
      league = new League(4, divisions, [], config, null)
      save() — atomic write
      Print warning to stderr
2. Return League(version=4)
3. Command proceeds normally.
```

Three sequential saves occur on first invocation. Each is atomic. Performance is negligible for a local small file.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| `targetGamesPerTeam` nullability | `int` (0 = sentinel), `Integer` (null = unset), require at creation | **`int` with 0 sentinel + required at `add` time** | Consistent with `gameDurationMinutes` (also `int`); 0 is invalid for the scheduling constraint so it cleanly signals "not configured"; requiring at creation prevents orphaned records | Migrated divisions have `targetGamesPerTeam = 0` requiring an extra `edit` call. Acceptable — organizers were not using the target field before v2. |
| League config storage | Inline fields on `League`, separate `LeagueConfig` record, separate file | **`LeagueConfig` nested record on `League`** | Groups logically related fields; matches the Java record idiom used throughout; `null` config cleanly signals "not yet configured" without per-field null checks | `League` gains a new field, requiring `withConfig()` helper and a migration step. One-time setup cost. |
| Season dates location | On `LeagueConfig`, as flags to Phase 1 `generate` command | **`LeagueConfig`** | The field block out-of-season warning requires access to season dates at block-add time, before any generation command runs. Storing on `LeagueConfig` satisfies this requirement without requiring a separate lookup | Organizers may set dates and then change their minds; but editing config is low-friction. |
| Field block identification | UUID exposed to user, 1-based index, date+start as natural key | **1-based display index** | Consistent with the window-number convention from the v1 spec; ergonomic for CLI users who have the list output in front of them | Index shifts after delete (same as v1 window numbers). Acceptable for a single-user CLI. |
| Field date override identification | 1-based index, `--date` as natural key (since date is unique per field) | **1-based display index** | Consistency with block numbering is more important than exploiting uniqueness; `list` is always the reference | Slightly more verbose than using `--date` as a key, but uniform with block editing. |
| Multiple blocks per date | Allowed (each blocks a different time range), one block per date | **Allowed** | The spec does not restrict blocks per date; a holiday with morning and afternoon tournaments would require two separate blocks; one-block-per-date would force combining them, losing granularity | Organizers can add overlapping blocks on the same date. The scheduler handles this correctly (any slot overlapping any block is excluded), so overlaps are harmless but confusing. A future validation could warn. |
| `FieldWindowCommand` retirement | Keep and alias, rename, delete | **Delete entirely** | The v2 availability model is semantically incompatible with v1 windows; aliasing would maintain dead code; the schema migration discards all window data anyway | Organizers who have memorized `planr field window` must learn `planr field block`. Migration warning makes this explicit. |
| Config cross-field validation at edit time | Validate sunset > sunrise only if both provided in same call, always validate, never validate | **Only when both provided in the same call** | Enforcing always would prevent setting sunrise without first knowing sunset (and vice versa); the scheduling slice already validates completeness before Phase 1 | A config with sunrise > sunset is technically invalid and won't be caught until generation. This is a scheduling-layer concern, not an entity-management concern. |

---

## Operational Concerns

**Error handling:** Unchanged pattern — validation errors to `stderr` + exit 1; I/O errors to `stderr` + exit 2. Stack traces suppressed.

**Data integrity:** Atomic write (temp + rename) for all mutations, as established. Multi-step migration does three sequential atomic writes — each individual write is safe; in the event of a crash mid-chain, the guard re-runs on next invocation starting from the saved intermediate version.

**Deletion of `AvailabilityWindow.java`:** Remove the class file. Any command code in `FieldWindowCommand` that referenced it is deleted with the class. Verify no other source file imports `AvailabilityWindow` before deleting (only `Field.java` and `FieldWindowCommand.java` referenced it).

**`PlanrApp` registration:** Add `ConfigCommand` to the `@Command(subcommands = {...})` annotation on `PlanrApp`. Register `FieldBlockCommand` and `FieldOverrideCommand` as nested subcommands of `FieldCommand` (replacing `FieldWindowCommand`). Remove `FieldWindowCommand` from the registration.

**Testing strategy:**

New unit tests required:

- `DivisionCommandTest.Add` — `--target` required: missing flag exits with usage; `--target 0` exits 1; `--target -1` exits 1; `--target 5` succeeds.
- `DivisionCommandTest.Edit` — `--target` optional; `--target 0` exits 1 if provided; `--target 10` updates and saves.
- `DivisionCommandTest.List` — target=0 shows `0*` and trailing warning; all configured shows no warning.
- `ConfigCommandTest.Set` — no flags exits 1; `--sunrise` alone succeeds; both flags with sunset ≤ sunrise exits 1; valid pair saves and returns.
- `ConfigCommandTest.Show` — null config shows all `(not set)`; partially set config shows mix; fully set shows all values.
- `FieldBlockCommandTest` — add: success (no season config, no warning); add with out-of-season date: success + warning; add with end ≤ start: exits 1; add to nonexistent field: exits 1. Edit: merge semantics for start/end validation. Delete: index shift after delete. List: empty output.
- `FieldOverrideCommandTest` — add: success; add duplicate date: exits 1 with override number in message. Edit: date uniqueness check when `--date` changed. Delete, list: same patterns as blocks.
- `FieldCommandTest.Delete` — output message reflects blocks and overrides count.
- `FieldCommandTest.List` — BLOCKS and OVERRIDES columns shown correctly.
- `LeagueStoreTest` — v1→v4 migration: write v1 file, call `load()`, assert v4 file with empty blocks/dateOverrides on fields, config with null times. v3→v4 migration: write v3 file with windows, assert windows are gone and blocks/dateOverrides are empty.
- Jackson round-trip: `FieldBlock` (`LocalDate` as ISO, `LocalTime` as `HH:mm`), `FieldDateOverride`, `LeagueConfig` (null and non-null).

Tests continue to use the `systemProperty 'user.home'` redirect and `maxParallelForks = 1`.

**Capacity:** A typical league has 2–10 fields with 10–50 blocks per season and 0–5 date overrides per field. The JSON file remains well under 100 KB. No performance concern.

---

## Implementation Plan

Tasks are ordered to keep the project in a compilable state at each phase boundary. Steps within a phase share no dependencies and may be done in any order; phases must be followed in sequence.

---

### Phase 1 — New Model Records

These are leaf types with no inbound dependencies. Create them first so all later phases can reference them freely.

**Step 1 — Create `LeagueConfig.java`**
Record with four nullable fields: `sunriseTime: LocalTime`, `sunsetTime: LocalTime`, `seasonStart: LocalDate`, `seasonEnd: LocalDate`. Add a `static LeagueConfig empty()` factory returning an all-null instance; this is the value used during migration.

**Step 2 — Create `FieldBlock.java`**
Record with: `id: UUID`, `date: LocalDate`, `startTime: LocalTime`, `endTime: LocalTime`. No methods — all mutation helpers live on `Field`.

**Step 3 — Create `FieldDateOverride.java`**
Record with: `id: UUID`, `date: LocalDate`, `openStart: LocalTime`, `openEnd: LocalTime`. No methods.

> **Compile gate:** `gradle compileJava` must pass before proceeding.

---

### Phase 2 — Modify Existing Model Records

These changes break `FieldWindowCommand.java` (references `Field.windows()` which is removed). Accept the compile break — it is resolved in Phase 4 when `FieldWindowCommand` is deleted.

**Step 4 — Modify `Division.java`**
Add `targetGamesPerTeam: int` as a new record component after `gameDurationMinutes`. Update the three existing `withTeam*` helpers to forward the new field in the `Division` constructor call. No compact constructor normalization needed for `int` — the Java default of `0` is the correct migration sentinel.

**Step 5 — Modify `Field.java`**
Replace `List<AvailabilityWindow> windows` with two lists: `List<FieldBlock> blocks` and `List<FieldDateOverride> dateOverrides`. Add mutation helpers:
- `withBlockAdded(FieldBlock)`, `withBlockReplaced(int zeroBasedIndex, FieldBlock)`, `withBlockRemoved(int zeroBasedIndex)`
- `withOverrideAdded(FieldDateOverride)`, `withOverrideReplaced(int zeroBasedIndex, FieldDateOverride)`, `withOverrideRemoved(int zeroBasedIndex)`

The compact constructor must normalize null `blocks` → `List.of()` and null `dateOverrides` → `List.of()` (same pattern as the existing null-check for `windows`).

**Step 6 — Modify `League.java`**
Add `config: LeagueConfig` as a new record component. Add `withConfig(LeagueConfig newConfig)` mutation helper. Update all existing `withDivision*` and `withField*` and `withSchedule` helpers to forward `config` in their `League` constructor calls (they currently forward all fields — just add the new one). The compact constructor does not normalize `null` config — null is the valid "not yet configured" state.

> **Note:** The project does not compile cleanly at this point because `FieldWindowCommand.java` references the deleted `Field.windows()`. Do not run `gradle build`. If a partial compile check is needed, temporarily comment out `FieldWindowCommand`'s registration in `FieldCommand.java`.

---

### Phase 3 — Delete Retired Files

**Step 7 — Delete `AvailabilityWindow.java`**
`Field.java` no longer references it. `FieldWindowCommand.java` still imports it but is itself being deleted in Phase 4; the compile break there is already accepted. Verify no other file imports `AvailabilityWindow` (only `Field.java` and `FieldWindowCommand.java` referenced it in the prior implementation).

---

### Phase 4 — Store Migration

This phase restores compilation.

**Step 8 — Modify `LeagueStore.java`**

Four changes in order:

1. **Update `CURRENT_VERSION`** — change from `3` to `4`.

2. **Update existing migration guard constructors** — the v1→v2 and v2→v3 guards construct new `League` instances; each must now pass `null` (or `league.config()` where available) as the new `config` argument. Concretely:
   - v1→v2 guard: `new League(2, league.divisions(), List.of(), null, null)` (config=null, schedule=null)
   - v2→v3 guard: `new League(3, league.divisions(), league.fields(), null, null)` (config=null, schedule=null)

3. **Add v3→v4 migration guard** — after the v2→v3 guard:
   ```
   if (league.version() < 4) {
       List<Field> migratedFields = league.fields().stream()
           .map(f -> new Field(f.id(), f.name(), f.address(), List.of(), List.of()))
           .toList();
       LeagueConfig config = LeagueConfig.empty();
       league = new League(4, league.divisions(), migratedFields, config, league.schedule());
       save(league);
       System.err.println("Warning: Field availability windows from a previous version "
           + "have been removed. Please configure field blocks for the new season.");
   }
   ```

4. **Verify `LocalDate` serialization** — `LocalDate` ISO 8601 format is the `JavaTimeModule` default when `WRITE_DATES_AS_TIMESTAMPS` is disabled. Confirm the existing `ObjectMapper` configuration in `LeagueStore` already satisfies this (it should — no new deserializer registration is required).

> **Compile gate:** `gradle compileJava` must pass (all model changes are complete; `FieldWindowCommand.java` still fails — see Phase 5, Step 13).

---

### Phase 5 — Command Implementations

All steps in this phase are independent of each other. They share a dependency on Phases 1–4 being complete but not on each other.

**Step 9 — Modify `DivisionCommand.java`**

Three inner classes change:

- **`Add`**: Add `@Option(names = "--target", required = true, description = "Target games per team") int target`. After Picocli parses, validate `target >= 1`; if not, print error and exit 1. Build `Division` with `targetGamesPerTeam = target`. Update success message to: `Division "<name>" added (<N> min/game, target <T> games/team).`

- **`Edit`**: Add `@Option(names = "--target", required = false) Integer target` (boxed so null = not provided). If non-null, validate `>= 1`; if invalid, exit 1. Apply to the replacement `Division` if provided.

- **`List`**: Add TARGET column between DURATION and TEAMS. After printing the table, scan divisions for any with `targetGamesPerTeam == 0`; if any exist, print: `Warning: <N> division(s) have no target configured. Set with 'planr division edit <name> --target <n>'.`

**Step 10 — Create `ConfigCommand.java`**

Top-level `@Command(name = "config", subcommands = {Set.class, Show.class})` with two static inner classes:

- **`Set`**: Four optional `@Option` fields — `--sunrise` and `--sunset` as `LocalTime` (parsed with `DateTimeFormatter.ofPattern("HH:mm")`), `--start` and `--end` as `LocalDate` (ISO format). Validate at least one is present. If both `--sunrise` and `--sunset` are non-null in this call, validate `sunset.isAfter(sunrise)`. If both `--start` and `--end` are non-null in this call, validate `end.isAfter(start)`. Load league; if `league.config()` is null, start from `LeagueConfig.empty()`; merge provided values over existing; save.

- **`Show`**: Load league; if `league.config()` is null, treat all fields as null. Print four-line block with labels and values (or `(not set)` for nulls).

Access store via `@ParentCommand ConfigCommand parent` → `parent.app.store` (same pattern as `DivisionCommand`).

**Step 11 — Create `FieldBlockCommand.java`**

`@Command(name = "block", subcommands = {Add.class, Edit.class, Delete.class, List.class})` nested under `FieldCommand`. Access store via `parent.fieldCmd.app.store`.

- **`Add`**: Positional `<field-name>`; options `--date` (String, parse to `LocalDate`), `--start` and `--end` (String, parse to `LocalTime` with `HH:mm` pattern). Parse dates first (exit 1 on parse failure with specific format message). Validate `endTime.isAfter(startTime)`. Load league, find field (exit 1 if not found). Build `FieldBlock`, call `field.withBlockAdded(block)`, replace field in league, save. Compute `blockNumber = newField.blocks().size()`. Print success. Then: if `league.config()` is non-null and both `seasonStart`/`seasonEnd` are non-null, check whether block date is outside `[seasonStart, seasonEnd]`; if so, print season warning.

- **`Edit`**: Positional `<field-name>` and `<block-number>` (int). Options `--date`, `--start`, `--end` all optional; validate at least one provided. Load, find field, validate 1-based index (convert to 0-based: `idx = blockNumber - 1`; valid range is `0..blocks.size()-1`). Merge: start with existing block's values; apply provided options over them. Validate merged `endTime.isAfter(startTime)`. Build replacement `FieldBlock` with same `id`. Replace, save. Print success. Apply season warning to resulting date.

- **`Delete`**: Positional `<field-name>` and `<block-number>`. Load, find field, validate index, call `field.withBlockRemoved(idx)`, save. Print success.

- **`List`**: Positional `<field-name>`. Load, find field. If `field.blocks().isEmpty()`, print empty message. Otherwise print table with `#`, `DATE`, `START`, `END` columns in insertion order.

**Step 12 — Create `FieldOverrideCommand.java`**

`@Command(name = "override", subcommands = {Add.class, Edit.class, Delete.class, List.class})` nested under `FieldCommand`.

- **`Add`**: Same parsing as `FieldBlockCommand.Add` but uses `openStart`/`openEnd`. After loading and finding the field, check date uniqueness: `field.dateOverrides().stream().anyMatch(o -> o.date().equals(date))`. If a match exists, find its 1-based index and print the duplicate error with that number; exit 1. Otherwise build `FieldDateOverride`, call `field.withOverrideAdded(override)`, save. Apply season warning.

- **`Edit`**: Same merge pattern as `FieldBlockCommand.Edit`. Additionally: if `--date` is provided, check that no other override (excluding the one being edited) has that same date. If a conflict exists, print the duplicate-date error with the conflicting override's 1-based index; exit 1.

- **`Delete`** and **`List`**: Identical pattern to `FieldBlockCommand.Delete`/`List` with `dateOverrides` and `OPEN START`/`OPEN END` column labels.

**Step 13 — Modify `FieldCommand.java`**

Three changes:

1. **Subcommand registration**: In the `@Command(subcommands = {...})` annotation, remove `FieldWindowCommand.class`; add `FieldBlockCommand.class` and `FieldOverrideCommand.class`.

2. **`Delete` inner class**: Before calling `league.withFieldRemoved(field.id())`, capture `int blockCount = field.blocks().size()` and `int overrideCount = field.dateOverrides().size()`. Update success message: `Field "<name>" deleted (<B> block(s), <O> override(s) removed).`

3. **`List` inner class**: Replace the WINDOWS column with BLOCKS and OVERRIDES columns. Read counts from `field.blocks().size()` and `field.dateOverrides().size()`.

**Step 14 — Delete `FieldWindowCommand.java`**

All references to it have been removed in Step 13. Delete the file.

> **Compile gate:** `gradle compileJava` passes. `gradle test` passes all previously passing tests (existing `FieldCommandTest` tests may need minor updates for the new list/delete output — see Phase 6).

---

### Phase 6 — PlanrApp Wiring

**Step 15 — Modify `PlanrApp.java`**

Add `ConfigCommand.class` to the `@Command(subcommands = {...})` annotation on `PlanrApp`. Verify `FieldWindowCommand.class` is not directly registered here (per the CLAUDE.md architecture, `FieldWindowCommand` was registered under `FieldCommand`, not `PlanrApp`; no removal needed here).

> **Smoke test:** `gradle installDist && ./build/install/planr/bin/planr --help` must list `config` in the top-level subcommand list. `./build/install/planr/bin/planr field --help` must list `block` and `override`, and must not list `window`.

---

### Phase 7 — Tests

All test steps are independent of each other within this phase.

**Step 16 — Update `DivisionCommandTest`**

- Fix all existing `Add.success`-style tests: add `--target 10` (or any valid value) to the invocation — `--target` is now required and Picocli will fail without it.
- Add `Add.missingTarget` — invoke without `--target`; assert non-zero exit and usage output.
- Add `Add.targetZero` — `--target 0`; assert exit 1 and error message.
- Add `Add.targetNegative` — `--target -1`; assert exit 1.
- Add `Edit.targetUpdated` — edit existing division with `--target 10`; assert saved value.
- Add `Edit.targetZeroRejected` — `--target 0` on edit; assert exit 1.
- Add `List.showsTargetColumn` — division list includes TARGET header and values.
- Add `List.targetZeroWarning` — at least one division with `targetGamesPerTeam = 0`; assert warning line in stdout.

**Step 17 — Create `ConfigCommandTest`** (extends `CommandTestBase`)

- `Set.noFlagsExits1`
- `Set.sunriseAloneSucceeds` — verify only `sunriseTime` updated; others remain null.
- `Set.sunsetAtOrBeforeSunriseExits1`
- `Set.validSunriseSunset` — both set; verify saved.
- `Set.seasonEndBeforeStartExits1`
- `Set.validSeasonDates` — both set; verify saved.
- `Set.allFourFlags` — full config set in one call; verify all values saved.
- `Show.allNull` — no config set; all lines show `(not set)`.
- `Show.partiallySet` — one or two values set; correct mix displayed.
- `Show.fullConfig` — all four values shown correctly.

**Step 18 — Create `FieldBlockCommandTest`** (extends `CommandTestBase`)

- `Add.success` — field exists, valid date/start/end; assert success message and saved block; no season warning when config is null.
- `Add.outOfSeasonWarning` — config with season set; block date outside range; assert success message AND warning line both printed.
- `Add.insideSeasonNoWarning` — block date inside season; assert no warning.
- `Add.endBeforeStartExits1`
- `Add.fieldNotFoundExits1`
- `Add.invalidDateFormatExits1`
- `Edit.successUpdatesDate` — verify date changed; other fields preserved.
- `Edit.mergeValidEndAfterStart` — `--end` only; merged range is valid; succeeds.
- `Edit.mergeInvalidEndBeforeStart` — `--end` only that produces end ≤ existing start; exits 1.
- `Edit.invalidIndexExits1`
- `Edit.noFlagsExits1`
- `Delete.success` — block removed; list shows remaining blocks with shifted indices.
- `List.empty` — no blocks message.
- `List.withBlocks` — table output matches format.

**Step 19 — Create `FieldOverrideCommandTest`** (extends `CommandTestBase`)

- `Add.success`
- `Add.duplicateDateExits1` — second add for same date; exit 1 with existing override number in message.
- `Add.endBeforeStartExits1`
- `Add.fieldNotFoundExits1`
- `Edit.success`
- `Edit.dateChangedToConflict` — editing date to match another existing override's date; exit 1 with conflicting override number.
- `Edit.dateChangedToNonConflict` — editing date to a free date; succeeds.
- `Delete.success`
- `List.empty`
- `List.withOverrides`

**Step 20 — Update `FieldCommandTest`**

- `Delete.messageReflectsBlocksAndOverrides` — create field, add blocks and overrides, delete; assert new message format with counts.
- `Delete.messageWithZeroCounts` — delete field with no blocks/overrides; assert `(0 block(s), 0 override(s) removed)`.
- `List.showsBlocksAndOverridesColumns` — assert BLOCKS and OVERRIDES columns present; WINDOWS column absent.
- Remove or update any test that asserted the old WINDOWS column or the old delete message.

**Step 21 — Update `LeagueStoreTest`**

- `migration_v1_to_v4` — write a minimal v1 JSON (version=1, divisions with no `targetGamesPerTeam`, no `fields`); call `load()`; assert: version field is 4, fields list is empty, config is non-null with all four fields null, warning printed to stderr.
- `migration_v3_to_v4_stripsWindows` — write a v3 JSON with at least one field containing `windows` entries; call `load()`; assert: version is 4, field has empty `blocks` and `dateOverrides`, no `windows` key in saved JSON.
- `jacksonRoundTrip_FieldBlock` — construct a `FieldBlock`, serialize to JSON string, deserialize back; assert `date` serialized as `"2026-06-15"`, `startTime` as `"09:00"` (not `"09:00:00"`).
- `jacksonRoundTrip_FieldDateOverride` — same verification for `openStart`/`openEnd`.
- `jacksonRoundTrip_LeagueConfig_nulls` — serialize `LeagueConfig.empty()`; assert all four fields are JSON `null`; deserialize; assert all null.
- `jacksonRoundTrip_LeagueConfig_fullySet` — serialize with all four non-null values; round-trip; assert values match.

> **Compile and test gate:** `gradle test` must pass with zero failures.

---

### Phase 8 — Final Verification

**Step 22 — End-to-end smoke test**

After `gradle installDist`, run the full happy-path sequence manually and verify each output matches the API contracts in this spec:

```
planr config set --sunrise 07:00 --sunset 20:00 --start 2026-06-01 --end 2026-08-31
planr config show
planr division add "Majors" --duration 120 --target 12
planr division list
planr team add "Majors" "Blue Jays"
planr field add "Riverside Park"
planr field block add "Riverside Park" --date 2026-06-15 --start 09:00 --end 12:00
planr field block add "Riverside Park" --date 2026-09-15 --start 09:00 --end 12:00
planr field block list "Riverside Park"
planr field override add "Riverside Park" --date 2026-07-04 --start 10:00 --end 16:00
planr field override add "Riverside Park" --date 2026-07-04 --start 11:00 --end 15:00
planr field override list "Riverside Park"
planr field list
planr field delete "Riverside Park"
```

Expected observations:
- `field block add` with date 2026-09-15 succeeds and prints the out-of-season warning.
- Second `field override add` for 2026-07-04 exits 1 with the duplicate-date message referencing override #1.
- `field list` shows BLOCKS and OVERRIDES columns with correct counts before deletion.
- `field delete` prints the cascade message with block and override counts.

---

## Out of Scope / Future Work

- **Phase 1 (Team Schedule Generation)** — separate spec. Reads `Division.targetGamesPerTeam`; validates it is ≥ 1 and produces round-robin + fill games. Reads `LeagueConfig.seasonStart`, `seasonEnd`, `sunriseTime`, `sunsetTime` as prerequisites.
- **Phase 2 (Field Assignment / OR-Tools)** — separate spec. The scheduler effective-open-window logic (priority: `FieldDateOverride` > league defaults; subtract `FieldBlock` ranges) is implemented in `SchedulerService`, not in entity management.
- **Duplicate block warning** — the spec does not prohibit two blocks on the same date and field that cover the same hours. A future validation pass could warn on overlapping blocks.
- **Recurring field blocks** — explicitly out of scope per the PRD. Each block is date-specific; recurring patterns require individual entries.
- **`planr config show` in `planr division list` / `planr field block list`** — the season warning at block-add time is sufficient for v2. A future enhancement could show "outside season" context in the list output.
- **`$PLANR_DATA_DIR` env var override** — deferred from prior slices; still deferred.
- **Multi-league support** — deferred.
