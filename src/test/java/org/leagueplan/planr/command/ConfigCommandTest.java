package org.leagueplan.planr.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConfigCommandTest extends CommandTestBase {

  // -------------------------------------------------------------------------
  // config set
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("config set")
  class Set {

    @Test
    @DisplayName("exits 0 and prints confirmation when setting sunrise only")
    void setSunriseOnly() {
      int exit = execute("config", "set", "--sunrise", "07:00");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));
    }

    @Test
    @DisplayName("exits 0 and prints confirmation when setting sunset only")
    void setSunsetOnly() {
      int exit = execute("config", "set", "--sunset", "20:00");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));
    }

    @Test
    @DisplayName("exits 0 when setting all four options at once")
    void setAllOptions() {
      int exit =
          execute(
              "config",
              "set",
              "--sunrise",
              "07:00",
              "--sunset",
              "20:00",
              "--start",
              "2026-06-01",
              "--end",
              "2026-08-31");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("exits 1 when no options are provided")
    void failsWhenNoOptionsProvided() {
      int exit = execute("config", "set");
      assertEquals(1, exit);
      assertTrue(stderr().contains("At least one"));
    }

    @Test
    @DisplayName("exits 1 when sunrise time format is invalid")
    void failsOnInvalidSunriseFormat() {
      int exit = execute("config", "set", "--sunrise", "7am");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Invalid time"));
    }

    @Test
    @DisplayName("exits 1 when sunset time format is invalid")
    void failsOnInvalidSunsetFormat() {
      int exit = execute("config", "set", "--sunset", "8pm");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Invalid time"));
    }

    @Test
    @DisplayName("exits 1 when both sunrise and sunset provided and sunrise >= sunset")
    void failsWhenSunriseAfterSunset() {
      int exit = execute("config", "set", "--sunrise", "20:00", "--sunset", "07:00");
      assertEquals(1, exit);
      assertTrue(stderr().contains("after"));
    }

    @Test
    @DisplayName("exits 1 when season start date format is invalid")
    void failsOnInvalidStartDateFormat() {
      int exit = execute("config", "set", "--start", "June 1");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Invalid date"));
    }

    @Test
    @DisplayName("exits 1 when season end date format is invalid")
    void failsOnInvalidEndDateFormat() {
      int exit = execute("config", "set", "--end", "08/31/2026");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Invalid date"));
    }

    @Test
    @DisplayName("exits 1 when both season dates provided and start >= end")
    void failsWhenSeasonStartAfterEnd() {
      int exit = execute("config", "set", "--start", "2026-09-01", "--end", "2026-06-01");
      assertEquals(1, exit);
      assertTrue(stderr().contains("after"));
    }

    @Test
    @DisplayName("persists settings so they survive across invocations")
    void persistsAcrossInvocations() {
      execute("config", "set", "--sunrise", "07:30", "--sunset", "19:30");
      execute("config", "show");
      String out = stdout();
      assertTrue(out.contains("07:30"));
      assertTrue(out.contains("19:30"));
    }

    @Test
    @DisplayName("subsequent set calls merge with existing config — unset fields are preserved")
    void mergesWithExistingConfig() {
      execute("config", "set", "--sunrise", "07:00");
      execute("config", "set", "--sunset", "20:00");
      execute("config", "show");
      String out = stdout();
      assertTrue(out.contains("07:00"));
      assertTrue(out.contains("20:00"));
    }

    @Test
    @DisplayName("exits 0 when setting max-games-per-week to a valid value")
    void setsMaxGamesPerWeek() {
      int exit = execute("config", "set", "--max-games-per-week", "3");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));
    }

    @Test
    @DisplayName("exits 1 when max-games-per-week is zero")
    void failsWhenMaxGamesPerWeekIsZero() {
      int exit = execute("config", "set", "--max-games-per-week", "0");
      assertEquals(1, exit);
      assertTrue(stderr().contains("positive integer"));
    }

    @Test
    @DisplayName("exits 1 when max-games-per-week is negative")
    void failsWhenMaxGamesPerWeekIsNegative() {
      int exit = execute("config", "set", "--max-games-per-week", "-1");
      assertEquals(1, exit);
      assertTrue(stderr().contains("positive integer"));
    }

    @Test
    @DisplayName("exits 0 when setting rest-days to a positive value")
    void setsRestDays() {
      int exit = execute("config", "set", "--rest-days", "2");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));
    }

    @Test
    @DisplayName("exits 0 when setting rest-days to 0 (no rest required)")
    void setsRestDaysToZero() {
      int exit = execute("config", "set", "--rest-days", "0");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("exits 1 when rest-days is negative")
    void failsWhenRestDaysIsNegative() {
      int exit = execute("config", "set", "--rest-days", "-1");
      assertEquals(1, exit);
      assertTrue(stderr().contains("non-negative integer"));
    }

    @Test
    @DisplayName("exits 0 when setting max-games-per-week and rest-days together")
    void setsMaxGamesAndRestDaysTogether() {
      int exit = execute("config", "set", "--max-games-per-week", "3", "--rest-days", "2");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("persists max-games-per-week so it appears in config show")
    void persistsMaxGamesPerWeek() {
      execute("config", "set", "--max-games-per-week", "3");
      execute("config", "show");
      assertTrue(stdout().contains("3"));
    }

    @Test
    @DisplayName("persists rest-days so it appears in config show")
    void persistsRestDays() {
      execute("config", "set", "--rest-days", "2");
      execute("config", "show");
      assertTrue(stdout().contains("2"));
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      int exit = execute("config", "set", "--sunrise", "07:00");
      assertEquals(2, exit);
      assertTrue(stderr().contains("Failed to access league data"));
    }

    // --field-buffer-minutes

    @Test
    @DisplayName("exits 0 when setting field-buffer-minutes to 0 (back-to-back allowed)")
    void setsFieldBufferToZero() {
      int exit = execute("config", "set", "--field-buffer-minutes", "0");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));
    }

    @Test
    @DisplayName("exits 0 when setting field-buffer-minutes to a positive integer")
    void setsFieldBufferToPositiveValue() {
      int exit = execute("config", "set", "--field-buffer-minutes", "15");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));
    }

    @Test
    @DisplayName("exits 1 when field-buffer-minutes is negative")
    void failsWhenFieldBufferIsNegative() {
      int exit = execute("config", "set", "--field-buffer-minutes", "-1");
      assertEquals(1, exit);
      assertTrue(stderr().contains("non-negative integer"));
    }

    @Test
    @DisplayName("exits 1 when field-buffer-minutes is not a valid integer")
    void failsWhenFieldBufferIsNotAnInteger() {
      int exit = execute("config", "set", "--field-buffer-minutes", "abc");
      assertEquals(1, exit);
      assertTrue(stderr().contains("non-negative integer"));
    }

    @Test
    @DisplayName("persists field-buffer-minutes so it appears in config show")
    void persistsFieldBufferMinutes() {
      execute("config", "set", "--field-buffer-minutes", "10");
      execute("config", "show");
      assertTrue(stdout().contains("10 min"));
    }

    @Test
    @DisplayName("field-buffer-minutes merges with existing config without touching other fields")
    void fieldBufferMergesWithExistingConfig() {
      execute("config", "set", "--sunrise", "08:00");
      execute("config", "set", "--field-buffer-minutes", "20");
      execute("config", "show");
      String out = stdout();
      assertTrue(out.contains("08:00"), "sunrise should be preserved");
      assertTrue(out.contains("20 min"), "field buffer should be set");
    }

    // --grid-minutes

    @Test
    @DisplayName("exits 0 when setting grid-minutes to 30 (the default value)")
    void setsGridToThirty() {
      int exit = execute("config", "set", "--grid-minutes", "30");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));
    }

    @Test
    @DisplayName("exits 0 when setting grid-minutes to 15")
    void setsGridToFifteen() {
      int exit = execute("config", "set", "--grid-minutes", "15");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("exits 0 when setting grid-minutes to 60 (one game per hour)")
    void setsGridToSixty() {
      int exit = execute("config", "set", "--grid-minutes", "60");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("exits 0 when setting grid-minutes to 1 (finest granularity)")
    void setsGridToOne() {
      int exit = execute("config", "set", "--grid-minutes", "1");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("exits 1 when grid-minutes is 0")
    void failsWhenGridIsZero() {
      int exit = execute("config", "set", "--grid-minutes", "0");
      assertEquals(1, exit);
      assertTrue(stderr().contains("evenly divides 60"));
    }

    @Test
    @DisplayName("exits 1 when grid-minutes is negative")
    void failsWhenGridIsNegative() {
      int exit = execute("config", "set", "--grid-minutes", "-5");
      assertEquals(1, exit);
      assertTrue(stderr().contains("evenly divides 60"));
    }

    @Test
    @DisplayName("exits 1 when grid-minutes does not evenly divide 60 (7 is rejected)")
    void failsWhenGridDoesNotDivideSixty() {
      int exit = execute("config", "set", "--grid-minutes", "7");
      assertEquals(1, exit);
      assertTrue(stderr().contains("evenly divides 60"));
    }

    @Test
    @DisplayName("exits 1 when grid-minutes exceeds 60 (120 is rejected)")
    void failsWhenGridExceedsSixty() {
      int exit = execute("config", "set", "--grid-minutes", "120");
      assertEquals(1, exit);
      assertTrue(stderr().contains("evenly divides 60"));
    }

    @Test
    @DisplayName("exits 1 when grid-minutes is not a valid integer")
    void failsWhenGridIsNotAnInteger() {
      int exit = execute("config", "set", "--grid-minutes", "half");
      assertEquals(1, exit);
      assertTrue(stderr().contains("evenly divides 60"));
    }

    @Test
    @DisplayName("persists grid-minutes so it appears in config show")
    void persistsGridMinutes() {
      execute("config", "set", "--grid-minutes", "15");
      execute("config", "show");
      assertTrue(stdout().contains("15 min"));
    }

    @Test
    @DisplayName("field-buffer-minutes and grid-minutes can be set together")
    void setsFieldBufferAndGridTogether() {
      int exit = execute("config", "set", "--field-buffer-minutes", "0", "--grid-minutes", "30");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));
    }
  }

  // -------------------------------------------------------------------------
  // config show
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("config show")
  class Show {

    @Test
    @DisplayName("exits 0 and shows (not set) for all fields when nothing is configured")
    void showsNotSetWhenUnconfigured() {
      int exit = execute("config", "show");
      assertEquals(0, exit);
      assertTrue(stdout().contains("(not set)"));
    }

    @Test
    @DisplayName("shows all four fields")
    void showsFourFields() {
      execute("config", "show");
      String out = stdout();
      assertTrue(out.contains("Sunrise"));
      assertTrue(out.contains("Sunset"));
      assertTrue(out.contains("Season start"));
      assertTrue(out.contains("Season end"));
    }

    @Test
    @DisplayName("shows configured values after a set")
    void showsConfiguredValues() {
      execute(
          "config",
          "set",
          "--sunrise",
          "08:00",
          "--sunset",
          "18:00",
          "--start",
          "2026-06-01",
          "--end",
          "2026-08-31");
      execute("config", "show");
      String out = stdout();
      assertTrue(out.contains("08:00"));
      assertTrue(out.contains("18:00"));
      assertTrue(out.contains("2026-06-01"));
      assertTrue(out.contains("2026-08-31"));
    }

    @Test
    @DisplayName("shows max games/week and min rest days fields")
    void showsConstraintFields() {
      execute("config", "show");
      String out = stdout();
      assertTrue(out.contains("Max games/week"));
      assertTrue(out.contains("Min rest days"));
    }

    @Test
    @DisplayName("shows (default) for max-games-per-week when not explicitly set")
    void showsDefaultLabelForMaxGamesPerWeek() {
      execute("config", "show");
      assertTrue(
          stdout()
              .lines()
              .filter(l -> l.contains("Max games/week"))
              .anyMatch(l -> l.contains("(default)")));
    }

    @Test
    @DisplayName("shows (default) for rest-days when not explicitly set")
    void showsDefaultLabelForRestDays() {
      execute("config", "show");
      assertTrue(
          stdout()
              .lines()
              .filter(l -> l.contains("Min rest days"))
              .anyMatch(l -> l.contains("(default)")));
    }

    @Test
    @DisplayName("shows explicit value (no default label) for max-games-per-week when set")
    void showsExplicitMaxGamesValue() {
      execute("config", "set", "--max-games-per-week", "3");
      execute("config", "show");
      assertTrue(
          stdout()
              .lines()
              .filter(l -> l.contains("Max games/week"))
              .anyMatch(l -> l.contains("3") && !l.contains("(default)")));
    }

    @Test
    @DisplayName("shows explicit value (no default label) for rest-days when set")
    void showsExplicitRestDaysValue() {
      execute("config", "set", "--rest-days", "0");
      execute("config", "show");
      assertTrue(
          stdout()
              .lines()
              .filter(l -> l.contains("Min rest days"))
              .anyMatch(l -> !l.contains("(default)")));
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      int exit = execute("config", "show");
      assertEquals(2, exit);
      assertTrue(stderr().contains("Failed to access league data"));
    }

    @Test
    @DisplayName("shows Field buffer and Grid interval labels")
    void showsBufferAndGridLabels() {
      execute("config", "show");
      String out = stdout();
      assertTrue(out.contains("Field buffer"), "should show 'Field buffer' label");
      assertTrue(out.contains("Grid interval"), "should show 'Grid interval' label");
    }

    @Test
    @DisplayName("shows (default) for field-buffer-minutes when not explicitly set")
    void showsDefaultLabelForFieldBuffer() {
      execute("config", "show");
      assertTrue(
          stdout()
              .lines()
              .filter(l -> l.contains("Field buffer"))
              .anyMatch(l -> l.contains("(default)")),
          "unset field buffer should display '(default)'");
    }

    @Test
    @DisplayName("shows (default) for grid-minutes when not explicitly set")
    void showsDefaultLabelForGridInterval() {
      execute("config", "show");
      assertTrue(
          stdout()
              .lines()
              .filter(l -> l.contains("Grid interval"))
              .anyMatch(l -> l.contains("(default)")),
          "unset grid interval should display '(default)'");
    }

    @Test
    @DisplayName("field buffer default value is 0 min")
    void fieldBufferDefaultIsZero() {
      execute("config", "show");
      assertTrue(
          stdout()
              .lines()
              .filter(l -> l.contains("Field buffer"))
              .anyMatch(l -> l.contains("0 min")),
          "default field buffer should display '0 min'");
    }

    @Test
    @DisplayName("grid interval default value is 30 min")
    void gridIntervalDefaultIsThirty() {
      execute("config", "show");
      assertTrue(
          stdout()
              .lines()
              .filter(l -> l.contains("Grid interval"))
              .anyMatch(l -> l.contains("30 min")),
          "default grid interval should display '30 min'");
    }

    @Test
    @DisplayName("shows explicit value without (default) for field-buffer-minutes when set")
    void showsExplicitFieldBufferValue() {
      execute("config", "set", "--field-buffer-minutes", "10");
      execute("config", "show");
      assertTrue(
          stdout()
              .lines()
              .filter(l -> l.contains("Field buffer"))
              .anyMatch(l -> l.contains("10 min") && !l.contains("(default)")),
          "explicit field buffer should show value without '(default)'");
    }

    @Test
    @DisplayName("shows explicit value without (default) for grid-minutes when set")
    void showsExplicitGridIntervalValue() {
      execute("config", "set", "--grid-minutes", "15");
      execute("config", "show");
      assertTrue(
          stdout()
              .lines()
              .filter(l -> l.contains("Grid interval"))
              .anyMatch(l -> l.contains("15 min") && !l.contains("(default)")),
          "explicit grid interval should show value without '(default)'");
    }

    @Test
    @DisplayName("field buffer 0 when explicitly set shows '0 min' without (default)")
    void showsExplicitZeroFieldBuffer() {
      execute("config", "set", "--field-buffer-minutes", "0");
      execute("config", "show");
      assertTrue(
          stdout()
              .lines()
              .filter(l -> l.contains("Field buffer"))
              .anyMatch(l -> l.contains("0 min") && !l.contains("(default)")),
          "explicitly set 0 should not show '(default)'");
    }
  }
}
