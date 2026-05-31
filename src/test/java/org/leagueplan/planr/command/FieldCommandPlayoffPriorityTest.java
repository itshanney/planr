package org.leagueplan.planr.command;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the --playoff-priority and --no-playoff-priority options on `planr field edit`, and
 * verification that `planr field list` displays the PLAYOFF_PRI column correctly.
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC 12: --playoff-priority 1 persists the rank
 *   <li>AC 13: --playoff-priority 0 exits 1 with "must be a positive integer"
 *   <li>AC 14: --playoff-priority -1 exits 1 with "must be a positive integer"
 *   <li>AC 15: --no-playoff-priority removes the rank
 *   <li>AC 16: field list displays PLAYOFF_PRI column
 *   <li>AC 20: regular-season assign is unaffected by playoff priority (tested in scheduler tests)
 * </ul>
 */
class FieldCommandPlayoffPriorityTest extends CommandTestBase {

  private void addField(String name) {
    execute("field", "add", name);
  }

  // ---------------------------------------------------------------------------
  // --playoff-priority validation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("--playoff-priority validation")
  class PlayoffPriorityValidation {

    @Test
    @DisplayName("exits 0 and persists rank 1 on the named field")
    void rankOneIsPersisted() {
      addField("Westfield");
      int exit = execute("field", "edit", "Westfield", "--playoff-priority", "1");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));

      execute("field", "list");
      assertTrue(stdout().contains("1"), "field list should show the persisted priority rank");
    }

    @Test
    @DisplayName("exits 0 and persists a higher rank value such as 5")
    void higherRankValueIsPersisted() {
      addField("Community Park");
      int exit = execute("field", "edit", "Community Park", "--playoff-priority", "5");
      assertEquals(0, exit);

      execute("field", "list");
      assertTrue(stdout().contains("5"));
    }

    @Test
    @DisplayName("exits 1 with 'must be a positive integer' for rank 0")
    void rankZeroIsRejected() {
      addField("Westfield");
      int exit = execute("field", "edit", "Westfield", "--playoff-priority", "0");
      assertEquals(1, exit);
      assertTrue(stderr().contains("must be a positive integer"));
    }

    @Test
    @DisplayName("exits 1 with 'must be a positive integer' for negative rank")
    void negativeRankIsRejected() {
      addField("Westfield");
      int exit = execute("field", "edit", "Westfield", "--playoff-priority", "-1");
      assertEquals(1, exit);
      assertTrue(stderr().contains("must be a positive integer"));
    }

    @Test
    @DisplayName("exits 1 with mutually exclusive error when both options are present")
    void playoffPriorityAndNoPriorityAreMutuallyExclusive() {
      addField("Westfield");
      int exit =
          execute(
              "field", "edit", "Westfield", "--playoff-priority", "1", "--no-playoff-priority");
      assertEquals(1, exit);
      assertTrue(stderr().contains("mutually exclusive"));
    }
  }

  // ---------------------------------------------------------------------------
  // --no-playoff-priority
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("--no-playoff-priority")
  class NoPlayoffPriority {

    @Test
    @DisplayName("exits 0 and removes a previously set priority rank")
    void removesExistingRank() {
      addField("Westfield");
      execute("field", "edit", "Westfield", "--playoff-priority", "1");

      int exit = execute("field", "edit", "Westfield", "--no-playoff-priority");
      assertEquals(0, exit);
      assertTrue(stdout().contains("updated"));

      execute("field", "list");
      // After clearing, the PLAYOFF_PRI column should show "--"
      assertTrue(
          stdout().contains("--"),
          "list should show '--' after priority rank is cleared");
    }

    @Test
    @DisplayName("exits 0 on a field that has no priority set (idempotent clear)")
    void idempotentOnFieldWithNoPriority() {
      addField("Westfield");
      int exit = execute("field", "edit", "Westfield", "--no-playoff-priority");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("does not alter other field properties when clearing the priority")
    void preservesOtherFieldsWhenClearingPriority() {
      addField("Westfield");
      execute("field", "edit", "Westfield", "--address", "100 Park Ave");
      execute("field", "edit", "Westfield", "--playoff-priority", "2");

      execute("field", "edit", "Westfield", "--no-playoff-priority");

      execute("field", "list");
      String out = stdout();
      assertTrue(out.contains("100 Park Ave"), "address should be unchanged");
      assertFalse(out.contains("  2 ") || out.matches("(?s).*Westfield\\s+100 Park Ave.*  2 .*"),
          "rank 2 should no longer appear as a priority value");
    }
  }

  // ---------------------------------------------------------------------------
  // field list — PLAYOFF_PRI column
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("field list — PLAYOFF_PRI column")
  class ListPlayoffPriorityColumn {

    @Test
    @DisplayName("shows PLAYOFF_PRI header in list output")
    void playoffPriColumnHeaderIsPresent() {
      addField("Westfield");
      execute("field", "list");
      assertTrue(
          stdout().contains("PLAYOFF_PRI"),
          "field list should include the PLAYOFF_PRI column header");
    }

    @Test
    @DisplayName("shows '--' for a field with no priority set")
    void showsDashWhenNoPrioritySet() {
      addField("Westfield");
      execute("field", "list");
      assertTrue(stdout().contains("--"));
    }

    @Test
    @DisplayName("shows the numeric rank for a field with priority set")
    void showsNumericRankWhenSet() {
      addField("Westfield");
      execute("field", "edit", "Westfield", "--playoff-priority", "3");

      execute("field", "list");
      assertTrue(stdout().contains("3"));
    }

    @Test
    @DisplayName("shows rank for one field and '--' for another in the same table")
    void mixedRankedAndUnrankedFieldsInSameTable() {
      addField("Westfield");
      addField("Community Park");
      execute("field", "edit", "Westfield", "--playoff-priority", "1");

      execute("field", "list");
      String out = stdout();
      assertTrue(out.contains("1"), "Westfield should show rank 1");
      assertTrue(out.contains("--"), "Community Park should show '--'");
    }
  }

  // ---------------------------------------------------------------------------
  // No-args guard (--playoff-priority and --no-playoff-priority count as options)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("no-args guard")
  class NoArgsGuard {

    @Test
    @DisplayName("--playoff-priority alone is accepted as a valid single option")
    void playoffPriorityAloneIsAccepted() {
      addField("Westfield");
      int exit = execute("field", "edit", "Westfield", "--playoff-priority", "1");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("--no-playoff-priority alone is accepted as a valid single option")
    void noPlayoffPriorityAloneIsAccepted() {
      addField("Westfield");
      int exit = execute("field", "edit", "Westfield", "--no-playoff-priority");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("exits 1 when no options at all are provided")
    void noOptionsProvidedExits1() {
      addField("Westfield");
      int exit = execute("field", "edit", "Westfield");
      assertEquals(1, exit);
      assertTrue(stderr().contains("At least one"));
    }
  }
}
