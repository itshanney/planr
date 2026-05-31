# Tech Spec: `planr schedule view` Filter Parity and `--team-schedule` Removal

**Date:** 2026-05-31  
**Status:** Final (rev 2 — filter parity extended to TEAM_SCHEDULE path + stats added to DRAFT/FINALIZED path)  
**PRD:** `features/2026-05-31-schedule-view-filter-parity.md`

---

## Overview

Rev 1 of this spec addressed three issues: filter-count guard, `--team-schedule` removal, and empty-result stderr/exit-1. All three were implemented. A fourth issue was uncovered during testing: (1) the TEAM_SCHEDULE state path ignores all filter flags entirely — it always passes the raw, unfiltered game list to both `printTeamScheduleTable` and `printTeamScheduleStats`; (2) the DRAFT/FINALIZED path only renders the game table, leaving no downstream stat blocks for the filter to scope, whereas the user expects the same HOME/AWAY BALANCE + HEAD-TO-HEAD blocks that appear in the TEAM_SCHEDULE view.

This revision adds: (A) filter application to the TEAM_SCHEDULE path; (B) stats blocks (BALANCE + HEAD-TO-HEAD) after the game table in DRAFT/FINALIZED, scoped to the filtered game list; (C) new `printBalanceBlock` and `printHeadToHeadBlock` overloads that accept `List<ScheduledGame>` instead of `List<TeamGame>`. No model, persistence, or scheduler changes are required. The analogy is `CalendarCommand.applyFilter()` — one filter applied once, and every downstream output derives from that filtered set.

---

## Component Diagram

```
ScheduleCommand.ViewCmd (the only component changing)
  └── call()
        ├── state == NONE guard                 — unchanged (exits 1)
        ├── filter-count guard                  — unchanged (exits 1 if >1 set)
        │
        ├── [TEAM_SCHEDULE path — NOW FIXED]
        │     ├── [NEW] --field guard           — exits 1; no fields assigned yet in this state
        │     ├── entity validation             — same as DRAFT path (division/team lookup)
        │     ├── [NEW] filter loop             — filters List<TeamGame> the same way DRAFT path does
        │     ├── [NEW] empty-result guard      — exits 1 + stderr if filtered list is empty
        │     ├── printTeamScheduleTable        — receives filtered list (was raw)
        │     └── printTeamScheduleStats        — receives filtered list (was raw)
        │
        └── [DRAFT/FINALIZED path]
              ├── entity validation             — unchanged
              ├── filter loop                   — unchanged (correct)
              ├── empty-result guard            — unchanged (correct)
              ├── printFullScheduleTable        — unchanged (correct)
              └── [NEW] conditional stats block
                    ├── if filter == null or --division:
                    │     group filtered ScheduledGames by divisionName
                    │     for each group: printBalanceBlock(group, divisionName) [new overload]
                    │                     printHeadToHeadBlock(group, divisionName) [new overload]
                    └── if --team or --field: skip stats (game table only)

[NEW] printBalanceBlock(List<ScheduledGame>, String divisionName)
[NEW] printHeadToHeadBlock(List<ScheduledGame>, String divisionName)
  — same rendering logic as the TeamGame overloads; only the source type differs
```

---

## Data Model

No data model changes. `ScheduledGame`, `TeamSchedule`, `Schedule`, `League`, and `LeagueStore` are all untouched.

Both rendering overloads only need `homeTeamName()` and `awayTeamName()` — fields present on both `TeamGame` and `ScheduledGame` with identical names.

---

## API Contracts (CLI surface)

### `planr schedule view`

| State | Option | Before | After |
|---|---|---|---|
| TEAM_SCHEDULE | `--division <name>` | silently ignored — all divisions shown | filters matchup table and stats to that division |
| TEAM_SCHEDULE | `--team <name>` | silently ignored — all games shown | filters matchup table and stats to games involving that team |
| TEAM_SCHEDULE | `--field <name>` | silently ignored — no fields assigned yet | exit 1, stderr: `"Error: --field cannot be used when no field assignment exists."` |
| DRAFT/FINALIZED | no filter | game table only | game table + per-division BALANCE + HEAD-TO-HEAD |
| DRAFT/FINALIZED | `--division <name>` | game table only | game table + BALANCE + HEAD-TO-HEAD for that division |
| DRAFT/FINALIZED | `--team <name>` | game table only | game table only (no per-team stats block) |
| DRAFT/FINALIZED | `--field <name>` | game table only | game table only (no per-field stats block) |

**Exit codes — new cases only:**

| Scenario | Exit | Stream | Message |
|---|---|---|---|
| TEAM_SCHEDULE + `--field` supplied | 1 | stderr | `"Error: --field cannot be used when no field assignment exists."` |
| TEAM_SCHEDULE + valid filter + zero matching games | 1 | stderr | `"Error: No games match the specified filter."` |

All other exit codes and messages are unchanged from rev 1.

---

## Critical Path Walkthrough

### Path 1 — TEAM_SCHEDULE, `--division Majors`, multi-division league

1. `planr schedule view --division Majors` in TEAM_SCHEDULE state (2 divisions: Majors, AAA)
2. filter-count guard: 1 → passes
3. `--field` guard: not set → passes
4. entity validation: `league.findDivision("Majors")` → found → passes
5. **[NEW]** filter loop over `league.teamSchedule().games()`:
   - Majors games → retained; AAA games → excluded
   - `filteredTeamGames` = Majors-only list
6. empty-result guard: list non-empty → passes
7. `printTeamScheduleTable(filteredTeamGames)` — shows only Majors matchups
8. `printTeamScheduleStats(filteredTeamGames)` — BALANCE + HEAD-TO-HEAD for Majors only
9. exit 0

### Path 2 — TEAM_SCHEDULE, `--field "Eastside"` supplied

1. filter-count guard: 1 → passes
2. **[NEW]** `--field` guard fires: `System.err.println("Error: --field cannot be used when no field assignment exists.")`, `return 1`
3. No output, exit 1

### Path 3 — DRAFT, no filter (happy path, enhanced)

1. `planr schedule view` in DRAFT state
2. filter-count guard: 0 → passes
3. entity validation: skipped (no filter)
4. filter loop: no filter applied → `filtered` = all scheduled games
5. `printFullScheduleTable(filtered, labels)` — full schedule table
6. **[NEW]** stats block: filter is null → group `filtered` by `divisionName`; for each group call `printBalanceBlock(group, divisionName)` and `printHeadToHeadBlock(group, divisionName)` (new `ScheduledGame` overloads)
7. exit 0

### Path 4 — DRAFT, `--division Majors`, multi-division league

1. `planr schedule view --division Majors`
2. filter-count guard: 1 → passes
3. entity validation: division found → passes
4. filter loop: only Majors games retained → `filtered` = Majors-only
5. `printFullScheduleTable(filtered, labels)` — Majors games only
6. **[NEW]** stats block: divisionFilter set → group `filtered` by `divisionName` (only "Majors" will appear); print BALANCE + HEAD-TO-HEAD for Majors
7. exit 0

### Path 5 — DRAFT, `--team "Blue Jays"` (no stats)

1. `planr schedule view --team "Blue Jays"`
2. filter-count guard: 1 → passes
3. entity validation: team found → passes
4. filter loop: only Blue Jays games retained → `filtered` = Blue Jays games
5. `printFullScheduleTable(filtered, labels)` — Blue Jays games only
6. **[NEW]** stats block: teamFilter set → **skip** (stats tables are not meaningful for a single-team filter)
7. exit 0

### Path 6 — DRAFT, `--field "Riverside Park"` (no stats)

1. filter-count guard: 1 → passes
2. entity validation: field found → passes
3. filter loop: only Riverside Park games → `filtered`
4. `printFullScheduleTable(filtered, labels)`
5. **[NEW]** stats block: fieldFilter set → **skip**
6. exit 0

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risks Accepted |
|---|---|---|---|---|
| Stats in DRAFT/FINALIZED for `--team` / `--field` | Show team stats / field stats vs. skip | Skip — game table only | A per-team balance block for one team is trivially "1 game home, 1 game away" — no useful signal. A per-field stats block has no clear content. Consistent with CalendarCommand, which just filters events and shows the resulting calendar with no additional stat section. | None — the game table is already filtered, which is the primary ask |
| New overloads vs. shared adapter for `ScheduledGame` stats | Add `printBalanceBlock(List<ScheduledGame>)` overload vs. project to a shared `GameRow` record | Overloads | `GameRow` abstraction adds a layer for two methods that share no other callers. Overloads are boring and direct. | Slight code duplication across the two rendering methods — acceptable for two short static methods |
| Stats block placement | Before game table vs. after | After | Consistent with TEAM_SCHEDULE view (matchup table → stats). Users see games first, stats second. | None |
| `--field` in TEAM_SCHEDULE state | Error vs. empty result | Hard error | TEAM_SCHEDULE has no field assignments, so `--field` can never produce results. A clear error prevents user confusion ("Why are no games shown?"). Matches `CalendarCommand` principle of failing fast on nonsensical input. | None |
| Filter-count guard placement | After entity validation vs. before | Before entity validation | Matches `CalendarCommand` and original rev 1 spec. Avoids confusing entity-not-found errors when the real problem is passing two filters. | None |
| Empty-result message on stderr vs. stdout | Keep on stdout (exit 1 only) | Move to stderr (exit 1) | All other error messages go to stderr. | Already implemented in rev 1 |

---

## Operational Concerns

No deployment concerns — single-user CLI, no services, no migrations.

---

## Implementation Checklist

### `ScheduleCommand.java` — `ViewCmd` only

**Step A — Add filter application to the TEAM_SCHEDULE path**

Replace the current unconditional TEAM_SCHEDULE block (which passes `league.teamSchedule().games()` raw) with:

```
// A1: --field guard (no field assignment in TEAM_SCHEDULE state)
if (fieldFilter != null) {
    System.err.println("Error: --field cannot be used when no field assignment exists.");
    return 1;
}

// A2: entity validation (same pattern as DRAFT path)
if (divisionFilter != null && league.findDivision(divisionFilter).isEmpty()) {
    System.err.printf("Error: Division \"%s\" not found.%n", divisionFilter);
    return 1;
}
if (teamFilter != null) {
    boolean found = league.divisions().stream()
        .flatMap(d -> d.teams().stream())
        .anyMatch(t -> t.name().equalsIgnoreCase(teamFilter));
    if (!found) {
        System.err.printf("Error: Team \"%s\" not found.%n", teamFilter);
        return 1;
    }
}

// A3: filter loop
List<TeamGame> allTeamGames = league.teamSchedule().games();
List<TeamGame> filteredTeamGames = new ArrayList<>();
for (TeamGame g : allTeamGames) {
    if (divisionFilter != null && !g.divisionName().equalsIgnoreCase(divisionFilter)) continue;
    if (teamFilter != null
        && !g.homeTeamName().equalsIgnoreCase(teamFilter)
        && !g.awayTeamName().equalsIgnoreCase(teamFilter)) continue;
    filteredTeamGames.add(g);
}

// A4: empty-result guard
if (filteredTeamGames.isEmpty()) {
    System.err.println("Error: No games match the specified filter.");
    return 1;
}

// A5: render with filtered list
System.out.println("Schedule status: TEAM_SCHEDULE");
System.out.println();
printTeamScheduleTable(filteredTeamGames);
System.out.println();
printTeamScheduleStats(filteredTeamGames);
return 0;
```

**Step B — Add conditional stats block after the game table in DRAFT/FINALIZED path**

After the existing `printFullScheduleTable(filtered, labels)` call, insert:

```
// Show stats blocks when filter is null (group by division) or --division (one group)
if (fieldFilter == null && teamFilter == null) {
    System.out.println();
    // Group filtered ScheduledGames by division in encounter order
    LinkedHashMap<String, List<ScheduledGame>> byDivision = new LinkedHashMap<>();
    for (ScheduledGame g : filtered) {
        byDivision.computeIfAbsent(g.divisionName(), k -> new ArrayList<>()).add(g);
    }
    for (Map.Entry<String, List<ScheduledGame>> entry : byDivision.entrySet()) {
        printBalanceBlock(entry.getValue(), entry.getKey());
        System.out.println();
        printHeadToHeadBlock(entry.getValue(), entry.getKey());
        System.out.println();
    }
}
```

**Step C — Add `ScheduledGame` overloads of the two rendering methods**

Add static overloads alongside the existing `TeamGame` overloads:

```
static void printBalanceBlock(List<ScheduledGame> games, String divisionName) {
    // same logic as printBalanceBlock(List<TeamGame>, ...) but calling
    // g.homeTeamName() / g.awayTeamName() on ScheduledGame
}

static void printHeadToHeadBlock(List<ScheduledGame> games, String divisionName) {
    // same logic as printHeadToHeadBlock(List<TeamGame>, ...) but calling
    // g.homeTeamName() / g.awayTeamName() on ScheduledGame
}
```

Both `ScheduledGame` and `TeamGame` expose `homeTeamName()` and `awayTeamName()` with identical signatures, so the method body is copy-identical except for the type token.

---

## Test Changes — `ScheduleCommandTest`

### Remove (0 tests)

None removed in this revision (rev 1 removals already done).

### Update (1 test)

- **`ViewStats.fullViewInDraftStateDoesNotShowStats`** — this test asserts `assertFalse(out.contains("HOME/AWAY BALANCE"))` and `assertFalse(out.contains("HEAD-TO-HEAD"))`. These assertions are now wrong. Invert both to `assertTrue`, or replace with a test that verifies `HOME/AWAY BALANCE — Majors` appears in DRAFT view. Rename to `fullViewInDraftStateShowsStats`.

### Add (7 tests)

**TEAM_SCHEDULE path filter:**

1. **`--division` filter scopes matchup table (TEAM_SCHEDULE)**  
   Setup: 2 divisions (Majors, AAA), run `generateTeamSchedule()`.  
   Execute: `"schedule", "view", "--division", "Majors"`.  
   Assert: exit 0; `stdout()` contains "Blue Jays", "Cardinals", "TEAM_SCHEDULE"; does NOT contain "Red Sox" or "Yankees".

2. **`--team` filter scopes matchup table (TEAM_SCHEDULE)**  
   Setup: minimal league (Blue Jays, Cardinals), run `generateTeamSchedule()`.  
   Execute: `"schedule", "view", "--team", "Blue Jays"`.  
   Assert: exit 0; `stdout()` contains "Blue Jays".

3. **`--field` filter rejected in TEAM_SCHEDULE state**  
   Setup: minimal league, run `generateTeamSchedule()`.  
   Execute: `"schedule", "view", "--field", "Riverside Park"`.  
   Assert: exit 1; `stderr()` contains `"--field cannot be used when no field assignment exists"`.

4. **`--division` filter zero-match in TEAM_SCHEDULE state**  
   Setup: minimal league (Majors only), run `generateTeamSchedule()`.  
   Execute: `"schedule", "view", "--division", "AAA"` where AAA does not exist.  
   Assert: exit 1; `stderr()` contains `"not found"`. *(Entity validation fires before empty-result guard.)*

**DRAFT/FINALIZED stats:**

5. **Stats blocks appear in DRAFT view without filter**  
   Setup: `addMinimalLeague()`, `generateDraft()`.  
   Execute: `"schedule", "view"`.  
   Assert: `stdout()` contains `"HOME/AWAY BALANCE"` and `"HEAD-TO-HEAD"`.

6. **Stats blocks appear and are scoped when `--division` filter supplied in DRAFT view**  
   Setup: 2-division league (Majors + AAA), `generateDraft()`.  
   Execute: `"schedule", "view", "--division", "Majors"`.  
   Assert: exit 0; `stdout()` contains `"HOME/AWAY BALANCE — Majors"`; does NOT contain `"HOME/AWAY BALANCE — AAA"`.

7. **Stats blocks absent when `--team` filter supplied in DRAFT view**  
   Setup: `addMinimalLeague()`, `generateDraft()`.  
   Execute: `"schedule", "view", "--team", "Blue Jays"`.  
   Assert: exit 0; `stdout()` does NOT contain `"HOME/AWAY BALANCE"`; does NOT contain `"HEAD-TO-HEAD"`.

---

## Out of Scope / Future Work

- `planr schedule export --team-schedule` — unchanged. Removing it from export is a separate decision.
- Filter alignment for `planr schedule status` or `planr schedule export` — not covered here.
- The `planr schedule stats` view has no filter flags; no change needed.
- Factoring the two rendering method pairs into a shared generic or interface — the overload duplication is small and the refactor adds no user-visible value.
