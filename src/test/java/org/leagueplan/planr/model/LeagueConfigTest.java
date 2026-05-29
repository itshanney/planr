package org.leagueplan.planr.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LeagueConfigTest {

  private static final LocalTime T9 = LocalTime.of(9, 0);
  private static final LocalTime T17 = LocalTime.of(17, 0);
  private static final LocalTime T18 = LocalTime.of(18, 0);
  private static final LocalTime T21 = LocalTime.of(21, 0);

  // -------------------------------------------------------------------------
  // Compact constructor null normalization
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("compact constructor")
  class CompactConstructor {

    @Test
    @DisplayName("normalizes null dowWindows to empty list")
    void normalizesNullDowWindows() {
      LeagueConfig config =
          new LeagueConfig(T9, T18, null, null, null, List.of(), null, null, null, null);
      assertNotNull(config.dowWindows());
      assertTrue(config.dowWindows().isEmpty());
    }

    @Test
    @DisplayName("normalizes null blockedDays to empty list")
    void normalizesNullBlockedDays() {
      LeagueConfig config =
          new LeagueConfig(T9, T18, null, null, List.of(), null, null, null, null, null);
      assertNotNull(config.blockedDays());
      assertTrue(config.blockedDays().isEmpty());
    }

    @Test
    @DisplayName("preserves non-null dowWindows unchanged")
    void preservesNonNullDowWindows() {
      DayOfWeekWindow window = new DayOfWeekWindow(DayOfWeek.WEDNESDAY, T17, T21);
      LeagueConfig config =
          new LeagueConfig(T9, T18, null, null, List.of(window), List.of(), null, null, null, null);
      assertEquals(1, config.dowWindows().size());
      assertEquals(DayOfWeek.WEDNESDAY, config.dowWindows().get(0).day());
    }
  }

  // -------------------------------------------------------------------------
  // withDowWindowSet
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("withDowWindowSet")
  class WithDowWindowSet {

    @Test
    @DisplayName("adds a window when no window exists for that day")
    void addsWindowForNewDay() {
      LeagueConfig config = LeagueConfig.empty();
      DayOfWeekWindow window = new DayOfWeekWindow(DayOfWeek.WEDNESDAY, T17, T21);
      LeagueConfig updated = config.withDowWindowSet(window);

      assertEquals(1, updated.dowWindows().size());
      assertEquals(DayOfWeek.WEDNESDAY, updated.dowWindows().get(0).day());
      assertEquals(T17, updated.dowWindows().get(0).openStart());
      assertEquals(T21, updated.dowWindows().get(0).openEnd());
    }

    @Test
    @DisplayName("replaces the existing window when one already exists for that day")
    void replacesExistingWindow() {
      DayOfWeekWindow original = new DayOfWeekWindow(DayOfWeek.WEDNESDAY, T9, T18);
      LeagueConfig config = LeagueConfig.empty().withDowWindowSet(original);

      DayOfWeekWindow replacement = new DayOfWeekWindow(DayOfWeek.WEDNESDAY, T17, T21);
      LeagueConfig updated = config.withDowWindowSet(replacement);

      assertEquals(1, updated.dowWindows().size());
      assertEquals(T17, updated.dowWindows().get(0).openStart());
      assertEquals(T21, updated.dowWindows().get(0).openEnd());
    }

    @Test
    @DisplayName("does not affect windows for other days")
    void preservesOtherDayWindows() {
      LeagueConfig config =
          LeagueConfig.empty().withDowWindowSet(new DayOfWeekWindow(DayOfWeek.MONDAY, T9, T18));

      LeagueConfig updated =
          config.withDowWindowSet(new DayOfWeekWindow(DayOfWeek.WEDNESDAY, T17, T21));

      assertEquals(2, updated.dowWindows().size());
      assertTrue(updated.dowWindows().stream().anyMatch(w -> w.day() == DayOfWeek.MONDAY));
      assertTrue(updated.dowWindows().stream().anyMatch(w -> w.day() == DayOfWeek.WEDNESDAY));
    }

    @Test
    @DisplayName("does not mutate the original config")
    void isImmutable() {
      LeagueConfig config = LeagueConfig.empty();
      config.withDowWindowSet(new DayOfWeekWindow(DayOfWeek.WEDNESDAY, T17, T21));
      assertTrue(config.dowWindows().isEmpty());
    }
  }

  // -------------------------------------------------------------------------
  // withDowWindowRemoved
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("withDowWindowRemoved")
  class WithDowWindowRemoved {

    @Test
    @DisplayName("removes the window for the specified day")
    void removesWindowForSpecifiedDay() {
      LeagueConfig config =
          LeagueConfig.empty().withDowWindowSet(new DayOfWeekWindow(DayOfWeek.WEDNESDAY, T17, T21));

      LeagueConfig updated = config.withDowWindowRemoved(DayOfWeek.WEDNESDAY);
      assertTrue(updated.dowWindows().isEmpty());
    }

    @Test
    @DisplayName("returns empty list when day had no window")
    void isNoOpWhenDayHasNoWindow() {
      LeagueConfig config = LeagueConfig.empty();
      LeagueConfig updated = config.withDowWindowRemoved(DayOfWeek.FRIDAY);
      assertTrue(updated.dowWindows().isEmpty());
    }

    @Test
    @DisplayName("preserves windows for other days when removing one")
    void preservesOtherWindows() {
      LeagueConfig config =
          LeagueConfig.empty()
              .withDowWindowSet(new DayOfWeekWindow(DayOfWeek.MONDAY, T9, T18))
              .withDowWindowSet(new DayOfWeekWindow(DayOfWeek.WEDNESDAY, T17, T21));

      LeagueConfig updated = config.withDowWindowRemoved(DayOfWeek.WEDNESDAY);

      assertEquals(1, updated.dowWindows().size());
      assertEquals(DayOfWeek.MONDAY, updated.dowWindows().get(0).day());
    }
  }

  // -------------------------------------------------------------------------
  // withBlockedDayAdded
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("withBlockedDayAdded")
  class WithBlockedDayAdded {

    @Test
    @DisplayName("adds a blocked day to an empty list")
    void addsToEmptyList() {
      LeagueConfig config = LeagueConfig.empty().withBlockedDayAdded(DayOfWeek.SUNDAY);
      assertEquals(1, config.blockedDays().size());
      assertTrue(config.blockedDays().contains(DayOfWeek.SUNDAY));
    }

    @Test
    @DisplayName("appends without removing existing blocked days")
    void appendsToExistingList() {
      LeagueConfig config =
          LeagueConfig.empty()
              .withBlockedDayAdded(DayOfWeek.SATURDAY)
              .withBlockedDayAdded(DayOfWeek.SUNDAY);
      assertEquals(2, config.blockedDays().size());
      assertTrue(config.blockedDays().contains(DayOfWeek.SATURDAY));
      assertTrue(config.blockedDays().contains(DayOfWeek.SUNDAY));
    }

    @Test
    @DisplayName("does not mutate the original config")
    void isImmutable() {
      LeagueConfig config = LeagueConfig.empty();
      config.withBlockedDayAdded(DayOfWeek.SUNDAY);
      assertTrue(config.blockedDays().isEmpty());
    }
  }

  // -------------------------------------------------------------------------
  // withBlockedDayRemoved
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("withBlockedDayRemoved")
  class WithBlockedDayRemoved {

    @Test
    @DisplayName("removes the specified day from blocked days")
    void removesSpecifiedDay() {
      LeagueConfig config = LeagueConfig.empty().withBlockedDayAdded(DayOfWeek.SUNDAY);
      LeagueConfig updated = config.withBlockedDayRemoved(DayOfWeek.SUNDAY);
      assertTrue(updated.blockedDays().isEmpty());
    }

    @Test
    @DisplayName("returns empty list when day was not blocked")
    void isNoOpWhenDayNotBlocked() {
      LeagueConfig config = LeagueConfig.empty();
      LeagueConfig updated = config.withBlockedDayRemoved(DayOfWeek.FRIDAY);
      assertTrue(updated.blockedDays().isEmpty());
    }

    @Test
    @DisplayName("preserves other blocked days when removing one")
    void preservesOtherBlockedDays() {
      LeagueConfig config =
          LeagueConfig.empty()
              .withBlockedDayAdded(DayOfWeek.SATURDAY)
              .withBlockedDayAdded(DayOfWeek.SUNDAY);

      LeagueConfig updated = config.withBlockedDayRemoved(DayOfWeek.SUNDAY);

      assertEquals(1, updated.blockedDays().size());
      assertTrue(updated.blockedDays().contains(DayOfWeek.SATURDAY));
    }
  }

  // -------------------------------------------------------------------------
  // withFieldBufferMinutes
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("withFieldBufferMinutes")
  class WithFieldBufferMinutes {

    @Test
    @DisplayName("sets the field buffer value on an empty config")
    void setsValue() {
      LeagueConfig updated = LeagueConfig.empty().withFieldBufferMinutes(15);
      assertEquals(15, updated.fieldBufferMinutes());
    }

    @Test
    @DisplayName("zero is a valid field buffer value")
    void acceptsZero() {
      LeagueConfig updated = LeagueConfig.empty().withFieldBufferMinutes(0);
      assertEquals(0, updated.fieldBufferMinutes());
    }

    @Test
    @DisplayName("threads all other fields through unchanged")
    void preservesOtherFields() {
      LeagueConfig base =
          new LeagueConfig(T9, T18, null, null, List.of(), List.of(), 3, 2, null, null);
      LeagueConfig updated = base.withFieldBufferMinutes(10);

      assertEquals(T9, updated.sunriseTime());
      assertEquals(T18, updated.sunsetTime());
      assertEquals(3, updated.maxGamesPerWeek());
      assertEquals(2, updated.minRestDays());
      assertNull(updated.gridMinutes());
      assertEquals(10, updated.fieldBufferMinutes());
    }

    @Test
    @DisplayName("null clears the field buffer (unset sentinel)")
    void acceptsNull() {
      LeagueConfig base = LeagueConfig.empty().withFieldBufferMinutes(15);
      LeagueConfig cleared = base.withFieldBufferMinutes(null);
      assertNull(cleared.fieldBufferMinutes());
    }

    @Test
    @DisplayName("does not mutate the original config")
    void isImmutable() {
      LeagueConfig config = LeagueConfig.empty();
      config.withFieldBufferMinutes(30);
      assertNull(config.fieldBufferMinutes());
    }
  }

  // -------------------------------------------------------------------------
  // withGridMinutes
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("withGridMinutes")
  class WithGridMinutes {

    @Test
    @DisplayName("sets the grid minutes value on an empty config")
    void setsValue() {
      LeagueConfig updated = LeagueConfig.empty().withGridMinutes(30);
      assertEquals(30, updated.gridMinutes());
    }

    @Test
    @DisplayName("threads all other fields through unchanged")
    void preservesOtherFields() {
      LeagueConfig base =
          new LeagueConfig(T9, T18, null, null, List.of(), List.of(), 3, 2, 10, null);
      LeagueConfig updated = base.withGridMinutes(15);

      assertEquals(T9, updated.sunriseTime());
      assertEquals(T18, updated.sunsetTime());
      assertEquals(3, updated.maxGamesPerWeek());
      assertEquals(2, updated.minRestDays());
      assertEquals(10, updated.fieldBufferMinutes());
      assertEquals(15, updated.gridMinutes());
    }

    @Test
    @DisplayName("null clears the grid minutes (unset sentinel)")
    void acceptsNull() {
      LeagueConfig base = LeagueConfig.empty().withGridMinutes(15);
      LeagueConfig cleared = base.withGridMinutes(null);
      assertNull(cleared.gridMinutes());
    }

    @Test
    @DisplayName("does not mutate the original config")
    void isImmutable() {
      LeagueConfig config = LeagueConfig.empty();
      config.withGridMinutes(60);
      assertNull(config.gridMinutes());
    }
  }
}
