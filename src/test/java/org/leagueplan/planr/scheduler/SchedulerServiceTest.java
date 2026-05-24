package org.leagueplan.planr.scheduler;

import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import org.leagueplan.planr.model.ScheduledGame;
import org.leagueplan.planr.model.Team;
import org.leagueplan.planr.model.TeamSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SchedulerService.generate().
 *
 * All tests use small leagues (2-4 teams per division) so the solver completes in milliseconds.
 * Fields are open every day between sunrise and sunset; no day-of-week windows exist in v2.
 */
class SchedulerServiceTest {

    private static final LocalDate SEASON_START = LocalDate.of(2026, 6, 1);
    // June–August: 91 days, 33 slots/day with 09:00-18:00 config and 15-min grid.
    private static final LocalDate SEASON_END   = LocalDate.of(2026, 8, 31);
    // Short 7-day season used in infeasibility tests: narrow config (1 slot/day) × 7 days = 7 slots < 12.
    private static final LocalDate SHORT_SEASON_END = LocalDate.of(2026, 6, 7);

    // Normal config: fields open 09:00–18:00 over the full season.
    private static final LeagueConfig CONFIG = new LeagueConfig(
        LocalTime.of(9, 0), LocalTime.of(18, 0), SEASON_START, SEASON_END);
    // Narrow config: fields open 09:00–10:00 → exactly 1 slot/day for 60-min games.
    // Season dates are set to SEASON_START/SEASON_END; generateShort() swaps in SHORT_SEASON_END.
    private static final LeagueConfig NARROW_CONFIG = new LeagueConfig(
        LocalTime.of(9, 0), LocalTime.of(10, 0), SEASON_START, SEASON_END);

    // ---------------------------------------------------------------------------
    // League builder helpers
    // ---------------------------------------------------------------------------

    private static Team team(String name) {
        return new Team(UUID.randomUUID(), name);
    }

    private static Division division(String name, int duration, Team... teams) {
        // sets target to 2*(N-1) so these integration tests produce N*(N-1) total games
        int target = 2 * Math.max(1, teams.length - 1);
        return new Division(UUID.randomUUID(), name, duration, target, List.of(teams));
    }

    private static Field field(String name) {
        return new Field(UUID.randomUUID(), name, null, List.of(), List.of());
    }

    private static League league(LeagueConfig config, List<Division> divisions, List<Field> fields) {
        League base = new League(4, config, divisions, fields, null, null);
        TeamScheduleResult tsResult = new TeamScheduleService().generate(base);
        if (tsResult instanceof TeamScheduleResult.Failure f) {
            throw new IllegalStateException("Test setup: " + f.message());
        }
        TeamSchedule ts = ((TeamScheduleResult.Success) tsResult).schedule();
        return new League(4, config, divisions, fields, ts, null);
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

    /** Uses the short (7-day) season — triggers pre-solve infeasibility for 4+ teams with narrow config. */
    private ScheduleResult generateShort(League l) {
        LeagueConfig shortConfig = new LeagueConfig(
            l.config().sunriseTime(), l.config().sunsetTime(), SEASON_START, SHORT_SEASON_END);
        League shortLeague = new League(4, shortConfig, l.divisions(), l.fields(), l.teamSchedule(), null);
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
        games.forEach(g -> { teamIds.add(g.homeTeamId()); teamIds.add(g.awayTeamId()); });
        List<UUID> teamList = new ArrayList<>(teamIds);

        for (int i = 0; i < teamList.size(); i++) {
            for (int j = i + 1; j < teamList.size(); j++) {
                UUID x = teamList.get(i), y = teamList.get(j);
                long count = games.stream()
                    .filter(g -> (g.homeTeamId().equals(x) && g.awayTeamId().equals(y))
                              || (g.homeTeamId().equals(y) && g.awayTeamId().equals(x)))
                    .count();
                assertTrue(count >= 1,
                    "Unordered pair should appear at least once; got " + count + " for (" + x + ", " + y + ")");
            }
        }
    }

    @Test
    @DisplayName("each team's home and away game counts differ by at most N-1 (fill reduces initial RR skew)")
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
            assertTrue(Math.abs(home - away) <= n - 1,
                "Home/away imbalance for team " + entry.getKey() + ": home=" + home + " away=" + away);
        }
    }

    // ---------------------------------------------------------------------------
    // Field conflict constraint (C2)
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("no two games on the same field overlap including the 15-minute buffer")
    void noFieldConflictsIncludingBuffer() {
        ScheduleResult result = generate(fourTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        for (int i = 0; i < games.size(); i++) {
            ScheduledGame a = games.get(i);
            for (int j = i + 1; j < games.size(); j++) {
                ScheduledGame b = games.get(j);
                if (!a.fieldId().equals(b.fieldId()) || !a.date().equals(b.date())) continue;
                int aStart = toMinutes(a.startTime());
                int aEnd   = aStart + a.gameDurationMinutes() + 15;
                int bStart = toMinutes(b.startTime());
                int bEnd   = bStart + b.gameDurationMinutes() + 15;
                assertFalse(aStart < bEnd && bStart < aEnd,
                    "Field conflict: games " + i + " and " + j + " on "
                    + a.fieldName() + " at " + a.date());
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
                    a.homeTeamId().equals(b.homeTeamId()) ||
                    a.homeTeamId().equals(b.awayTeamId()) ||
                    a.awayTeamId().equals(b.homeTeamId()) ||
                    a.awayTeamId().equals(b.awayTeamId());
                assertFalse(teamOverlap,
                    "Team double-booked on " + a.date() + ": games " + i + " and " + j);
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
            assertFalse(g.date().isBefore(SEASON_START),
                "Game date " + g.date() + " is before season start " + SEASON_START);
            assertFalse(g.date().isAfter(SEASON_END),
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
        Team a1 = team("Red Sox"),   a2 = team("Yankees");
        Division majors = division("Majors", 60, m1, m2);
        Division aaa    = division("AAA",    60, a1, a2);
        Field f = field("Riverside Park");

        League l = league(CONFIG, List.of(majors, aaa), List.of(f));
        ScheduleResult result = generate(l);

        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        // 2 teams × 2 divisions = 2 games each = 4 total
        assertEquals(4, games.size());

        long majorsCount = games.stream()
            .filter(g -> g.divisionName().equals("Majors")).count();
        long aaaCount = games.stream()
            .filter(g -> g.divisionName().equals("AAA")).count();
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

        DivisionSummary majorsSummary = success.divisionSummaries().stream()
            .filter(s -> s.divisionName().equals("Majors"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Majors summary missing"));
        assertEquals(12, majorsSummary.gamesRequested(),
            "Majors should have 12 games requested");
        assertTrue(majorsSummary.gamesAssigned() < 12,
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
        Division aaa    = division("AAA",    60, a1, a2);
        Field f = field("Narrow");
        League l = league(NARROW_CONFIG, List.of(majors, aaa), List.of(f));

        ScheduleResult result = generateShort(l);
        assertInstanceOf(ScheduleResult.Success.class, result);
        ScheduleResult.Success success = (ScheduleResult.Success) result;
        assertFalse(success.targetMet());

        List<DivisionSummary> summaries = success.divisionSummaries();
        assertEquals(2, summaries.size(), "Should have summaries for both divisions");

        DivisionSummary majorsSummary = summaries.stream()
            .filter(s -> s.divisionName().equals("Majors")).findFirst().orElseThrow();
        assertTrue(majorsSummary.gamesAssigned() < 12,
            "Majors should be partial");

        assertTrue(summaries.stream().anyMatch(s -> s.divisionName().equals("AAA")),
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
        Team a1 = team("Red Sox"),   a2 = team("Yankees");
        Division majors = division("Majors", 60, m1, m2);
        Division aaa    = division("AAA",    60, a1, a2);
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
    @DisplayName("DivisionSummary.gamesAssigned matches the count of ScheduledGames for that division")
    void divisionSummaryGamesAssignedEqualsActualAssignedCount() {
        Team m1 = team("Blue Jays"), m2 = team("Cardinals");
        Team a1 = team("Red Sox"),   a2 = team("Yankees");
        Division majors = division("Majors", 60, m1, m2);
        Division aaa    = division("AAA",    60, a1, a2);
        Field f = field("Riverside Park");
        League l = league(CONFIG, List.of(majors, aaa), List.of(f));

        ScheduleResult result = generate(l);
        assertInstanceOf(ScheduleResult.Success.class, result);
        ScheduleResult.Success success = (ScheduleResult.Success) result;

        for (DivisionSummary summary : success.divisionSummaries()) {
            long actual = success.games().stream()
                .filter(g -> g.divisionName().equals(summary.divisionName()))
                .count();
            assertEquals(summary.gamesAssigned(), (int) actual,
                "gamesAssigned mismatch for " + summary.divisionName());
        }
    }

    @Test
    @DisplayName("DivisionSummary.slotsAvailable matches estimateAvailableSlots() for the same config")
    void slotsAvailableMatchesEstimateAvailableSlots() {
        League l = twoTeamLeague();
        ScheduleResult result = generate(l);
        assertInstanceOf(ScheduleResult.Success.class, result);
        DivisionSummary summary = ((ScheduleResult.Success) result).divisionSummaries().get(0);

        Division div = l.divisions().get(0);
        int estimate = new SchedulerService().estimateAvailableSlots(l, div.id(), div.gameDurationMinutes());
        assertEquals(estimate, summary.slotsAvailable());
    }

    @Test
    @DisplayName("partial: 4-team div with 1-day narrow season gets exactly 1 game assigned")
    void partialSuccessWhenSingleSlotAvailableForMultipleFixtures() {
        // 1-day season × 09:00-10:00 window = 1 slot; 4-team div = 12 fixtures → 1 game assigned
        LeagueConfig oneDayConfig = new LeagueConfig(
            LocalTime.of(9, 0), LocalTime.of(10, 0), SEASON_START, SEASON_START);
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
        Team aaa1   = team("F1"), aaa2   = team("F2");
        Division tBall = division("T-Ball", 90, tball1, tball2);
        Division aaa   = division("AAA",    60, aaa1,   aaa2);
        Field f = field("Riverside Park");
        League l = league(NARROW_CONFIG, List.of(tBall, aaa), List.of(f));

        ScheduleResult result = generate(l);
        assertInstanceOf(ScheduleResult.Success.class, result);
        ScheduleResult.Success success = (ScheduleResult.Success) result;
        assertFalse(success.targetMet());

        DivisionSummary tBallSummary = success.divisionSummaries().stream()
            .filter(s -> s.divisionName().equals("T-Ball")).findFirst().orElseThrow();
        assertEquals(0, tBallSummary.slotsAvailable(),
            "90-min game does not fit in 60-min window → 0 slots");
        assertEquals(0, tBallSummary.gamesAssigned());

        DivisionSummary aaaSummary = success.divisionSummaries().stream()
            .filter(s -> s.divisionName().equals("AAA")).findFirst().orElseThrow();
        assertTrue(aaaSummary.targetMet());
    }

    @Test
    @DisplayName("divisionSummaries are sorted alphabetically by division name")
    void divisionSummariesAreSortedAlphabetically() {
        Team z1 = team("ZA"), z2 = team("ZB");
        Team a1 = team("AA"), a2 = team("AB");
        Team m1 = team("MA"), m2 = team("MB");
        Division zebra  = new Division(UUID.randomUUID(), "Zebra",  60, 2, List.of(z1, z2));
        Division alpha  = new Division(UUID.randomUUID(), "Alpha",  60, 2, List.of(a1, a2));
        Division majors = new Division(UUID.randomUUID(), "Majors", 60, 2, List.of(m1, m2));
        Field f = field("Riverside Park");
        League l = league(CONFIG, List.of(zebra, alpha, majors), List.of(f));

        ScheduleResult result = generate(l);
        assertInstanceOf(ScheduleResult.Success.class, result);
        List<DivisionSummary> summaries = ((ScheduleResult.Success) result).divisionSummaries();
        assertEquals(3, summaries.size());
        assertEquals("Alpha",  summaries.get(0).divisionName());
        assertEquals("Majors", summaries.get(1).divisionName());
        assertEquals("Zebra",  summaries.get(2).divisionName());
    }

    // ---------------------------------------------------------------------------
    // Result metadata
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("success result has overridden=false on all games")
    void allGamesHaveOverriddenFalse() {
        ScheduleResult result = generate(twoTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        ((ScheduleResult.Success) result).games()
            .forEach(g -> assertFalse(g.overridden(), "Freshly generated game should not be overridden"));
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
            assertTrue(cmp <= 0,
                "Games not sorted at index " + i + ": " + prev.date() + " " + prev.startTime()
                + " vs " + curr.date() + " " + curr.startTime());
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
    // Utility
    // ---------------------------------------------------------------------------

    private static int toMinutes(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }
}
