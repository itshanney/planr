package org.leagueplan.planr.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.leagueplan.planr.model.BracketSide;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.Playoff;
import org.leagueplan.planr.model.PlayoffGame;
import org.leagueplan.planr.model.PlayoffState;
import org.leagueplan.planr.store.LeagueStore;

/**
 * End-to-end integration tests for {@code planr calendar}.
 *
 * <p>Minimal league setup: 1 division "Majors" with 2 teams, 1 field, season June 2026. The CP-SAT
 * solver assigns specific dates non-deterministically, so tests assert on structure and filtering
 * behavior rather than exact dates.
 */
class CalendarCommandTest extends CommandTestBase {

  // ---------------------------------------------------------------------------
  // Setup helpers
  // ---------------------------------------------------------------------------

  private void addMinimalLeague() {
    execute("division", "add", "Majors", "--duration", "60", "--target", "2");
    execute("team", "add", "Majors", "Blue Jays");
    execute("team", "add", "Majors", "Cardinals");
    execute("field", "add", "Riverside Park");
    execute(
        "config",
        "set",
        "--sunrise", "07:00",
        "--sunset", "20:00",
        "--start", "2026-06-01",
        "--end", "2026-06-30");
  }

  private int provideStdinAndExecute(String stdinContent, String... args) {
    InputStream original = System.in;
    System.setIn(new ByteArrayInputStream(stdinContent.getBytes()));
    try {
      return execute(args);
    } finally {
      System.setIn(original);
    }
  }

  /** Runs Phase 1 and Phase 2 schedule assignment. Returns exit code. */
  private int assignSchedule() {
    int e1 = provideStdinAndExecute("yes\n", "schedule", "generate");
    if (e1 != 0) return e1;
    return provideStdinAndExecute("yes\n", "schedule", "assign");
  }

  /**
   * Injects a playoff game with an assigned date directly into the store, bypassing the CP-SAT
   * solver. The game is added to the "Majors" division with bracket references as participants.
   */
  private void injectAssignedPlayoffGame(LocalDate date, LocalTime time) throws IOException {
    LeagueStore store = new LeagueStore();
    League league = store.load();
    Division div = league.findDivision("Majors").orElseThrow();
    UUID fieldId = league.fields().isEmpty() ? UUID.randomUUID() : league.fields().get(0).id();

    PlayoffGame pg =
        new PlayoffGame(
            UUID.randomUUID(),
            "W-R1",
            BracketSide.WINNERS,
            "W of G1",
            "W of G2",
            date,
            time,
            fieldId,
            false,
            false);
    Playoff playoff =
        new Playoff(div.id(), date, date, PlayoffState.ASSIGNED, List.of(pg));
    store.save(league.withPlayoffAdded(playoff));
  }

  // ---------------------------------------------------------------------------
  // Argument validation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("argument validation")
  class ArgumentValidation {

    @Test
    @DisplayName("exits 1 when --weekly and --monthly are both supplied")
    void weeklyAndMonthlyAreMutuallyExclusive() {
      int exit = execute("calendar", "--weekly", "--monthly");
      assertEquals(1, exit);
      assertTrue(stderr().contains("mutually exclusive"));
    }

    @Test
    @DisplayName("exits 1 when --division and --team are both supplied")
    void divisionAndTeamAreMutuallyExclusive() {
      int exit = execute("calendar", "--division", "Majors", "--team", "Blue Jays");
      assertEquals(1, exit);
      assertTrue(stderr().contains("At most one"));
    }

    @Test
    @DisplayName("exits 1 when --division and --field are both supplied")
    void divisionAndFieldAreMutuallyExclusive() {
      int exit = execute("calendar", "--division", "Majors", "--field", "Riverside Park");
      assertEquals(1, exit);
      assertTrue(stderr().contains("At most one"));
    }

    @Test
    @DisplayName("exits 1 when --team and --field are both supplied")
    void teamAndFieldAreMutuallyExclusive() {
      int exit = execute("calendar", "--team", "Blue Jays", "--field", "Riverside Park");
      assertEquals(1, exit);
      assertTrue(stderr().contains("At most one"));
    }

    @Test
    @DisplayName("exits 1 when --week is supplied with --monthly")
    void weekFlagRequiresWeeklyMode() {
      int exit = execute("calendar", "--monthly", "--week", "2026-06-01");
      assertEquals(1, exit);
      assertTrue(stderr().contains("--week cannot be used with --monthly"));
    }

    @Test
    @DisplayName("exits 1 when --month is supplied without --monthly")
    void monthFlagRequiresMonthlyMode() {
      int exit = execute("calendar", "--month", "2026-06");
      assertEquals(1, exit);
      assertTrue(stderr().contains("--month can only be used with --monthly"));
    }

    @Test
    @DisplayName("exits 1 when --month is supplied with --weekly explicitly")
    void monthFlagWithWeeklyModeExits1() {
      int exit = execute("calendar", "--weekly", "--month", "2026-06");
      assertEquals(1, exit);
      assertTrue(stderr().contains("--month can only be used with --monthly"));
    }

    @Test
    @DisplayName("exits 1 when --month value has invalid format")
    void invalidMonthFormatExits1() {
      int exit = execute("calendar", "--monthly", "--month", "06-2026");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Invalid month"));
    }

    @Test
    @DisplayName("exits 1 when --month value has an impossible month number")
    void invalidMonthValueExits1() {
      int exit = execute("calendar", "--monthly", "--month", "2026-13");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Invalid month"));
    }

    @Test
    @DisplayName("exits 1 when --month value is completely non-numeric")
    void nonNumericMonthExits1() {
      int exit = execute("calendar", "--monthly", "--month", "june-2026");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Invalid month"));
    }
  }

  // ---------------------------------------------------------------------------
  // Entity validation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("entity validation")
  class EntityValidation {

    @Test
    @DisplayName("exits 1 when --division names a division that does not exist")
    void unknownDivisionExits1() {
      addMinimalLeague();
      int exit = execute("calendar", "--division", "NoSuchDivision");
      assertEquals(1, exit);
      assertTrue(stderr().contains("NoSuchDivision"));
    }

    @Test
    @DisplayName("exits 1 when --team names a team that does not exist")
    void unknownTeamExits1() {
      addMinimalLeague();
      int exit = execute("calendar", "--team", "Nonexistent Team");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Nonexistent Team"));
    }

    @Test
    @DisplayName("exits 1 when --field names a field that does not exist")
    void unknownFieldExits1() {
      addMinimalLeague();
      int exit = execute("calendar", "--field", "Nonexistent Field");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Nonexistent Field"));
    }
  }

  // ---------------------------------------------------------------------------
  // No assigned events
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("no assigned events")
  class NoAssignedEvents {

    @Test
    @DisplayName("exits 1 with a clear message when no schedule has been assigned")
    void freshLeagueWithNoScheduleExits1() {
      addMinimalLeague();
      int exit = execute("calendar");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No assigned events found"));
    }

    @Test
    @DisplayName("exits 1 even when a team schedule (Phase 1 only) exists")
    void teamScheduleAloneIsNotSufficientForEvents() {
      addMinimalLeague();
      execute("schedule", "generate");
      int exit = execute("calendar");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No assigned events found"));
    }

    @Test
    @DisplayName("exits 2 when league data is corrupted")
    void corruptedLeagueDataExits2() throws IOException {
      corruptLeagueFile();
      int exit = execute("calendar");
      assertEquals(2, exit);
      assertTrue(stderr().contains("Failed to access league data"));
    }
  }

  // ---------------------------------------------------------------------------
  // Weekly view
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("weekly view")
  class WeeklyView {

    @Test
    @DisplayName("exits 0 and emits a week header after schedule is assigned")
    void showsWeekHeaderAfterAssign() {
      addMinimalLeague();
      assertEquals(0, assignSchedule());

      int exit = execute("calendar");
      assertEquals(0, exit);
      assertTrue(stdout().contains("Week of"));
    }

    @Test
    @DisplayName("shows at least one [G] event line after schedule is assigned")
    void showsGameEventsAfterAssign() {
      addMinimalLeague();
      assignSchedule();

      execute("calendar");
      assertTrue(stdout().contains("[G]"));
    }

    @Test
    @DisplayName("game event lines contain home vs away, field name, and division")
    void gameEventLineContainsExpectedComponents() {
      addMinimalLeague();
      assignSchedule();

      execute("calendar");
      String out = stdout();
      // One of the two possible matchups must appear
      assertTrue(
          out.contains("Blue Jays vs Cardinals") || out.contains("Cardinals vs Blue Jays"));
      assertTrue(out.contains("Riverside Park"));
      assertTrue(out.contains("(Majors)"));
    }

    @Test
    @DisplayName("--week flag limits output to the specified week")
    void weekFlagSelectsCorrectWeek() {
      addMinimalLeague();
      assignSchedule();

      // A week in July 2026 is outside the season; no events should appear
      int exit = execute("calendar", "--week", "2026-07-15");
      assertEquals(0, exit);
      // All 7 days should show (no events)
      long noEventsCount = stdout().lines()
          .filter(l -> l.contains("(no events)"))
          .count();
      assertEquals(7, noEventsCount);
    }

    @Test
    @DisplayName("defaults to earliest event week when --week is omitted")
    void defaultsToEarliestEventWeek() {
      addMinimalLeague();
      assignSchedule();

      // Without --week the default week contains at least one game
      execute("calendar");
      assertTrue(stdout().contains("[G]"));
    }

    @Test
    @DisplayName("summary line counts are non-zero after schedule assign")
    void summaryLineReflectsEventCount() {
      addMinimalLeague();
      assignSchedule();

      execute("calendar");
      // Summary contains at least one game (G: >0)
      String summary = stdout().lines()
          .filter(l -> l.contains("events this week"))
          .findFirst()
          .orElse("");
      assertFalse(summary.isEmpty());
      assertFalse(summary.contains("(G: 0  PO: 0  P: 0)"), "Expected at least one game");
    }
  }

  // ---------------------------------------------------------------------------
  // Monthly view
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("monthly view")
  class MonthlyView {

    @Test
    @DisplayName("exits 0 and contains month title and legend after schedule assign")
    void showsMonthTitleAndLegend() {
      addMinimalLeague();
      assignSchedule();

      int exit = execute("calendar", "--monthly", "--month", "2026-06");
      assertEquals(0, exit);
      assertTrue(stdout().contains("June 2026"));
      assertTrue(stdout().contains("Legend: G = Game   PO = Playoff   P = Practice"));
    }

    @Test
    @DisplayName("--month flag selects the correct month; out-of-season month has no events")
    void monthFlagSelectsCorrectMonth() {
      addMinimalLeague();
      assignSchedule();

      // July is outside the June season; no events should appear in the listing
      int exit = execute("calendar", "--monthly", "--month", "2026-07");
      assertEquals(0, exit);
      assertTrue(stdout().contains("July 2026"));
      assertTrue(stdout().contains("0 events in July 2026"));
    }

    @Test
    @DisplayName("summary line counts games correctly for the selected month")
    void summaryLineCountsGames() {
      addMinimalLeague();
      assignSchedule();

      execute("calendar", "--monthly", "--month", "2026-06");
      String summary = stdout().lines()
          .filter(l -> l.contains("events in June 2026"))
          .findFirst()
          .orElse("");
      assertFalse(summary.isEmpty());
      // With 2 teams targeting 2 games each, we expect at least 1 game in the month
      assertFalse(summary.startsWith("0 events"), "Expected at least one game in June 2026");
    }

    @Test
    @DisplayName("monthly listing shows game events in event listing section")
    void monthlyListingShowsGames() {
      addMinimalLeague();
      assignSchedule();

      execute("calendar", "--monthly", "--month", "2026-06");
      assertTrue(stdout().contains("[G]"));
    }
  }

  // ---------------------------------------------------------------------------
  // Filter: --division
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("filter: --division")
  class FilterByDivision {

    @Test
    @DisplayName("shows only events for the named division")
    void showsOnlyNamedDivisionEvents() {
      // Add a second division with its own team and a different field
      addMinimalLeague();
      execute("division", "add", "Minors", "--duration", "60", "--target", "2");
      execute("team", "add", "Minors", "Red Sox");
      execute("team", "add", "Minors", "Yankees");
      execute("field", "add", "South Field");
      assignSchedule();

      execute("calendar", "--division", "Majors");
      String out = stdout();
      // Only Majors teams appear in the event lines
      assertFalse(out.contains("(Minors)"), "Minors events should be filtered out");
      assertTrue(out.contains("(Majors)") || out.contains("(no events)"));
    }

    @Test
    @DisplayName("division filter is case-insensitive")
    void divisionFilterIsCaseInsensitive() {
      addMinimalLeague();
      assignSchedule();

      int exitLower = execute("calendar", "--division", "majors");
      int exitUpper = execute("calendar", "--division", "MAJORS");
      assertEquals(0, exitLower);
      assertEquals(0, exitUpper);
    }

    @Test
    @DisplayName("exits 1 when filtered division has no assigned events in the dataset")
    void divisionWithNoEventsExits1() {
      addMinimalLeague();
      // Add Minors with NO teams — the solver cannot generate games for it
      execute("division", "add", "Minors", "--duration", "60", "--target", "2");
      assignSchedule();

      // Minors has no games; filtering by it should exit 1
      int exit = execute("calendar", "--division", "Minors");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No assigned events found"));
    }
  }

  // ---------------------------------------------------------------------------
  // Filter: --team
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("filter: --team")
  class FilterByTeam {

    @Test
    @DisplayName("shows only events featuring the named team")
    void showsOnlyNamedTeamEvents() {
      addMinimalLeague();
      assignSchedule();

      execute("calendar", "--team", "Blue Jays");
      String out = stdout();
      // Every [G] or [P] event line must mention Blue Jays
      boolean allLinesContainTeam =
          out.lines()
              .filter(l -> l.contains("[G]") || l.contains("[P]") || l.contains("[PO]"))
              .allMatch(l -> l.toLowerCase().contains("blue jays"));
      assertTrue(allLinesContainTeam);
    }

    @Test
    @DisplayName("team filter is case-insensitive")
    void teamFilterIsCaseInsensitive() {
      addMinimalLeague();
      assignSchedule();

      int exitLower = execute("calendar", "--team", "blue jays");
      int exitMixed = execute("calendar", "--team", "Blue Jays");
      assertEquals(0, exitLower);
      assertEquals(0, exitMixed);
    }

    @Test
    @DisplayName("playoff events are excluded from team-filtered results")
    void teamFilterExcludesPlayoffEvents() throws IOException {
      addMinimalLeague();
      assignSchedule();
      // Inject a playoff game into the Majors division
      injectAssignedPlayoffGame(LocalDate.of(2026, 6, 15), LocalTime.of(10, 0));

      // With team filter: no playoff events
      execute("calendar", "--team", "Blue Jays");
      String filteredOut = stdout();
      assertFalse(filteredOut.contains("[PO]"),
          "Playoff events should be excluded from team-filtered results");
      assertTrue(filteredOut.contains("[G]") || filteredOut.contains("(no events)"),
          "Game or practice events for Blue Jays should appear");
    }
  }

  // ---------------------------------------------------------------------------
  // Filter: --field
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("filter: --field")
  class FilterByField {

    @Test
    @DisplayName("shows only events at the named field")
    void showsOnlyNamedFieldEvents() {
      addMinimalLeague();
      assignSchedule();

      execute("calendar", "--field", "Riverside Park");
      String out = stdout();
      // Every event line must reference Riverside Park
      boolean allLinesOnCorrectField =
          out.lines()
              .filter(l -> l.contains("[G]") || l.contains("[P]") || l.contains("[PO]"))
              .allMatch(l -> l.contains("Riverside Park"));
      assertTrue(allLinesOnCorrectField);
    }

    @Test
    @DisplayName("field filter is case-insensitive")
    void fieldFilterIsCaseInsensitive() {
      addMinimalLeague();
      assignSchedule();

      int exitLower = execute("calendar", "--field", "riverside park");
      int exitUpper = execute("calendar", "--field", "RIVERSIDE PARK");
      assertEquals(0, exitLower);
      assertEquals(0, exitUpper);
    }

    @Test
    @DisplayName("exits 1 when no events are assigned to the named field")
    void fieldWithNoEventsExits1() throws IOException {
      addMinimalLeague();
      assignSchedule();
      // Inject a new field into the store AFTER assignment so the solver never assigned to it
      LeagueStore store = new LeagueStore();
      League league = store.load();
      Field ghost = new Field(UUID.randomUUID(), "Ghost Field", "", List.of(), List.of(), List.of(), null);
      store.save(league.withFieldAdded(ghost));

      int exit = execute("calendar", "--field", "Ghost Field");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No assigned events found"));
    }
  }

  // ---------------------------------------------------------------------------
  // toWeekStart static helper
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("CalendarCommand.toWeekStart")
  class ToWeekStartHelper {

    @Test
    @DisplayName("Sunday maps to itself")
    void sundayReturnsSameDate() {
      LocalDate sunday = LocalDate.of(2026, 5, 3);
      assertEquals(sunday, CalendarCommand.toWeekStart(sunday));
    }

    @Test
    @DisplayName("Saturday maps to the preceding Sunday")
    void saturdayMapsToPrecedingSunday() {
      LocalDate saturday = LocalDate.of(2026, 5, 9);
      assertEquals(LocalDate.of(2026, 5, 3), CalendarCommand.toWeekStart(saturday));
    }

    @Test
    @DisplayName("all 7 days of a week map to the same Sunday")
    void allSevenDaysMapToSameSunday() {
      LocalDate expectedSunday = LocalDate.of(2026, 5, 3);
      for (int d = 0; d < 7; d++) {
        LocalDate day = expectedSunday.plusDays(d);
        assertEquals(
            expectedSunday,
            CalendarCommand.toWeekStart(day),
            "Day " + day + " should map to week start " + expectedSunday);
      }
    }

    @Test
    @DisplayName("toWeekStart and CalendarRenderer.toSundayOnOrBefore agree on the same input")
    void weekStartMatchesSundayOnOrBefore() {
      // Both methods implement the same Sunday-first logic; they must agree on every day of the week
      LocalDate base = LocalDate.of(2026, 5, 3); // Sunday
      for (int d = 0; d < 7; d++) {
        LocalDate day = base.plusDays(d);
        assertEquals(
            CalendarRenderer.toSundayOnOrBefore(day),
            CalendarCommand.toWeekStart(day),
            "Methods disagree on " + day);
      }
    }
  }
}
