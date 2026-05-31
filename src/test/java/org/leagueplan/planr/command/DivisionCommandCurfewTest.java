package org.leagueplan.planr.command;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the --curfew-time and --no-curfew-time options on `planr division edit`, and
 * verification that `planr division list` displays the CURFEW column correctly.
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC 1: --curfew-time HH:mm persists the value
 *   <li>AC 2: --curfew-time 25:00 exits 1 with "invalid time"
 *   <li>AC 3: --curfew-time abc exits 1 with "invalid time"
 *   <li>AC 4: --no-curfew-time removes the curfew
 *   <li>AC 5: division list displays CURFEW column
 *   <li>AC 6: curfew is a hard start-time constraint (tested in SchedulerServiceCurfewTest)
 *   <li>AC 10: division without curfew behaves as before
 *   <li>AC 11: changing curfew after assignment doesn't alter stored games (tested separately)
 * </ul>
 */
class DivisionCommandCurfewTest extends CommandTestBase {

  private void addDivision(String name) {
    execute("division", "add", name, "--duration", "60", "--target", "4");
  }

  // ---------------------------------------------------------------------------
  // --curfew-time validation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("--curfew-time validation")
  class CurfewTimeValidation {

    @Test
    @DisplayName("exits 0 and persists 19:30 as the division curfew")
    void validTimeIsPersisted() {
      addDivision("6U");
      int exit = execute("division", "edit", "6U", "--curfew-time", "19:30");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));

      execute("division", "list");
      assertTrue(stdout().contains("19:30"), "division list should show the persisted curfew");
    }

    @Test
    @DisplayName("exits 0 and persists midnight curfew 00:00")
    void midnightCurfewIsPersisted() {
      addDivision("6U");
      int exit = execute("division", "edit", "6U", "--curfew-time", "00:00");
      assertEquals(0, exit);

      execute("division", "list");
      assertTrue(stdout().contains("00:00"));
    }

    @Test
    @DisplayName("exits 1 with 'invalid time' for out-of-range hour 25:00")
    void hour25IsRejected() {
      addDivision("6U");
      int exit = execute("division", "edit", "6U", "--curfew-time", "25:00");
      assertEquals(1, exit);
      assertTrue(stderr().toLowerCase().contains("invalid time"));
    }

    @Test
    @DisplayName("exits 1 with 'invalid time' for out-of-range hour 24:00")
    void hour24IsRejected() {
      addDivision("6U");
      int exit = execute("division", "edit", "6U", "--curfew-time", "24:00");
      assertEquals(1, exit);
      assertTrue(stderr().toLowerCase().contains("invalid time"));
    }

    @Test
    @DisplayName("exits 1 with 'invalid time' for non-numeric input")
    void nonNumericInputIsRejected() {
      addDivision("6U");
      int exit = execute("division", "edit", "6U", "--curfew-time", "abc");
      assertEquals(1, exit);
      assertTrue(stderr().toLowerCase().contains("invalid time"));
    }

    @Test
    @DisplayName("exits 1 with 'invalid time' for seconds-included format HH:mm:ss")
    void withSecondsIsRejected() {
      addDivision("6U");
      int exit = execute("division", "edit", "6U", "--curfew-time", "19:30:00");
      assertEquals(1, exit);
      assertTrue(stderr().toLowerCase().contains("invalid time"));
    }

    @Test
    @DisplayName("exits 1 with 'invalid time' for out-of-range minutes")
    void minutesBeyond59IsRejected() {
      addDivision("6U");
      int exit = execute("division", "edit", "6U", "--curfew-time", "19:60");
      assertEquals(1, exit);
      assertTrue(stderr().toLowerCase().contains("invalid time"));
    }

    @Test
    @DisplayName("exits 1 with mutually exclusive error when both options are present")
    void curfewTimeAndNoCurfewTimeAreMutuallyExclusive() {
      addDivision("6U");
      int exit =
          execute("division", "edit", "6U", "--curfew-time", "19:30", "--no-curfew-time");
      assertEquals(1, exit);
      assertTrue(
          stderr().contains("mutually exclusive"),
          "error should name the mutually exclusive constraint");
    }
  }

  // ---------------------------------------------------------------------------
  // --no-curfew-time
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("--no-curfew-time")
  class NoCurfewTime {

    @Test
    @DisplayName("exits 0 and removes a previously set curfew")
    void removesExistingCurfew() {
      addDivision("6U");
      execute("division", "edit", "6U", "--curfew-time", "19:30");

      int exit = execute("division", "edit", "6U", "--no-curfew-time");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));

      execute("division", "list");
      assertFalse(
          stdout().contains("19:30"),
          "curfew should be gone from list output after --no-curfew-time");
    }

    @Test
    @DisplayName("exits 0 on a division that has no curfew set (idempotent clear)")
    void idempotentOnDivisionWithNoCurfew() {
      addDivision("6U");
      int exit = execute("division", "edit", "6U", "--no-curfew-time");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("does not alter other division fields when clearing the curfew")
    void preservesOtherFieldsWhenClearingCurfew() {
      addDivision("6U");
      execute("division", "edit", "6U", "--duration", "90");
      execute("division", "edit", "6U", "--curfew-time", "19:00");

      execute("division", "edit", "6U", "--no-curfew-time");

      execute("division", "list");
      String out = stdout();
      assertTrue(out.contains("90 min"), "game duration should be unchanged after curfew clear");
      assertFalse(out.contains("19:00"), "curfew value should no longer appear");
    }
  }

  // ---------------------------------------------------------------------------
  // division list — CURFEW column
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("division list — CURFEW column")
  class ListCurfewColumn {

    @Test
    @DisplayName("shows CURFEW header in list output")
    void curfewColumnHeaderIsPresent() {
      addDivision("Majors");
      execute("division", "list");
      assertTrue(stdout().contains("CURFEW"), "list output should include the CURFEW column");
    }

    @Test
    @DisplayName("shows '--' when no curfew is set")
    void showsDashWhenNoCurfew() {
      addDivision("Majors");
      execute("division", "list");
      assertTrue(
          stdout().contains("--"),
          "list should show '--' for a division without a configured curfew");
    }

    @Test
    @DisplayName("shows formatted time HH:mm when curfew is set")
    void showsFormattedTimeWhenCurfewIsSet() {
      addDivision("6U");
      execute("division", "edit", "6U", "--curfew-time", "19:30");

      execute("division", "list");
      assertTrue(stdout().contains("19:30"));
    }

    @Test
    @DisplayName("shows curfew for one division and '--' for another in the same table")
    void mixedCurfewAndNoCurfewInSameTable() {
      addDivision("6U");
      addDivision("Majors");
      execute("division", "edit", "6U", "--curfew-time", "19:30");

      execute("division", "list");
      String out = stdout();
      assertTrue(out.contains("19:30"), "6U should show its curfew");
      assertTrue(out.contains("--"), "Majors should show '--'");
    }
  }

  // ---------------------------------------------------------------------------
  // No-args guard
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("no-args guard")
  class NoArgsGuard {

    @Test
    @DisplayName("--curfew-time alone is accepted as a valid single option")
    void curfewTimeAloneIsAccepted() {
      addDivision("6U");
      int exit = execute("division", "edit", "6U", "--curfew-time", "20:00");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("--no-curfew-time alone is accepted as a valid single option")
    void noCurfewTimeAloneIsAccepted() {
      addDivision("6U");
      int exit = execute("division", "edit", "6U", "--no-curfew-time");
      assertEquals(0, exit);
    }
  }
}
