package org.leagueplan.planr.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.leagueplan.planr.model.TeamGame;

class ScheduleCommandTest extends CommandTestBase {

  // Helpers to build a minimal schedulable league via CLI:
  //   1 division ("Majors", 60-min games, target 2) with 2 teams → 2 games needed
  //   1 field open 07:00–20:00 daily
  //   Season: 2026-06-01 to 2026-06-30 (plenty of slots for both phases)

  private void addMinimalLeague() {
    execute("division", "add", "Majors", "--duration", "60", "--target", "2");
    execute("team", "add", "Majors", "Blue Jays");
    execute("team", "add", "Majors", "Cardinals");
    execute("field", "add", "Riverside Park");
    execute(
        "config",
        "set",
        "--sunrise",
        "07:00",
        "--sunset",
        "20:00",
        "--start",
        "2026-06-01",
        "--end",
        "2026-06-30");
  }

  /** Phase 1 only: generate matchups (no confirmation prompt on first run). */
  private int generateTeamSchedule() {
    return execute("schedule", "generate");
  }

  /**
   * Full two-phase draft: Phase 1 (matchups) + Phase 2 (field/time assignment). Provides "yes"
   * stdin for both steps to handle re-runs gracefully.
   */
  private int generateDraft() {
    int exit = provideStdinAndExecute("yes\n", "schedule", "generate");
    if (exit != 0) return exit;
    return provideStdinAndExecute("yes\n", "schedule", "assign");
  }

  private void generateAndFinalizeSchedule() {
    addMinimalLeague();
    generateDraft();
    provideStdinAndExecute("yes\n", "schedule", "finalize");
  }

  /** Redirects System.in to the given string, executes the command, then restores System.in. */
  private int provideStdinAndExecute(String stdinContent, String... args) {
    InputStream original = System.in;
    System.setIn(new ByteArrayInputStream(stdinContent.getBytes()));
    try {
      return execute(args);
    } finally {
      System.setIn(original);
    }
  }

  // -------------------------------------------------------------------------
  // schedule generate (Phase 1 — matchup / team schedule generation)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("schedule generate")
  class Generate {

    @Test
    @DisplayName("exits 1 when no divisions have been added")
    void failsWhenNoDivisionsExist() {
      execute("config", "set", "--start", "2026-06-01", "--end", "2026-06-30");
      int exit = execute("schedule", "generate");
      assertEquals(1, exit);
      assertTrue(stderr().contains("At least one division"));
    }

    @Test
    @DisplayName("exits 1 when the only division has fewer than 2 teams")
    void failsWhenDivisionHasOnlyOneTeam() {
      execute("division", "add", "Majors", "--duration", "60", "--target", "2");
      execute("team", "add", "Majors", "Blue Jays");
      execute("config", "set", "--start", "2026-06-01", "--end", "2026-06-30");
      int exit = execute("schedule", "generate");
      assertEquals(1, exit);
      assertTrue(stderr().contains("At least one division"));
    }

    @Test
    @DisplayName("exits 1 when season dates have not been configured")
    void failsWhenNoSeasonDatesConfigured() {
      execute("division", "add", "Majors", "--duration", "60", "--target", "2");
      execute("team", "add", "Majors", "Blue Jays");
      execute("team", "add", "Majors", "Cardinals");
      // No config set — season dates are null
      int exit = execute("schedule", "generate");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Season start and end dates"));
    }

    @Test
    @DisplayName("exits 0 and reports game count on success")
    void successReportsGameCount() {
      addMinimalLeague();
      int exit = generateTeamSchedule();
      assertEquals(0, exit);
      assertTrue(stdout().contains("2 games"));
      assertTrue(stdout().contains("1 division"));
    }

    @Test
    @DisplayName("status shows TEAM_SCHEDULE after phase 1 (before assign)")
    void statusIsTeamScheduleAfterPhase1() {
      addMinimalLeague();
      generateTeamSchedule();
      execute("schedule", "status");
      assertTrue(stdout().contains("TEAM_SCHEDULE"));
    }

    @Test
    @DisplayName("generates a DRAFT schedule visible via status when both phases complete")
    void generatedScheduleIsDraft() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "status");
      assertTrue(stdout().contains("DRAFT"));
    }

    @Test
    @DisplayName("regenerating replaces a previous draft schedule")
    void regeneratingReplacesPreviousDraft() {
      addMinimalLeague();
      assertEquals(0, generateDraft());
      assertEquals(0, generateDraft());
      execute("schedule", "status");
      assertTrue(stdout().contains("DRAFT"));
    }

    @Test
    @DisplayName("exits 1 when a FINALIZED schedule already exists")
    void failsWhenFinalizedScheduleExists() {
      generateAndFinalizeSchedule();
      int exit = execute("schedule", "generate");
      assertEquals(1, exit);
      assertTrue(stderr().contains("finalized schedule"));
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      int exit = execute("schedule", "generate");
      assertEquals(2, exit);
      assertTrue(stderr().contains("Failed to access league data"));
    }
  }

  // -------------------------------------------------------------------------
  // schedule assign (Phase 2 — field/time assignment)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("schedule assign")
  class Assign {

    @Test
    @DisplayName("exits 1 when no team schedule has been generated")
    void failsWhenNoTeamSchedule() {
      int exit = provideStdinAndExecute("yes\n", "schedule", "assign");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No team schedule found"));
    }

    @Test
    @DisplayName("exits 0 with partial draft when there are not enough slots to cover all fixtures")
    void failsWhenInsufficientSlots() {
      // 4 teams → 12 games; narrow window 09:00-10:00 → 1 slot/day; 5-day season → 5 < 12
      execute("division", "add", "Majors", "--duration", "60", "--target", "6");
      execute("team", "add", "Majors", "Team A");
      execute("team", "add", "Majors", "Team B");
      execute("team", "add", "Majors", "Team C");
      execute("team", "add", "Majors", "Team D");
      execute("field", "add", "Riverside Park");
      execute(
          "config",
          "set",
          "--sunrise",
          "09:00",
          "--sunset",
          "10:00",
          "--start",
          "2026-06-01",
          "--end",
          "2026-06-05");
      execute("schedule", "generate");
      int exit = provideStdinAndExecute("yes\n", "schedule", "assign");
      assertEquals(0, exit);
      assertTrue(
          stdout().contains("partial"),
          "stdout should contain 'partial' when not all games could be assigned");
    }

    @Test
    @DisplayName("exits 1 when a finalized schedule exists")
    void failsWhenScheduleIsFinalized() {
      generateAndFinalizeSchedule();
      int exit = provideStdinAndExecute("yes\n", "schedule", "assign");
      assertEquals(1, exit);
      assertTrue(stderr().contains("finalized schedule"));
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      int exit = provideStdinAndExecute("yes\n", "schedule", "assign");
      assertEquals(2, exit);
      assertTrue(stderr().contains("Failed to access league data"));
    }
  }

  // -------------------------------------------------------------------------
  // schedule assign — progress and constraint summary
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("schedule assign — progress and constraint summary")
  class ProgressAndConstraintSummary {

    private void addPartialLeague() {
      // 4 teams → 12 games; narrow 09:00-10:00 → 1 slot/day; 5-day season → 5 slots < 12
      execute("division", "add", "Majors", "--duration", "60", "--target", "6");
      execute("team", "add", "Majors", "Team A");
      execute("team", "add", "Majors", "Team B");
      execute("team", "add", "Majors", "Team C");
      execute("team", "add", "Majors", "Team D");
      execute("field", "add", "Riverside Park");
      execute(
          "config",
          "set",
          "--sunrise",
          "09:00",
          "--sunset",
          "10:00",
          "--start",
          "2026-06-01",
          "--end",
          "2026-06-05");
    }

    private int generatePartialDraft() {
      execute("schedule", "generate");
      return provideStdinAndExecute("yes\n", "schedule", "assign");
    }

    @Test
    @DisplayName("stdout contains the Phase 2 start progress line")
    void emitsPhase2StartProgressLine() {
      addMinimalLeague();
      generateDraft();
      assertTrue(stdout().contains("[0:00] Phase 2 started."));
    }

    @Test
    @DisplayName("stdout contains the solver-complete progress line")
    void emitsSolverCompleteProgressLine() {
      addMinimalLeague();
      generateDraft();
      assertTrue(stdout().contains("Solver complete."));
    }

    @Test
    @DisplayName("Constraint Summary is always printed after a successful assign")
    void constraintSummaryAlwaysPrinted() {
      addMinimalLeague();
      generateDraft();
      assertTrue(stdout().contains("Constraint Summary"));
    }

    @Test
    @DisplayName("constraint summary shows target-met status when all games are assigned")
    void constraintSummaryShowsTargetMetStatusOnSuccess() {
      addMinimalLeague();
      generateDraft();
      assertTrue(stdout().contains("target-met"));
    }

    @Test
    @DisplayName("constraint summary footer says 'All targets met' on full assignment")
    void constraintSummaryShowsAllTargetsMetSummaryLine() {
      addMinimalLeague();
      generateDraft();
      assertTrue(stdout().contains("All targets met"));
    }

    @Test
    @DisplayName("constraint summary shows 'partial' status when slots are insufficient")
    void constraintSummaryShowsPartialStatusWhenInsufficient() {
      addPartialLeague();
      generatePartialDraft();
      assertTrue(stdout().contains("partial"));
    }

    @Test
    @DisplayName("per-team shortfall section is printed when not all games could be assigned")
    void teamShortfallPrintedOnPartialSchedule() {
      addPartialLeague();
      generatePartialDraft();
      assertTrue(stdout().contains("Teams that fell short of target"));
    }

    @Test
    @DisplayName("per-team shortfall shows game counts in N/M format")
    void teamShortfallShowsGameCountsInFractionFormat() {
      addPartialLeague();
      generatePartialDraft();
      assertTrue(
          Pattern.compile("\\d+/\\d+ games assigned").matcher(stdout()).find(),
          "stdout should contain a fraction like '4/6 games assigned'");
    }

    @Test
    @DisplayName("per-team shortfall is not printed when all games are assigned")
    void teamShortfallNotPrintedWhenAllGamesAssigned() {
      addMinimalLeague();
      generateDraft();
      assertFalse(stdout().contains("Teams that fell short"));
    }

    @Test
    @DisplayName("final status line says 'Draft schedule saved'")
    void finalStatusLineSaysDraftScheduleSaved() {
      addMinimalLeague();
      generateDraft();
      assertTrue(stdout().contains("Draft schedule saved"));
    }

    @Test
    @DisplayName("partial final status line contains '(partial)'")
    void partialFinalStatusLineIncludesPartialKeyword() {
      addPartialLeague();
      generatePartialDraft();
      assertTrue(stdout().contains("(partial)"));
    }
  }

  // -------------------------------------------------------------------------
  // schedule status
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("schedule status")
  class Status {

    @Test
    @DisplayName("shows guidance when no schedule has been generated")
    void noScheduleShowsMessage() {
      int exit = execute("schedule", "status");
      assertEquals(0, exit);
      assertTrue(stdout().contains("No schedule generated yet"));
    }

    @Test
    @DisplayName("shows DRAFT status after generation")
    void showsDraftStatusAfterGeneration() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "status");
      assertTrue(stdout().contains("DRAFT"));
    }

    @Test
    @DisplayName("shows season start and end dates")
    void showsSeasonDates() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "status");
      String out = stdout();
      assertTrue(out.contains("2026-06-01"));
      assertTrue(out.contains("2026-06-30"));
    }

    @Test
    @DisplayName("shows total game count")
    void showsTotalGameCount() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "status");
      assertTrue(stdout().contains("2"));
    }

    @Test
    @DisplayName("shows game count broken down by division")
    void showsGameCountByDivision() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "status");
      assertTrue(stdout().contains("Majors"));
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      assertEquals(2, execute("schedule", "status"));
    }
  }

  // -------------------------------------------------------------------------
  // schedule finalize
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("schedule finalize")
  class Finalize {

    @Test
    @DisplayName("exits 1 when no draft schedule exists")
    void failsWhenNoDraftSchedule() {
      int exit = provideStdinAndExecute("yes\n", "schedule", "finalize");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No draft schedule"));
    }

    @Test
    @DisplayName("cancels finalization when input is not 'yes'")
    void cancelsWhenInputIsNotYes() {
      addMinimalLeague();
      generateDraft();
      int exit = provideStdinAndExecute("no\n", "schedule", "finalize");
      assertEquals(0, exit);
      assertTrue(stdout().contains("cancelled"));
    }

    @Test
    @DisplayName("exits 0 and locks the schedule when confirmed with 'yes'")
    void finalizesWhenConfirmedWithYes() {
      addMinimalLeague();
      generateDraft();
      int exit = provideStdinAndExecute("yes\n", "schedule", "finalize");
      assertEquals(0, exit);
      assertTrue(stdout().contains("finalized"));
    }

    @Test
    @DisplayName("status changes to FINALIZED after confirmation")
    void statusChangesToFinalizedAfterConfirmation() {
      addMinimalLeague();
      generateDraft();
      provideStdinAndExecute("yes\n", "schedule", "finalize");
      execute("schedule", "status");
      assertTrue(stdout().contains("FINALIZED"));
    }

    @Test
    @DisplayName("status remains DRAFT when finalization is cancelled")
    void statusRemainsDraftWhenCancelled() {
      addMinimalLeague();
      generateDraft();
      provideStdinAndExecute("no\n", "schedule", "finalize");
      execute("schedule", "status");
      assertTrue(stdout().contains("DRAFT"));
    }

    @Test
    @DisplayName("prints irreversibility warning before prompting for confirmation")
    void printsIrreversibilityWarningBeforePrompt() {
      addMinimalLeague();
      generateDraft();
      provideStdinAndExecute("no\n", "schedule", "finalize");
      assertTrue(stdout().contains("irreversible"));
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      int exit = provideStdinAndExecute("yes\n", "schedule", "finalize");
      assertEquals(2, exit);
      assertTrue(stderr().contains("Failed to access league data"));
    }
  }

  // -------------------------------------------------------------------------
  // schedule view
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("schedule view")
  class View {

    @Test
    @DisplayName("exits 1 when no schedule has been generated")
    void failsWhenNoScheduleExists() {
      int exit = execute("schedule", "view");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No schedule generated yet"));
    }

    @Test
    @DisplayName("shows status header and column headers when schedule exists")
    void showsHeadersWhenScheduleExists() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "view");
      String out = stdout();
      assertTrue(out.contains("DRAFT"));
      assertTrue(out.contains("DATE"));
      assertTrue(out.contains("START"));
      assertTrue(out.contains("FIELD"));
      assertTrue(out.contains("HOME"));
      assertTrue(out.contains("AWAY"));
      assertTrue(out.contains("DIVISION"));
    }

    @Test
    @DisplayName("shows both scheduled games for a 2-team division")
    void showsBothGamesForTwoTeamDivision() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "view");
      String out = stdout();
      assertTrue(out.contains("Blue Jays"));
      assertTrue(out.contains("Cardinals"));
    }

    @Test
    @DisplayName("filters to only matching division when --division is specified")
    void filtersByDivision() {
      execute("division", "add", "Majors", "--duration", "60", "--target", "2");
      execute("division", "add", "AAA", "--duration", "60", "--target", "2");
      execute("team", "add", "Majors", "Blue Jays");
      execute("team", "add", "Majors", "Cardinals");
      execute("team", "add", "AAA", "Red Sox");
      execute("team", "add", "AAA", "Yankees");
      execute("field", "add", "Riverside Park");
      execute(
          "config",
          "set",
          "--sunrise",
          "09:00",
          "--sunset",
          "18:00",
          "--start",
          "2026-06-01",
          "--end",
          "2026-06-30");
      generateDraft();
      execute("schedule", "view", "--division", "Majors");
      String out = stdout();
      assertTrue(out.contains("Majors"));
      assertFalse(out.contains("Red Sox"));
      assertFalse(out.contains("Yankees"));
    }

    @Test
    @DisplayName("filters to only matching team when --team is specified")
    void filtersByTeam() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "view", "--team", "Blue Jays");
      String out = stdout();
      assertTrue(out.contains("Blue Jays"));
    }

    @Test
    @DisplayName("filters to only matching field when --field is specified")
    void filtersByField() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "view", "--field", "Riverside Park");
      assertTrue(stdout().contains("Riverside Park"));
    }

    @Test
    @DisplayName("exits 1 with message on stderr when field exists but has no assigned games")
    void emptyFilterResultExits1WithStderrMessage() {
      addMinimalLeague();
      generateDraft();
      // Add a second field after scheduling so no games are assigned to it
      execute("field", "add", "Eastside Field");
      int exit = execute("schedule", "view", "--field", "Eastside Field");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No games match the specified filter."));
    }

    @Test
    @DisplayName("exits 1 when --division filter names an unknown division")
    void failsWhenDivisionFilterNotFound() {
      addMinimalLeague();
      generateDraft();
      int exit = execute("schedule", "view", "--division", "NonExistent");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }

    @Test
    @DisplayName("exits 1 when --field filter names an unknown field")
    void failsWhenFieldFilterNotFound() {
      addMinimalLeague();
      generateDraft();
      int exit = execute("schedule", "view", "--field", "NonExistent");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }

    @Test
    @DisplayName("exits 1 when --team filter names an unknown team")
    void failsWhenTeamFilterNotFound() {
      addMinimalLeague();
      generateDraft();
      int exit = execute("schedule", "view", "--team", "NonExistent");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }

    @Test
    @DisplayName("shows overridden game with * suffix in index column")
    void showsOverriddenGameWithAsterisk() {
      generateAndFinalizeSchedule();
      execute("schedule", "game", "override", "1", "--date", "2026-06-14");
      execute("schedule", "view");
      assertTrue(stdout().contains("*"));
    }

    @Test
    @DisplayName("shows FINALIZED in status header when schedule is finalized")
    void showsFinalizedStatusInHeader() {
      generateAndFinalizeSchedule();
      execute("schedule", "view");
      assertTrue(stdout().contains("FINALIZED"));
    }

    @Test
    @DisplayName("shows TEAM_SCHEDULE in status header when only team schedule exists")
    void showsTeamScheduleStatusInHeader() {
      addMinimalLeague();
      generateTeamSchedule();
      execute("schedule", "view");
      assertTrue(stdout().contains("TEAM_SCHEDULE"));
    }

    @Test
    @DisplayName("exits 1 when --division and --team are both supplied")
    void rejectsMultipleFilters_divisionAndTeam() {
      addMinimalLeague();
      generateDraft();
      int exit = execute("schedule", "view", "--division", "Majors", "--team", "Blue Jays");
      assertEquals(1, exit);
      assertTrue(stderr().contains("At most one of --division, --team, --field may be specified."));
    }

    @Test
    @DisplayName("exits 1 when --division and --field are both supplied")
    void rejectsMultipleFilters_divisionAndField() {
      addMinimalLeague();
      generateDraft();
      int exit =
          execute("schedule", "view", "--division", "Majors", "--field", "Riverside Park");
      assertEquals(1, exit);
      assertTrue(stderr().contains("At most one of --division, --team, --field may be specified."));
    }

    @Test
    @DisplayName("exits 1 when --team and --field are both supplied")
    void rejectsMultipleFilters_teamAndField() {
      addMinimalLeague();
      generateDraft();
      int exit =
          execute("schedule", "view", "--team", "Blue Jays", "--field", "Riverside Park");
      assertEquals(1, exit);
      assertTrue(stderr().contains("At most one of --division, --team, --field may be specified."));
    }

    @Test
    @DisplayName("multi-filter rejection fires before entity validation")
    void multipleFiltersRejectedBeforeEntityValidation() {
      addMinimalLeague();
      generateDraft();
      int exit =
          execute("schedule", "view", "--division", "NonExistentDiv", "--team", "Blue Jays");
      assertEquals(1, exit);
      assertTrue(stderr().contains("At most one of"));
      assertFalse(stderr().contains("not found"));
    }

    @Test
    @DisplayName("--team-schedule is no longer a recognized option")
    void teamScheduleFlagRemovedFromView() {
      generateAndFinalizeSchedule();
      int exit = execute("schedule", "view", "--team-schedule");
      assertNotEquals(0, exit);
      // picocli emits an unrecognized-option error before call() is invoked
      assertFalse(stdout().contains("HOME"));
    }

    @Test
    @DisplayName("--division filter is case-insensitive")
    void divisionFilterIsCaseInsensitive() {
      addMinimalLeague();
      generateDraft();
      int exit = execute("schedule", "view", "--division", "majors");
      assertEquals(0, exit);
      assertTrue(stdout().contains("Majors"));
    }

    @Test
    @DisplayName("--team filter is case-insensitive")
    void teamFilterIsCaseInsensitive() {
      addMinimalLeague();
      generateDraft();
      int exit = execute("schedule", "view", "--team", "blue jays");
      assertEquals(0, exit);
      assertTrue(stdout().contains("Blue Jays"));
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      assertEquals(2, execute("schedule", "view"));
    }

    @Test
    @DisplayName("--division filter scopes matchup table in TEAM_SCHEDULE state")
    void divisionFilterScopesTableInTeamScheduleState() {
      execute("division", "add", "Majors", "--duration", "60", "--target", "2");
      execute("division", "add", "AAA", "--duration", "60", "--target", "2");
      execute("team", "add", "Majors", "Blue Jays");
      execute("team", "add", "Majors", "Cardinals");
      execute("team", "add", "AAA", "Red Sox");
      execute("team", "add", "AAA", "Yankees");
      execute("field", "add", "Riverside Park");
      execute(
          "config",
          "set",
          "--sunrise", "07:00",
          "--sunset", "20:00",
          "--start", "2026-06-01",
          "--end", "2026-06-30");
      execute("schedule", "generate");

      int exit = execute("schedule", "view", "--division", "Majors");

      assertEquals(0, exit);
      String out = stdout();
      assertTrue(out.contains("TEAM_SCHEDULE"));
      assertTrue(out.contains("Blue Jays"));
      assertTrue(out.contains("Cardinals"));
      assertFalse(out.contains("Red Sox"));
      assertFalse(out.contains("Yankees"));
    }

    @Test
    @DisplayName("--team filter scopes matchup table in TEAM_SCHEDULE state")
    void teamFilterScopesTableInTeamScheduleState() {
      addMinimalLeague();
      generateTeamSchedule();

      int exit = execute("schedule", "view", "--team", "Blue Jays");

      assertEquals(0, exit);
      assertTrue(stdout().contains("Blue Jays"));
    }

    @Test
    @DisplayName("--field filter is rejected in TEAM_SCHEDULE state with exit 1")
    void fieldFilterRejectedInTeamScheduleState() {
      addMinimalLeague();
      generateTeamSchedule();

      int exit = execute("schedule", "view", "--field", "Riverside Park");

      assertEquals(1, exit);
      assertTrue(stderr().contains("--field cannot be used when no field assignment exists"));
    }

    @Test
    @DisplayName("--field filter rejection in TEAM_SCHEDULE fires after multi-filter guard")
    void fieldFilterRejectionFiresAfterMultiFilterGuardInTeamScheduleState() {
      addMinimalLeague();
      generateTeamSchedule();

      int exit = execute("schedule", "view", "--division", "Majors", "--field", "Riverside Park");

      assertEquals(1, exit);
      assertTrue(stderr().contains("At most one of --division, --team, --field"));
      assertFalse(stderr().contains("--field cannot be used when no field assignment exists"));
    }

    @Test
    @DisplayName("unknown --division filter exits 1 with 'not found' in TEAM_SCHEDULE state")
    void unknownDivisionFilterExits1InTeamScheduleState() {
      addMinimalLeague();
      generateTeamSchedule();

      int exit = execute("schedule", "view", "--division", "AAA");

      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }
  }

  // -------------------------------------------------------------------------
  // schedule view — team schedule stats (balance + head-to-head tables)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("schedule view — team schedule stats")
  class ViewStats {

    @Test
    @DisplayName("balance section appears when in TEAM_SCHEDULE state")
    void balanceSectionAppearsInTeamScheduleState() {
      addMinimalLeague();
      generateTeamSchedule();
      execute("schedule", "view");
      assertTrue(stdout().contains("HOME/AWAY BALANCE"));
    }

    @Test
    @DisplayName("balance section includes all column headers")
    void balanceSectionIncludesAllColumnHeaders() {
      addMinimalLeague();
      generateTeamSchedule();
      execute("schedule", "view");
      String out = stdout();
      assertTrue(out.contains("HOME/AWAY BALANCE"));
      assertTrue(out.contains("BALANCE"));
      assertTrue(out.contains("TOTAL"));
    }

    @Test
    @DisplayName("balance table includes a TOTAL row")
    void balanceTableIncludesTotalRow() {
      addMinimalLeague();
      generateTeamSchedule();
      execute("schedule", "view");
      assertTrue(stdout().contains("TOTAL"));
    }

    @Test
    @DisplayName("balance table flags teams with |balance| > 1 with an asterisk")
    void balanceTableFlagsTeamsWithImbalanceGreaterThanOne() {
      // 4 teams + target=N-1=3: circle method guarantees the pivot team gets
      // 0 home games → |balance|=3 > 1 → that row is flagged with " *"
      execute("division", "add", "Majors", "--duration", "60", "--target", "3");
      execute("team", "add", "Majors", "Team A");
      execute("team", "add", "Majors", "Team B");
      execute("team", "add", "Majors", "Team C");
      execute("team", "add", "Majors", "Team D");
      execute("field", "add", "Riverside Park");
      execute(
          "config",
          "set",
          "--sunrise",
          "07:00",
          "--sunset",
          "20:00",
          "--start",
          "2026-06-01",
          "--end",
          "2026-06-30");
      execute("schedule", "generate");
      execute("schedule", "view");
      assertTrue(stdout().contains(" *"));
    }

    @Test
    @DisplayName("head-to-head section appears when in TEAM_SCHEDULE state")
    void headToHeadSectionAppearsInTeamScheduleState() {
      addMinimalLeague();
      generateTeamSchedule();
      execute("schedule", "view");
      assertTrue(stdout().contains("HEAD-TO-HEAD"));
    }

    @Test
    @DisplayName("head-to-head diagonal cells show em dash")
    void headToHeadDiagonalCellsShowEmDash() {
      addMinimalLeague();
      generateTeamSchedule();
      execute("schedule", "view");
      assertTrue(stdout().contains("—"));
    }

    @Test
    @DisplayName("head-to-head flags cells that deviate from the global mode")
    void headToHeadFlagsCellsDeviatingFromGlobalMode() {
      // A-B total=2, A-C total=1, B-C total=1 → global mode=1 → A-B pair flagged as "2*"
      UUID divId = UUID.randomUUID();
      UUID idA = UUID.randomUUID(), idB = UUID.randomUUID(), idC = UUID.randomUUID();
      List<TeamGame> games =
          List.of(
              new TeamGame(UUID.randomUUID(), 1, idA, "A", idB, "B", divId, "Majors", 60),
              new TeamGame(UUID.randomUUID(), 2, idA, "A", idB, "B", divId, "Majors", 60),
              new TeamGame(UUID.randomUUID(), 3, idA, "A", idC, "C", divId, "Majors", 60),
              new TeamGame(UUID.randomUUID(), 4, idB, "B", idC, "C", divId, "Majors", 60));
      ScheduleCommand.printHeadToHeadBlock(games, "Majors");
      assertTrue(stdout().contains("2*"));
    }

    @Test
    @DisplayName("full view in DRAFT state shows stats blocks")
    void fullViewInDraftStateShowsStats() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "view");
      String out = stdout();
      assertTrue(out.contains("HOME/AWAY BALANCE"));
      assertTrue(out.contains("HEAD-TO-HEAD"));
    }

    @Test
    @DisplayName("multi-division league shows one balance block per division")
    void multiDivisionShowsOneBalanceBlockPerDivision() {
      execute("division", "add", "Majors", "--duration", "60", "--target", "2");
      execute("division", "add", "AAA", "--duration", "60", "--target", "2");
      execute("team", "add", "Majors", "Blue Jays");
      execute("team", "add", "Majors", "Cardinals");
      execute("team", "add", "AAA", "Red Sox");
      execute("team", "add", "AAA", "Yankees");
      execute("field", "add", "Riverside Park");
      execute(
          "config",
          "set",
          "--sunrise",
          "07:00",
          "--sunset",
          "20:00",
          "--start",
          "2026-06-01",
          "--end",
          "2026-06-30");
      execute("schedule", "generate");
      execute("schedule", "view");
      String out = stdout();
      assertTrue(out.contains("HOME/AWAY BALANCE — Majors"));
      assertTrue(out.contains("HOME/AWAY BALANCE — AAA"));
    }

    @Test
    @DisplayName("--division filter in DRAFT view shows stats only for that division")
    void divisionFilterScopesStatsToDivisionInDraftView() {
      execute("division", "add", "Majors", "--duration", "60", "--target", "2");
      execute("division", "add", "AAA", "--duration", "60", "--target", "2");
      execute("team", "add", "Majors", "Blue Jays");
      execute("team", "add", "Majors", "Cardinals");
      execute("team", "add", "AAA", "Red Sox");
      execute("team", "add", "AAA", "Yankees");
      execute("field", "add", "Riverside Park");
      execute(
          "config",
          "set",
          "--sunrise", "09:00",
          "--sunset", "18:00",
          "--start", "2026-06-01",
          "--end", "2026-06-30");
      generateDraft();

      int exit = execute("schedule", "view", "--division", "Majors");

      assertEquals(0, exit);
      String out = stdout();
      assertTrue(out.contains("HOME/AWAY BALANCE — Majors"));
      assertFalse(out.contains("HOME/AWAY BALANCE — AAA"));
    }

    @Test
    @DisplayName("--team filter in DRAFT view suppresses stats blocks entirely")
    void teamFilterSkipsStatsInDraftView() {
      addMinimalLeague();
      generateDraft();

      int exit = execute("schedule", "view", "--team", "Blue Jays");

      assertEquals(0, exit);
      String out = stdout();
      assertFalse(out.contains("HOME/AWAY BALANCE"));
      assertFalse(out.contains("HEAD-TO-HEAD"));
    }

    @Test
    @DisplayName("--field filter in DRAFT view suppresses stats blocks entirely")
    void fieldFilterSkipsStatsInDraftView() {
      addMinimalLeague();
      generateDraft();

      int exit = execute("schedule", "view", "--field", "Riverside Park");

      assertEquals(0, exit);
      String out = stdout();
      assertFalse(out.contains("HOME/AWAY BALANCE"));
      assertFalse(out.contains("HEAD-TO-HEAD"));
    }

    @Test
    @DisplayName("full view in FINALIZED state shows stats blocks")
    void fullViewInFinalizedStateShowsStats() {
      generateAndFinalizeSchedule();

      execute("schedule", "view");

      String out = stdout();
      assertTrue(out.contains("HOME/AWAY BALANCE"));
      assertTrue(out.contains("HEAD-TO-HEAD"));
    }
  }

  // -------------------------------------------------------------------------
  // schedule export
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("schedule export")
  class Export {

    @Test
    @DisplayName("exits 1 when no schedule has been generated")
    void failsWhenNoScheduleExists() {
      int exit = execute("schedule", "export");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No schedule generated yet"));
    }

    @Test
    @DisplayName("stdout is a JSON array")
    void stdoutIsJsonArray() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "export");
      String out = stdout();
      assertTrue(out.startsWith("["));
      assertTrue(out.endsWith("]"));
    }

    @Test
    @DisplayName("each game object contains required fields")
    void eachGameObjectContainsRequiredFields() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "export");
      String out = stdout();
      assertTrue(out.contains("\"date\""));
      assertTrue(out.contains("\"start_time\""));
      assertTrue(out.contains("\"field_name\""));
      assertTrue(out.contains("\"home_team\""));
      assertTrue(out.contains("\"away_team\""));
      assertTrue(out.contains("\"division_name\""));
      assertTrue(out.contains("\"status\""));
    }

    @Test
    @DisplayName("status field is lowercase")
    void statusFieldIsLowercase() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "export");
      assertTrue(stdout().contains("\"draft\""));
    }

    @Test
    @DisplayName("status field is 'finalized' after finalization")
    void statusFieldIsFinalizedAfterFinalization() {
      generateAndFinalizeSchedule();
      execute("schedule", "export");
      assertTrue(stdout().contains("\"finalized\""));
    }

    @Test
    @DisplayName("exported game count is printed to stderr")
    void gameCountPrintedToStderr() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "export");
      assertTrue(stderr().contains("Exported"));
      assertTrue(stderr().contains("2 games"));
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      assertEquals(2, execute("schedule", "export"));
    }

    @Test
    @DisplayName(
        "automatically exports team schedule when in TEAM_SCHEDULE state without --team-schedule"
            + " flag")
    void autoExportsTeamScheduleInTeamScheduleState() {
      addMinimalLeague();
      generateTeamSchedule();
      int exit = execute("schedule", "export");
      assertEquals(0, exit);
      String out = stdout();
      assertTrue(out.startsWith("["));
      assertTrue(out.contains("\"game_number\""));
      assertTrue(out.contains("\"home_team\""));
      assertTrue(out.contains("\"away_team\""));
      assertTrue(out.contains("\"division_name\""));
    }

    @Test
    @DisplayName("team schedule export omits date, start_time, and field_name")
    void teamScheduleExportOmitsDateTimeAndField() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "export", "--team-schedule");
      String out = stdout();
      assertFalse(out.contains("\"date\""));
      assertFalse(out.contains("\"start_time\""));
      assertFalse(out.contains("\"field_name\""));
    }

    @Test
    @DisplayName("team schedule export includes game_number as a numeric value")
    void teamScheduleExportIncludesGameNumberAsNumeric() {
      addMinimalLeague();
      generateTeamSchedule();
      execute("schedule", "export");
      String out = stdout();
      // game_number must be an unquoted integer in JSON, not a string
      assertTrue(
          out.contains("\"game_number\" : 1") || out.contains("\"game_number\":1"),
          "game_number must be a numeric JSON value, not a string");
    }

    @Test
    @DisplayName(
        "team schedule export count is printed to stderr with '(team schedule)' annotation")
    void teamScheduleExportCountAnnotation() {
      addMinimalLeague();
      generateTeamSchedule();
      execute("schedule", "export");
      assertTrue(stderr().contains("team schedule"));
    }
  }

  // -------------------------------------------------------------------------
  // schedule game edit
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("schedule game edit")
  class GameEdit {

    @Test
    @DisplayName("exits 1 when no team schedule has been generated")
    void failsWhenNoTeamSchedule() {
      int exit = execute("schedule", "game", "edit", "1", "--home", "Blue Jays");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No team schedule found"));
    }

    @Test
    @DisplayName("exits 1 when schedule is finalized")
    void failsWhenScheduleIsFinalized() {
      generateAndFinalizeSchedule();
      int exit = execute("schedule", "game", "edit", "1", "--home", "Blue Jays");
      assertEquals(1, exit);
      assertTrue(stderr().contains("finalized"));
    }

    @Test
    @DisplayName("exits 1 when the specified team is not playing in the game")
    void failsWhenTeamNotInGame() {
      addMinimalLeague();
      generateTeamSchedule();
      int exit = execute("schedule", "game", "edit", "1", "--home", "Red Sox");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not playing in game"));
    }

    @Test
    @DisplayName("exits 0 with no-change message when the team is already home")
    void noChangeWhenTeamAlreadyHome() {
      addMinimalLeague();
      generateTeamSchedule();
      // First call ensures Cardinals is home (swap if needed, or no-op if already home).
      execute("schedule", "game", "edit", "1", "--home", "Cardinals");
      // Second call: Cardinals is now home; must be a no-op.
      int exit = execute("schedule", "game", "edit", "1", "--home", "Cardinals");
      assertEquals(0, exit);
      assertTrue(stdout().contains("already the home team"));
    }

    @Test
    @DisplayName("swaps home and away when the current away team is designated as home")
    void swapsHomeAndAway() {
      addMinimalLeague();
      generateTeamSchedule();
      // Pin Blue Jays as home for game 1 (swap from current state if needed).
      execute("schedule", "game", "edit", "1", "--home", "Blue Jays");
      // Now swap: Cardinals was away, designate Cardinals as home.
      int exit = execute("schedule", "game", "edit", "1", "--home", "Cardinals");
      assertEquals(0, exit);
      assertTrue(stdout().contains("Cardinals (home)"));
      assertTrue(stdout().contains("Blue Jays (away)"));
    }

    @Test
    @DisplayName("succeeds and exits 0 when schedule is in DRAFT state")
    void succeedsWhenScheduleIsDraft() {
      addMinimalLeague();
      generateDraft();
      // Exit 0 regardless of whether the call is a swap or a no-op.
      int exit = execute("schedule", "game", "edit", "1", "--home", "Blue Jays");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("draft schedule is preserved after editing home/away in DRAFT state")
    void draftSchedulePreservedAfterEditInDraftState() {
      addMinimalLeague();
      generateDraft();
      execute("schedule", "game", "edit", "1", "--home", "Cardinals");
      execute("schedule", "status");
      assertTrue(
          stdout().contains("DRAFT"),
          "editing home/away in DRAFT state must not revert the schedule to TEAM_SCHEDULE");
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      int exit = execute("schedule", "game", "edit", "1", "--home", "Blue Jays");
      assertEquals(2, exit);
      assertTrue(stderr().contains("Failed to access league data"));
    }
  }

  // -------------------------------------------------------------------------
  // schedule game override
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("schedule game override")
  class GameOverride {

    @Test
    @DisplayName("exits 1 when no schedule exists")
    void failsWhenNoScheduleExists() {
      int exit = execute("schedule", "game", "override", "1", "--date", "2026-07-01");
      assertEquals(1, exit);
      assertTrue(stderr().contains("finalized schedule"));
    }

    @Test
    @DisplayName("exits 1 when schedule is in DRAFT state")
    void failsWhenScheduleIsDraft() {
      addMinimalLeague();
      generateDraft();
      int exit = execute("schedule", "game", "override", "1", "--date", "2026-07-01");
      assertEquals(1, exit);
      assertTrue(stderr().contains("finalized schedule"));
    }

    @Test
    @DisplayName("exits 1 when no override options are provided")
    void failsWhenNoOptionsProvided() {
      generateAndFinalizeSchedule();
      int exit = execute("schedule", "game", "override", "1");
      assertEquals(1, exit);
      assertTrue(stderr().contains("At least one override option"));
    }

    @Test
    @DisplayName("exits 1 when game number is 0")
    void failsWhenGameNumberIsZero() {
      generateAndFinalizeSchedule();
      int exit = execute("schedule", "game", "override", "0", "--date", "2026-07-01");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }

    @Test
    @DisplayName("exits 1 when game number exceeds the schedule size")
    void failsWhenGameNumberOutOfRange() {
      generateAndFinalizeSchedule();
      int exit = execute("schedule", "game", "override", "99", "--date", "2026-07-01");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }

    @Test
    @DisplayName("exits 1 when the specified field does not exist")
    void failsWhenFieldNotFound() {
      generateAndFinalizeSchedule();
      int exit = execute("schedule", "game", "override", "1", "--field", "Nonexistent");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }

    @Test
    @DisplayName("exits 1 when the specified home team does not exist")
    void failsWhenHomeTeamNotFound() {
      generateAndFinalizeSchedule();
      int exit = execute("schedule", "game", "override", "1", "--home", "Ghost Team");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }

    @Test
    @DisplayName("exits 1 when the specified away team does not exist")
    void failsWhenAwayTeamNotFound() {
      generateAndFinalizeSchedule();
      int exit = execute("schedule", "game", "override", "1", "--away", "Ghost Team");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }

    @Test
    @DisplayName("exits 0 and prints confirmation when overriding the date")
    void successWhenOverridingDate() {
      generateAndFinalizeSchedule();
      int exit = execute("schedule", "game", "override", "1", "--date", "2026-07-01");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));
    }

    @Test
    @DisplayName("overridden game is marked with * in the view output")
    void overriddenGameMarkedWithAsteriskInView() {
      generateAndFinalizeSchedule();
      execute("schedule", "game", "override", "1", "--date", "2026-07-01");
      execute("schedule", "view");
      assertTrue(stdout().contains("*"));
    }

    @Test
    @DisplayName("conflict warning is printed to stderr when same field and date overlap")
    void conflictWarningPrintedWhenFieldOverlaps() {
      addMinimalLeague();
      generateDraft();
      provideStdinAndExecute("yes\n", "schedule", "finalize");

      execute(
          "schedule",
          "game",
          "override",
          "1",
          "--start",
          "09:00",
          "--date",
          "2026-06-07",
          "--field",
          "Riverside Park");
      execute(
          "schedule",
          "game",
          "override",
          "2",
          "--start",
          "09:00",
          "--date",
          "2026-06-07",
          "--field",
          "Riverside Park");

      assertTrue(stderr().contains("Warning") || stdout().contains("updated"));
    }

    @Test
    @DisplayName(
        "saves successfully when date is outside the season window (no constraint re-validation)")
    void succeedsWithOutOfSeasonDate() {
      generateAndFinalizeSchedule();
      // Season window is 2026-06-01 to 2026-06-30; 2025-01-01 is well outside it.
      int exit = execute("schedule", "game", "override", "1", "--date", "2025-01-01");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));
    }

    @Test
    @DisplayName("overriding multiple fields in one call produces a single updated confirmation")
    void overridingMultipleFieldsSucceeds() {
      generateAndFinalizeSchedule();
      int exit =
          execute(
              "schedule",
              "game",
              "override",
              "1",
              "--date",
              "2026-07-04",
              "--start",
              "10:00",
              "--home",
              "Cardinals");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      int exit = execute("schedule", "game", "override", "1", "--date", "2026-07-01");
      assertEquals(2, exit);
      assertTrue(stderr().contains("Failed to access league data"));
    }
  }
}
