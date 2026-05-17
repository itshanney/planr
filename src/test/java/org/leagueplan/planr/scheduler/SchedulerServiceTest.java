package org.leagueplan.planr.scheduler;

import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import org.leagueplan.planr.model.ScheduledGame;
import org.leagueplan.planr.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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

    // Normal config: fields open 09:00–18:00 every day.
    private static final LeagueConfig CONFIG = new LeagueConfig(
        LocalTime.of(9, 0), LocalTime.of(18, 0), null, null);
    // Narrow config: fields open 09:00–10:00 → exactly 1 slot/day for 60-min games.
    private static final LeagueConfig NARROW_CONFIG = new LeagueConfig(
        LocalTime.of(9, 0), LocalTime.of(10, 0), null, null);

    // ---------------------------------------------------------------------------
    // League builder helpers
    // ---------------------------------------------------------------------------

    private static Team team(String name) {
        return new Team(UUID.randomUUID(), name);
    }

    private static Division division(String name, int duration, Team... teams) {
        return new Division(UUID.randomUUID(), name, duration, 0, List.of(teams));
    }

    private static Field field(String name) {
        return new Field(UUID.randomUUID(), name, null, List.of(), List.of());
    }

    private static League league(LeagueConfig config, List<Division> divisions, List<Field> fields) {
        return new League(4, config, divisions, fields, null);
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
        return new SchedulerService().generate(l, SEASON_START, SEASON_END);
    }

    /** Uses the short (7-day) season where narrow config produces 7 slots — triggers pre-solve infeasibility for 4+ teams. */
    private ScheduleResult generateShort(League l) {
        return new SchedulerService().generate(l, SEASON_START, SHORT_SEASON_END);
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
    // Home/away balance: each ordered pair appears exactly once
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("each team-pair appears exactly once with each home/away assignment")
    void eachTeamPairAppearsOnceInEachDirection() {
        ScheduleResult result = generate(fourTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        for (int i = 0; i < games.size(); i++) {
            ScheduledGame gi = games.get(i);
            long count = games.stream()
                .filter(g -> g.homeTeamId().equals(gi.homeTeamId())
                          && g.awayTeamId().equals(gi.awayTeamId()))
                .count();
            assertEquals(1, count,
                "Ordered pair (" + gi.homeTeamName() + ", " + gi.awayTeamName()
                + ") should appear exactly once");
        }
    }

    @Test
    @DisplayName("the reverse of every game (home↔away swapped) also exists in the schedule")
    void reverseOfEveryGameAlsoExists() {
        ScheduleResult result = generate(fourTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        for (ScheduledGame g : games) {
            long reverseCount = games.stream()
                .filter(other -> other.homeTeamId().equals(g.awayTeamId())
                              && other.awayTeamId().equals(g.homeTeamId()))
                .count();
            assertEquals(1, reverseCount,
                "Reverse of (" + g.homeTeamName() + " vs " + g.awayTeamName()
                + ") should exist exactly once");
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
    @DisplayName("returns Failure when there are fewer slots than fixtures for a division")
    void returnsFailureWhenInsufficientSlots() {
        // 4 teams → 12 games needed. Narrow config (09:00-10:00) → 1 slot/day.
        // Short season (7 days) → 7 slots < 12 → pre-solve catches infeasibility.
        Team t1 = team("A"), t2 = team("B"), t3 = team("C"), t4 = team("D");
        Division div = division("Majors", 60, t1, t2, t3, t4);
        Field f = field("Riverside Park");
        League l = league(NARROW_CONFIG, List.of(div), List.of(f));

        ScheduleResult result = generateShort(l);
        assertInstanceOf(ScheduleResult.Failure.class, result);
    }

    @Test
    @DisplayName("failure message names the infeasible division and includes game counts")
    void failureMessageNamesInfeasibleDivision() {
        Team t1 = team("A"), t2 = team("B"), t3 = team("C"), t4 = team("D");
        Division div = division("Majors", 60, t1, t2, t3, t4);
        Field f = field("Riverside Park");
        League l = league(NARROW_CONFIG, List.of(div), List.of(f));

        ScheduleResult result = generateShort(l);
        assertInstanceOf(ScheduleResult.Failure.class, result);
        String msg = ((ScheduleResult.Failure) result).message();
        assertTrue(msg.contains("Majors"),
            "Failure message should name the infeasible division; got: " + msg);
        assertTrue(msg.contains("12"),
            "Failure message should include 12 games required; got: " + msg);
    }

    @Test
    @DisplayName("feasible division is listed as OK in the failure diagnostic when another division is infeasible")
    void feasibleDivisionListedAsOkInFailureDiagnostic() {
        // Majors: infeasible (4 teams → 12 games, 7 slots available)
        // AAA:    feasible  (2 teams → 2 games, 7 slots available)
        Team m1 = team("A"), m2 = team("B"), m3 = team("C"), m4 = team("D");
        Team a1 = team("E"), a2 = team("F");
        Division majors = division("Majors", 60, m1, m2, m3, m4);
        Division aaa    = division("AAA",    60, a1, a2);
        Field f = field("Narrow");
        League l = league(NARROW_CONFIG, List.of(majors, aaa), List.of(f));

        ScheduleResult result = generateShort(l);
        assertInstanceOf(ScheduleResult.Failure.class, result);
        String msg = ((ScheduleResult.Failure) result).message();
        assertTrue(msg.contains("AAA") && msg.contains("OK"),
            "Failure message should list AAA as OK; got: " + msg);
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
