# League Planner — Core Scheduling Feature

**Date:** 2026-05-15
**Status:** Ready for Architecture

---

## Problem Statement

Little league baseball organizers currently have no dedicated tool for managing the complex scheduling constraints of a multi-division season. Manually balancing teams across shared fields with varying availability leads to conflicts, inequitable field time distribution, and significant administrative burden. League Planner will eliminate this by providing a structured workflow to configure league entities and generate a conflict-free, balanced schedule.

---

## Proposed Solution

A web application that allows a league organizer to define the core entities of their league:
* Divisions (with per-division game durations)
* Teams within each Division
* Fields and Field Availability (with optional division-specific time blocks)

The organizer then generates an optimized round-robin game schedule for the season. The scheduler enforces field availability constraints, applies a fixed 15-minute buffer between games, and distributes home/away assignments equitably via round-robin rotation. The schedule exists in one of two states — **Draft** or **Finalized**. In draft mode the organizer may regenerate as many times as needed. Once finalized, the schedule is locked and only individual game overrides are permitted. The organizer can view and export the schedule as JSON.

This spec covers **Phase 1**: entity management, schedule generation, draft/finalize lifecycle, and JSON export. Multi-organizer support, team/parent-facing views, and post-finalization bulk edits are out of scope.

---

## User Stories

1. **As a league organizer**, I want to define divisions with a game duration and assign teams to them, so that the scheduler knows how long each game runs and can pack fields without conflicts.

2. **As a league organizer**, I want to register baseball fields and configure availability windows — optionally restricted to a specific division — so that the scheduler assigns games only when and where fields are actually usable.

3. **As a league organizer**, I want to generate a round-robin schedule where each team plays every other team in its division exactly twice (once home, once away), so that the season is balanced and fair.

4. **As a league organizer**, I want to finalize a draft schedule after confirming, and then override individual games one at a time with full authority, so that the league schedule is stable while still allowing exception handling for circumstances beyond my control.

5. **As a league organizer**, I want to export the schedule as a JSON file, so that I can share or import it into other systems.

---

## Acceptance Criteria

### Division & Team Management

- [ ] An organizer can create a division with a unique name (e.g., "T-Ball", "Coast", "AAA", "Majors") and a game duration in minutes.
- [ ] An organizer can edit a division's name or game duration.
- [ ] An organizer can delete a division only if it has no teams assigned to it.
- [ ] An organizer can add one or more teams to a division; each team name must be unique within its division.
- [ ] An organizer can edit or delete a team without affecting other teams in the division.
- [ ] The system rejects creation of a division or team with an empty name.
- [ ] The system rejects a game duration that is not a positive integer number of minutes.

### Field Management

- [ ] An organizer can create a field with a name and an optional address/location description.
- [ ] An organizer can add availability windows to a field; each window specifies: day of the week, start time, end time, and an optional division restriction.
- [ ] When a division restriction is set on an availability window, the scheduler will only place games for that division within that window.
- [ ] When no division restriction is set on an availability window, the scheduler may place games from any division within that window.
- [ ] An organizer can add multiple availability windows per field.
- [ ] An organizer can edit or delete an availability window without deleting the field.
- [ ] An organizer can delete a field; deleting a field removes all of its associated availability windows.
- [ ] The system rejects an availability window where end time ≤ start time.

### Schedule Generation

- [ ] An organizer can initiate schedule generation only after at least one division with two or more teams and at least one field with at least one availability window have been configured.
- [ ] The system requires the organizer to specify a season start date and end date before generating.
- [ ] The scheduler produces a full round-robin schedule: within each division, every pair of teams plays exactly twice — once with Team A as home and once with Team A as away.
- [ ] Home/away assignments across a team's games are distributed by round-robin rotation such that no team is home for all of its first-half games.
- [ ] The generated schedule contains no two games assigned to the same field at an overlapping time, where overlap includes the 15-minute buffer following each game.
- [ ] Every game in the generated schedule falls within a configured availability window on its assigned field, and respects any division restriction on that window.
- [ ] A game's scheduled time slot is exactly equal to the game duration of its division; the game slot does not consume the subsequent 15-minute buffer.
- [ ] No team plays more than one game on the same calendar day.
- [ ] All scheduled games fall within the configured season date range.
- [ ] The system displays a clear, specific error if it cannot produce a valid complete schedule given the constraints (e.g., "Insufficient field availability for AAA division — 12 games cannot be scheduled within the season window"), rather than generating a partial schedule silently.
- [ ] Schedule generation completes within 30 seconds for a league with up to 10 divisions, 100 teams total, and 10 fields.
- [ ] A newly generated schedule is created in **Draft** status.

### Schedule Lifecycle

- [ ] The league has exactly one schedule at any given time; it is in either **Draft** or **Finalized** state.
- [ ] While in Draft state, the organizer can regenerate the schedule as many times as desired; each regeneration discards the previous draft and replaces it with a new one.
- [ ] The organizer can promote a Draft schedule to **Finalized** state via an explicit finalize action.
- [ ] Before finalizing, the system presents a confirmation warning informing the organizer that finalization is irreversible and the schedule will be locked.
- [ ] Finalization is one-way; a finalized schedule cannot be reverted to Draft or regenerated.
- [ ] Once finalized, the organizer can override an individual game by editing any combination of: date, start time, field assignment, home team, and away team.
- [ ] Individual game overrides on a finalized schedule are saved without re-validating the full schedule against constraints.
- [ ] The system displays a non-blocking warning to the organizer if an individual game override creates a field conflict (same field, overlapping time including buffer) with another game on the same date.

### Schedule Viewing

- [ ] The organizer can view the schedule in a list view, filterable by division, team, and field.
- [ ] Each schedule entry displays: date, start time, field name, home team, away team, and division name.
- [ ] The schedule view indicates whether the schedule is in Draft or Finalized state.

### Schedule Export

- [ ] The organizer can export the schedule (in either state) as a JSON file.
- [ ] The exported JSON is an array of game objects; each object contains: `date` (ISO 8601 date), `start_time` (ISO 8601 time), `field_name`, `home_team`, `away_team`, `division_name`, `status` ("draft" or "finalized").

---

## Out of Scope

- Multi-organizer accounts and access control (single organizer for MVP).
- Team manager or parent-facing views.
- Push notifications or email distribution of schedules.
- Playoff or tournament bracket scheduling.
- Rainout/reschedule workflows.
- Bulk editing or reverting a finalized schedule.
- Configurable buffer time (fixed at 15 minutes league-wide).
- Team home-field preferences or constraints.
- Travel time or geographic constraints between fields.
- Player-level roster management.
- Mobile native applications.
- CSV, PDF, or iCal export formats.

---

## Open Questions

None — all questions resolved.

---

## Dependencies

- **Scheduling algorithm design** — The algorithm must handle round-robin pair generation, home/away rotation, per-division game durations, the fixed 15-minute buffer, and division-restricted availability windows simultaneously. The approach (constraint satisfaction, greedy rotation, etc.) must be chosen before implementation begins, as it directly impacts feasibility and the 30-second performance acceptance criterion.
- **No external API dependencies identified for Phase 1.**
