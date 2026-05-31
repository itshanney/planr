package org.leagueplan.planr.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.FieldDivisionLock;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import org.leagueplan.planr.model.ScheduledGame;
import org.leagueplan.planr.model.Team;
import org.leagueplan.planr.model.TeamSchedule;

/**
 * Integration tests for SchedulerService.generate().
 *
 * <p>All tests use small leagues (2-4 teams per division) so the solver completes in milliseconds.
 * Fields are open every day between sunrise and sunset; no day-of-week windows exist in v2.
 */
class SchedulerServiceTest {

  private static final LocalDate SEASON_START = LocalDate.of(2026, 6, 1);
  // June–August: 91 days, 17 slots/day with 09:00-18:00 config and 30-min grid.
  private static final LocalDate SEASON_END = LocalDate.of(2026, 8, 31);
  // Short 7-day season used in infeasibility tests: narrow config (1 slot/day) × 7 days = 7 slots <
  // 12.
  private static final LocalDate SHORT_SEASON_END = LocalDate.of(2026, 6, 7);

  // Normal config: fields open 09:00–18:00 over the full season.
  private static final LeagueConfig CONFIG =
      new LeagueConfig(
          LocalTime.of(9, 0),
          LocalTime.of(18, 0),
          SEASON_START,
          SEASON_END,
          List.of(),
          List.of(),
          null,
          null,
          null,
          null);
  // Narrow config: fields open 09:00–10:00 → exactly 1 slot/day for 60-min games.
  // Season dates are set to SEASON_START/SEASON_END; generateShort() swaps in SHORT_SEASON_END.
  private static final LeagueConfig NARROW_CONFIG =
      new LeagueConfig(
          LocalTime.of(9, 0),
          LocalTime.of(10, 0),
          SEASON_START,
          SEASON_END,
          List.of(),
          List.of(),
          null,
          null,
          null,
          null);

  // ---------------------------------------------------------------------------
  // League builder helpers
  // ---------------------------------------------------------------------------

  private static Team team(String name) {
    return new Team(UUID.randomUUID(), name);
  }

  private static Division division(String name, int duration, Team... teams) {
    // sets target to 2*(N-1) so these integration tests produce N*(N-1) total games
    int target = 2 * Math.max(1, teams.length - 1);
    return new Division(
        UUID.randomUUID(), name, duration, target, List.of(teams), null, null, null, null, null);
  }

  private static Field field(String name) {
    return new Field(UUID.randomUUID(), name, null, List.of(), List.of(), List.of(), null);
  }

  private static League league(LeagueConfig config, List<Division> divisions, List<Field> fields) {
    League base = new League(4, config, divisions, fields, null, null, List.of(), List.of());
    TeamScheduleResult tsResult = new TeamScheduleService().generate(base);
    if (tsResult instanceof TeamScheduleResult.Failure f) {
      throw new IllegalStateException("Test setup: " + f.message());
    }
    TeamSchedule ts = ((TeamScheduleResult.Success) tsResult).schedule();
    return new League(4, config, divisions, fields, ts, null, List.of(), List.of());
  }

  /** Build a minimal 2-team league. 2 games required. */
  private static League twoTeamLeague() {
    Team t1 = team("Blue Jays");
    Team t2 = team("Cardinals");
    Division div = division("Majors", 60, t1, t2);
    Field f = field("Riverside Park");
    return league(CONFIG, List.of(div), List.of(f));
  }

  /** Build a 4-team league. 12 games (4×3) required. */
  private static League fourTeamLeague() {
    Team t1 = team("Blue Jays");
    Team t2 = team("Cardinals");
    Team t3 = team("Red Sox");
    Team t4 = team("Yankees");
    Division div = division("Majors", 60, t1, t2, t3, t4);
    Field f = field("Riverside Park");
    return league(CONFIG, List.of(div), List.of(f));
  }

  /** Build a 3-team league (odd count). 6 games (3×2) required. */
  private static League threeTeamLeague() {
    Team t1 = team("Blue Jays");
    Team t2 = team("Cardinals");
    Team t3 = team("Red Sox");
    Division div = division("Majors", 60, t1, t2, t3);
    Field f = field("Riverside Park");
    return league(CONFIG, List.of(div), List.of(f));
  }

  private ScheduleResult generate(League l) {
    return new SchedulerService().assign(l);
  }

  /**
   * Uses the short (7-day) season — triggers pre-solve infeasibility for 4+ teams with narrow
   * config.
   */
  private ScheduleResult generateShort(League l) {
    LeagueConfig shortConfig =
        new LeagueConfig(
            l.config().sunriseTime(),
            l.config().sunsetTime(),
            SEASON_START,
            SHORT_SEASON_END,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null);
    League shortLeague =
        new League(
            5,
            shortConfig,
            l.divisions(),
            l.fields(),
            l.teamSchedule(),
            null,
            List.of(),
            List.of());
    return new SchedulerService().assign(shortLeague);
  }

  // ---------------------------------------------------------------------------
  // Fixture count correctness
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("generates exactly N*(N-1) games for a 2-team division")
  void twoTeamDivisionProducesTwoGames() {
    ScheduleResult result = generate(twoTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    assertEquals(2, ((ScheduleResult.Success) result).games().size());
  }

  @Test
  @DisplayName("generates exactly N*(N-1) games for a 4-team division")
  void fourTeamDivisionProducesTwelveGames() {
    ScheduleResult result = generate(fourTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    assertEquals(12, ((ScheduleResult.Success) result).games().size());
  }

  @Test
  @DisplayName("generates exactly N*(N-1) games for a 3-team division (odd count)")
  void threeTeamDivisionProducesSixGames() {
    ScheduleResult result = generate(threeTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    assertEquals(6, ((ScheduleResult.Success) result).games().size());
  }

  // ---------------------------------------------------------------------------
  // Home/away balance: each unordered pair appears; home/away counts are balanced
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("each unordered team pair appears at least once in the schedule")
  void eachUnorderedTeamPairAppearsAtLeastOnce() {
    ScheduleResult result = generate(fourTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

    Set<UUID> teamIds = new HashSet<>();
    games.forEach(
        g -> {
          teamIds.add(g.homeTeamId());
          teamIds.add(g.awayTeamId());
        });
    List<UUID> teamList = new ArrayList<>(teamIds);

    for (int i = 0; i < teamList.size(); i++) {
      for (int j = i + 1; j < teamList.size(); j++) {
        UUID x = teamList.get(i), y = teamList.get(j);
        long count =
            games.stream()
                .filter(
                    g ->
                        (g.homeTeamId().equals(x) && g.awayTeamId().equals(y))
                            || (g.homeTeamId().equals(y) && g.awayTeamId().equals(x)))
                .count();
        assertTrue(
            count >= 1,
            "Unordered pair should appear at least once; got "
                + count
                + " for ("
                + x
                + ", "
                + y
                + ")");
      }
    }
  }

  @Test
  @DisplayName(
      "each team's home and away game counts differ by at most N-1 (fill reduces initial RR skew)")
  void homeAndAwayCountsDifferByAtMostNMinus1() {
    // 4 teams → at most 3 imbalance. Verifies balance-tracking is active:
    // without it, some teams could be home/away for all fill games (imbalance up to 6).
    int n = 4;
    ScheduleResult result = generate(fourTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

    Map<UUID, int[]> counts = new HashMap<>();
    for (ScheduledGame g : games) {
      counts.computeIfAbsent(g.homeTeamId(), k -> new int[2])[0]++;
      counts.computeIfAbsent(g.awayTeamId(), k -> new int[2])[1]++;
    }
    for (Map.Entry<UUID, int[]> entry : counts.entrySet()) {
      int home = entry.getValue()[0];
      int away = entry.getValue()[1];
      assertTrue(
          Math.abs(home - away) <= n - 1,
          "Home/away imbalance for team " + entry.getKey() + ": home=" + home + " away=" + away);
    }
  }

  // ---------------------------------------------------------------------------
  // Field conflict constraint (C2)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("no two games on the same field overlap (respects the configured field buffer)")
  void noFieldConflictsIncludingBuffer() {
    ScheduleResult result = generate(fourTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

    // fourTeamLeague() uses CONFIG which has null fieldBufferMinutes → default = 0
    int buffer = SchedulerService.DEFAULT_FIELD_BUFFER_MINUTES;

    for (int i = 0; i < games.size(); i++) {
      ScheduledGame a = games.get(i);
      for (int j = i + 1; j < games.size(); j++) {
        ScheduledGame b = games.get(j);
        if (!a.fieldId().equals(b.fieldId()) || !a.date().equals(b.date())) continue;
        int aStart = toMinutes(a.startTime());
        int aEnd = aStart + a.gameDurationMinutes() + buffer;
        int bStart = toMinutes(b.startTime());
        int bEnd = bStart + b.gameDurationMinutes() + buffer;
        assertFalse(
            aStart < bEnd && bStart < aEnd,
            "Field conflict: games "
                + i
                + " and "
                + j
                + " on "
                + a.fieldName()
                + " at "
                + a.date());
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Team double-booking constraint (C3)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("no team plays more than once on the same calendar day")
  void noTeamPlaysMoreThanOncePerDay() {
    ScheduleResult result = generate(fourTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

    for (int i = 0; i < games.size(); i++) {
      ScheduledGame a = games.get(i);
      for (int j = i + 1; j < games.size(); j++) {
        ScheduledGame b = games.get(j);
        if (!a.date().equals(b.date())) continue;
        boolean teamOverlap =
            a.homeTeamId().equals(b.homeTeamId())
                || a.homeTeamId().equals(b.awayTeamId())
                || a.awayTeamId().equals(b.homeTeamId())
                || a.awayTeamId().equals(b.awayTeamId());
        assertFalse(
            teamOverlap, "Team double-booked on " + a.date() + ": games " + i + " and " + j);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Season date boundary
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("all games fall within the configured season date range")
  void allGamesFallWithinSeasonDates() {
    ScheduleResult result = generate(twoTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

    for (ScheduledGame g : games) {
      assertFalse(
          g.date().isBefore(SEASON_START),
          "Game date " + g.date() + " is before season start " + SEASON_START);
      assertFalse(
          g.date().isAfter(SEASON_END),
          "Game date " + g.date() + " is after season end " + SEASON_END);
    }
  }

  // ---------------------------------------------------------------------------
  // Multi-division schedules
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("schedules all fixtures across two divisions independently")
  void schedulesAllFixturesAcrossTwoDivisions() {
    Team m1 = team("Blue Jays"), m2 = team("Cardinals");
    Team a1 = team("Red Sox"), a2 = team("Yankees");
    Division majors = division("Majors", 60, m1, m2);
    Division aaa = division("AAA", 60, a1, a2);
    Field f = field("Riverside Park");

    League l = league(CONFIG, List.of(majors, aaa), List.of(f));
    ScheduleResult result = generate(l);

    assertInstanceOf(ScheduleResult.Success.class, result);
    List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

    // 2 teams × 2 divisions = 2 games each = 4 total
    assertEquals(4, games.size());

    long majorsCount = games.stream().filter(g -> g.divisionName().equals("Majors")).count();
    long aaaCount = games.stream().filter(g -> g.divisionName().equals("AAA")).count();
    assertEquals(2, majorsCount);
    assertEquals(2, aaaCount);
  }

  // ---------------------------------------------------------------------------
  // Infeasibility detection
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("returns partial Success (not Failure) when there are fewer slots than fixtures")
  void returnsFailureWhenInsufficientSlots() {
    // 4 teams → 12 games needed. Narrow config (09:00-10:00) → 1 slot/day.
    // Short season (7 days) → 7 slots < 12 → solver assigns as many as possible.
    Team t1 = team("A"), t2 = team("B"), t3 = team("C"), t4 = team("D");
    Division div = division("Majors", 60, t1, t2, t3, t4);
    Field f = field("Riverside Park");
    League l = league(NARROW_CONFIG, List.of(div), List.of(f));

    ScheduleResult result = generateShort(l);
    assertInstanceOf(ScheduleResult.Success.class, result);
    ScheduleResult.Success success = (ScheduleResult.Success) result;
    assertFalse(success.targetMet(), "Should be partial when slots < fixtures");
    assertTrue(success.games().size() < 12, "Fewer than 12 games should be assigned");
    assertTrue(success.games().size() > 0, "At least some games should be assigned");
  }

  @Test
  @DisplayName("partial success DivisionSummary names the deficit division with correct counts")
  void failureMessageNamesInfeasibleDivision() {
    Team t1 = team("A"), t2 = team("B"), t3 = team("C"), t4 = team("D");
    Division div = division("Majors", 60, t1, t2, t3, t4);
    Field f = field("Riverside Park");
    League l = league(NARROW_CONFIG, List.of(div), List.of(f));

    ScheduleResult result = generateShort(l);
    assertInstanceOf(ScheduleResult.Success.class, result);
    ScheduleResult.Success success = (ScheduleResult.Success) result;
    assertFalse(success.targetMet());

    DivisionSummary majorsSummary =
        success.divisionSummaries().stream()
            .filter(s -> s.divisionName().equals("Majors"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Majors summary missing"));
    assertEquals(12, majorsSummary.gamesRequested(), "Majors should have 12 games requested");
    assertTrue(
        majorsSummary.gamesAssigned() < 12,
        "Majors should have fewer than 12 games assigned; got: " + majorsSummary.gamesAssigned());
  }

  @Test
  @DisplayName("both divisions appear in summaries when one has a slot deficit")
  void feasibleDivisionListedAsOkInFailureDiagnostic() {
    // Majors: 4 teams → 12 games, 7 slots available → partial
    // AAA:    2 teams → 2 games, 7 slots available → may be target-met
    Team m1 = team("A"), m2 = team("B"), m3 = team("C"), m4 = team("D");
    Team a1 = team("E"), a2 = team("F");
    Division majors = division("Majors", 60, m1, m2, m3, m4);
    Division aaa = division("AAA", 60, a1, a2);
    Field f = field("Narrow");
    League l = league(NARROW_CONFIG, List.of(majors, aaa), List.of(f));

    ScheduleResult result = generateShort(l);
    assertInstanceOf(ScheduleResult.Success.class, result);
    ScheduleResult.Success success = (ScheduleResult.Success) result;
    assertFalse(success.targetMet());

    List<DivisionSummary> summaries = success.divisionSummaries();
    assertEquals(2, summaries.size(), "Should have summaries for both divisions");

    DivisionSummary majorsSummary =
        summaries.stream().filter(s -> s.divisionName().equals("Majors")).findFirst().orElseThrow();
    assertTrue(majorsSummary.gamesAssigned() < 12, "Majors should be partial");

    assertTrue(
        summaries.stream().anyMatch(s -> s.divisionName().equals("AAA")),
        "AAA summary should be present");
  }

  // ---------------------------------------------------------------------------
  // DivisionSummary accuracy
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("targetMet is true when all games are assigned in a full-season league")
  void targetMetIsTrueWhenAllGamesAssigned() {
    ScheduleResult result = generate(twoTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    assertTrue(((ScheduleResult.Success) result).targetMet());
  }

  @Test
  @DisplayName("divisionSummaries contains one entry per division in a multi-division league")
  void divisionSummariesPresentForEachDivision() {
    Team m1 = team("Blue Jays"), m2 = team("Cardinals");
    Team a1 = team("Red Sox"), a2 = team("Yankees");
    Division majors = division("Majors", 60, m1, m2);
    Division aaa = division("AAA", 60, a1, a2);
    Field f = field("Riverside Park");
    League l = league(CONFIG, List.of(majors, aaa), List.of(f));

    ScheduleResult result = generate(l);
    assertInstanceOf(ScheduleResult.Success.class, result);
    List<DivisionSummary> summaries = ((ScheduleResult.Success) result).divisionSummaries();
    assertEquals(2, summaries.size());
    assertTrue(summaries.stream().anyMatch(s -> s.divisionName().equals("Majors")));
    assertTrue(summaries.stream().anyMatch(s -> s.divisionName().equals("AAA")));
  }

  @Test
  @DisplayName("DivisionSummary.gamesRequested equals the total fixture count for the division")
  void divisionSummaryGamesRequestedMatchesFixtureCount() {
    // 4-team div: helper sets target=6 → TeamScheduleService generates 12 total games
    ScheduleResult result = generate(fourTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    DivisionSummary summary = ((ScheduleResult.Success) result).divisionSummaries().get(0);
    assertEquals(12, summary.gamesRequested());
  }

  @Test
  @DisplayName(
      "DivisionSummary.gamesAssigned matches the count of ScheduledGames for that division")
  void divisionSummaryGamesAssignedEqualsActualAssignedCount() {
    Team m1 = team("Blue Jays"), m2 = team("Cardinals");
    Team a1 = team("Red Sox"), a2 = team("Yankees");
    Division majors = division("Majors", 60, m1, m2);
    Division aaa = division("AAA", 60, a1, a2);
    Field f = field("Riverside Park");
    League l = league(CONFIG, List.of(majors, aaa), List.of(f));

    ScheduleResult result = generate(l);
    assertInstanceOf(ScheduleResult.Success.class, result);
    ScheduleResult.Success success = (ScheduleResult.Success) result;

    for (DivisionSummary summary : success.divisionSummaries()) {
      long actual =
          success.games().stream()
              .filter(g -> g.divisionName().equals(summary.divisionName()))
              .count();
      assertEquals(
          summary.gamesAssigned(),
          (int) actual,
          "gamesAssigned mismatch for " + summary.divisionName());
    }
  }

  @Test
  @DisplayName(
      "DivisionSummary.slotsAvailable matches estimateAvailableSlots() for the same config")
  void slotsAvailableMatchesEstimateAvailableSlots() {
    League l = twoTeamLeague();
    ScheduleResult result = generate(l);
    assertInstanceOf(ScheduleResult.Success.class, result);
    DivisionSummary summary = ((ScheduleResult.Success) result).divisionSummaries().get(0);

    Division div = l.divisions().get(0);
    int estimate =
        new SchedulerService().estimateAvailableSlots(l, div.id(), div.gameDurationMinutes());
    assertEquals(estimate, summary.slotsAvailable());
  }

  @Test
  @DisplayName("partial: 4-team div with 1-day narrow season gets exactly 1 game assigned")
  void partialSuccessWhenSingleSlotAvailableForMultipleFixtures() {
    // 1-day season × 09:00-10:00 window = 1 slot; 4-team div = 12 fixtures → 1 game assigned
    LeagueConfig oneDayConfig =
        new LeagueConfig(
            LocalTime.of(9, 0),
            LocalTime.of(10, 0),
            SEASON_START,
            SEASON_START,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null);
    Team t1 = team("A"), t2 = team("B"), t3 = team("C"), t4 = team("D");
    Division div = division("Majors", 60, t1, t2, t3, t4);
    Field f = field("Riverside Park");
    League l = league(oneDayConfig, List.of(div), List.of(f));

    ScheduleResult result = new SchedulerService().assign(l);
    assertInstanceOf(ScheduleResult.Success.class, result);
    ScheduleResult.Success success = (ScheduleResult.Success) result;
    assertFalse(success.targetMet());
    assertEquals(1, success.games().size(), "1 slot available → exactly 1 game assigned");
    DivisionSummary summary = success.divisionSummaries().get(0);
    assertEquals(12, summary.gamesRequested());
    assertEquals(1, summary.gamesAssigned());
    assertEquals(1, summary.slotsAvailable());
  }

  @Test
  @DisplayName("division with no fitting slots gets 0 games assigned; other division is target-met")
  void divisionWithNoFittingSlotGetsZeroGamesAssigned() {
    // T-Ball: 90-min games in 09:00-10:00 window (60 min) → 0 slots, 0 games
    // AAA:    60-min games in same window → 1 slot/day × 91 days → target-met
    Team tball1 = team("T1"), tball2 = team("T2");
    Team aaa1 = team("F1"), aaa2 = team("F2");
    Division tBall = division("T-Ball", 90, tball1, tball2);
    Division aaa = division("AAA", 60, aaa1, aaa2);
    Field f = field("Riverside Park");
    League l = league(NARROW_CONFIG, List.of(tBall, aaa), List.of(f));

    ScheduleResult result = generate(l);
    assertInstanceOf(ScheduleResult.Success.class, result);
    ScheduleResult.Success success = (ScheduleResult.Success) result;
    assertFalse(success.targetMet());

    DivisionSummary tBallSummary =
        success.divisionSummaries().stream()
            .filter(s -> s.divisionName().equals("T-Ball"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        0, tBallSummary.slotsAvailable(), "90-min game does not fit in 60-min window → 0 slots");
    assertEquals(0, tBallSummary.gamesAssigned());

    DivisionSummary aaaSummary =
        success.divisionSummaries().stream()
            .filter(s -> s.divisionName().equals("AAA"))
            .findFirst()
            .orElseThrow();
    assertTrue(aaaSummary.targetMet());
  }

  @Test
  @DisplayName("divisionSummaries are sorted alphabetically by division name")
  void divisionSummariesAreSortedAlphabetically() {
    Team z1 = team("ZA"), z2 = team("ZB");
    Team a1 = team("AA"), a2 = team("AB");
    Team m1 = team("MA"), m2 = team("MB");
    Division zebra =
        new Division(UUID.randomUUID(), "Zebra", 60, 2, List.of(z1, z2), null, null, null, null, null);
    Division alpha =
        new Division(UUID.randomUUID(), "Alpha", 60, 2, List.of(a1, a2), null, null, null, null, null);
    Division majors =
        new Division(UUID.randomUUID(), "Majors", 60, 2, List.of(m1, m2), null, null, null, null, null);
    Field f = field("Riverside Park");
    League l = league(CONFIG, List.of(zebra, alpha, majors), List.of(f));

    ScheduleResult result = generate(l);
    assertInstanceOf(ScheduleResult.Success.class, result);
    List<DivisionSummary> summaries = ((ScheduleResult.Success) result).divisionSummaries();
    assertEquals(3, summaries.size());
    assertEquals("Alpha", summaries.get(0).divisionName());
    assertEquals("Majors", summaries.get(1).divisionName());
    assertEquals("Zebra", summaries.get(2).divisionName());
  }

  // ---------------------------------------------------------------------------
  // Result metadata
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("success result has overridden=false on all games")
  void allGamesHaveOverriddenFalse() {
    ScheduleResult result = generate(twoTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    ((ScheduleResult.Success) result)
        .games()
        .forEach(
            g -> assertFalse(g.overridden(), "Freshly generated game should not be overridden"));
  }

  @Test
  @DisplayName("games are sorted by date then start time then field name")
  void gamesAreSortedByDateThenStartThenField() {
    ScheduleResult result = generate(fourTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

    for (int i = 1; i < games.size(); i++) {
      ScheduledGame prev = games.get(i - 1);
      ScheduledGame curr = games.get(i);
      int cmp = prev.date().compareTo(curr.date());
      if (cmp == 0) cmp = prev.startTime().compareTo(curr.startTime());
      if (cmp == 0) cmp = prev.fieldName().compareTo(curr.fieldName());
      assertTrue(
          cmp <= 0,
          "Games not sorted at index "
              + i
              + ": "
              + prev.date()
              + " "
              + prev.startTime()
              + " vs "
              + curr.date()
              + " "
              + curr.startTime());
    }
  }

  @Test
  @DisplayName("denormalized team and field names match league configuration")
  void denormalizedNamesMatchLeagueConfiguration() {
    ScheduleResult result = generate(twoTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

    for (ScheduledGame g : games) {
      assertTrue(
          g.homeTeamName().equals("Blue Jays") || g.homeTeamName().equals("Cardinals"),
          "Unexpected home team name: " + g.homeTeamName());
      assertTrue(
          g.awayTeamName().equals("Blue Jays") || g.awayTeamName().equals("Cardinals"),
          "Unexpected away team name: " + g.awayTeamName());
      assertEquals("Riverside Park", g.fieldName());
      assertEquals("Majors", g.divisionName());
    }
  }

  // ---------------------------------------------------------------------------
  // C4: Max games per week (hard cap)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("C4: no team exceeds the configured max-games-per-week cap")
  void noTeamExceedsWeekCap() {
    // Cap = 1 game/week; 4-team div → needs many weeks to spread games
    LeagueConfig capConfig =
        new LeagueConfig(
            LocalTime.of(9, 0),
            LocalTime.of(18, 0),
            SEASON_START,
            SEASON_END,
            List.of(),
            List.of(),
            1,
            null,
            null,
            null);

    Team t1 = team("Blue Jays"), t2 = team("Cardinals");
    Team t3 = team("Red Sox"), t4 = team("Yankees");
    Division div = division("Majors", 60, t1, t2, t3, t4);
    Field f = field("Riverside Park");
    League l = league(capConfig, List.of(div), List.of(f));

    ScheduleResult result = new SchedulerService().assign(l);
    assertInstanceOf(ScheduleResult.Success.class, result);
    List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

    // Collect per-team ISO-week game counts
    Map<String, Integer> weekCounts = new HashMap<>();
    for (ScheduledGame g : games) {
      int isoWeek = g.date().get(WeekFields.ISO.weekOfWeekBasedYear());
      int isoYear = g.date().get(WeekFields.ISO.weekBasedYear());
      String homeKey = g.homeTeamId() + "|" + isoYear + "W" + isoWeek;
      String awayKey = g.awayTeamId() + "|" + isoYear + "W" + isoWeek;
      weekCounts.merge(homeKey, 1, Integer::sum);
      weekCounts.merge(awayKey, 1, Integer::sum);
    }

    for (Map.Entry<String, Integer> entry : weekCounts.entrySet()) {
      assertTrue(
          entry.getValue() <= 1,
          "Team exceeded cap of 1 game/week in week "
              + entry.getKey()
              + ": got "
              + entry.getValue());
    }
  }

  @Test
  @DisplayName("C4: default cap of 2 games/week is respected when no cap is configured")
  void defaultWeekCapIsRespected() {
    // CONFIG has null maxGamesPerWeek → default of 2 applies
    ScheduleResult result = generate(fourTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

    Map<String, Integer> weekCounts = new HashMap<>();
    for (ScheduledGame g : games) {
      int isoWeek = g.date().get(WeekFields.ISO.weekOfWeekBasedYear());
      int isoYear = g.date().get(WeekFields.ISO.weekBasedYear());
      String homeKey = g.homeTeamId() + "|" + isoYear + "W" + isoWeek;
      String awayKey = g.awayTeamId() + "|" + isoYear + "W" + isoWeek;
      weekCounts.merge(homeKey, 1, Integer::sum);
      weekCounts.merge(awayKey, 1, Integer::sum);
    }

    for (Map.Entry<String, Integer> entry : weekCounts.entrySet()) {
      assertTrue(
          entry.getValue() <= SchedulerService.DEFAULT_MAX_GAMES_PER_WEEK,
          "Team exceeded default cap in week " + entry.getKey() + ": got " + entry.getValue());
    }
  }

  // ---------------------------------------------------------------------------
  // C5: Minimum rest days between games
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("C5: no team plays on back-to-back days when rest-days is set to 1")
  void noTeamPlaysOnConsecutiveDays() {
    // CONFIG.minRestDays = null → default of 1 applies; verify explicitly
    ScheduleResult result = generate(fourTeamLeague());
    assertInstanceOf(ScheduleResult.Success.class, result);
    List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

    for (ScheduledGame a : games) {
      for (ScheduledGame b : games) {
        if (a == b) continue;
        boolean sameTeam =
            a.homeTeamId().equals(b.homeTeamId())
                || a.homeTeamId().equals(b.awayTeamId())
                || a.awayTeamId().equals(b.homeTeamId())
                || a.awayTeamId().equals(b.awayTeamId());
        if (!sameTeam) continue;
        long daysBetween = Math.abs(a.date().toEpochDay() - b.date().toEpochDay());
        assertTrue(
            daysBetween == 0 || daysBetween >= SchedulerService.DEFAULT_MIN_REST_DAYS + 1,
            "Team played with fewer than "
                + SchedulerService.DEFAULT_MIN_REST_DAYS
                + " rest day(s) between "
                + a.date()
                + " and "
                + b.date());
      }
    }
  }

  @Test
  @DisplayName(
      "C5: rest-days=0 allows same-day double-headers (C3 prevents same-day games; rest=0 is"
          + " satisfied)")
  void restDaysZeroDoesNotBlockSameDayGames() {
    // With restDays=0, no rest constraint beyond C3. Should still produce a valid schedule.
    LeagueConfig noRestConfig =
        new LeagueConfig(
            LocalTime.of(9, 0),
            LocalTime.of(18, 0),
            SEASON_START,
            SEASON_END,
            List.of(),
            List.of(),
            null,
            0,
            null,
            null);

    Team t1 = team("Blue Jays"), t2 = team("Cardinals");
    Division div = division("Majors", 60, t1, t2);
    Field f = field("Riverside Park");
    League l = league(noRestConfig, List.of(div), List.of(f));

    ScheduleResult result = new SchedulerService().assign(l);
    assertInstanceOf(ScheduleResult.Success.class, result);
    assertTrue(
        ((ScheduleResult.Success) result).targetMet(),
        "Full schedule should be assigned when rest-days=0 with ample slots");
  }

  // ---------------------------------------------------------------------------
  // Field division lock (slot filtering)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("field lock: locked field contributes 0 slots to non-owning division")
  void lockedFieldContributesZeroSlotsToOtherDivision() {
    // One field locked entirely to Majors; T-Ball gets no slots from that field
    Team t1 = team("Blue Jays"), t2 = team("Cardinals");
    Team tb1 = team("T1"), tb2 = team("T2");
    Division majors = division("Majors", 60, t1, t2);
    Division tBall = division("T-Ball", 60, tb1, tb2);

    UUID majorsId = majors.id();
    FieldDivisionLock lock = new FieldDivisionLock(majorsId, SEASON_START, SEASON_END);
    Field lockedField =
        new Field(UUID.randomUUID(), "Field A", null, List.of(), List.of(), List.of(lock), null);

    League l = league(CONFIG, List.of(majors, tBall), List.of(lockedField));

    int tBallSlots = new SchedulerService().estimateAvailableSlots(l, tBall.id(), 60);
    assertEquals(0, tBallSlots, "T-Ball should get 0 slots from a field locked entirely to Majors");
  }

  @Test
  @DisplayName("field lock: locked field still contributes slots to the owning division")
  void lockedFieldContributesSlotsToDivisionThatOwnsLock() {
    Team t1 = team("Blue Jays"), t2 = team("Cardinals");
    Division majors = division("Majors", 60, t1, t2);
    UUID majorsId = majors.id();

    FieldDivisionLock lock = new FieldDivisionLock(majorsId, SEASON_START, SEASON_END);
    Field lockedField =
        new Field(UUID.randomUUID(), "Field A", null, List.of(), List.of(), List.of(lock), null);

    League l = league(CONFIG, List.of(majors), List.of(lockedField));
    int slotsWithLock = new SchedulerService().estimateAvailableSlots(l, majorsId, 60);

    // Baseline: same field with no lock
    Field unlockedField =
        new Field(UUID.randomUUID(), "Field A", null, List.of(), List.of(), List.of(), null);
    League baselineLeague = league(CONFIG, List.of(majors), List.of(unlockedField));
    int slotsWithout = new SchedulerService().estimateAvailableSlots(baselineLeague, majorsId, 60);

    assertEquals(
        slotsWithout,
        slotsWithLock,
        "Owning division should see the same slots whether or not a lock is present");
  }

  @Test
  @DisplayName("field lock: games are only assigned to the locked field for the owning division")
  void lockedFieldOnlyHostsOwningDivisionGames() {
    // Two divisions; field locked to Majors; second unlocked field available to both
    Team m1 = team("Blue Jays"), m2 = team("Cardinals");
    Team a1 = team("Red Sox"), a2 = team("Yankees");
    Division majors = division("Majors", 60, m1, m2);
    Division aaa = division("AAA", 60, a1, a2);

    UUID majorsId = majors.id();
    FieldDivisionLock lock = new FieldDivisionLock(majorsId, SEASON_START, SEASON_END);
    Field lockedField =
        new Field(UUID.randomUUID(), "Majors Field", null, List.of(), List.of(), List.of(lock), null);
    Field unlockedField =
        new Field(UUID.randomUUID(), "Open Field", null, List.of(), List.of(), List.of(), null);

    League l = league(CONFIG, List.of(majors, aaa), List.of(lockedField, unlockedField));
    ScheduleResult result = new SchedulerService().assign(l);
    assertInstanceOf(ScheduleResult.Success.class, result);

    List<ScheduledGame> games = ((ScheduleResult.Success) result).games();
    UUID lockedFieldId = lockedField.id();
    UUID aaaId = aaa.id();

    for (ScheduledGame g : games) {
      if (g.fieldId().equals(lockedFieldId)) {
        assertFalse(
            g.divisionName().equals("AAA"),
            "AAA game should never be assigned to Majors Field (locked to Majors)");
      }
    }
  }

  @Test
  @DisplayName(
      "field lock: partial-period lock — non-owning division gets slots outside lock range")
  void partialPeriodLockLeavesRestOfSeasonOpen() {
    // Lock applies only to June; July onward is open to all divisions
    Team t1 = team("Blue Jays"), t2 = team("Cardinals");
    Team a1 = team("Red Sox"), a2 = team("Yankees");
    Division majors = division("Majors", 60, t1, t2);
    Division aaa = division("AAA", 60, a1, a2);

    UUID majorsId = majors.id();
    LocalDate lockEnd = LocalDate.of(2026, 6, 30);
    FieldDivisionLock lock = new FieldDivisionLock(majorsId, SEASON_START, lockEnd);
    Field lockedField =
        new Field(UUID.randomUUID(), "Field A", null, List.of(), List.of(), List.of(lock), null);

    League l = league(CONFIG, List.of(majors, aaa), List.of(lockedField));

    int aaaSlots = new SchedulerService().estimateAvailableSlots(l, aaa.id(), 60);
    assertTrue(aaaSlots > 0, "AAA should get slots from Field A outside the June lock period");

    // Compare: full-season lock gives 0 for AAA
    FieldDivisionLock fullLock = new FieldDivisionLock(majorsId, SEASON_START, SEASON_END);
    Field fullyLockedField =
        new Field(UUID.randomUUID(), "Field A", null, List.of(), List.of(), List.of(fullLock), null);
    League fullLeague = league(CONFIG, List.of(majors, aaa), List.of(fullyLockedField));
    int aaaSlotsFullLock = new SchedulerService().estimateAvailableSlots(fullLeague, aaa.id(), 60);
    assertEquals(0, aaaSlotsFullLock);

    assertTrue(
        aaaSlots > aaaSlotsFullLock,
        "Partial lock should yield more slots for AAA than a full-season lock");
  }

  // ---------------------------------------------------------------------------
  // E1: Bidirectional pinning — owning division is confined to its locked field
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "E1: all owning division games are assigned to the locked field, not the unlocked one")
  void owningDivisionGamesOnlyOnLockedField() {
    Team m1 = team("Blue Jays"), m2 = team("Cardinals");
    Division majors = division("Majors", 60, m1, m2);
    UUID majorsId = majors.id();

    FieldDivisionLock lock = new FieldDivisionLock(majorsId, SEASON_START, SEASON_END);
    Field lockedField =
        new Field(UUID.randomUUID(), "Locked Field", null, List.of(), List.of(), List.of(lock), null);
    Field unlockedField =
        new Field(UUID.randomUUID(), "Unlocked Field", null, List.of(), List.of(), List.of(), null);

    League l = league(CONFIG, List.of(majors), List.of(lockedField, unlockedField));
    ScheduleResult result = new SchedulerService().assign(l);
    assertInstanceOf(ScheduleResult.Success.class, result);
    assertTrue(
        ((ScheduleResult.Success) result).targetMet(),
        "Locked field has ample capacity — full target should be met");

    UUID lockedFieldId = lockedField.id();
    for (ScheduledGame g : ((ScheduleResult.Success) result).games()) {
      assertEquals(
          lockedFieldId,
          g.fieldId(),
          "Game on "
              + g.date()
              + " landed on \""
              + g.fieldName()
              + "\" — owning division must be confined to the locked field");
    }
  }

  @Test
  @DisplayName("E1: adding an unlocked field does not increase slot count for a pinned division")
  void addingUnlockedFieldDoesNotIncreaseSlotCountForPinnedDivision() {
    Team t1 = team("Blue Jays"), t2 = team("Cardinals");
    Division majors = division("Majors", 60, t1, t2);
    UUID majorsId = majors.id();

    FieldDivisionLock lock = new FieldDivisionLock(majorsId, SEASON_START, SEASON_END);
    Field lockedField =
        new Field(UUID.randomUUID(), "Locked", null, List.of(), List.of(), List.of(lock), null);
    Field unlockedField =
        new Field(UUID.randomUUID(), "Unlocked", null, List.of(), List.of(), List.of(), null);

    League oneFieldLeague = league(CONFIG, List.of(majors), List.of(lockedField));
    League twoFieldLeague = league(CONFIG, List.of(majors), List.of(lockedField, unlockedField));

    int slotsOneField = new SchedulerService().estimateAvailableSlots(oneFieldLeague, majorsId, 60);
    int slotsTwoFields =
        new SchedulerService().estimateAvailableSlots(twoFieldLeague, majorsId, 60);

    assertEquals(
        slotsOneField,
        slotsTwoFields,
        "Unlocked field must not contribute slots to a division pinned to a different field");
  }

  @Test
  @DisplayName(
      "E1: pinning is released outside the lock date range — both fields contribute in July")
  void pinningIsReleasedOutsideLockDateRange() {
    Team t1 = team("Blue Jays"), t2 = team("Cardinals");
    Division majors = division("Majors", 60, t1, t2);
    UUID majorsId = majors.id();

    LocalDate juneEnd = LocalDate.of(2026, 6, 30);
    LocalDate julyStart = LocalDate.of(2026, 7, 1);
    LocalDate julyEnd = LocalDate.of(2026, 7, 31);

    // Lock active only in June; season for this test is July only
    FieldDivisionLock juneLock = new FieldDivisionLock(majorsId, SEASON_START, juneEnd);
    Field pinnedField =
        new Field(UUID.randomUUID(), "Pinned", null, List.of(), List.of(), List.of(juneLock), null);
    Field unlockedField =
        new Field(UUID.randomUUID(), "Unlocked", null, List.of(), List.of(), List.of(), null);

    LeagueConfig julyConfig =
        new LeagueConfig(
            LocalTime.of(9, 0),
            LocalTime.of(18, 0),
            julyStart,
            julyEnd,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null);

    League twoFieldLeague =
        league(julyConfig, List.of(majors), List.of(pinnedField, unlockedField));
    League singleFieldLeague = league(julyConfig, List.of(majors), List.of(unlockedField));

    int slotsWithBoth = new SchedulerService().estimateAvailableSlots(twoFieldLeague, majorsId, 60);
    int slotsWithSingle =
        new SchedulerService().estimateAvailableSlots(singleFieldLeague, majorsId, 60);

    assertEquals(
        2 * slotsWithSingle,
        slotsWithBoth,
        "Lock expired before July — both fields must contribute, doubling slot count");
  }

  @Test
  @DisplayName(
      "E1: division locked to two different fields in sequential periods gets slots from each in"
          + " turn")
  void divisionLockedToTwoFieldsSequentiallyGetsSlotsFromEachPeriod() {
    Team t1 = team("Blue Jays"), t2 = team("Cardinals");
    Division majors = division("Majors", 60, t1, t2);
    UUID majorsId = majors.id();

    LocalDate juneEnd = LocalDate.of(2026, 6, 30);
    LocalDate julyStart = LocalDate.of(2026, 7, 1);
    LocalDate julyEnd = LocalDate.of(2026, 7, 31);

    FieldDivisionLock lockA = new FieldDivisionLock(majorsId, SEASON_START, juneEnd);
    FieldDivisionLock lockB = new FieldDivisionLock(majorsId, julyStart, julyEnd);
    Field fieldA =
        new Field(UUID.randomUUID(), "Field A", null, List.of(), List.of(), List.of(lockA), null);
    Field fieldB =
        new Field(UUID.randomUUID(), "Field B", null, List.of(), List.of(), List.of(lockB), null);

    LeagueConfig twoMonthConfig =
        new LeagueConfig(
            LocalTime.of(9, 0),
            LocalTime.of(18, 0),
            SEASON_START,
            julyEnd,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null);

    League l = league(twoMonthConfig, List.of(majors), List.of(fieldA, fieldB));
    int totalSlots = new SchedulerService().estimateAvailableSlots(l, majorsId, 60);

    // In June (30 days), only Field A is usable; in July (31 days), only Field B is usable.
    // Global window 09:00–18:00, 60-min games, default 30-min grid.
    int grid = SchedulerService.DEFAULT_GRID_MINUTES;
    int slotsPerFieldPerDay = ((18 - 9) * 60 - 60) / grid + 1;
    int expected = 30 * slotsPerFieldPerDay + 31 * slotsPerFieldPerDay;

    assertEquals(
        expected,
        totalSlots,
        "Slots should equal Field A's June capacity + Field B's July capacity only");
  }

  @Test
  @DisplayName(
      "E1: pinned division still excluded from non-locked fields alongside non-owning division"
          + " exclusion")
  void pinningAndExclusionCoexistCorrectly() {
    // Three fields: one locked to Majors, one locked to AAA, one open.
    // Majors must use only the Majors-locked field; AAA must use only the AAA-locked field.
    // Neither should land on the open field.
    Team m1 = team("Blue Jays"), m2 = team("Cardinals");
    Team a1 = team("Red Sox"), a2 = team("Yankees");
    Division majors = division("Majors", 60, m1, m2);
    Division aaa = division("AAA", 60, a1, a2);

    UUID majorsId = majors.id();
    UUID aaaId = aaa.id();

    FieldDivisionLock majorsLock = new FieldDivisionLock(majorsId, SEASON_START, SEASON_END);
    FieldDivisionLock aaaLock = new FieldDivisionLock(aaaId, SEASON_START, SEASON_END);
    Field majorsField =
        new Field(
            UUID.randomUUID(), "Majors Field", null, List.of(), List.of(), List.of(majorsLock), null);
    Field aaaField =
        new Field(UUID.randomUUID(), "AAA Field", null, List.of(), List.of(), List.of(aaaLock), null);
    Field openField =
        new Field(UUID.randomUUID(), "Open Field", null, List.of(), List.of(), List.of(), null);

    League l = league(CONFIG, List.of(majors, aaa), List.of(majorsField, aaaField, openField));
    ScheduleResult result = new SchedulerService().assign(l);
    assertInstanceOf(ScheduleResult.Success.class, result);

    UUID majorsFieldId = majorsField.id();
    UUID aaaFieldId = aaaField.id();

    for (ScheduledGame g : ((ScheduleResult.Success) result).games()) {
      if (g.divisionName().equals("Majors")) {
        assertEquals(
            majorsFieldId,
            g.fieldId(),
            "Majors game must be on Majors Field, not on " + g.fieldName());
      } else {
        assertEquals(
            aaaFieldId, g.fieldId(), "AAA game must be on AAA Field, not on " + g.fieldName());
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Utility
  // ---------------------------------------------------------------------------

  private static int toMinutes(LocalTime t) {
    return t.getHour() * 60 + t.getMinute();
  }
}
