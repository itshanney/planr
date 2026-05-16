# Tech Spec: Division & Team Management — `planr` CLI

**Date:** 2026-05-15
**Status:** Ready for Implementation
**Scope:** Division & Team Management acceptance criteria from `features/2026-05-15-league-planner-core-scheduling.md`
**Phase:** CLI prototype (precedes web application)

---

## Overview

`planr` is a Java 25 command-line application built with Gradle. It implements the Division and Team Management slice of League Planner: creating, editing, deleting, and listing divisions (with game durations) and teams (scoped to a division). All state is persisted in a single JSON file at `~/.planr/league.json`. Every mutating operation writes the full file atomically before returning — there is no in-flight state between the file and the in-memory model. The CLI uses Picocli for subcommand dispatch and Jackson for JSON serialization. The application is distributed as an executable wrapper script named `planr`.

---

## Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│  planr (CLI entry point)                                        │
│  PlanrApp — root @Command; wires subcommands, injects store     │
└────────────────────┬────────────────────────────────────────────┘
                     │ dispatches to
          ┌──────────┴──────────┐
          ▼                     ▼
┌──────────────────┐   ┌──────────────────┐
│ DivisionCommand  │   │ TeamCommand       │
│ add / edit /     │   │ add / edit /      │
│ delete / list    │   │ delete / list     │
└────────┬─────────┘   └────────┬──────────┘
         │                      │
         └──────────┬───────────┘
                    ▼
         ┌──────────────────────┐
         │  LeagueStore         │
         │  Single source of    │
         │  truth; loads from   │
         │  and saves to disk   │
         └──────────┬───────────┘
                    │ read / atomic write
                    ▼
         ┌──────────────────────┐
         │  ~/.planr/league.json│
         │  JSON file on disk   │
         └──────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|---|---|
| `PlanrApp` | Picocli root command; bootstraps `LeagueStore`; routes to subcommands |
| `DivisionCommand` | Validates and executes division CRUD operations via `LeagueStore` |
| `TeamCommand` | Validates and executes team CRUD operations via `LeagueStore` |
| `LeagueStore` | Loads `league.json` on first access; exposes mutation methods; writes atomically after each mutation |
| `league.json` | Sole persistence layer; single file holding all divisions and teams |

---

## Data Model

### In-Memory (Java Records)

```
League
  └── List<Division>
        ├── id: UUID
        ├── name: String          (unique across all divisions)
        ├── gameDurationMinutes: int  (positive integer)
        └── List<Team>
              ├── id: UUID
              └── name: String    (unique within its division)
```

All model classes are Java records — immutable value types. Mutation produces new record instances; `LeagueStore` replaces the in-memory root and writes to disk.

### JSON File Shape (`~/.planr/league.json`)

```json
{
  "version": 1,
  "divisions": [
    {
      "id": "a1b2c3d4-...",
      "name": "Majors",
      "gameDurationMinutes": 120,
      "teams": [
        { "id": "e5f6g7h8-...", "name": "Blue Jays" },
        { "id": "i9j0k1l2-...", "name": "Cardinals" }
      ]
    }
  ]
}
```

**Notes:**
- `version` is a top-level integer reserved for future schema migrations. Set to `1` for this release.
- IDs are UUIDs generated at creation time and never exposed to the user in command output. They are used internally to disambiguate and serve as stable keys for future features.
- The file is the single source of truth. If it does not exist on first run, `LeagueStore` creates it with an empty `divisions` array.

---

## Command Contract

All commands print one line of output on success and one line on error (to stderr). Exit code `0` = success, `1` = validation error, `2` = I/O error.

### Division Commands

#### `planr division add <name> --duration <minutes>`
| | |
|---|---|
| Validation | name non-empty; name unique across divisions; duration is positive integer |
| On success | Adds division, saves file. Prints: `Division "Majors" added (120 min/game).` |
| On error | Prints to stderr: `Error: Division "Majors" already exists.` Exit 1 |

#### `planr division edit <name> [--name <new-name>] [--duration <minutes>]`
| | |
|---|---|
| Validation | Division with `<name>` must exist; at least one of `--name` or `--duration` required; new name (if provided) must not conflict with an existing division; duration (if provided) is positive integer |
| On success | Updates division, saves file. Prints: `Division "Majors" updated.` |
| On error | `Error: Division "Coast" not found.` Exit 1 |

#### `planr division delete <name>`
| | |
|---|---|
| Validation | Division must exist; division must have zero teams |
| On success | Removes division, saves file. Prints: `Division "Majors" deleted.` |
| On error (not found) | `Error: Division "Majors" not found.` Exit 1 |
| On error (has teams) | `Error: Division "Majors" has 4 team(s). Remove all teams before deleting the division.` Exit 1 |

#### `planr division list`
| | |
|---|---|
| Validation | None |
| On success (divisions exist) | Tabular output — one row per division: name, duration, team count. Example below. |
| On success (no divisions) | `No divisions configured. Use 'planr division add' to create one.` |

```
DIVISION     DURATION    TEAMS
---------    --------    -----
Majors       120 min     4
AAA          90 min      6
Coast        90 min      5
T-Ball       60 min      8
```

---

### Team Commands

#### `planr team add <division-name> <team-name>`
| | |
|---|---|
| Validation | Division must exist; team name non-empty; team name unique within the division |
| On success | Adds team, saves file. Prints: `Team "Blue Jays" added to division "Majors".` |
| On error (division not found) | `Error: Division "Majors" not found.` Exit 1 |
| On error (duplicate team) | `Error: Team "Blue Jays" already exists in division "Majors".` Exit 1 |

#### `planr team edit <division-name> <team-name> --name <new-name>`
| | |
|---|---|
| Validation | Division must exist; team must exist within that division; new name non-empty; new name unique within the division |
| On success | Updates team, saves file. Prints: `Team "Blue Jays" renamed to "Royals" in division "Majors".` |
| On error (not found) | `Error: Team "Blue Jays" not found in division "Majors".` Exit 1 |

#### `planr team delete <division-name> <team-name>`
| | |
|---|---|
| Validation | Division must exist; team must exist within that division |
| On success | Removes team, saves file. Prints: `Team "Blue Jays" removed from division "Majors".` |
| On error | `Error: Team "Blue Jays" not found in division "Majors".` Exit 1 |

#### `planr team list <division-name>`
| | |
|---|---|
| Validation | Division must exist |
| On success (teams exist) | One team name per line, alphabetically sorted |
| On success (no teams) | `No teams in division "Majors". Use 'planr team add' to create one.` |
| On error | `Error: Division "Majors" not found.` Exit 1 |

---

## Critical Path Walkthroughs

### 1. Add a Division

```
User: planr division add "Majors" --duration 120

1. Picocli parses: name="Majors", duration=120
2. DivisionCommand calls LeagueStore.load()
   → If league.json missing: create ~/.planr/, write empty league, return empty League record
   → If exists: deserialize JSON → League record (in-memory)
3. Validate: name non-empty ✓, duration > 0 ✓, no existing division named "Majors" ✓
4. Build new Division record: id=UUID.randomUUID(), name="Majors", duration=120, teams=[]
5. Build new League record: previous divisions + new division
6. LeagueStore.save(league):
   a. Serialize league to JSON string
   b. Write to ~/.planr/league.json.tmp (atomic temp file, same directory)
   c. Files.move(tmp → league.json, ATOMIC_MOVE, REPLACE_EXISTING)
7. Print: Division "Majors" added (120 min/game).
8. Exit 0
```

**Error path (duplicate name):**
- Step 3 finds existing division with name "Majors"
- Print to stderr: `Error: Division "Majors" already exists.`
- Exit 1 (no file write)

---

### 2. Delete a Division (with team guard)

```
User: planr division delete "Majors"

1. Picocli parses: name="Majors"
2. LeagueStore.load() → League record
3. Find division by name (case-insensitive match — see Tradeoff Log)
4. Validate: division exists ✓
5. Validate: division.teams().isEmpty() — if not empty, exit with error
6. Build new League record: divisions minus the matched division
7. LeagueStore.save(league) — atomic write
8. Print: Division "Majors" deleted.
9. Exit 0
```

---

### 3. Add a Team to a Division

```
User: planr team add "Majors" "Blue Jays"

1. Picocli parses: divisionName="Majors", teamName="Blue Jays"
2. LeagueStore.load() → League record
3. Find division by name — error if not found
4. Validate: teamName non-empty ✓
5. Validate: no existing team in division named "Blue Jays" (case-insensitive) ✓
6. Build new Team record: id=UUID.randomUUID(), name="Blue Jays"
7. Build new Division record: existing division with teams + new team
8. Build new League record: replace old division with updated division
9. LeagueStore.save(league) — atomic write
10. Print: Team "Blue Jays" added to division "Majors".
11. Exit 0
```

---

## Project Structure

```
planr/
├── settings.gradle
├── build.gradle
└── src/
    └── main/
        └── java/
            └── com/leagueplanner/planr/
                ├── PlanrApp.java              # @Command root; main()
                ├── command/
                │   ├── DivisionCommand.java   # @Command("division") + subcommands
                │   └── TeamCommand.java       # @Command("team") + subcommands
                ├── model/
                │   ├── League.java            # record: List<Division>
                │   ├── Division.java          # record: id, name, duration, List<Team>
                │   └── Team.java              # record: id, name
                └── store/
                    └── LeagueStore.java       # load(), save(); atomic write logic
```

**No `util/` package.** Jackson `ObjectMapper` is configured inline in `LeagueStore` — there is one place that touches serialization and it does not need a wrapper.

---

## Build Configuration (`build.gradle`)

Key configuration points for the implementer:

```
plugins: application, java (toolchain: Java 25)
mainClass: com.leagueplanner.planr.PlanrApp
applicationName: planr   ← produces bin/planr wrapper script via Gradle application plugin

dependencies:
  implementation: info.picocli:picocli:4.7.x
  implementation: com.fasterxml.jackson.core:jackson-databind:2.x
  annotationProcessor: info.picocli:picocli-codegen:4.7.x  ← generates GraalVM reflect config (future use)
```

The Gradle `application` plugin's `installDist` task produces a `build/install/planr/bin/planr` shell script. During development, `./gradlew run --args="division list"` is the primary execution path.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| CLI framework | Picocli, Apache Commons CLI, manual arg parsing | **Picocli** | Annotation-driven subcommand support; best-in-class `--help` generation; active maintenance | Adds a dependency; acceptable given it's the de facto standard |
| JSON library | Jackson, Gson, Moshi | **Jackson** | Most mature; excellent record support in 2.x; widest ecosystem | Heavier than Gson; irrelevant at this scale |
| File location | `./league.json`, `~/.planr/league.json`, `$PLANR_HOME` | **`~/.planr/league.json`** | CLI tools conventionally live in `~`; avoids polluting every working directory; no env var needed for MVP | User cannot easily manage multiple leagues — acceptable for Phase 1 |
| Atomic write | Direct overwrite, write-then-rename | **Write to `.tmp` then `Files.move(ATOMIC_MOVE)`** | Prevents partial writes corrupting the only copy of league data | `ATOMIC_MOVE` is best-effort on some filesystems; risk is very low for a local file on macOS/Linux |
| Name matching | Case-sensitive, case-insensitive | **Case-insensitive** | User experience: `planr division delete majors` should work even if stored as "Majors"; stored value preserves original casing | Two divisions named "majors" and "MAJORS" cannot coexist — correct behavior |
| ID strategy | UUID, sequential int, name-as-key | **UUID, internal only** | Names can change (edit command); UUIDs provide a stable identity; never shown to user in Phase 1 | Slight overhead generating UUIDs; irrelevant at this scale |
| Immutable records | Mutable POJOs, records | **Java records** | Immutable by default; eliminates accidental mutation bugs in the store; fits Java 25 idioms | Jackson requires `@JsonCreator` or module config to deserialize into records — one-time setup cost |
| Load strategy | Load on every command, cache in-process | **Load on every command** | CLI is a single-shot process; there is no long-running process to cache state in; always reads fresh from disk | Negligible I/O cost for a small JSON file |

---

## Operational Concerns

**Error handling:**
- All validation errors print to `stderr` and exit `1`.
- All I/O errors (cannot read/write file, disk full) print to `stderr` and exit `2`.
- Stack traces are never shown to the user; they may be directed to a log file via stderr redirect if needed for debugging.

**Data integrity:**
- The atomic write (temp file + rename) is the only guard against corruption. There is no checksum or backup.
- If `league.json` is manually corrupted, `planr` will fail with an I/O error on next invocation. Recovery is manual (edit or delete the file).

**No rollback mechanism for Phase 1.** This is acceptable because:
- All operations are single-file writes.
- The file is human-readable JSON and can be manually corrected.
- A backup-before-write strategy can be added later if needed.

**Testing:** Unit tests cover `LeagueStore` (load/save round-trip), `DivisionCommand` (all validation paths), and `TeamCommand` (all validation paths) using a temp directory for the JSON file. No mocking of the file system — tests write real files.

---

## Out of Scope / Future Work

- **Field and Field Availability management** — next CLI slice, separate spec.
- **Schedule generation** — requires field management to be complete first.
- **Web application** — this CLI validates the data model and business rules before the web layer is built.
- **Multi-league support** — deferred; requires either multiple files or a named-league flag.
- **Multi-organizer / auth** — out of scope for Phase 1 per the product spec.
- **GraalVM native image** — `picocli-codegen` annotation processor is included now so reflection config is generated automatically, making a future native build straightforward.
- **`$PLANR_DATA_DIR` env var override** — easy to add; deferred until a user asks for it.
