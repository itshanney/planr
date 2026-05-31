package org.leagueplan.planr.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import org.leagueplan.planr.model.Team;
import org.leagueplan.planr.model.TeamGame;

class TeamScheduleServiceTest {

  private static final LeagueConfig VALID_CONFIG =
      new LeagueConfig(
          LocalTime.of(9, 0),
          LocalTime.of(18, 0),
          LocalDate.of(2026, 6, 1),
          LocalDate.of(2026, 8, 31),
          List.of(),
          List.of(),
          null,
          null,
          null,
          null);

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Team team(String name) {
    return new Team(UUID.randomUUID(), name);
  }

  private static Division division(String name, int target, Team... teams) {
    return new Division(
        UUID.randomUUID(), name, 60, target, List.of(teams), null, null, null, null, null);
  }

  private static League league(LeagueConfig config, Division... divisions) {
    return new League(4, config, List.of(divisions), List.of(), null, null, List.of(), List.of());
  }

  private static League league(Division... divisions) {
    return league(VALID_CONFIG, divisions);
  }

  private TeamScheduleResult generate(League l) {
    return new TeamScheduleService().generate(l);
  }

  private static List<TeamGame> successGames(TeamScheduleResult result) {
    assertInstanceOf(TeamScheduleResult.Success.class, result);
    return ((TeamScheduleResult.Success) result).schedule().games();
  }

  private static long gamesPlayedBy(List<TeamGame> games, UUID teamId) {
    return games.stream()
        .filter(g -> g.homeTeamId().equals(teamId) || g.awayTeamId().equals(teamId))
        .count();
  }

  // ---------------------------------------------------------------------------
  // Round-robin game counts (no fill, target = N-1)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("2 teams with target=1 produces exactly 1 round-robin game")
  void twoTeamsProducesOneRoundRobinGame() {
    Team a = team("Blue Jays"), b = team("Cardinals");
    Division div = division("Majors", 1, a, b);
    List<TeamGame> games = successGames(generate(league(div)));

    assertEquals(1, games.size());
    assertEquals(1, gamesPlayedBy(games, a.id()));
    assertEquals(1, gamesPlayedBy(games, b.id()));
  }

  @Test
  @DisplayName(
      "3 teams (odd count) with target=2 produces 3 round-robin games with no bye teams in output")
  void threeTeamsProducesThreeGamesWithNoByeTeams() {
    Team a = team("Blue Jays"), b = team("Cardinals"), c = team("Red Sox");
    Division div = division("Majors", 2, a, b, c);
    List<TeamGame> games = successGames(generate(league(div)));

    assertEquals(3, games.size());
    for (UUID id : List.of(a.id(), b.id(), c.id())) {
      assertEquals(
          2,
          gamesPlayedBy(games, id),
          "Each team should play exactly 2 games in a 3-team single round-robin");
    }
    for (TeamGame g : games) {
      assertNotEquals("[unknown]", g.homeTeamName(), "No bye/unknown team should appear");
      assertNotEquals("[unknown]", g.awayTeamName(), "No bye/unknown team should appear");
    }
  }

  @Test
  @DisplayName("4 teams with target=3 produces exactly 6 round-robin games (N*(N-1)/2)")
  void fourTeamsProducesSixRoundRobinGames() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 3, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    assertEquals(6, games.size());
    for (UUID id : List.of(a.id(), b.id(), c.id(), d.id())) {
      assertEquals(
          3,
          gamesPlayedBy(games, id),
          "Each team should play exactly N-1=3 games in a single round-robin");
    }
  }

  @Test
  @DisplayName("6 teams with target=5 produces exactly 15 round-robin games (N*(N-1)/2)")
  void sixTeamsProducesFifteenRoundRobinGames() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D"), e = team("E"), f = team("F");
    Division div = division("Majors", 5, a, b, c, d, e, f);
    List<TeamGame> games = successGames(generate(league(div)));

    assertEquals(15, games.size());
    for (Team t : List.of(a, b, c, d, e, f)) {
      assertEquals(
          5,
          gamesPlayedBy(games, t.id()),
          "Each team should play exactly N-1=5 games in a single round-robin");
    }
  }

  // ---------------------------------------------------------------------------
  // Round-robin structural properties
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("4 teams with target=3 — each unordered pair appears exactly once")
  void fourTeamsEachUnorderedPairAppearsExactlyOnce() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 3, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    List<UUID> teams = List.of(a.id(), b.id(), c.id(), d.id());
    for (int i = 0; i < teams.size(); i++) {
      for (int j = i + 1; j < teams.size(); j++) {
        UUID x = teams.get(i), y = teams.get(j);
        long count =
            games.stream()
                .filter(
                    g ->
                        (g.homeTeamId().equals(x) && g.awayTeamId().equals(y))
                            || (g.homeTeamId().equals(y) && g.awayTeamId().equals(x)))
                .count();
        assertEquals(
            1,
            count,
            "Unordered pair (" + x + ", " + y + ") should appear exactly once with no fill");
      }
    }
  }

  @Test
  @DisplayName("4 teams with target=3 — home/away imbalance is at most N-1 per team")
  void fourTeamsRoundRobinHomeAwayBalanced() {
    // The circle method produces approximately even schedules; the worst-positioned team
    // can end up all-away in a single round-robin (N-1 = 3 games). ≤ N-1 is tight.
    int n = 4;
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 3, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    for (Team t : List.of(a, b, c, d)) {
      long home = games.stream().filter(g -> g.homeTeamId().equals(t.id())).count();
      long away = games.stream().filter(g -> g.awayTeamId().equals(t.id())).count();
      assertTrue(
          Math.abs(home - away) <= n - 1,
          "Team "
              + t.name()
              + " home/away imbalance > "
              + (n - 1)
              + ": home="
              + home
              + " away="
              + away);
    }
  }

  // ---------------------------------------------------------------------------
  // Fill games
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("2 teams with target=4 extends to 4 total games via fill")
  void twoTeamsFillGamesExtendToTarget() {
    Team a = team("Blue Jays"), b = team("Cardinals");
    Division div = division("Majors", 4, a, b);
    List<TeamGame> games = successGames(generate(league(div)));

    assertEquals(4, games.size());
    assertEquals(4, gamesPlayedBy(games, a.id()));
    assertEquals(4, gamesPlayedBy(games, b.id()));
  }

  @Test
  @DisplayName("4 teams with target=8 produces 16 total games (6 round-robin + 10 fill)")
  void fourTeamsFillProducesSixteenGames() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 8, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    assertEquals(16, games.size());
    for (Team t : List.of(a, b, c, d)) {
      assertEquals(8, gamesPlayedBy(games, t.id()), "Each team should play exactly 8 games");
    }
  }

  @Test
  @DisplayName("4 teams with target=8 — fill reduces home/away imbalance (at most N-1 per team)")
  void fourTeamsFillHomeAwayBalanced() {
    // Without balance tracking, some teams could end up always home in fill rounds (imbalance ≫
    // N-1).
    // The fill algorithm assigns home to the team with the higher away-over-home deficit, keeping
    // the imbalance bounded. N-1 = 3 is a safe upper bound.
    int n = 4;
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 8, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    for (Team t : List.of(a, b, c, d)) {
      long home = games.stream().filter(g -> g.homeTeamId().equals(t.id())).count();
      long away = games.stream().filter(g -> g.awayTeamId().equals(t.id())).count();
      assertTrue(
          Math.abs(home - away) <= n - 1,
          "Team "
              + t.name()
              + " home/away imbalance > "
              + (n - 1)
              + " after fill: home="
              + home
              + " away="
              + away);
    }
  }

  @Test
  @DisplayName("3 teams with target=8 — all teams reach at least 7 games despite odd-team sit-outs")
  void threeTeamsFillAllTeamsReachAtLeastSevenGames() {
    Team a = team("A"), b = team("B"), c = team("C");
    Division div = division("Majors", 8, a, b, c);
    List<TeamGame> games = successGames(generate(league(div)));

    for (Team t : List.of(a, b, c)) {
      long count = gamesPlayedBy(games, t.id());
      assertTrue(count >= 7, "Team " + t.name() + " should have at least 7 games; got " + count);
    }
  }

  // ---------------------------------------------------------------------------
  // Fill round logging
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("no fill round logs when target equals N-1")
  void noFillRoundLogsWhenTargetEqualsNMinus1() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 3, a, b, c, d);
    TeamScheduleResult result = generate(league(div));

    assertInstanceOf(TeamScheduleResult.Success.class, result);
    assertTrue(
        ((TeamScheduleResult.Success) result).fillRoundLogs().isEmpty(),
        "No fill rounds expected when target equals N-1");
  }

  @Test
  @DisplayName("fill round logs contain one entry per fill round in expected format")
  void fillRoundLogsHaveCorrectCountAndFormat() {
    // 4 teams, target=8: RR gives 3 games per team; need 5 more → 5 fill rounds (2 games each)
    Team a = team("Alpha"), b = team("Beta"), c = team("Delta"), d = team("Gamma");
    Division div = division("Majors", 8, a, b, c, d);
    TeamScheduleResult result = generate(league(div));

    assertInstanceOf(TeamScheduleResult.Success.class, result);
    List<String> logs = ((TeamScheduleResult.Success) result).fillRoundLogs();

    assertEquals(5, logs.size(), "Expected 5 fill round log entries for 4 teams with target=8");
    for (int i = 0; i < logs.size(); i++) {
      String log = logs.get(i);
      assertTrue(
          log.startsWith("Fill round " + (i + 1) + " complete:"),
          "Log entry "
              + i
              + " should start with 'Fill round "
              + (i + 1)
              + " complete:'; got: "
              + log);
      assertTrue(log.contains("Alpha"), "Log should contain team name 'Alpha': " + log);
      assertTrue(log.contains("Beta"), "Log should contain team name 'Beta': " + log);
      assertTrue(log.contains("Delta"), "Log should contain team name 'Delta': " + log);
      assertTrue(log.contains("Gamma"), "Log should contain team name 'Gamma': " + log);
    }
  }

  // ---------------------------------------------------------------------------
  // Validation failures
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("target below N-1 returns Failure naming the division and the correct minimum")
  void targetBelowMinimumReturnsFailure() {
    // N=4, minimum=N-1=3, target=2 is too low
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 2, a, b, c, d);
    TeamScheduleResult result = generate(league(div));

    assertInstanceOf(TeamScheduleResult.Failure.class, result);
    String msg = ((TeamScheduleResult.Failure) result).message();
    assertTrue(msg.contains("Majors"), "Error should name the failing division; got: " + msg);
    assertTrue(msg.contains("3"), "Error should state minimum target of 3; got: " + msg);
    assertTrue(msg.contains("2"), "Error should state the actual target of 2; got: " + msg);
  }

  @Test
  @DisplayName("target equal to N-1 is accepted (minimum valid target)")
  void targetEqualToNMinus1IsAccepted() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 3, a, b, c, d);
    assertInstanceOf(TeamScheduleResult.Success.class, generate(league(div)));
  }

  @Test
  @DisplayName("all failing divisions are reported together in a single Failure")
  void allFailingDivisionsReportedTogether() {
    // Majors: 4 teams, target=2 (< min 3)
    // AAA: 3 teams, target=1 (< min 2)
    Division majors = division("Majors", 2, team("A"), team("B"), team("C"), team("D"));
    Division aaa = division("AAA", 1, team("X"), team("Y"), team("Z"));
    TeamScheduleResult result = generate(league(majors, aaa));

    assertInstanceOf(TeamScheduleResult.Failure.class, result);
    String msg = ((TeamScheduleResult.Failure) result).message();
    assertTrue(msg.contains("Majors"), "Error should name Majors; got: " + msg);
    assertTrue(msg.contains("AAA"), "Error should name AAA; got: " + msg);
  }

  @Test
  @DisplayName("missing season dates returns Failure")
  void missingSeasonDatesReturnsFailure() {
    Team a = team("A"), b = team("B");
    Division div = division("Majors", 1, a, b);
    LeagueConfig noDateConfig =
        new LeagueConfig(
            LocalTime.of(9, 0),
            LocalTime.of(18, 0),
            null,
            null,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null);
    assertInstanceOf(TeamScheduleResult.Failure.class, generate(league(noDateConfig, div)));
  }

  @Test
  @DisplayName("no division with 2 or more teams returns Failure")
  void noDivisionsWithTwoOrMoreTeamsReturnsFailure() {
    Division div = division("Majors", 1, team("Solo"));
    assertInstanceOf(TeamScheduleResult.Failure.class, generate(league(div)));
  }

  // ---------------------------------------------------------------------------
  // Multi-division
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("two divisions each produce the correct game count independently")
  void twoDivisionsProduceCorrectGameCountsIndependently() {
    // Majors: 4 teams, target=3 → 6 games (N*(N-1)/2, no fill)
    Division majors = division("Majors", 3, team("M1"), team("M2"), team("M3"), team("M4"));
    // AAA: 3 teams, target=2 → 3 games (N*(N-1)/2, no fill)
    Division aaa = division("AAA", 2, team("A1"), team("A2"), team("A3"));

    List<TeamGame> games = successGames(generate(league(majors, aaa)));

    long majorsCount = games.stream().filter(g -> g.divisionId().equals(majors.id())).count();
    long aaaCount = games.stream().filter(g -> g.divisionId().equals(aaa.id())).count();
    assertEquals(6, majorsCount, "Majors should produce 6 games");
    assertEquals(3, aaaCount, "AAA should produce 3 games");
    assertEquals(9, games.size(), "Total should be 9");
  }

  // ---------------------------------------------------------------------------
  // Game numbering
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("game numbers form a sequential series starting at 1")
  void gameNumbersAreSequentialFromOne() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 6, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    List<Integer> numbers =
        games.stream().map(TeamGame::gameNumber).sorted().collect(Collectors.toList());

    for (int i = 0; i < numbers.size(); i++) {
      assertEquals(
          i + 1, numbers.get(i), "Game number at sorted position " + i + " should be " + (i + 1));
    }
  }

  // ---------------------------------------------------------------------------
  // Determinism
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("same league input produces identical TeamSchedule on repeated calls")
  void sameInputProducesDeterministicOutput() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 6, a, b, c, d);
    League l = league(div);

    List<TeamGame> games1 = successGames(generate(l));
    List<TeamGame> games2 = successGames(generate(l));

    assertEquals(games1.size(), games2.size());
    for (int i = 0; i < games1.size(); i++) {
      TeamGame g1 = games1.get(i), g2 = games2.get(i);
      assertEquals(g1.gameNumber(), g2.gameNumber(), "Game number mismatch at index " + i);
      assertEquals(g1.homeTeamId(), g2.homeTeamId(), "Home team mismatch at index " + i);
      assertEquals(g1.awayTeamId(), g2.awayTeamId(), "Away team mismatch at index " + i);
      assertEquals(g1.divisionId(), g2.divisionId(), "Division mismatch at index " + i);
    }
  }
}
