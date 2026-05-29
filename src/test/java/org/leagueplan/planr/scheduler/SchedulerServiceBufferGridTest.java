package org.leagueplan.planr.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import org.leagueplan.planr.model.ScheduledGame;
import org.leagueplan.planr.model.Team;
import org.leagueplan.planr.model.TeamSchedule;

/**
 * Tests for configurable field buffer and game-start grid (fieldBufferMinutes, gridMinutes) in
 * SchedulerService.
 *
 * <p>The fast tests use estimateAvailableSlots() so they complete in milliseconds without invoking
 * CP-SAT. The solver tests (marked with "C2" for field non-overlap) run the full assign() call and
 * are intentionally narrow (small season, few teams) to keep runtimes short.
 *
 * <p>All arithmetic uses the formula: slots = floor((window - duration - buffer) / grid) + 1
 */
class SchedulerServiceBufferGridTest {

  private static final LocalTime T9 = LocalTime.of(9, 0);
  private static final LocalTime T11 = LocalTime.of(11, 0);
  private static final LocalTime T18 = LocalTime.of(18, 0);

  // 1-day season: June 1, 2026 (Monday)
  private static final LocalDate DAY1 = LocalDate.of(2026, 6, 1);
  // 7-day season: June 1–7, 2026
  private static final LocalDate DAY7 = LocalDate.of(2026, 6, 7);

  private final SchedulerService service = new SchedulerService();

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static League leagueFor(LeagueConfig config) {
    Team t1 = new Team(UUID.randomUUID(), "Blue Jays");
    Team t2 = new Team(UUID.randomUUID(), "Cardinals");
    Division div =
        new Division(UUID.randomUUID(), "Majors", 60, 2, List.of(t1, t2), null, null, null, null);
    Field field =
        new Field(UUID.randomUUID(), "Riverside Park", null, List.of(), List.of(), List.of());
    return new League(9, config, List.of(div), List.of(field), null, null, List.of(), List.of());
  }

  private static League leagueForWith(LeagueConfig config, Division div, Field field) {
    League base =
        new League(9, config, List.of(div), List.of(field), null, null, List.of(), List.of());
    TeamScheduleResult tsResult = new TeamScheduleService().generate(base);
    if (tsResult instanceof TeamScheduleResult.Failure f) {
      throw new IllegalStateException("Test setup: " + f.message());
    }
    TeamSchedule ts = ((TeamScheduleResult.Success) tsResult).schedule();
    return new League(9, config, List.of(div), List.of(field), ts, null, List.of(), List.of());
  }

  private static UUID divId(League league) {
    return league.divisions().get(0).id();
  }

  /**
   * Expected slot count per the formula: floor((windowMinutes - duration - buffer) / grid) + 1
   * Returns 0 when no game fits.
   */
  private static int slots(LocalTime open, LocalTime close, int duration, int buffer, int grid) {
    int window = (int) java.time.Duration.between(open, close).toMinutes();
    int net = window - duration - buffer;
    return net >= 0 ? net / grid + 1 : 0;
  }

  // ---------------------------------------------------------------------------
  // estimateAvailableSlots — field buffer
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("field buffer — estimateAvailableSlots")
  class FieldBuffer {

    @Test
    @DisplayName("null fieldBufferMinutes uses the default of 0")
    void nullBufferUsesDefaultOfZero() {
      LeagueConfig configNullBuffer =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, null, 30);
      LeagueConfig configExplicitZero =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, 30);

      int withNull =
          service.estimateAvailableSlots(
              leagueFor(configNullBuffer), divId(leagueFor(configNullBuffer)), 60);
      int withZero =
          service.estimateAvailableSlots(
              leagueFor(configExplicitZero), divId(leagueFor(configExplicitZero)), 60);

      assertEquals(withZero, withNull, "null buffer must behave identically to explicit 0");
    }

    @Test
    @DisplayName("buffer=0 slot count matches formula with no dead time")
    void bufferZeroSlotCountMatchesFormula() {
      LeagueConfig config =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, 30);
      League league = leagueFor(config);

      int actual = service.estimateAvailableSlots(league, divId(league), 60);
      int expected = slots(T9, T18, 60, 0, 30); // (540-60-0)/30+1 = 16+1 = 17

      assertEquals(expected, actual);
    }

    @Test
    @DisplayName("positive buffer reduces slot count compared to buffer=0")
    void positiveBufferReducesSlotCount() {
      LeagueConfig noBuffer =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, 30);
      LeagueConfig withBuffer =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 30, 30);

      League l1 = leagueFor(noBuffer);
      League l2 = leagueFor(withBuffer);

      int slotsNoBuffer = service.estimateAvailableSlots(l1, divId(l1), 60);
      int slotsWithBuffer = service.estimateAvailableSlots(l2, divId(l2), 60);

      assertTrue(
          slotsWithBuffer < slotsNoBuffer, "buffer=30 should yield fewer slots than buffer=0");
    }

    @Test
    @DisplayName("buffer=30 slot count matches formula")
    void bufferThirtySlotCountMatchesFormula() {
      // Window 9:00-18:00 (540 min), dur=60, buf=30, grid=30
      // (540-60-30)/30+1 = 450/30+1 = 15+1 = 16
      LeagueConfig config =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 30, 30);
      League league = leagueFor(config);

      int actual = service.estimateAvailableSlots(league, divId(league), 60);
      int expected = slots(T9, T18, 60, 30, 30);

      assertEquals(expected, actual);
    }

    @Test
    @DisplayName("buffer exactly equal to the gap between game end and window end adds one slot")
    void bufferAtExactBoundaryCountsSlot() {
      // Window 9:00-11:00 (120 min), dur=60, buf=60, grid=30
      // 9:00 + 60 + 60 = 11:00 — isAfter(11:00) is false → fits
      // 9:30 + 60 + 60 = 11:30 — isAfter(11:00) is true → doesn't fit
      // Expected: 1 slot
      LeagueConfig config =
          new LeagueConfig(T9, T11, DAY1, DAY1, List.of(), List.of(), null, null, 60, 30);
      League league = leagueFor(config);

      int actual = service.estimateAvailableSlots(league, divId(league), 60);
      assertEquals(1, actual, "exactly one slot when duration + buffer == window width");
    }

    @Test
    @DisplayName("buffer that makes duration + buffer exceed window yields zero slots")
    void oversizedBufferYieldsZeroSlots() {
      // Window 9:00-10:30 (90 min), dur=60, buf=60 → no slot fits (60+60=120 > 90)
      LeagueConfig config =
          new LeagueConfig(
              T9, LocalTime.of(10, 30), DAY1, DAY1, List.of(), List.of(), null, null, 60, 30);
      League league = leagueFor(config);

      int actual = service.estimateAvailableSlots(league, divId(league), 60);
      assertEquals(0, actual, "buffer + duration exceeds window — no slots possible");
    }

    @Test
    @DisplayName("buffer affects every day in a multi-day season consistently")
    void bufferAppliesConsistentlyAcrossMultipleDays() {
      // Season = 7 days; buffer=0 vs buffer=30, single field, same window
      LeagueConfig noBuffer =
          new LeagueConfig(T9, T18, DAY1, DAY7, List.of(), List.of(), null, null, 0, 30);
      LeagueConfig withBuffer =
          new LeagueConfig(T9, T18, DAY1, DAY7, List.of(), List.of(), null, null, 30, 30);

      League l1 = leagueFor(noBuffer);
      League l2 = leagueFor(withBuffer);

      int slotsNoBuffer = service.estimateAvailableSlots(l1, divId(l1), 60);
      int slotsWithBuffer = service.estimateAvailableSlots(l2, divId(l2), 60);

      int perDayDiff = slots(T9, T18, 60, 0, 30) - slots(T9, T18, 60, 30, 30);
      assertEquals(
          slotsNoBuffer - 7 * perDayDiff,
          slotsWithBuffer,
          "buffer effect should be uniform across all 7 days");
    }
  }

  // ---------------------------------------------------------------------------
  // estimateAvailableSlots — grid minutes
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("grid minutes — estimateAvailableSlots")
  class GridMinutes {

    @Test
    @DisplayName("null gridMinutes uses the default of 30")
    void nullGridUsesDefaultOfThirty() {
      LeagueConfig configNull =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, null);
      LeagueConfig configThirty =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, 30);

      League l1 = leagueFor(configNull);
      League l2 = leagueFor(configThirty);

      assertEquals(
          service.estimateAvailableSlots(l2, divId(l2), 60),
          service.estimateAvailableSlots(l1, divId(l1), 60),
          "null grid must behave identically to explicit 30");
    }

    @Test
    @DisplayName("grid=30 slot count matches formula")
    void gridThirtyMatchesFormula() {
      // (540-60-0)/30+1 = 17
      LeagueConfig config =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, 30);
      League league = leagueFor(config);

      assertEquals(
          slots(T9, T18, 60, 0, 30), service.estimateAvailableSlots(league, divId(league), 60));
    }

    @Test
    @DisplayName("grid=15 produces more slots than grid=30 for the same window")
    void finerGridProducesMoreSlots() {
      LeagueConfig grid30 =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, 30);
      LeagueConfig grid15 =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, 15);

      League l30 = leagueFor(grid30);
      League l15 = leagueFor(grid15);

      assertTrue(
          service.estimateAvailableSlots(l15, divId(l15), 60)
              > service.estimateAvailableSlots(l30, divId(l30), 60),
          "grid=15 should produce more slots than grid=30");
    }

    @Test
    @DisplayName("grid=15 slot count matches formula")
    void gridFifteenMatchesFormula() {
      // (540-60-0)/15+1 = 32+1 = 33
      LeagueConfig config =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, 15);
      League league = leagueFor(config);

      assertEquals(
          slots(T9, T18, 60, 0, 15), service.estimateAvailableSlots(league, divId(league), 60));
    }

    @Test
    @DisplayName("grid=60 produces one slot per hour in the window")
    void gridSixtyProducesOneSlotPerHour() {
      // 9:00-18:00, dur=60, grid=60 → starts at 9:00,10:00,...,17:00 = 9 slots
      LeagueConfig config =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, 60);
      League league = leagueFor(config);

      int actual = service.estimateAvailableSlots(league, divId(league), 60);
      assertEquals(slots(T9, T18, 60, 0, 60), actual);
      assertEquals(9, actual, "9:00-18:00 with 60-min games and 60-min grid = 9 slots");
    }

    @Test
    @DisplayName("grid=60 produces fewer slots than grid=30")
    void coarserGridProducesFewerSlots() {
      LeagueConfig grid30 =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, 30);
      LeagueConfig grid60 =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, 60);

      League l30 = leagueFor(grid30);
      League l60 = leagueFor(grid60);

      assertTrue(
          service.estimateAvailableSlots(l60, divId(l60), 60)
              < service.estimateAvailableSlots(l30, divId(l30), 60),
          "grid=60 should produce fewer slots than grid=30");
    }

    @Test
    @DisplayName("grid=1 produces the maximum possible slot count")
    void gridOneProducesMaxSlots() {
      // (540-60-0)/1+1 = 481 slots
      LeagueConfig config =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 0, 1);
      League league = leagueFor(config);

      int actual = service.estimateAvailableSlots(league, divId(league), 60);
      assertEquals(slots(T9, T18, 60, 0, 1), actual);
    }

    @Test
    @DisplayName("grid value is consistent across multiple matching days in the season")
    void gridAppliesConsistentlyAcrossMultipleDays() {
      LeagueConfig grid30 =
          new LeagueConfig(T9, T18, DAY1, DAY7, List.of(), List.of(), null, null, 0, 30);
      LeagueConfig grid15 =
          new LeagueConfig(T9, T18, DAY1, DAY7, List.of(), List.of(), null, null, 0, 15);

      League l30 = leagueFor(grid30);
      League l15 = leagueFor(grid15);

      int slotsPerDay30 = slots(T9, T18, 60, 0, 30);
      int slotsPerDay15 = slots(T9, T18, 60, 0, 15);

      assertEquals(
          7 * slotsPerDay30,
          service.estimateAvailableSlots(l30, divId(l30), 60),
          "grid=30 total should be 7× the per-day count");
      assertEquals(
          7 * slotsPerDay15,
          service.estimateAvailableSlots(l15, divId(l15), 60),
          "grid=15 total should be 7× the per-day count");
    }
  }

  // ---------------------------------------------------------------------------
  // estimateAvailableSlots — buffer + grid together
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("buffer and grid combined — estimateAvailableSlots")
  class BufferAndGridCombined {

    @Test
    @DisplayName("buffer=30 and grid=60 produce the correct combined slot count")
    void bufferAndGridCombinedMatchFormula() {
      // Window 9:00-18:00 (540 min), dur=60, buf=30, grid=60
      // (540-60-30)/60+1 = 450/60+1 = 7+1 = 8
      LeagueConfig config =
          new LeagueConfig(T9, T18, DAY1, DAY1, List.of(), List.of(), null, null, 30, 60);
      League league = leagueFor(config);

      int actual = service.estimateAvailableSlots(league, divId(league), 60);
      assertEquals(slots(T9, T18, 60, 30, 60), actual);
    }

    @Test
    @DisplayName("buffer=60 and grid=30 with narrow 2-hour window yields exactly 1 slot")
    void bufferSixtyInTwoHourWindowYieldsOneSlot() {
      // Window 9:00-11:00 (120 min), dur=60, buf=60, grid=30
      // 9:00: 9:00+60+60=11:00 ≤ 11:00 ✓
      // 9:30: 9:30+60+60=11:30 > 11:00 ✗
      LeagueConfig config =
          new LeagueConfig(T9, T11, DAY1, DAY1, List.of(), List.of(), null, null, 60, 30);
      League league = leagueFor(config);

      assertEquals(1, service.estimateAvailableSlots(league, divId(league), 60));
    }

    @Test
    @DisplayName("buffer=0 and grid=30 with 2-hour window yields 3 slots")
    void bufferZeroInTwoHourWindowYieldsThreeSlots() {
      // Window 9:00-11:00 (120 min), dur=60, buf=0, grid=30
      // 9:00: 9:00+60+0=10:00 ≤ 11:00 ✓
      // 9:30: 9:30+60+0=10:30 ≤ 11:00 ✓
      // 10:00: 10:00+60+0=11:00 ≤ 11:00 ✓
      // 10:30: 10:30+60+0=11:30 > 11:00 ✗
      LeagueConfig config =
          new LeagueConfig(T9, T11, DAY1, DAY1, List.of(), List.of(), null, null, 0, 30);
      League league = leagueFor(config);

      assertEquals(3, service.estimateAvailableSlots(league, divId(league), 60));
    }
  }

  // ---------------------------------------------------------------------------
  // assign() — C2 field non-overlap with configurable buffer
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("assign() — field non-overlap with configurable buffer")
  class AssignFieldNonOverlap {

    /**
     * Builds a 2-division league (1 game each) with a 1-day season and the given buffer. Using two
     * separate divisions means C3 (no team plays twice in one day) does not prevent both games from
     * landing on the same day, so the only limiting factor is field availability.
     */
    private League twoGameOneDayLeague(int bufferMinutes) {
      // targetGamesPerTeam=1 → team schedule produces exactly 1 game per division
      Team d1t1 = new Team(UUID.randomUUID(), "Blue Jays");
      Team d1t2 = new Team(UUID.randomUUID(), "Cardinals");
      Team d2t1 = new Team(UUID.randomUUID(), "Red Sox");
      Team d2t2 = new Team(UUID.randomUUID(), "Yankees");
      Division div1 =
          new Division(
              UUID.randomUUID(), "Majors", 60, 1, List.of(d1t1, d1t2), null, null, null, null);
      Division div2 =
          new Division(
              UUID.randomUUID(), "AAA", 60, 1, List.of(d2t1, d2t2), null, null, null, null);
      Field field =
          new Field(UUID.randomUUID(), "Riverside Park", null, List.of(), List.of(), List.of());

      LeagueConfig config =
          new LeagueConfig(T9, T11, DAY1, DAY1, List.of(), List.of(), null, 0, bufferMinutes, 30);

      League base =
          new League(
              9, config, List.of(div1, div2), List.of(field), null, null, List.of(), List.of());
      TeamScheduleResult tsResult = new TeamScheduleService().generate(base);
      if (tsResult instanceof TeamScheduleResult.Failure f) {
        throw new IllegalStateException("Test setup: " + f.message());
      }
      TeamSchedule ts = ((TeamScheduleResult.Success) tsResult).schedule();
      return new League(
          9, config, List.of(div1, div2), List.of(field), ts, null, List.of(), List.of());
    }

    @Test
    @DisplayName("C2: buffer=0 allows two 60-min games back-to-back on the same field")
    void bufferZeroAllowsBackToBackGames() {
      // 1 field, window 9:00-11:00 (120 min), grid=30, buffer=0
      // Slots 9:00, 9:30, 10:00 — 9:00 and 10:00 have no tick overlap → both games fit
      ScheduleResult result = service.assign(twoGameOneDayLeague(0));

      assertInstanceOf(ScheduleResult.Success.class, result);
      assertTrue(
          ((ScheduleResult.Success) result).targetMet(),
          "buffer=0 in a 2-hour window should accommodate both games back-to-back");
    }

    @Test
    @DisplayName("C2: buffer=60 in a 2-hour window prevents scheduling two 60-min games")
    void bufferSixtyPreventsTwoGamesInTwoHourWindow() {
      // buffer=60 → only 1 slot (9:00+60+60=11:00 fits; 9:30+60+60=11:30 doesn't)
      // 2 games needed but only 1 slot → targetMet = false
      ScheduleResult result = service.assign(twoGameOneDayLeague(60));

      assertInstanceOf(ScheduleResult.Success.class, result);
      assertFalse(
          ((ScheduleResult.Success) result).targetMet(),
          "buffer=60 leaves only 1 slot in a 2-hour window — not enough for 2 games");
    }
  }

  // ---------------------------------------------------------------------------
  // assign() — grid controls start time alignment
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("assign() — grid controls start time alignment")
  class AssignGridAlignment {

    @Test
    @DisplayName("grid=60 produces game start times only on the hour")
    void gridSixtyAlignedToHour() {
      LeagueConfig config =
          new LeagueConfig(T9, T18, DAY1, DAY7, List.of(), List.of(), null, null, 0, 60);
      Team t1 = new Team(UUID.randomUUID(), "Blue Jays");
      Team t2 = new Team(UUID.randomUUID(), "Cardinals");
      Division div =
          new Division(UUID.randomUUID(), "Majors", 60, 2, List.of(t1, t2), null, null, null, null);
      Field field =
          new Field(UUID.randomUUID(), "Riverside Park", null, List.of(), List.of(), List.of());

      League league = leagueForWith(config, div, field);
      ScheduleResult result = service.assign(league);

      assertInstanceOf(ScheduleResult.Success.class, result);
      List<ScheduledGame> games = ((ScheduleResult.Success) result).games();
      assertFalse(games.isEmpty());

      for (ScheduledGame g : games) {
        assertEquals(
            0,
            g.startTime().getMinute(),
            "grid=60 — game at " + g.startTime() + " should start on the hour");
      }
    }

    @Test
    @DisplayName("grid=30 produces game start times only on :00 or :30")
    void gridThirtyAlignedToHalfHour() {
      LeagueConfig config =
          new LeagueConfig(T9, T18, DAY1, DAY7, List.of(), List.of(), null, null, 0, 30);
      Team t1 = new Team(UUID.randomUUID(), "Blue Jays");
      Team t2 = new Team(UUID.randomUUID(), "Cardinals");
      Division div =
          new Division(UUID.randomUUID(), "Majors", 60, 2, List.of(t1, t2), null, null, null, null);
      Field field =
          new Field(UUID.randomUUID(), "Riverside Park", null, List.of(), List.of(), List.of());

      League league = leagueForWith(config, div, field);
      ScheduleResult result = service.assign(league);

      assertInstanceOf(ScheduleResult.Success.class, result);
      List<ScheduledGame> games = ((ScheduleResult.Success) result).games();
      assertFalse(games.isEmpty());

      for (ScheduledGame g : games) {
        int minute = g.startTime().getMinute();
        assertTrue(
            minute == 0 || minute == 30,
            "grid=30 — game at " + g.startTime() + " should start on :00 or :30");
      }
    }

    @Test
    @DisplayName("grid=15 permits start times at :00, :15, :30, and :45")
    void gridFifteenPermitsQuarterHourStarts() {
      // Use a narrow window to force the solver to use non-:30 slots
      // 9:00-12:00 (180 min), grid=15, dur=60 → slots at 9:00, 9:15, 9:30, ..., 11:00 = 9 slots
      LeagueConfig config =
          new LeagueConfig(
              T9, LocalTime.of(12, 0), DAY1, DAY7, List.of(), List.of(), null, null, 0, 15);
      Team t1 = new Team(UUID.randomUUID(), "Blue Jays");
      Team t2 = new Team(UUID.randomUUID(), "Cardinals");
      Division div =
          new Division(UUID.randomUUID(), "Majors", 60, 2, List.of(t1, t2), null, null, null, null);
      Field field =
          new Field(UUID.randomUUID(), "Riverside Park", null, List.of(), List.of(), List.of());

      League league = leagueForWith(config, div, field);
      ScheduleResult result = service.assign(league);

      assertInstanceOf(ScheduleResult.Success.class, result);
      List<ScheduledGame> games = ((ScheduleResult.Success) result).games();
      assertFalse(games.isEmpty());

      for (ScheduledGame g : games) {
        int minute = g.startTime().getMinute();
        assertTrue(
            minute % 15 == 0,
            "grid=15 — game at " + g.startTime() + " minute should be 0, 15, 30, or 45");
      }
    }
  }
}
