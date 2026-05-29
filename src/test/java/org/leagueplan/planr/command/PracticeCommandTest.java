package org.leagueplan.planr.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.PracticeSchedule;
import org.leagueplan.planr.model.PracticeSlot;
import org.leagueplan.planr.model.PracticeState;
import org.leagueplan.planr.model.Team;
import org.leagueplan.planr.store.LeagueStore;

/**
 * End-to-end command tests for `planr practice`.
 *
 * <p>Excludes `practice assign` (requires the CP-SAT solver + stdin interaction). All tests use
 * 2-team divisions with small practice counts (1-2) to stay fast.
 *
 * <p>Setup pattern: - Add division with --practice-count, --practice-duration-minutes,
 * --practice-start, --practice-end - Add teams - Add field + config for assign tests only
 */
class PracticeCommandTest extends CommandTestBase {

  private static final String SEASON_START = "2026-06-01";
  private static final String PRAC_START = "2026-04-01";
  private static final String PRAC_END = "2026-05-15";

  // ---------------------------------------------------------------------------
  // Setup helpers
  // ---------------------------------------------------------------------------

  private void addConfiguredDivision(String name, int practiceCount) {
    execute("division", "add", name, "--duration", "60", "--target", "4");
    execute(
        "division",
        "edit",
        name,
        "--practice-count",
        String.valueOf(practiceCount),
        "--practice-duration-minutes",
        "60",
        "--practice-start",
        PRAC_START,
        "--practice-end",
        PRAC_END);
    execute("config", "set", "--start", SEASON_START, "--end", "2026-08-31");
  }

  private void addTeams(String division, String... teams) {
    for (String team : teams) {
      execute("team", "add", division, team);
    }
  }

  private int clearPractice(String division, String stdinResponse) {
    InputStream original = System.in;
    System.setIn(new ByteArrayInputStream(stdinResponse.getBytes()));
    try {
      return execute("practice", "clear", "--division", division);
    } finally {
      System.setIn(original);
    }
  }

  /**
   * Injects field/time assignments directly into practice slots via LeagueStore, bypassing the
   * CP-SAT solver. Needed for sort-order tests that require pre-assigned slot state without
   * running the full assignment pipeline. Teams absent from dateByTeam remain unassigned.
   */
  private void injectPracticeAssignments(
      String divisionName, Map<String, LocalDate> dateByTeam, Map<String, LocalTime> timeByTeam)
      throws IOException {
    LeagueStore store = new LeagueStore();
    League league = store.load();
    Division division = league.findDivision(divisionName).orElseThrow();
    PracticeSchedule ps = league.findPracticeSchedule(division.id()).orElseThrow();

    List<PracticeSlot> updatedSlots =
        ps.slots().stream()
            .map(
                slot -> {
                  String teamName =
                      division.teams().stream()
                          .filter(t -> t.id().equals(slot.teamId()))
                          .findFirst()
                          .map(Team::name)
                          .orElse("");
                  LocalDate date = dateByTeam.get(teamName);
                  return date != null
                      ? slot.withAssignment(
                          date, timeByTeam.getOrDefault(teamName, LocalTime.of(10, 0)), null)
                      : slot;
                })
            .toList();

    league =
        league.withPracticeScheduleReplaced(
            division.id(), ps.withSlots(updatedSlots).withState(PracticeState.ASSIGNED));
    store.save(league);
  }

  // ---------------------------------------------------------------------------
  // practice generate
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("practice generate")
  class Generate {

    @Test
    @DisplayName("exits 0 and prints per-division slot count for a configured division")
    void successSingleDivision() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays", "Cardinals");

      int exit = execute("practice", "generate");
      assertEquals(0, exit);
      assertTrue(stdout().contains("Generated 4 practice slots for Majors"));
      assertTrue(stdout().contains("2 teams × 2 practices"));
    }

    @Test
    @DisplayName("exits 0 and prints summary line when multiple divisions qualify")
    void summaryLineMultipleDivisions() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("division", "add", "Minors", "--duration", "60", "--target", "4");
      execute(
          "division",
          "edit",
          "Minors",
          "--practice-count",
          "1",
          "--practice-duration-minutes",
          "60",
          "--practice-start",
          PRAC_START,
          "--practice-end",
          PRAC_END);
      addTeams("Minors", "Red Sox", "Yankees");

      int exit = execute("practice", "generate");
      assertEquals(0, exit);
      assertTrue(stdout().contains("2 division(s) processed"));
    }

    @Test
    @DisplayName("skips a division with incomplete practice configuration")
    void skipsUnconfiguredDivision() {
      execute("division", "add", "Unconfigured", "--duration", "60", "--target", "4");
      addTeams("Unconfigured", "Team A", "Team B");

      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");

      execute("practice", "generate");
      assertTrue(stdout().contains("Skipping Unconfigured"));
    }

    @Test
    @DisplayName("skips a division that already has a practice schedule")
    void skipsAlreadyGeneratedDivision() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");

      execute("practice", "generate");
      execute("practice", "generate");
      assertTrue(stdout().contains("Skipping Majors: practices already generated"));
    }

    @Test
    @DisplayName("skips a configured division with no teams")
    void skipsConfiguredDivisionWithNoTeams() {
      addConfiguredDivision("Empty", 2);
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");

      execute("practice", "generate");
      assertTrue(stdout().contains("Skipping Empty: no teams configured"));
    }

    @Test
    @DisplayName("exits 1 when no divisions qualify")
    void failsWhenNoDivisionsQualify() {
      execute("division", "add", "Unconfigured", "--duration", "60", "--target", "4");

      int exit = execute("practice", "generate");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No divisions qualify"));
    }

    @Test
    @DisplayName("persists practice schedule so subsequent status shows GENERATED")
    void persistsAcrossInvocations() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");

      execute("practice", "generate");
      execute("practice", "view");
      assertTrue(stdout().contains("GENERATED"));
    }

    @Test
    @DisplayName("exits 2 on corrupt data file")
    void ioErrorReturns2() throws Exception {
      corruptLeagueFile();
      assertEquals(2, execute("practice", "generate"));
    }
  }

  // ---------------------------------------------------------------------------
  // practice view (summary)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("practice view (summary)")
  class ViewSummary {

    @Test
    @DisplayName("shows NOT_CONFIGURED for a division with no practice config")
    void notConfiguredState() {
      execute("division", "add", "Bare", "--duration", "60", "--target", "4");
      execute("practice", "view");
      assertTrue(stdout().contains("NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("shows NOT_STARTED for a fully configured division with no schedule")
    void notStartedState() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "view");
      assertTrue(stdout().contains("NOT_STARTED"));
    }

    @Test
    @DisplayName("shows GENERATED after practice generate runs")
    void generatedState() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");
      execute("practice", "view");
      assertTrue(stdout().contains("GENERATED"));
    }

    @Test
    @DisplayName("shows ASSIGNED and TOTAL counts after generate")
    void showsAssignedAndTotal() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");
      execute("practice", "view");
      // 2 teams × 2 practices = 4 total; none assigned yet
      assertTrue(stdout().contains("4")); // total count
    }

    @Test
    @DisplayName("exits 0 with 'No divisions configured' when league is empty")
    void noDivisionsConfigured() {
      int exit = execute("practice", "view");
      assertEquals(0, exit);
      assertTrue(stdout().contains("No divisions configured"));
    }

    @Test
    @DisplayName("exits 2 on corrupt data file")
    void ioErrorReturns2() throws Exception {
      corruptLeagueFile();
      assertEquals(2, execute("practice", "view"));
    }
  }

  // ---------------------------------------------------------------------------
  // practice view --division (detail)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("practice view --division (detail)")
  class ViewDetail {

    @Test
    @DisplayName("prints per-slot table with team names and UNASSIGNED rows")
    void printsDetailTable() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");

      int exit = execute("practice", "view", "--division", "Majors");
      assertEquals(0, exit);
      assertTrue(stdout().contains("Blue Jays"));
      assertTrue(stdout().contains("Cardinals"));
      assertTrue(stdout().contains("UNASSIGNED"));
    }

    @Test
    @DisplayName("prints division header with period dates")
    void printsPeriodHeader() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");

      execute("practice", "view", "--division", "Majors");
      assertTrue(stdout().contains(PRAC_START));
      assertTrue(stdout().contains(PRAC_END));
    }

    @Test
    @DisplayName("slot numbers appear as '1 of N' and '2 of N'")
    void slotNumbersAreCorrect() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays");
      execute("practice", "generate");

      execute("practice", "view", "--division", "Majors");
      assertTrue(stdout().contains("1 of 2"));
      assertTrue(stdout().contains("2 of 2"));
    }

    @Test
    @DisplayName("division lookup is case-insensitive")
    void caseInsensitiveLookup() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");
      int exit = execute("practice", "view", "--division", "majors");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("exits 1 when division does not exist")
    void failsWhenDivisionNotFound() {
      int exit = execute("practice", "view", "--division", "Ghost");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }

    @Test
    @DisplayName("exits 1 when no practice schedule exists for a valid division")
    void failsWhenNoScheduleForDivision() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      int exit = execute("practice", "view", "--division", "Majors");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No practice schedule exists"));
    }

    @Test
    @DisplayName("unassigned slots are sorted by team name alphabetically")
    void unassignedSlotsSortedByTeamName() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Zebras", "Apples", "Mangoes");
      execute("practice", "generate");

      execute("practice", "view", "--division", "Majors");
      String out = stdout();
      assertTrue(out.indexOf("Apples") < out.indexOf("Mangoes"), "Apples before Mangoes");
      assertTrue(out.indexOf("Mangoes") < out.indexOf("Zebras"), "Mangoes before Zebras");
    }

    @Test
    @DisplayName("assigned slots appear before unassigned slots")
    void assignedSlotsBeforeUnassignedSlots() throws IOException {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Cardinals", "Blue Jays");
      execute("practice", "generate");

      // Assign only Blue Jays; Cardinals stays unassigned
      injectPracticeAssignments(
          "Majors",
          Map.of("Blue Jays", LocalDate.of(2026, 4, 10)),
          Map.of("Blue Jays", LocalTime.of(10, 0)));

      execute("practice", "view", "--division", "Majors");
      String out = stdout();
      assertTrue(out.indexOf("2026-04-10") < out.indexOf("UNASSIGNED"),
          "assigned date should appear before UNASSIGNED row");
    }

    @Test
    @DisplayName("assigned slots are sorted by date ascending")
    void assignedSlotsSortedByDateAscending() throws IOException {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Alpha", "Beta", "Gamma");
      execute("practice", "generate");

      injectPracticeAssignments(
          "Majors",
          Map.of(
              "Alpha", LocalDate.of(2026, 4, 15),
              "Beta", LocalDate.of(2026, 4, 10),
              "Gamma", LocalDate.of(2026, 4, 20)),
          Map.of(
              "Alpha", LocalTime.of(10, 0),
              "Beta", LocalTime.of(10, 0),
              "Gamma", LocalTime.of(10, 0)));

      execute("practice", "view", "--division", "Majors");
      String out = stdout();
      int apr10 = out.indexOf("2026-04-10");
      int apr15 = out.indexOf("2026-04-15");
      int apr20 = out.indexOf("2026-04-20");
      assertTrue(apr10 >= 0 && apr15 >= 0 && apr20 >= 0, "all three dates should appear");
      assertTrue(apr10 < apr15, "2026-04-10 before 2026-04-15");
      assertTrue(apr15 < apr20, "2026-04-15 before 2026-04-20");
    }

    @Test
    @DisplayName("slots on the same date are sorted by start time ascending")
    void slotsOnSameDateSortedByStartTimeAscending() throws IOException {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Team A", "Team B");
      execute("practice", "generate");

      // Team A gets 14:00, Team B gets 09:00 — output should be Team B first
      injectPracticeAssignments(
          "Majors",
          Map.of("Team A", LocalDate.of(2026, 4, 10), "Team B", LocalDate.of(2026, 4, 10)),
          Map.of("Team A", LocalTime.of(14, 0), "Team B", LocalTime.of(9, 0)));

      execute("practice", "view", "--division", "Majors");
      String out = stdout();
      int t09 = out.indexOf("09:00");
      int t14 = out.indexOf("14:00");
      assertTrue(t09 >= 0 && t14 >= 0, "both times should appear in output");
      assertTrue(t09 < t14, "09:00 should appear before 14:00");
    }
  }

  // ---------------------------------------------------------------------------
  // practice clear
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("practice clear")
  class Clear {

    @Test
    @DisplayName("removes the schedule after 'yes' confirmation and exits 0")
    void successWithYesConfirmation() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");

      int exit = clearPractice("Majors", "yes\n");
      assertEquals(0, exit);
      assertTrue(stdout().contains("cleared"));
    }

    @Test
    @DisplayName("schedule is gone after clear — status shows NOT_STARTED")
    void scheduleRemovedAfterClear() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");
      clearPractice("Majors", "yes\n");

      execute("practice", "view");
      assertTrue(stdout().contains("NOT_STARTED"));
    }

    @Test
    @DisplayName("does not remove schedule on non-'yes' input")
    void cancelledOnNonYes() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");

      int exit = clearPractice("Majors", "no\n");
      assertEquals(0, exit);
      assertTrue(stdout().contains("Cancelled"));

      execute("practice", "view");
      assertTrue(stdout().contains("GENERATED"));
    }

    @Test
    @DisplayName("confirmation prompt includes slot count")
    void promptIncludesSlotCount() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");

      clearPractice("Majors", "no\n");
      // 2 teams × 2 practices = 4 slots
      assertTrue(stdout().contains("4 slot(s)"));
    }

    @Test
    @DisplayName("exits 1 when division does not exist")
    void failsWhenDivisionNotFound() {
      int exit = execute("practice", "clear", "--division", "Ghost");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }

    @Test
    @DisplayName("exits 1 when no practice schedule exists for the division")
    void failsWhenNoScheduleExists() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      int exit = execute("practice", "clear", "--division", "Majors");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No practice schedule exists"));
    }

    @Test
    @DisplayName("exits 2 on corrupt data file")
    void ioErrorReturns2() throws Exception {
      corruptLeagueFile();
      assertEquals(2, execute("practice", "clear", "--division", "Majors"));
    }
  }

  // ---------------------------------------------------------------------------
  // Generate → Status → Clear lifecycle
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("generate → view → clear lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("full cycle: generate transitions to GENERATED; clear reverts to NOT_STARTED")
    void generateThenClear() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");

      execute("practice", "view");
      assertTrue(stdout().contains("NOT_STARTED"));

      execute("practice", "generate");
      execute("practice", "view");
      assertTrue(stdout().contains("GENERATED"));

      clearPractice("Majors", "yes\n");
      execute("practice", "view");
      assertTrue(stdout().contains("NOT_STARTED"));
    }

    @Test
    @DisplayName("can re-generate after clearing")
    void canRegenerateAfterClear() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");

      execute("practice", "generate");
      clearPractice("Majors", "yes\n");

      int exit = execute("practice", "generate");
      assertEquals(0, exit);
      execute("practice", "view");
      assertTrue(stdout().contains("GENERATED"));
    }

    @Test
    @DisplayName("two divisions generate independently and both show up in status")
    void twoDivisionsIndependent() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("division", "add", "Minors", "--duration", "60", "--target", "4");
      execute(
          "division",
          "edit",
          "Minors",
          "--practice-count",
          "1",
          "--practice-duration-minutes",
          "60",
          "--practice-start",
          PRAC_START,
          "--practice-end",
          PRAC_END);
      addTeams("Minors", "Red Sox", "Yankees");

      execute("practice", "generate");
      execute("practice", "view");
      assertTrue(stdout().contains("Majors"));
      assertTrue(stdout().contains("Minors"));
    }
  }
}
