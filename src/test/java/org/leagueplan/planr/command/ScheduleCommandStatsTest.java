package org.leagueplan.planr.command;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.leagueplan.planr.model.ScheduledGame;
import org.leagueplan.planr.model.TeamGame;

/**
 * Unit tests for ScheduleCommand.printBalanceBlock and printHeadToHeadBlock.
 *
 * <p>These tests call the package-private static helpers directly (no CLI round-trip) and capture
 * stdout via the CommandTestBase redirect. Each test arranges a minimal game list, acts by calling
 * the static method, then asserts on the captured output.
 */
class ScheduleCommandStatsTest extends CommandTestBase {

  private static final UUID DIV = UUID.nameUUIDFromBytes("div".getBytes());

  /** Creates a TeamGame where home and away are identified by name only (ID is deterministic). */
  private static TeamGame game(String home, String away) {
    return new TeamGame(
        UUID.randomUUID(),
        1,
        UUID.nameUUIDFromBytes(home.getBytes()),
        home,
        UUID.nameUUIDFromBytes(away.getBytes()),
        away,
        DIV,
        "Majors",
        60);
  }

  /** Creates a TeamGame in a named division. */
  private static TeamGame game(String home, String away, String divisionName) {
    return new TeamGame(
        UUID.randomUUID(),
        1,
        UUID.nameUUIDFromBytes(home.getBytes()),
        home,
        UUID.nameUUIDFromBytes(away.getBytes()),
        away,
        DIV,
        divisionName,
        60);
  }

  // -------------------------------------------------------------------------
  // printBalanceBlock
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("printBalanceBlock")
  class BalanceBlock {

    @Test
    @DisplayName("section header includes the division name after the em-dash")
    void sectionHeaderIncludesDivisionName() {
      ScheduleCommand.printBalanceBlock(List.of(game("Alpha", "Beta")), "Premier");
      assertTrue(stdout().contains("HOME/AWAY BALANCE — Premier"));
    }

    @Test
    @DisplayName("teams are sorted alphabetically regardless of first-occurrence order")
    void teamsAreSortedAlphabetically() {
      // Games arrive in Zebra→Alpha→Mango order; rows must appear Alpha, Mango, Zebra
      List<TeamGame> games =
          List.of(game("Zebra", "Alpha"), game("Alpha", "Mango"), game("Mango", "Zebra"));
      ScheduleCommand.printBalanceBlock(games, "Majors");
      String out = stdout();
      int alphaPos = out.indexOf("Alpha");
      int mangoPos = out.indexOf("Mango");
      int zebraPos = out.indexOf("Zebra");
      assertTrue(alphaPos < mangoPos, "Alpha must appear before Mango");
      assertTrue(mangoPos < zebraPos, "Mango must appear before Zebra");
    }

    @Test
    @DisplayName("HOME + AWAY = TOTAL for every team row")
    void homePlusAwayEqualsTotal() {
      // Alpha: home=3, away=1, total=4 — verify "4" appears in the alpha line
      List<TeamGame> games =
          List.of(
              game("Alpha", "Beta"),
              game("Alpha", "Beta"),
              game("Alpha", "Beta"),
              game("Beta", "Alpha"));
      ScheduleCommand.printBalanceBlock(games, "Majors");
      String alphaLine =
          Arrays.stream(stdout().split("\n"))
              .filter(l -> l.trim().startsWith("Alpha"))
              .findFirst()
              .orElse("");
      assertFalse(alphaLine.isEmpty(), "Alpha row must be present");
      // Alpha: home=3, away=1, total=4
      assertTrue(alphaLine.contains("4"), "TOTAL column for Alpha must be 4 (3+1)");
    }

    @Test
    @DisplayName("BALANCE equals HOME minus AWAY")
    void balanceEqualsHomeMinusAway() {
      // Alpha: home=3, away=1 → balance=+2; Beta: home=1, away=3 → balance=-2
      List<TeamGame> games =
          List.of(
              game("Alpha", "Beta"),
              game("Alpha", "Beta"),
              game("Alpha", "Beta"),
              game("Beta", "Alpha"));
      ScheduleCommand.printBalanceBlock(games, "Majors");
      String out = stdout();
      assertTrue(out.contains("+2"), "BALANCE for Alpha (3−1=+2) must appear as '+2'");
      assertTrue(out.contains("-2"), "BALANCE for Beta (1−3=−2) must appear as '-2'");
    }

    @Test
    @DisplayName("zero balance is shown as '0' without any sign character")
    void zeroBalanceDisplaysAsZeroWithoutSign() {
      // Alpha: home=1, away=1 → balance=0
      List<TeamGame> games = List.of(game("Alpha", "Beta"), game("Beta", "Alpha"));
      ScheduleCommand.printBalanceBlock(games, "Majors");
      String out = stdout();
      assertFalse(out.contains("+0"), "zero balance must not appear as '+0'");
      assertFalse(out.contains("-0"), "zero balance must not appear as '-0'");
      assertTrue(out.contains("0"), "'0' must appear for a balanced team");
    }

    @Test
    @DisplayName("positive balance is shown with an explicit leading '+' sign")
    void positiveBalanceDisplaysWithPlusSign() {
      // Alpha: home=2, away=0 → balance=+2
      List<TeamGame> games = List.of(game("Alpha", "Beta"), game("Alpha", "Beta"));
      ScheduleCommand.printBalanceBlock(games, "Majors");
      assertTrue(stdout().contains("+2"), "positive balance must appear with a '+' prefix");
    }

    @Test
    @DisplayName("negative balance is shown with a leading hyphen (ASCII '-')")
    void negativeBalanceDisplaysWithHyphen() {
      // Beta: home=0, away=2 → balance=-2
      List<TeamGame> games = List.of(game("Alpha", "Beta"), game("Alpha", "Beta"));
      ScheduleCommand.printBalanceBlock(games, "Majors");
      assertTrue(stdout().contains("-2"), "negative balance must appear with a '-' prefix");
    }

    @Test
    @DisplayName("|balance| = 1 does not trigger the flag (threshold is strictly > 1)")
    void absoluteBalanceOfOneIsNotFlagged() {
      // Alpha: home=2, away=1 → balance=+1; Beta: home=1, away=2 → balance=-1
      List<TeamGame> games =
          List.of(game("Alpha", "Beta"), game("Alpha", "Beta"), game("Beta", "Alpha"));
      ScheduleCommand.printBalanceBlock(games, "Majors");
      assertFalse(stdout().contains(" *"), "rows with |balance|=1 must not be flagged");
    }

    @Test
    @DisplayName("|balance| = 2 triggers the ' *' flag")
    void absoluteBalanceOfTwoIsFlaged() {
      // Alpha: home=3, away=1 → balance=+2 → flagged
      List<TeamGame> games =
          List.of(
              game("Alpha", "Beta"),
              game("Alpha", "Beta"),
              game("Alpha", "Beta"),
              game("Beta", "Alpha"));
      ScheduleCommand.printBalanceBlock(games, "Majors");
      assertTrue(stdout().contains(" *"), "rows with |balance|=2 must carry the ' *' flag");
    }

    @Test
    @DisplayName("TOTAL row sums HOME, AWAY, and TOTAL correctly")
    void totalRowShowsCorrectSums() {
      // Alpha: home=2, away=1; Beta: home=1, away=2 → TOTAL: HOME=3, AWAY=3, TOTAL=6
      List<TeamGame> games =
          List.of(game("Alpha", "Beta"), game("Alpha", "Beta"), game("Beta", "Alpha"));
      ScheduleCommand.printBalanceBlock(games, "Majors");
      String totalLine =
          Arrays.stream(stdout().split("\n"))
              .filter(l -> l.trim().startsWith("TOTAL"))
              .findFirst()
              .orElse("");
      assertFalse(totalLine.isEmpty(), "TOTAL row must be present");
      assertTrue(totalLine.contains("3"), "TOTAL HOME+AWAY subtotals must each be 3");
      assertTrue(totalLine.contains("6"), "TOTAL grand total must be 6");
    }

    @Test
    @DisplayName("TOTAL row has no BALANCE column — no sign character appears on that line")
    void totalRowOmitsBalanceColumn() {
      // Alpha: home=3, away=1 → balance=+2 (would normally show '+'); TOTAL row must not
      List<TeamGame> games =
          List.of(
              game("Alpha", "Beta"),
              game("Alpha", "Beta"),
              game("Alpha", "Beta"),
              game("Beta", "Alpha"));
      ScheduleCommand.printBalanceBlock(games, "Majors");
      String totalLine =
          Arrays.stream(stdout().split("\n"))
              .filter(l -> l.trim().startsWith("TOTAL"))
              .findFirst()
              .orElse("");
      assertFalse(totalLine.isEmpty(), "TOTAL row must be present");
      assertFalse(totalLine.contains("+"), "TOTAL row must not include a '+' balance value");
      assertFalse(totalLine.contains(" *"), "TOTAL row must not include a flag");
    }
  }

  // -------------------------------------------------------------------------
  // printHeadToHeadBlock
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("printHeadToHeadBlock")
  class HeadToHeadBlock {

    @Test
    @DisplayName("section header includes division name and pair-total annotation")
    void sectionHeaderIncludesDivisionNameAndAnnotation() {
      ScheduleCommand.printHeadToHeadBlock(List.of(game("Alpha", "Beta")), "Premier");
      String out = stdout();
      assertTrue(
          out.contains("HEAD-TO-HEAD — Premier"),
          "division name must appear in the section header");
      assertTrue(
          out.contains("total games between each pair"),
          "header must describe cells as total games between each pair");
    }

    @Test
    @DisplayName("column headers are sorted alphabetically")
    void columnHeadersAreSortedAlphabetically() {
      List<TeamGame> games =
          List.of(game("Zebra", "Alpha"), game("Alpha", "Mango"), game("Mango", "Zebra"));
      ScheduleCommand.printHeadToHeadBlock(games, "Majors");
      // Line 0 = section header; line 1 = column header row
      String headerLine = stdout().split("\n")[1];
      int alphaPos = headerLine.indexOf("Alpha");
      int mangoPos = headerLine.indexOf("Mango");
      int zebraPos = headerLine.indexOf("Zebra");
      assertTrue(alphaPos >= 0, "Alpha must appear in column headers");
      assertTrue(alphaPos < mangoPos, "Alpha must come before Mango in column headers");
      assertTrue(mangoPos < zebraPos, "Mango must come before Zebra in column headers");
    }

    @Test
    @DisplayName("row labels are sorted alphabetically")
    void rowLabelsAreSortedAlphabetically() {
      List<TeamGame> games =
          List.of(game("Zebra", "Alpha"), game("Alpha", "Mango"), game("Mango", "Zebra"));
      ScheduleCommand.printHeadToHeadBlock(games, "Majors");
      String[] lines = stdout().split("\n");
      // lines[0]=header, lines[1]=columns, lines[2]=separator, lines[3..5]=rows
      int alphaRow = -1, mangoRow = -1, zebraRow = -1;
      for (int i = 3; i < lines.length; i++) {
        String t = lines[i].trim();
        if (t.startsWith("Alpha")) alphaRow = i;
        else if (t.startsWith("Mango")) mangoRow = i;
        else if (t.startsWith("Zebra")) zebraRow = i;
      }
      assertTrue(alphaRow > 0 && alphaRow < mangoRow, "Alpha row must come before Mango row");
      assertTrue(mangoRow < zebraRow, "Mango row must come before Zebra row");
    }

    @Test
    @DisplayName("diagonal cells contain the em dash character (U+2014)")
    void diagonalCellsContainEmDash() {
      ScheduleCommand.printHeadToHeadBlock(
          List.of(game("Alpha", "Beta"), game("Beta", "Alpha")), "Majors");
      assertTrue(stdout().contains("—"), "diagonal cells must show '—' (U+2014)");
    }

    @Test
    @DisplayName("zero matchup count is displayed as '0*', not as a blank")
    void zeroMatchupDisplaysAsZeroNotBlank() {
      // A plays B and C, but B and C never face each other → B-C pair total = 0
      // Upper-triangle counts: A-B=1, A-C=1, B-C=0 → mode=1 → B-C flagged as "0*"
      List<TeamGame> games = List.of(game("A", "B"), game("A", "C"));
      ScheduleCommand.printHeadToHeadBlock(games, "Majors");
      assertTrue(stdout().contains("0*"), "cell with zero matchups must print '0*' when zero is not the global mode");
    }

    @Test
    @DisplayName("non-zero matchup count is displayed as a plain integer")
    void nonZeroMatchupDisplaysAsInteger() {
      // Alpha and Beta play 3 games total (Alpha hosts twice, Beta hosts once) → symmetric cell = 3
      // Only one pair exists → global mode = 3 → no flag → cell shown as plain "3"
      List<TeamGame> games =
          List.of(game("Alpha", "Beta"), game("Alpha", "Beta"), game("Beta", "Alpha"));
      ScheduleCommand.printHeadToHeadBlock(games, "Majors");
      assertTrue(stdout().contains("3"), "total matchup count of 3 must appear as '3'");
    }

    @Test
    @DisplayName("when all pair totals are equal, no cell is flagged")
    void uniformPairCountsProduceNoFlags() {
      // Each pair plays twice total (once in each direction) → global mode = 2 → no flags
      List<TeamGame> games =
          List.of(
              game("A", "B"), game("B", "A"),
              game("A", "C"), game("C", "A"),
              game("B", "C"), game("C", "B"));
      ScheduleCommand.printHeadToHeadBlock(games, "Majors");
      assertFalse(
          stdout().contains("*"),
          "no cell must be flagged when all pair totals equal the global mode");
    }

    @Test
    @DisplayName("cell deviating from global mode is flagged with '*' appended directly (no space)")
    void deviatingCellFlaggedWithAsteriskDirectlyAppended() {
      // A-B total=2, A-C total=1, B-C total=1 → upper-triangle freq {2:1, 1:2} → mode=1
      // A-B pair shown as "2*"; A-C and B-C shown as plain "1"
      List<TeamGame> games =
          List.of(game("A", "B"), game("A", "B"), game("A", "C"), game("B", "C"));
      ScheduleCommand.printHeadToHeadBlock(games, "Majors");
      String out = stdout();
      assertTrue(
          out.contains("2*"),
          "flagged cell must be count immediately followed by '*' with no space");
      assertFalse(out.contains("2 *"), "there must be no space between the count and the '*' flag");
    }

    @Test
    @DisplayName("when global-mode frequency ties, the lower value is chosen as mode")
    void modeTieBreakChoosesLowerValue() {
      // Path graph: A-B=1, B-C=1, C-D=1, A-C=0, A-D=0, B-D=0
      // Upper-triangle freq {1:3, 0:3} → tie → mode=0 (lower wins) → "1" pairs flagged as "1*"
      // If tie-break chose the higher value (mode=1), zeros would appear as "0*" instead.
      List<TeamGame> games = List.of(game("A", "B"), game("B", "C"), game("C", "D"));
      ScheduleCommand.printHeadToHeadBlock(games, "X");
      String out = stdout();
      assertTrue(
          out.contains("1*"),
          "when mode is 0 by tie-break, cells with value 1 must be flagged as '1*'");
      assertFalse(
          out.contains("0*"), "zero must not be flagged when it is the mode chosen by tie-break");
    }

    @Test
    @DisplayName("column header row is indented by spaces equal to the row-label column width")
    void columnHeaderRowIsIndentedByRowLabelWidth() {
      // "Alpha" is 5 chars → rowLabelW=5; header line must start with 5 spaces
      List<TeamGame> games = List.of(game("Alpha", "Beta"), game("Beta", "Alpha"));
      ScheduleCommand.printHeadToHeadBlock(games, "Majors");
      String[] lines = stdout().split("\n");
      // lines[0]=section header, lines[1]=column header row
      String columnHeaderLine = lines[1];
      assertTrue(
          columnHeaderLine.startsWith("     "),
          "column header row must start with at least 5 spaces (rowLabelW for 'Alpha')");
      assertFalse(
          columnHeaderLine.trim().isEmpty(),
          "column header row must contain team names after the indent");
    }

    @Test
    @DisplayName("column separator width is at least as wide as the team name in that column")
    void columnSeparatorWidthMeetsTeamNameLength() {
      // "LongTeamName" is 12 chars; its column separator must have ≥12 consecutive dashes
      List<TeamGame> games = List.of(game("LongTeamName", "Short"), game("Short", "LongTeamName"));
      ScheduleCommand.printHeadToHeadBlock(games, "Majors");
      assertTrue(
          stdout().contains("------------"),
          "separator for a 12-char team name column must contain at least 12 consecutive dashes");
    }

    @Test
    @DisplayName("matrix is symmetric: cell[A][B] equals cell[B][A]")
    void matrixIsSymmetric() {
      // Alpha hosts Beta 3 times; Beta never hosts Alpha — symmetric total = 3 in both cells
      List<TeamGame> games =
          List.of(game("Alpha", "Beta"), game("Alpha", "Beta"), game("Alpha", "Beta"));
      ScheduleCommand.printHeadToHeadBlock(games, "Majors");
      String[] lines = stdout().split("\n");
      // lines[0]=header, lines[1]=columns, lines[2]=separator, lines[3]=Alpha row, lines[4]=Beta row
      String alphaRow = lines[3];
      String betaRow = lines[4];
      assertTrue(alphaRow.contains("3"), "Alpha row must show 3 total games vs Beta");
      assertTrue(betaRow.contains("3"), "Beta row must also show 3 — cell[B][A] must equal cell[A][B]");
      assertFalse(betaRow.contains("0"), "Beta row must NOT show 0 — directional count has been replaced by pair total");
    }

    @Test
    @DisplayName("games in both directions are summed into one pair total")
    void gamesInBothDirectionsSumToSingleTotal() {
      // Alpha hosts Beta twice, Beta hosts Alpha once → pair total = 3
      List<TeamGame> games =
          List.of(game("Alpha", "Beta"), game("Alpha", "Beta"), game("Beta", "Alpha"));
      ScheduleCommand.printHeadToHeadBlock(games, "Majors");
      String out = stdout();
      assertTrue(out.contains("3"), "both cells must reflect the combined total of 3 games");
      assertFalse(out.contains("2"), "directional count of 2 must not appear as a standalone cell value");
      assertFalse(out.contains("1"), "directional count of 1 must not appear as a standalone cell value");
    }

    @Test
    @DisplayName("old home/away axis annotation is absent from the header")
    void oldHomeAwayAnnotationIsAbsent() {
      ScheduleCommand.printHeadToHeadBlock(List.of(game("Alpha", "Beta")), "Premier");
      String out = stdout();
      assertFalse(out.contains("row = home team"), "'row = home team' must not appear in the new header");
      assertFalse(out.contains("column = away team"), "'column = away team' must not appear in the new header");
    }

    @Test
    @DisplayName("single pair produces no flag because every pair equals the global mode")
    void singlePairProducesNoFlag() {
      // Only one pair exists → mode = its count → cell is not an outlier → no '*'
      List<TeamGame> games = List.of(game("Alpha", "Beta"), game("Alpha", "Beta"));
      ScheduleCommand.printHeadToHeadBlock(games, "Majors");
      assertFalse(stdout().contains("*"), "with only one pair the cell equals the mode and must not be flagged");
    }

  }

  // -------------------------------------------------------------------------
  // computeGlobalMode
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("computeGlobalMode")
  class ComputeGlobalMode {

    @Test
    @DisplayName("returns 0 when there are no teams (n=0)")
    void returnsZeroForNoTeams() {
      assertEquals(0, ScheduleCommand.computeGlobalMode(new int[0][0], 0));
    }

    @Test
    @DisplayName("returns 0 when there is exactly one team (no pairs)")
    void returnsZeroForSingleTeam() {
      int[][] matrix = {{0}};
      assertEquals(0, ScheduleCommand.computeGlobalMode(matrix, 1));
    }

    @Test
    @DisplayName("returns the pair count when there is exactly one pair (n=2)")
    void returnsPairCountForTwoTeams() {
      int[][] matrix = {{0, 5}, {5, 0}};
      assertEquals(5, ScheduleCommand.computeGlobalMode(matrix, 2));
    }

    @Test
    @DisplayName("returns 0 when all pairs have zero games")
    void returnsZeroWhenAllPairsAreZero() {
      int n = 4;
      int[][] matrix = new int[n][n];
      assertEquals(0, ScheduleCommand.computeGlobalMode(matrix, n));
    }

    @Test
    @DisplayName("returns the unique value when all pairs are equal")
    void returnsUniqueValueWhenAllPairsEqual() {
      // 3 teams, every pair plays 2 games
      int[][] matrix = {
        {0, 2, 2},
        {2, 0, 2},
        {2, 2, 0}
      };
      assertEquals(2, ScheduleCommand.computeGlobalMode(matrix, 3));
    }

    @Test
    @DisplayName("returns the most frequent pair count")
    void returnsMostFrequentPairCount() {
      // 4 teams: 5 pairs with count=1, 1 pair with count=3 → mode=1
      int n = 4;
      int[][] matrix = {
        {0, 3, 1, 1},
        {3, 0, 1, 1},
        {1, 1, 0, 1},
        {1, 1, 1, 0}
      };
      assertEquals(1, ScheduleCommand.computeGlobalMode(matrix, n));
    }

    @Test
    @DisplayName("each pair is counted once — lower triangle does not skew the mode")
    void eachPairCountedOnce() {
      // If lower triangle were counted too, the freq table would double every entry identically,
      // which would not change the mode — but if it were counted asymmetrically (e.g., off-diagonal
      // mismatch due to a bug), the result would change. We verify upper-triangle-only by constructing
      // a matrix where lower and upper triangles differ and asserting the upper-triangle value wins.
      // Upper triangle: (0,1)=2, (0,2)=2, (1,2)=2 → mode=2
      // Lower triangle (deliberately set differently): (1,0)=99, (2,0)=99, (2,1)=99
      int[][] matrix = {
        {0,  2,  2},
        {99, 0,  2},
        {99, 99, 0}
      };
      assertEquals(2, ScheduleCommand.computeGlobalMode(matrix, 3),
          "mode must be derived from upper triangle only; lower triangle must be ignored");
    }

    @Test
    @DisplayName("tie-break chooses the lower value")
    void tieBreakChoosesLowerValue() {
      // 4 teams in a path: upper pairs are (A-B=1, A-C=0, A-D=0, B-C=1, B-D=0, C-D=1)
      // freq {1:3, 0:3} → tie → lower value = 0
      int[][] matrix = {
        {0, 1, 0, 0},
        {1, 0, 1, 0},
        {0, 1, 0, 1},
        {0, 0, 1, 0}
      };
      assertEquals(0, ScheduleCommand.computeGlobalMode(matrix, 4),
          "when frequency ties, the lower value must be chosen as mode");
    }
  }

  // -------------------------------------------------------------------------
  // printScheduledHeadToHeadBlock
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("printScheduledHeadToHeadBlock")
  class ScheduledHeadToHeadBlock {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 1);
    private static final LocalTime TIME = LocalTime.of(9, 0);
    private static final UUID FIELD = UUID.nameUUIDFromBytes("field".getBytes());

    private ScheduledGame scheduledGame(String home, String away) {
      return new ScheduledGame(
          UUID.randomUUID(),
          DATE,
          TIME,
          FIELD,
          "Field 1",
          UUID.nameUUIDFromBytes(home.getBytes()),
          home,
          UUID.nameUUIDFromBytes(away.getBytes()),
          away,
          DIV,
          "Majors",
          60,
          false);
    }

    @Test
    @DisplayName("section header includes division name and pair-total annotation")
    void headerIncludesDivisionNameAndAnnotation() {
      ScheduleCommand.printScheduledHeadToHeadBlock(
          List.of(scheduledGame("Alpha", "Beta")), "Premier");
      String out = stdout();
      assertTrue(out.contains("HEAD-TO-HEAD — Premier"), "division name must appear in the header");
      assertTrue(
          out.contains("total games between each pair"),
          "header must describe cells as total games between each pair");
    }

    @Test
    @DisplayName("old home/away axis annotation is absent from the header")
    void oldHomeAwayAnnotationIsAbsent() {
      ScheduleCommand.printScheduledHeadToHeadBlock(
          List.of(scheduledGame("Alpha", "Beta")), "Premier");
      String out = stdout();
      assertFalse(out.contains("row = home team"), "'row = home team' must not appear");
      assertFalse(out.contains("column = away team"), "'column = away team' must not appear");
    }

    @Test
    @DisplayName("matrix is symmetric: cell[A][B] equals cell[B][A]")
    void matrixIsSymmetric() {
      // Alpha hosts Beta 2 times; Beta never hosts Alpha → symmetric total = 2 in both cells
      List<ScheduledGame> games =
          List.of(scheduledGame("Alpha", "Beta"), scheduledGame("Alpha", "Beta"));
      ScheduleCommand.printScheduledHeadToHeadBlock(games, "Majors");
      String[] lines = stdout().split("\n");
      String alphaRow = lines[3];
      String betaRow = lines[4];
      assertTrue(alphaRow.contains("2"), "Alpha row must show total of 2 games vs Beta");
      assertTrue(betaRow.contains("2"), "Beta row must also show 2 — symmetry requirement");
      assertFalse(betaRow.contains("0"), "Beta row must NOT show 0 — directional count is gone");
    }

    @Test
    @DisplayName("games in both directions are summed into one pair total")
    void gamesInBothDirectionsSumToSingleTotal() {
      List<ScheduledGame> games =
          List.of(
              scheduledGame("Alpha", "Beta"),
              scheduledGame("Alpha", "Beta"),
              scheduledGame("Beta", "Alpha"));
      ScheduleCommand.printScheduledHeadToHeadBlock(games, "Majors");
      assertTrue(stdout().contains("3"), "combined total of 3 games must appear in both cells");
    }

    @Test
    @DisplayName("cell deviating from global mode is flagged with '*'")
    void deviatingCellIsFlagged() {
      // A-B total=2, A-C total=1, B-C total=1 → mode=1 → A-B pair shown as "2*"
      List<ScheduledGame> games =
          List.of(
              scheduledGame("A", "B"),
              scheduledGame("A", "B"),
              scheduledGame("A", "C"),
              scheduledGame("B", "C"));
      ScheduleCommand.printScheduledHeadToHeadBlock(games, "Majors");
      assertTrue(stdout().contains("2*"), "pair with count above mode must be flagged with '*'");
    }

    @Test
    @DisplayName("zero-total pair is shown as '0*' when zero is not the global mode")
    void zeroPairFlaggedWhenNotMode() {
      // A plays B and C; B and C never play → B-C total=0, mode=1 → "0*"
      List<ScheduledGame> games =
          List.of(scheduledGame("A", "B"), scheduledGame("A", "C"));
      ScheduleCommand.printScheduledHeadToHeadBlock(games, "Majors");
      assertTrue(stdout().contains("0*"), "zero-game pair must appear as '0*' when zero is not the mode");
    }

    @Test
    @DisplayName("diagonal cells contain the em dash character (U+2014)")
    void diagonalCellsContainEmDash() {
      ScheduleCommand.printScheduledHeadToHeadBlock(
          List.of(scheduledGame("Alpha", "Beta")), "Majors");
      assertTrue(stdout().contains("—"), "diagonal cells must show '—' (U+2014)");
    }

    @Test
    @DisplayName("when all pair totals are equal, no cell is flagged")
    void uniformPairCountsProduceNoFlags() {
      // Each pair plays twice total (once each direction) → mode=2 → no flags
      List<ScheduledGame> games =
          List.of(
              scheduledGame("A", "B"), scheduledGame("B", "A"),
              scheduledGame("A", "C"), scheduledGame("C", "A"),
              scheduledGame("B", "C"), scheduledGame("C", "B"));
      ScheduleCommand.printScheduledHeadToHeadBlock(games, "Majors");
      assertFalse(stdout().contains("*"), "no cell must be flagged when all pair totals equal the mode");
    }
  }
}
