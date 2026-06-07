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
  @DisplayName("no cycle logs when target equals N-1 (minimum single round-robin)")
  void noCycleLogsWhenTargetEqualsNMinus1() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 3, a, b, c, d);
    TeamScheduleResult result = generate(league(div));

    assertInstanceOf(TeamScheduleResult.Success.class, result);
    assertTrue(
        ((TeamScheduleResult.Success) result).cycleLogs().isEmpty(),
        "No cycle logs expected when target equals N-1");
  }

  @Test
  @DisplayName("cycle logs: 4 teams target=8 produces 2 full-cycle logs and 1 partial-cycle log")
  void cycleLogsHaveCorrectCountAndFormat() {
    // N=4, N-1=3: fullCycles=8/3=2, remainder=8%3=2 → 2 "Cycle K" lines + 1 "Partial cycle" line
    Team a = team("Alpha"), b = team("Beta"), c = team("Delta"), d = team("Gamma");
    Division div = division("Majors", 8, a, b, c, d);
    TeamScheduleResult result = generate(league(div));

    assertInstanceOf(TeamScheduleResult.Success.class, result);
    List<String> logs = ((TeamScheduleResult.Success) result).cycleLogs();

    assertEquals(3, logs.size(), "Expected 3 cycle log entries for 4 teams with target=8");
    assertTrue(logs.get(0).startsWith("Cycle 1 complete:"), "First log: " + logs.get(0));
    assertTrue(logs.get(1).startsWith("Cycle 2 complete:"), "Second log: " + logs.get(1));
    assertTrue(
        logs.get(2).startsWith("Partial cycle (2 of 3 rounds) complete:"),
        "Third log: " + logs.get(2));
    for (String log : logs) {
      assertTrue(log.contains("Alpha"), "Log should contain 'Alpha': " + log);
      assertTrue(log.contains("Beta"), "Log should contain 'Beta': " + log);
      assertTrue(log.contains("Delta"), "Log should contain 'Delta': " + log);
      assertTrue(log.contains("Gamma"), "Log should contain 'Gamma': " + log);
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
  // Multi-cycle head-to-head balance
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("4 teams target=6 (double RR) — each pair meets exactly twice")
  void doubleRoundRobinEachPairMeetsTwice() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 6, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    assertEquals(12, games.size());
    List<UUID> teams = List.of(a.id(), b.id(), c.id(), d.id());
    for (int i = 0; i < teams.size(); i++) {
      for (int j = i + 1; j < teams.size(); j++) {
        UUID x = teams.get(i), y = teams.get(j);
        long h2h = headToHead(games, x, y);
        assertEquals(2, h2h, "Pair (" + x + ", " + y + ") should meet exactly twice; got " + h2h);
      }
    }
  }

  @Test
  @DisplayName("4 teams target=8 (between double and triple RR) — head-to-head imbalance at most 1")
  void betweenDoubleAndTripleRRHeadToHeadImbalanceAtMostOne() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 8, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    // fullCycles=2, remainder=2 → each pair meets 2 or 3 times (floor=2, floor+1=3)
    List<UUID> teams = List.of(a.id(), b.id(), c.id(), d.id());
    for (int i = 0; i < teams.size(); i++) {
      for (int j = i + 1; j < teams.size(); j++) {
        long h2h = headToHead(games, teams.get(i), teams.get(j));
        assertTrue(h2h >= 2 && h2h <= 3, "Head-to-head should be 2 or 3; got " + h2h);
      }
    }
  }

  @Test
  @DisplayName("4 teams target=4 — no pair from the partial cycle repeats from the first full cycle")
  void partialCyclePairsAreNotRepeatedFromFirstCycle() {
    // N=4, N-1=3: fullCycles=4/3=1, remainder=4%3=1 → 1 full cycle + 1 partial round
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 4, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    // The first full cycle covers 6 games (3 rounds × 2 games). The partial adds 2 more.
    // Every pair should appear at most twice total; the 2 partial games must be distinct pairs.
    List<UUID> teams = List.of(a.id(), b.id(), c.id(), d.id());
    for (int i = 0; i < teams.size(); i++) {
      for (int j = i + 1; j < teams.size(); j++) {
        long h2h = headToHead(games, teams.get(i), teams.get(j));
        assertTrue(h2h <= 2, "No pair should meet more than twice; got " + h2h);
      }
    }
  }

  @Test
  @DisplayName("3 teams (odd N) target=5 (odd) — succeeds via E-3 top-up; N-1 teams at T, 1 at T+1")
  void oddNOddTargetFiveSucceeds() {
    // N=3, T=5: fullCycles=2, remainder=1. After partial: A=4, B=5, C=5.
    // E-3 top-up: A paired with min-H2H opponent (B, tie-broken by div.teams() order).
    // Result: A=5, B=6, C=5. Exactly 1 team at T+1; unavoidable (N×T=15 is odd).
    Team a = team("A"), b = team("B"), c = team("C");
    Division div = division("Majors", 5, a, b, c);
    List<TeamGame> games = successGames(generate(league(div)));

    assertEquals(5, gamesPlayedBy(games, a.id()), "A should play exactly 5 games");
    assertEquals(6, gamesPlayedBy(games, b.id()), "B should play 6 games (top-up opponent)");
    assertEquals(5, gamesPlayedBy(games, c.id()), "C should play exactly 5 games");
  }

  @Test
  @DisplayName("4 teams target=6 (double RR) — cycle logs: exactly 2 lines both prefixed 'Cycle'")
  void doubleRRCycleLogCount() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 6, a, b, c, d);
    TeamScheduleResult result = generate(league(div));

    assertInstanceOf(TeamScheduleResult.Success.class, result);
    List<String> logs = ((TeamScheduleResult.Success) result).cycleLogs();

    assertEquals(2, logs.size(), "Double RR should produce exactly 2 cycle log lines");
    assertTrue(logs.get(0).startsWith("Cycle 1 complete:"), logs.get(0));
    assertTrue(logs.get(1).startsWith("Cycle 2 complete:"), logs.get(1));
  }

  @Test
  @DisplayName("4 teams target=8 — cycle logs: 2 full-cycle lines then 1 partial-cycle line")
  void partialRRCycleLogCount() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 8, a, b, c, d);
    TeamScheduleResult result = generate(league(div));

    assertInstanceOf(TeamScheduleResult.Success.class, result);
    List<String> logs = ((TeamScheduleResult.Success) result).cycleLogs();

    assertEquals(3, logs.size(), "Should produce 2 full-cycle logs + 1 partial-cycle log");
    assertTrue(logs.get(0).startsWith("Cycle 1 complete:"), logs.get(0));
    assertTrue(logs.get(1).startsWith("Cycle 2 complete:"), logs.get(1));
    assertTrue(logs.get(2).startsWith("Partial cycle (2 of 3 rounds) complete:"), logs.get(2));
  }

  // ---------------------------------------------------------------------------
  // Triple round-robin
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("4 teams target=9 (triple RR) — each pair meets exactly three times")
  void tripleRoundRobinEachPairMeetsThreeTimes() {
    // N=4, N-1=3: fullCycles=9/3=3, remainder=0 → 3 full cycles, 18 total games
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 9, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    assertEquals(18, games.size());
    List<UUID> teams = List.of(a.id(), b.id(), c.id(), d.id());
    for (int i = 0; i < teams.size(); i++) {
      for (int j = i + 1; j < teams.size(); j++) {
        long h2h = headToHead(games, teams.get(i), teams.get(j));
        assertEquals(3, h2h, "Each pair should meet exactly 3 times in triple RR; got " + h2h);
      }
    }
  }

  @Test
  @DisplayName("4 teams target=9 (triple RR) — cycle logs contain exactly 3 'Cycle K' lines")
  void tripleRoundRobinCycleLogs() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 9, a, b, c, d);
    TeamScheduleResult result = generate(league(div));

    assertInstanceOf(TeamScheduleResult.Success.class, result);
    List<String> logs = ((TeamScheduleResult.Success) result).cycleLogs();

    assertEquals(3, logs.size());
    assertTrue(logs.get(0).startsWith("Cycle 1 complete:"), logs.get(0));
    assertTrue(logs.get(1).startsWith("Cycle 2 complete:"), logs.get(1));
    assertTrue(logs.get(2).startsWith("Cycle 3 complete:"), logs.get(2));
  }

  // ---------------------------------------------------------------------------
  // Home/away correctness across cycles
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("4 teams target=6 (double RR) — each directed matchup appears exactly once")
  void doubleRREachDirectedMatchupAppearsExactlyOnce() {
    // If globalRound is working correctly, cycle 2 flips home/away for every pair vs cycle 1.
    // That means for each unordered pair (X, Y): exactly 1 game where X is home, 1 where Y is home.
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 6, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    List<UUID> teams = List.of(a.id(), b.id(), c.id(), d.id());
    for (int i = 0; i < teams.size(); i++) {
      for (int j = i + 1; j < teams.size(); j++) {
        UUID x = teams.get(i), y = teams.get(j);
        long xHome = games.stream()
            .filter(g -> g.homeTeamId().equals(x) && g.awayTeamId().equals(y))
            .count();
        long yHome = games.stream()
            .filter(g -> g.homeTeamId().equals(y) && g.awayTeamId().equals(x))
            .count();
        assertEquals(1, xHome, "(" + x + " home, " + y + " away) should appear exactly once");
        assertEquals(1, yHome, "(" + y + " home, " + x + " away) should appear exactly once");
      }
    }
  }

  @Test
  @DisplayName("4 teams target=6 (double RR, even N) — home/away imbalance is zero for every team")
  void evenNDoubleRRPerfectHomeAwayBalance() {
    // With globalRound, cycle 2 exactly reverses cycle 1 home/away → each team has equal home/away.
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 6, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    for (Team t : List.of(a, b, c, d)) {
      long home = games.stream().filter(g -> g.homeTeamId().equals(t.id())).count();
      long away = games.stream().filter(g -> g.awayTeamId().equals(t.id())).count();
      assertEquals(0, Math.abs(home - away),
          "Team " + t.name() + " should have equal home/away in double RR: home=" + home + " away=" + away);
    }
  }

  @Test
  @DisplayName("2 teams target=6 — home/away alternates perfectly across all 6 cycles")
  void twoTeamsManyCyclesAlternatingHomeAway() {
    // N=2, rotation is a no-op (only one matchup exists). globalRound must still alternate.
    Team a = team("A"), b = team("B");
    Division div = division("Majors", 6, a, b);
    List<TeamGame> games = successGames(generate(league(div)));

    assertEquals(6, games.size());
    long aHome = games.stream().filter(g -> g.homeTeamId().equals(a.id())).count();
    long bHome = games.stream().filter(g -> g.homeTeamId().equals(b.id())).count();
    assertEquals(3, aHome, "A should be home in exactly 3 of 6 games");
    assertEquals(3, bHome, "B should be home in exactly 3 of 6 games");
  }

  // ---------------------------------------------------------------------------
  // Odd-N partial cycle log format
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("3 teams (odd N) target=3 (odd) — succeeds via E-3 top-up; N-1 teams at T, 1 at T+1")
  void oddNOddTargetThreeSucceeds() {
    // N=3, T=3: fullCycles=1, remainder=1. After partial: A=2, B=3, C=3.
    // E-3 top-up: A paired with min-H2H opponent (B, tie-broken by div.teams() order).
    // Result: A=3, B=4, C=3. Exactly 1 team at T+1; unavoidable (N×T=9 is odd).
    Team a = team("A"), b = team("B"), c = team("C");
    Division div = division("Majors", 3, a, b, c);
    List<TeamGame> games = successGames(generate(league(div)));

    assertEquals(3, gamesPlayedBy(games, a.id()), "A should play exactly 3 games");
    assertEquals(4, gamesPlayedBy(games, b.id()), "B should play 4 games (top-up opponent)");
    assertEquals(3, gamesPlayedBy(games, c.id()), "C should play exactly 3 games");
  }

  @Test
  @DisplayName("3 teams (odd N) target=3 — partial-cycle log reflects post-top-up counts")
  void oddNOddTargetPartialCycleLogReflectsTopUpCounts() {
    // Log is emitted after E-3 top-up, so it must show B at 4 (not 3).
    Team a = team("A"), b = team("B"), c = team("C");
    Division div = division("Majors", 3, a, b, c);
    TeamScheduleResult result = generate(league(div));

    assertInstanceOf(TeamScheduleResult.Success.class, result);
    List<String> logs = ((TeamScheduleResult.Success) result).cycleLogs();
    assertEquals(2, logs.size(), "Expect cycle 1 log + partial-cycle log");
    String partialLog = logs.get(1);
    assertTrue(
        partialLog.startsWith("Partial cycle (1 of 3 rounds) complete:"),
        "Partial-cycle log prefix: " + partialLog);
    assertTrue(partialLog.contains("A 3"), "Log should show A at 3: " + partialLog);
    assertTrue(partialLog.contains("B 4"), "Log should show B at 4 (top-up opponent): " + partialLog);
    assertTrue(partialLog.contains("C 3"), "Log should show C at 3: " + partialLog);
  }

  @Test
  @DisplayName("5 teams (odd N) target=6 (even non-multiple) — all teams reach exactly 6 via E-2 make-up")
  void fiveTeamsEvenNonMultipleTargetAllReachTarget() {
    // N=5, N-1=4: fullCycles=1, remainder=2. Two short teams (A and D after partial rounds)
    // are paired with each other as the E-2 make-up game. All 5 teams end at 6.
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D"), e = team("E");
    Division div = division("Majors", 6, a, b, c, d, e);
    List<TeamGame> games = successGames(generate(league(div)));

    for (Team t : List.of(a, b, c, d, e)) {
      assertEquals(6, gamesPlayedBy(games, t.id()),
          "Team " + t.name() + " should play exactly 6 games");
    }
  }

  @Test
  @DisplayName("5 teams (odd N) target=7 (odd) — exactly 1 team plays T+1=8 games via E-3 top-up")
  void fiveTeamsOddTargetExactlyOneTeamAtTPlus1() {
    // N=5, T=7, N-1=4: fullCycles=1, remainder=3.
    // After partial (3 rounds): 3 teams short. E-2 pairs 1 pair → 1 team still short.
    // E-3 top-up: 1 remaining short team gets 1 extra game against min-H2H opponent.
    // Result: 4 teams at 7, 1 team at 8.
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D"), e = team("E");
    Division div = division("Majors", 7, a, b, c, d, e);
    List<TeamGame> games = successGames(generate(league(div)));

    long atTarget = List.of(a, b, c, d, e).stream()
        .filter(t -> gamesPlayedBy(games, t.id()) == 7)
        .count();
    long atTargetPlus1 = List.of(a, b, c, d, e).stream()
        .filter(t -> gamesPlayedBy(games, t.id()) == 8)
        .count();
    assertEquals(4L, atTarget, "Exactly 4 teams should be at T=7");
    assertEquals(1L, atTargetPlus1, "Exactly 1 team should be at T+1=8");
  }

  // ---------------------------------------------------------------------------
  // Log game-count numbers
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("cycle log lines embed the correct cumulative game count for each team")
  void cycleLogEmbedsCumulativeGameCounts() {
    // After cycle 1 (N=4, N-1=3): each team has played 3 games → log shows "{team} 3"
    // After cycle 2 (double RR): each team has played 6 games → log shows "{team} 6"
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 6, a, b, c, d);
    TeamScheduleResult result = generate(league(div));

    assertInstanceOf(TeamScheduleResult.Success.class, result);
    List<String> logs = ((TeamScheduleResult.Success) result).cycleLogs();

    assertEquals(2, logs.size());
    // Cycle 1 log must show each team at 3 games (alphabetical order: A 3, B 3, C 3, D 3)
    String afterCycle1 = logs.get(0);
    assertTrue(afterCycle1.contains("A 3"), "Cycle 1 log should show A at 3 games: " + afterCycle1);
    assertTrue(afterCycle1.contains("B 3"), "Cycle 1 log should show B at 3 games: " + afterCycle1);
    assertTrue(afterCycle1.contains("C 3"), "Cycle 1 log should show C at 3 games: " + afterCycle1);
    assertTrue(afterCycle1.contains("D 3"), "Cycle 1 log should show D at 3 games: " + afterCycle1);
    // Cycle 2 log must show each team at 6 games
    String afterCycle2 = logs.get(1);
    assertTrue(afterCycle2.contains("A 6"), "Cycle 2 log should show A at 6 games: " + afterCycle2);
    assertTrue(afterCycle2.contains("B 6"), "Cycle 2 log should show B at 6 games: " + afterCycle2);
    assertTrue(afterCycle2.contains("C 6"), "Cycle 2 log should show C at 6 games: " + afterCycle2);
    assertTrue(afterCycle2.contains("D 6"), "Cycle 2 log should show D at 6 games: " + afterCycle2);
  }

  // ---------------------------------------------------------------------------
  // Larger odd-N divisions
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("5 teams (odd N) target=4 — each pair appears exactly once (single RR)")
  void fiveTeamsSingleRREachPairOnce() {
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D"), e = team("E");
    Division div = division("Majors", 4, a, b, c, d, e);
    List<TeamGame> games = successGames(generate(league(div)));

    // N=5, single RR: N*(N-1)/2 = 10 games; each team plays 4 games.
    assertEquals(10, games.size());
    for (Team t : List.of(a, b, c, d, e)) {
      assertEquals(4, gamesPlayedBy(games, t.id()),
          "Each team should play exactly 4 games: " + t.name());
    }
    List<UUID> teams = List.of(a.id(), b.id(), c.id(), d.id(), e.id());
    for (int i = 0; i < teams.size(); i++) {
      for (int j = i + 1; j < teams.size(); j++) {
        long h2h = headToHead(games, teams.get(i), teams.get(j));
        assertEquals(1, h2h, "Each pair should appear exactly once in single RR; got " + h2h);
      }
    }
  }

  @Test
  @DisplayName("5 teams (odd N) target=8 (double RR) — each pair meets exactly twice")
  void fiveTeamsDoubleRREachPairMeetsTwice() {
    // N=5, N-1=4: fullCycles=2, remainder=0 → each pair meets exactly 2 times.
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D"), e = team("E");
    Division div = division("Majors", 8, a, b, c, d, e);
    List<TeamGame> games = successGames(generate(league(div)));

    List<UUID> teams = List.of(a.id(), b.id(), c.id(), d.id(), e.id());
    for (int i = 0; i < teams.size(); i++) {
      for (int j = i + 1; j < teams.size(); j++) {
        long h2h = headToHead(games, teams.get(i), teams.get(j));
        assertEquals(2, h2h, "Each pair should meet exactly twice in double RR; got " + h2h);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Game ID uniqueness
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("all game UUIDs are distinct even in a large schedule")
  void allGameUUIDsAreDistinct() {
    // 4 teams, target=12 (triple RR + 1 partial round) → 4*12/2 + partial = 26 games.
    // Distinct UUID assertion guards against any ID-generation collision.
    Team a = team("A"), b = team("B"), c = team("C"), d = team("D");
    Division div = division("Majors", 12, a, b, c, d);
    List<TeamGame> games = successGames(generate(league(div)));

    long distinctIds = games.stream().map(TeamGame::id).distinct().count();
    assertEquals(games.size(), distinctIds, "Every game must have a unique UUID");
  }

  // ---------------------------------------------------------------------------
  // Helpers for multi-cycle tests
  // ---------------------------------------------------------------------------

  private static long headToHead(List<TeamGame> games, UUID x, UUID y) {
    return games.stream()
        .filter(
            g ->
                (g.homeTeamId().equals(x) && g.awayTeamId().equals(y))
                    || (g.homeTeamId().equals(y) && g.awayTeamId().equals(x)))
        .count();
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
