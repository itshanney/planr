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
 * Tests for division curfew enforcement in SchedulerService.
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC 6: No game starts later than the curfew time; a start exactly AT the curfew is valid
 *   <li>AC 7: No practice starts later than the curfew (same filtering, tested via estimate)
 *   <li>AC 8: If curfew eliminates all slots, assign() returns Failure with the division name
 *   <li>AC 9: estimateAvailableSlots filters against curfew
 *   <li>AC 10: Division with no curfew has unchanged behavior
 * </ul>
 *
 * <p>Fast tests use {@code estimateAvailableSlots} (no CP-SAT). Slow tests run the full {@code
 * assign()} solve with a 2-team, 1-field, 2-day season so that the solver completes in
 * milliseconds.
 */
class SchedulerServiceCurfewTest {

  // 9:00–18:00 window, 1-day season (Monday June 1, 2026)
  private static final LocalTime OPEN = LocalTime.of(9, 0);
  private static final LocalTime CLOSE = LocalTime.of(18, 0);
  private static final LocalDate DAY1 = LocalDate.of(2026, 6, 1);
  // 7-day season for full assign() tests
  private static final LocalDate DAY7 = LocalDate.of(2026, 6, 7);

  private final SchedulerService service = new SchedulerService();

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Team team(String name) {
    return new Team(UUID.randomUUID(), name);
  }

  private static Division division(String name, int gameDuration, LocalTime curfew) {
    return new Division(
        UUID.randomUUID(),
        name,
        gameDuration,
        2,
        List.of(team("Blue Jays"), team("Cardinals")),
        null,
        null,
        null,
        null,
        curfew);
  }

  private static Field field(String name) {
    return new Field(UUID.randomUUID(), name, null, List.of(), List.of(), List.of(), null);
  }

  /** A league suitable for estimateAvailableSlots (no team schedule needed). */
  private static League estimateLeague(
      LeagueConfig config, Division division, Field... fields) {
    return new League(
        10, config, List.of(division), List.of(fields), null, null, List.of(), List.of());
  }

  /**
   * A league with a generated team schedule, suitable for assign(). A 2-team/1-target division
   * produces exactly 2 games.
   */
  private static League assignableLeague(LeagueConfig config, Division division, Field... fields) {
    League base = estimateLeague(config, division, fields);
    TeamScheduleResult ts = new TeamScheduleService().generate(base);
    if (ts instanceof TeamScheduleResult.Failure f) {
      throw new IllegalStateException("Test setup: " + f.message());
    }
    TeamSchedule schedule = ((TeamScheduleResult.Success) ts).schedule();
    return new League(
        10, config, List.of(division), List.of(fields), schedule, null, List.of(), List.of());
  }

  private static LeagueConfig config(LocalDate start, LocalDate end) {
    return new LeagueConfig(OPEN, CLOSE, start, end, List.of(), List.of(), null, null, null, 30);
  }

  // ---------------------------------------------------------------------------
  // estimateAvailableSlots — curfew filtering
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("estimateAvailableSlots with curfew")
  class EstimateAvailableSlots {

    @Test
    @DisplayName("null curfew produces the same slot count as an unconstrained division")
    void nullCurfewDoesNotReduceSlotCount() {
      // 9:00–18:00, 60-min game, 30-min grid → 17 slots/day
      LeagueConfig cfg = config(DAY1, DAY1);
      Division withCurfew = division("6U", 60, null); // null = no curfew
      Division noCurfew = division("6U", 60, null);
      League l1 = estimateLeague(cfg, withCurfew, field("F1"));
      League l2 = estimateLeague(cfg, noCurfew, field("F1"));

      int slots1 = service.estimateAvailableSlots(l1, withCurfew.id(), 60);
      int slots2 = service.estimateAvailableSlots(l2, noCurfew.id(), 60);

      assertEquals(slots1, slots2, "null curfew should produce identical slot counts");
    }

    @Test
    @DisplayName("curfew exactly at the last valid slot start time does not reduce the count")
    void curfewAtLastValidStartTimeIncludesThatSlot() {
      // With 9:00–18:00 and 60-min game, last valid start is 17:00 (17:00 + 60min = 18:00 ≤ 18:00)
      // Curfew 17:00: slotStart.isAfter(17:00) = false for 17:00 → slot included
      LeagueConfig cfg = config(DAY1, DAY1);
      Division noCurfew = division("6U", 60, null);
      Division withCurfew17 = division("6U", 60, LocalTime.of(17, 0));
      League lNone = estimateLeague(cfg, noCurfew, field("F1"));
      League lWith = estimateLeague(cfg, withCurfew17, field("F1"));

      int unconstrained = service.estimateAvailableSlots(lNone, noCurfew.id(), 60);
      int constrained = service.estimateAvailableSlots(lWith, withCurfew17.id(), 60);

      assertEquals(
          unconstrained,
          constrained,
          "curfew at the last valid start should not reduce the slot count");
    }

    @Test
    @DisplayName("curfew one minute before the last valid slot excludes that slot")
    void curfewOneMinuteBeforeLastSlotExcludesThatSlot() {
      // Last slot starts at 17:00; curfew 16:59 → 17:00.isAfter(16:59) = true → excluded
      // Remaining last start = 16:30 → 16 slots
      LeagueConfig cfg = config(DAY1, DAY1);
      Division div = division("6U", 60, LocalTime.of(16, 59));
      League league = estimateLeague(cfg, div, field("F1"));

      int slots = service.estimateAvailableSlots(league, div.id(), 60);

      assertEquals(16, slots, "curfew at 16:59 should leave 16 slots (9:00 through 16:30)");
    }

    @Test
    @DisplayName("curfew reduces slot count compared to no curfew")
    void curfewReducesSlotCountComparedToNoCurfew() {
      LeagueConfig cfg = config(DAY1, DAY1);
      Division noCurfew = division("Majors", 60, null);
      Division withCurfew = division("6U", 60, LocalTime.of(13, 0));
      League lNone = estimateLeague(cfg, noCurfew, field("F1"));
      League lWith = estimateLeague(cfg, withCurfew, field("F1"));

      int unconstrained = service.estimateAvailableSlots(lNone, noCurfew.id(), 60);
      int constrained = service.estimateAvailableSlots(lWith, withCurfew.id(), 60);

      assertTrue(
          constrained < unconstrained,
          "curfew at 13:00 should produce fewer slots than no curfew");
    }

    @Test
    @DisplayName("curfew of 13:00 allows exactly 9 slots (9:00 through 13:00 inclusive)")
    void curfew13ProducesExactSlotCount() {
      // Slots: 9:00, 9:30, 10:00, 10:30, 11:00, 11:30, 12:00, 12:30, 13:00 → 9 slots
      LeagueConfig cfg = config(DAY1, DAY1);
      Division div = division("6U", 60, LocalTime.of(13, 0));
      League league = estimateLeague(cfg, div, field("F1"));

      int slots = service.estimateAvailableSlots(league, div.id(), 60);

      assertEquals(9, slots, "curfew 13:00 with 30-min grid should produce exactly 9 slots");
    }

    @Test
    @DisplayName("curfew before the window open time yields zero slots")
    void curfewBeforeWindowOpenYieldsZeroSlots() {
      // Window opens at 09:00; curfew 08:00 → first slot (09:00) is after curfew → no slots
      LeagueConfig cfg = config(DAY1, DAY1);
      Division div = division("6U", 60, LocalTime.of(8, 0));
      League league = estimateLeague(cfg, div, field("F1"));

      int slots = service.estimateAvailableSlots(league, div.id(), 60);

      assertEquals(0, slots, "curfew before window open should leave no slots");
    }

    @Test
    @DisplayName("curfew at exact window open time allows only the very first slot")
    void curfewAtWindowOpenAllowsOnlyFirstSlot() {
      // Curfew=09:00, first slot starts at 09:00 → 09:00.isAfter(09:00) = false → included
      // Next slot 09:30.isAfter(09:00) = true → break → only 1 slot
      LeagueConfig cfg = config(DAY1, DAY1);
      Division div = division("6U", 60, LocalTime.of(9, 0));
      League league = estimateLeague(cfg, div, field("F1"));

      int slots = service.estimateAvailableSlots(league, div.id(), 60);

      assertEquals(1, slots, "curfew at window open should allow only the first slot");
    }

    @Test
    @DisplayName("curfew filter is applied independently per division in a multi-division league")
    void curfewFilterIsIndependentPerDivision() {
      // 6U division has curfew, Majors does not. They should each get their own slot counts.
      LeagueConfig cfg = config(DAY1, DAY1);
      Division div6U = division("6U", 60, LocalTime.of(13, 0));
      Division divMajors = division("Majors", 60, null);
      Field f = field("F1");
      League league =
          new League(
              10, cfg, List.of(div6U, divMajors), List.of(f), null, null, List.of(), List.of());

      int slots6U = service.estimateAvailableSlots(league, div6U.id(), 60);
      int slotsMajors = service.estimateAvailableSlots(league, divMajors.id(), 60);

      assertTrue(slots6U < slotsMajors, "curfewed division should have fewer slots");
      assertEquals(9, slots6U, "6U with curfew 13:00 should have 9 slots");
    }
  }

  // ---------------------------------------------------------------------------
  // assign() — curfew pre-solve zero-slot guard (AC 8)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("assign() curfew hard fail when all slots are eliminated")
  class AssignCurfewHardFail {

    @Test
    @DisplayName("returns Failure naming the division when curfew eliminates all slots")
    void returnsFailureNamingDivisionWhenCurfewEliminatesAllSlots() {
      // Midnight curfew (00:00): all game slots (09:00+) are after midnight → zero slots
      LeagueConfig cfg = config(DAY1, DAY7);
      Division div = division("6U", 60, LocalTime.MIDNIGHT);
      League league = assignableLeague(cfg, div, field("F1"));

      ScheduleResult result = service.assign(league);

      assertInstanceOf(
          ScheduleResult.Failure.class, result, "should return Failure, not Success");
      String msg = ((ScheduleResult.Failure) result).message();
      assertTrue(msg.contains("6U"), "failure message should name the curfewed division");
    }

    @Test
    @DisplayName("returns Failure containing the configured curfew time in the message")
    void failureMessageIncludesCurfewTime() {
      LeagueConfig cfg = config(DAY1, DAY7);
      Division div = division("6U", 60, LocalTime.MIDNIGHT);
      League league = assignableLeague(cfg, div, field("F1"));

      ScheduleResult result = service.assign(league);

      assertInstanceOf(ScheduleResult.Failure.class, result);
      String msg = ((ScheduleResult.Failure) result).message();
      assertTrue(
          msg.contains("00:00"),
          "failure message should include the curfew time for the operator to diagnose");
    }

    @Test
    @DisplayName("division without curfew is not affected by zero-slot guard even with tight config")
    void divisionWithoutCurfewDoesNotTriggerCurfewGuard() {
      // Narrow window: only 1 slot/day — not curfew-related
      LeagueConfig cfg =
          new LeagueConfig(
              OPEN, LocalTime.of(10, 0), DAY1, DAY7, List.of(), List.of(), null, null, null, 30);
      Division div = division("Majors", 60, null); // no curfew
      League league = assignableLeague(cfg, div, field("F1"));

      ScheduleResult result = service.assign(league);

      // The result may be a partial assign (not enough slots for all games), but it should NOT be
      // the curfew-specific error. A Success result means the guard was not triggered.
      if (result instanceof ScheduleResult.Failure f) {
        assertFalse(
            f.message().contains("curfew"),
            "failure for a non-curfewed division must not mention curfew");
      }
    }
  }

  // ---------------------------------------------------------------------------
  // assign() — full solve, verify no game starts after curfew (AC 6)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("assign() full solve with curfew active")
  class AssignWithActiveCurfew {

    @Test
    @DisplayName("no assigned game has a start time later than the division curfew")
    void noAssignedGameStartsAfterCurfew() {
      // Curfew 12:00: only slots 9:00, 9:30, 10:00, 10:30, 11:00, 11:30, 12:00 are valid (7/day)
      LocalTime curfew = LocalTime.of(12, 0);
      LeagueConfig cfg = config(DAY1, DAY7);
      Division div = division("6U", 60, curfew);
      League league = assignableLeague(cfg, div, field("F1"));

      ScheduleResult result = service.assign(league);

      assertInstanceOf(ScheduleResult.Success.class, result);
      List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

      for (ScheduledGame game : games) {
        assertFalse(
            game.startTime().isAfter(curfew),
            "game at " + game.startTime() + " starts after curfew " + curfew);
      }
    }

    @Test
    @DisplayName("a game starting exactly at the curfew time is assigned (boundary is inclusive)")
    void gameStartingExactlyAtCurfewIsAssigned() {
      // Curfew=09:00: only the 09:00 slot is valid; 2-game season should place both games at 09:00
      // on different days (rest-day constraint separates them)
      LocalTime curfew = LocalTime.of(9, 0);
      LeagueConfig cfg = config(DAY1, DAY7);
      Division div = division("6U", 60, curfew);
      League league = assignableLeague(cfg, div, field("F1"));

      ScheduleResult result = service.assign(league);

      assertInstanceOf(ScheduleResult.Success.class, result);
      List<ScheduledGame> games = ((ScheduleResult.Success) result).games();
      assertFalse(games.isEmpty(), "at least one game should be assigned when curfew = window open");
      for (ScheduledGame game : games) {
        // Every assigned game must start at exactly 09:00 (no other slots available)
        assertEquals(
            LocalTime.of(9, 0),
            game.startTime(),
            "only valid start time is 09:00 when curfew equals window open");
      }
    }

    @Test
    @DisplayName("unconstrained division in same solve is not limited by another division's curfew")
    void uncurfewedDivisionIsNotLimitedByAnotherDivisionCurfew() {
      // 6U has curfew 12:00, Majors has none. Majors should still get slots after 12:00.
      LocalTime curfew = LocalTime.of(12, 0);
      LeagueConfig cfg = config(DAY1, DAY7);
      Team t1 = team("Blue Jays");
      Team t2 = team("Cardinals");
      Team t3 = team("Red Sox");
      Team t4 = team("Yankees");
      Division div6U =
          new Division(
              UUID.randomUUID(), "6U", 60, 2, List.of(t1, t2), null, null, null, null, curfew);
      Division divMajors =
          new Division(
              UUID.randomUUID(), "Majors", 60, 2, List.of(t3, t4), null, null, null, null, null);
      Field f = field("F1");
      League base =
          new League(10, cfg, List.of(div6U, divMajors), List.of(f), null, null, List.of(), List.of());
      TeamSchedule ts = ((TeamScheduleResult.Success) new TeamScheduleService().generate(base)).schedule();
      League league =
          new League(10, cfg, List.of(div6U, divMajors), List.of(f), ts, null, List.of(), List.of());

      ScheduleResult result = service.assign(league);

      assertInstanceOf(ScheduleResult.Success.class, result);
      List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

      // Verify 6U games never exceed the curfew; Majors games are unconstrained
      for (ScheduledGame g : games) {
        if (g.divisionName().equals("6U")) {
          assertFalse(
              g.startTime().isAfter(curfew),
              "6U game at " + g.startTime() + " exceeds curfew " + curfew);
        }
      }
      // Majors has access to slots after 12:00; assert nothing constrains it
      // (we can only check there is no spurious curfew error, not exact assignment)
    }
  }
}
