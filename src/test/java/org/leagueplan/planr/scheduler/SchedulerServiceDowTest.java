package org.leagueplan.planr.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.leagueplan.planr.model.DayOfWeekWindow;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.FieldDateOverride;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import org.leagueplan.planr.model.Team;

/**
 * Tests for how SchedulerService respects league-wide blocked days and day-of-week windows.
 *
 * <p>Uses estimateAvailableSlots() throughout — it exercises the same resolveOpenWindow()
 * precedence logic as the full solver but runs in milliseconds without invoking CP-SAT.
 *
 * <p>Key dates used across these tests: June 1, 2026 = Monday June 3, 2026 = Wednesday June 7, 2026
 * = Sunday
 */
class SchedulerServiceDowTest {

  private static final LocalTime T9 = LocalTime.of(9, 0);
  private static final LocalTime T13 = LocalTime.of(13, 0);
  private static final LocalTime T18 = LocalTime.of(18, 0);
  private static final LocalTime T21 = LocalTime.of(21, 0);

  // June 1, 2026 = Monday
  private static final LocalDate MONDAY = LocalDate.of(2026, 6, 1);
  // June 3, 2026 = Wednesday
  private static final LocalDate WEDNESDAY = LocalDate.of(2026, 6, 3);
  // June 7, 2026 = Sunday
  private static final LocalDate SUNDAY = LocalDate.of(2026, 6, 7);

  private static final int GAME_DURATION = 60; // minutes

  private final SchedulerService service = new SchedulerService();

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Field fieldWithNoEntries(String name) {
    return new Field(UUID.randomUUID(), name, null, List.of(), List.of(), List.of(), null);
  }

  private static Field fieldWithOverride(String name, FieldDateOverride override) {
    return new Field(UUID.randomUUID(), name, null, List.of(), List.of(override), List.of(), null);
  }

  private static Division division(String name) {
    Team t1 = new Team(UUID.randomUUID(), "Blue Jays");
    Team t2 = new Team(UUID.randomUUID(), "Cardinals");
    return new Division(
        UUID.randomUUID(), name, GAME_DURATION, 2, List.of(t1, t2), null, null, null, null, null);
  }

  /** Build a league with no team schedule (sufficient for slot estimation). */
  private static League leagueFor(LeagueConfig config, Field field) {
    Division div = division("Majors");
    return new League(5, config, List.of(div), List.of(field), null, null, List.of(), List.of());
  }

  /**
   * Slot count for a window [open, close) with 60-minute games on the default 30-minute grid:
   * Formula: floor((close - open - duration) / grid) + 1
   */
  private static int expectedSlots(LocalTime open, LocalTime close, int durationMinutes) {
    int windowMinutes = (int) java.time.Duration.between(open, close).toMinutes();
    int gridMinutes = SchedulerService.DEFAULT_GRID_MINUTES;
    return (windowMinutes - durationMinutes) / gridMinutes + 1;
  }

  // ---------------------------------------------------------------------------
  // Blocked days
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("blocked days")
  class BlockedDays {

    @Test
    @DisplayName("a single-day season on a blocked day yields 0 available slots")
    void blockedDayYieldsZeroSlots() {
      // Season = June 7 (Sunday) only; Sunday is blocked
      LeagueConfig config =
          new LeagueConfig(
              T9,
              T18,
              SUNDAY,
              SUNDAY,
              List.of(),
              List.of(DayOfWeek.SUNDAY),
              null,
              null,
              null,
              null);
      League league = leagueFor(config, fieldWithNoEntries("Field A"));

      int slots = service.estimateAvailableSlots(league, divId(league), GAME_DURATION);
      assertEquals(0, slots);
    }

    @Test
    @DisplayName("a multi-day season with one day blocked omits that day from the slot count")
    void blockedDayIsOmittedFromMultiDaySeason() {
      // Season = June 1 (Mon) – June 7 (Sun); Sunday is blocked → 6 eligible days
      LeagueConfig config =
          new LeagueConfig(
              T9,
              T18,
              MONDAY,
              SUNDAY,
              List.of(),
              List.of(DayOfWeek.SUNDAY),
              null,
              null,
              null,
              null);
      League league = leagueFor(config, fieldWithNoEntries("Field A"));

      int slotsWithBlock = service.estimateAvailableSlots(league, divId(league), GAME_DURATION);

      // Compare against identical league without the block
      LeagueConfig noBlock =
          new LeagueConfig(T9, T18, MONDAY, SUNDAY, List.of(), List.of(), null, null, null, null);
      League leagueNoBlock = leagueFor(noBlock, fieldWithNoEntries("Field A"));
      int slotsWithoutBlock =
          service.estimateAvailableSlots(leagueNoBlock, divId(leagueNoBlock), GAME_DURATION);

      int perDaySlots = expectedSlots(T9, T18, GAME_DURATION); // 33
      assertEquals(
          slotsWithoutBlock - perDaySlots,
          slotsWithBlock,
          "blocking Sunday should remove exactly one day's worth of slots");
    }

    @Test
    @DisplayName("blocking all days in a season yields 0 slots")
    void allDaysBlockedYieldsZeroSlots() {
      // Season = June 1–7; block all seven days
      LeagueConfig config =
          new LeagueConfig(
              T9,
              T18,
              MONDAY,
              SUNDAY,
              List.of(),
              List.of(
                  DayOfWeek.MONDAY,
                  DayOfWeek.TUESDAY,
                  DayOfWeek.WEDNESDAY,
                  DayOfWeek.THURSDAY,
                  DayOfWeek.FRIDAY,
                  DayOfWeek.SATURDAY,
                  DayOfWeek.SUNDAY),
              null,
              null,
              null,
              null);
      League league = leagueFor(config, fieldWithNoEntries("Field A"));

      assertEquals(0, service.estimateAvailableSlots(league, divId(league), GAME_DURATION));
    }
  }

  // ---------------------------------------------------------------------------
  // FieldDateOverride beats blocked day (priority 1 > priority 2)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("FieldDateOverride takes priority over a blocked day")
  class OverrideBeatsBock {

    @Test
    @DisplayName("a FieldDateOverride on a blocked day returns slots for that day")
    void overrideOnBlockedDayReturnsSlots() {
      // Sunday is blocked league-wide, but this specific field has an override for June 7
      FieldDateOverride override =
          new FieldDateOverride(UUID.randomUUID(), SUNDAY, T9, T13); // 09:00–13:00
      Field field = fieldWithOverride("Field A", override);

      LeagueConfig config =
          new LeagueConfig(
              T9,
              T18,
              SUNDAY,
              SUNDAY,
              List.of(),
              List.of(DayOfWeek.SUNDAY),
              null,
              null,
              null,
              null);
      League league = leagueFor(config, field);

      int slots = service.estimateAvailableSlots(league, divId(league), GAME_DURATION);
      int expected = expectedSlots(T9, T13, GAME_DURATION); // 09:00–13:00
      assertEquals(expected, slots, "FieldDateOverride should rescue slots on a blocked day");
    }

    @Test
    @DisplayName("override window (not global sunrise/sunset) determines slot count on blocked day")
    void overrideWindowDeterminesSlots() {
      // Override provides 09:00–13:00 (4 hours), not the global 09:00–18:00
      FieldDateOverride override = new FieldDateOverride(UUID.randomUUID(), SUNDAY, T9, T13);
      Field field = fieldWithOverride("Field A", override);

      LeagueConfig config =
          new LeagueConfig(
              T9,
              T18,
              SUNDAY,
              SUNDAY,
              List.of(),
              List.of(DayOfWeek.SUNDAY),
              null,
              null,
              null,
              null);
      League league = leagueFor(config, field);

      int slots = service.estimateAvailableSlots(league, divId(league), GAME_DURATION);
      int expectedFromOverride = expectedSlots(T9, T13, GAME_DURATION);
      int expectedFromGlobal = expectedSlots(T9, T18, GAME_DURATION);

      assertEquals(expectedFromOverride, slots);
      assertNotEquals(
          expectedFromGlobal,
          slots,
          "slots should reflect override window, not global sunrise/sunset");
    }
  }

  // ---------------------------------------------------------------------------
  // Day-of-week windows (priority 3)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("day-of-week windows")
  class DowWindows {

    @Test
    @DisplayName("a DOW window narrows the slot count to the configured window")
    void dowWindowNarrowsSlots() {
      // Monday window: 16:00–21:00 (5 hours instead of global 09:00–18:00)
      DayOfWeekWindow monWindow = new DayOfWeekWindow(DayOfWeek.MONDAY, LocalTime.of(16, 0), T21);
      LeagueConfig config =
          new LeagueConfig(
              T9, T18, MONDAY, MONDAY, List.of(monWindow), List.of(), null, null, null, null);
      League league = leagueFor(config, fieldWithNoEntries("Field A"));

      int slots = service.estimateAvailableSlots(league, divId(league), GAME_DURATION);
      int expected = expectedSlots(LocalTime.of(16, 0), T21, GAME_DURATION);
      assertEquals(
          expected, slots, "slot count should match the DOW window, not the global sunrise/sunset");
    }

    @Test
    @DisplayName("a DOW window for a different day does not affect other days")
    void dowWindowForOtherDayDoesNotApply() {
      // Wednesday window; season is only Monday — window should have no effect
      DayOfWeekWindow wedWindow =
          new DayOfWeekWindow(DayOfWeek.WEDNESDAY, LocalTime.of(16, 0), T21);
      LeagueConfig config =
          new LeagueConfig(
              T9, T18, MONDAY, MONDAY, List.of(wedWindow), List.of(), null, null, null, null);
      League league = leagueFor(config, fieldWithNoEntries("Field A"));

      int slots = service.estimateAvailableSlots(league, divId(league), GAME_DURATION);
      int expected = expectedSlots(T9, T18, GAME_DURATION); // global sunrise/sunset applies
      assertEquals(expected, slots, "Wednesday DOW window should not affect a Monday-only season");
    }

    @Test
    @DisplayName("DOW window slot count is consistent across multiple matching days in the season")
    void dowWindowAppliesConsistentlyAcrossAllMatchingDays() {
      // Season = June 1 (Mon) – June 7 (Sun); Wednesday window narrows Wed only
      DayOfWeekWindow wedWindow =
          new DayOfWeekWindow(DayOfWeek.WEDNESDAY, LocalTime.of(16, 0), T21);
      LeagueConfig configWithWindow =
          new LeagueConfig(
              T9, T18, MONDAY, SUNDAY, List.of(wedWindow), List.of(), null, null, null, null);
      League leagueWithWindow = leagueFor(configWithWindow, fieldWithNoEntries("Field A"));

      LeagueConfig configNoWindow =
          new LeagueConfig(T9, T18, MONDAY, SUNDAY, List.of(), List.of(), null, null, null, null);
      League leagueNoWindow = leagueFor(configNoWindow, fieldWithNoEntries("Field A"));

      int slotsWithWindow =
          service.estimateAvailableSlots(leagueWithWindow, divId(leagueWithWindow), GAME_DURATION);
      int slotsWithoutWindow =
          service.estimateAvailableSlots(leagueNoWindow, divId(leagueNoWindow), GAME_DURATION);

      int globalPerDay = expectedSlots(T9, T18, GAME_DURATION);
      int windowPerDay = expectedSlots(LocalTime.of(16, 0), T21, GAME_DURATION);
      int diff = globalPerDay - windowPerDay;

      assertEquals(
          slotsWithoutWindow - diff,
          slotsWithWindow,
          "applying the window to Wednesday should reduce slots by exactly one day's difference");
    }
  }

  // ---------------------------------------------------------------------------
  // Precedence ordering — FieldDateOverride > blocked day > DOW window > global
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("four-level precedence chain")
  class Precedence {

    @Test
    @DisplayName("FieldDateOverride beats a DOW window on the same day")
    void overrideBeatsDowWindow() {
      // DOW window for Sunday: 16:00–21:00
      // Field override for June 7 (Sunday): 09:00–13:00
      DayOfWeekWindow sunWindow = new DayOfWeekWindow(DayOfWeek.SUNDAY, LocalTime.of(16, 0), T21);
      FieldDateOverride override = new FieldDateOverride(UUID.randomUUID(), SUNDAY, T9, T13);
      Field field = fieldWithOverride("Field A", override);

      LeagueConfig config =
          new LeagueConfig(
              T9, T18, SUNDAY, SUNDAY, List.of(sunWindow), List.of(), null, null, null, null);
      League league = leagueFor(config, field);

      int slots = service.estimateAvailableSlots(league, divId(league), GAME_DURATION);
      int expectedFromOverride = expectedSlots(T9, T13, GAME_DURATION);
      assertEquals(expectedFromOverride, slots, "FieldDateOverride should win over a DOW window");
    }

    @Test
    @DisplayName("DOW window beats global sunrise/sunset when no override or block applies")
    void dowWindowBeatsGlobal() {
      // Sunday window narrows from global 09:00–18:00 to 16:00–21:00
      DayOfWeekWindow sunWindow = new DayOfWeekWindow(DayOfWeek.SUNDAY, LocalTime.of(16, 0), T21);
      LeagueConfig config =
          new LeagueConfig(
              T9, T18, SUNDAY, SUNDAY, List.of(sunWindow), List.of(), null, null, null, null);
      League league = leagueFor(config, fieldWithNoEntries("Field A"));

      int slots = service.estimateAvailableSlots(league, divId(league), GAME_DURATION);
      int expectedFromWindow = expectedSlots(LocalTime.of(16, 0), T21, GAME_DURATION);
      int expectedFromGlobal = expectedSlots(T9, T18, GAME_DURATION);
      assertEquals(expectedFromWindow, slots);
      assertNotEquals(expectedFromGlobal, slots);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static UUID divId(League league) {
    return league.divisions().get(0).id();
  }
}
