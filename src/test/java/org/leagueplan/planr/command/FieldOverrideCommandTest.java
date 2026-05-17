package org.leagueplan.planr.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class FieldOverrideCommandTest extends CommandTestBase {

    @BeforeEach
    void addField() {
        execute("field", "add", "Riverside Park");
    }

    // -------------------------------------------------------------------------
    // field override add
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field override add")
    class Add {

        @Test
        @DisplayName("exits 0 and prints confirmation on success")
        void success() {
            int exit = execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "09:00", "--end", "14:00");
            assertEquals(0, exit);
            String out = stdout();
            assertTrue(out.contains("#1"));
            assertTrue(out.contains("Riverside Park"));
            assertTrue(out.contains("2026-06-07"));
            assertTrue(out.contains("09:00"));
            assertTrue(out.contains("14:00"));
        }

        @Test
        @DisplayName("override number increments with each add")
        void overrideNumberIncrements() {
            execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "09:00", "--end", "14:00");
            execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-14", "--start", "10:00", "--end", "16:00");
            assertTrue(stdout().contains("#2"));
        }

        @Test
        @DisplayName("exits 1 when date already has an override")
        void failsOnDuplicateDate() {
            execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "09:00", "--end", "14:00");
            int exit = execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "16:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("already exists"));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "override", "add", "NonExistent",
                "--date", "2026-06-07", "--start", "09:00", "--end", "14:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when date format is invalid")
        void failsOnInvalidDate() {
            int exit = execute("field", "override", "add", "Riverside Park",
                "--date", "06/07/2026", "--start", "09:00", "--end", "14:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid date"));
        }

        @Test
        @DisplayName("exits 1 when start time format is invalid")
        void failsOnInvalidStartTime() {
            int exit = execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "9am", "--end", "14:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid time"));
        }

        @Test
        @DisplayName("exits 1 when end time equals start time")
        void failsWhenEndEqualsStart() {
            int exit = execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "09:00", "--end", "09:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("End time must be after start time"));
        }

        @Test
        @DisplayName("exits 1 when end time is before start time")
        void failsWhenEndBeforeStart() {
            int exit = execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "14:00", "--end", "09:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("End time must be after start time"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "09:00", "--end", "14:00");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field override edit
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field override edit")
    class Edit {

        @BeforeEach
        void addOverride() {
            execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "09:00", "--end", "14:00");
        }

        @Test
        @DisplayName("exits 0 and prints confirmation when updating the date")
        void updatesDateSuccessfully() {
            int exit = execute("field", "override", "edit", "Riverside Park", "1",
                "--date", "2026-06-14");
            assertEquals(0, exit);
            assertTrue(stdout().contains("updated"));
        }

        @Test
        @DisplayName("updated date appears in list")
        void updatedDateAppearsInList() {
            execute("field", "override", "edit", "Riverside Park", "1", "--date", "2026-06-14");
            execute("field", "override", "list", "Riverside Park");
            assertTrue(stdout().contains("2026-06-14"));
            assertFalse(stdout().contains("2026-06-07"));
        }

        @Test
        @DisplayName("unspecified fields are preserved from the existing override")
        void preservesExistingValues() {
            execute("field", "override", "edit", "Riverside Park", "1", "--start", "10:00");
            execute("field", "override", "list", "Riverside Park");
            String out = stdout();
            assertTrue(out.contains("10:00"));
            assertTrue(out.contains("14:00"));
            assertTrue(out.contains("2026-06-07"));
        }

        @Test
        @DisplayName("exits 1 when no options are provided")
        void failsWhenNoOptionsProvided() {
            int exit = execute("field", "override", "edit", "Riverside Park", "1");
            assertEquals(1, exit);
            assertTrue(stderr().contains("At least one of"));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "override", "edit", "NonExistent", "1", "--date", "2026-06-14");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when override number is out of range")
        void failsWhenOverrideNumberOutOfRange() {
            int exit = execute("field", "override", "edit", "Riverside Park", "99", "--date", "2026-06-14");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when changing date to one that already has an override")
        void failsWhenNewDateConflictsWithExistingOverride() {
            execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-14", "--start", "09:00", "--end", "14:00");
            int exit = execute("field", "override", "edit", "Riverside Park", "1",
                "--date", "2026-06-14");
            assertEquals(1, exit);
            assertTrue(stderr().contains("already exists"));
        }

        @Test
        @DisplayName("exits 1 when resulting end time would not be after start time")
        void failsWhenEffectiveEndNotAfterStart() {
            int exit = execute("field", "override", "edit", "Riverside Park", "1", "--end", "08:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("End time must be after start time"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "override", "edit", "Riverside Park", "1", "--date", "2026-06-14");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field override delete
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field override delete")
    class Delete {

        @BeforeEach
        void addOverride() {
            execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "09:00", "--end", "14:00");
        }

        @Test
        @DisplayName("exits 0 and prints confirmation on success")
        void success() {
            int exit = execute("field", "override", "delete", "Riverside Park", "1");
            assertEquals(0, exit);
            assertTrue(stdout().contains("deleted"));
        }

        @Test
        @DisplayName("deleted override is absent from list")
        void deletedOverrideAbsentFromList() {
            execute("field", "override", "delete", "Riverside Park", "1");
            execute("field", "override", "list", "Riverside Park");
            assertTrue(stdout().contains("No date overrides"));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "override", "delete", "NonExistent", "1");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when override number is out of range")
        void failsWhenOverrideNumberOutOfRange() {
            int exit = execute("field", "override", "delete", "Riverside Park", "99");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "override", "delete", "Riverside Park", "1");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field override list
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field override list")
    class ListCmd {

        @Test
        @DisplayName("exits 0 and shows guidance when field has no overrides")
        void noOverridesShowsMessage() {
            int exit = execute("field", "override", "list", "Riverside Park");
            assertEquals(0, exit);
            assertTrue(stdout().contains("No date overrides"));
        }

        @Test
        @DisplayName("shows column headers when overrides exist")
        void showsColumnHeaders() {
            execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "09:00", "--end", "14:00");
            execute("field", "override", "list", "Riverside Park");
            String out = stdout();
            assertTrue(out.contains("DATE"));
            assertTrue(out.contains("OPEN START"));
            assertTrue(out.contains("OPEN END"));
        }

        @Test
        @DisplayName("shows correct 1-based index numbers for each override")
        void showsCorrectIndexNumbers() {
            execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "09:00", "--end", "14:00");
            execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-14", "--start", "10:00", "--end", "16:00");
            execute("field", "override", "list", "Riverside Park");
            String out = stdout();
            assertTrue(out.lines().anyMatch(l -> l.trim().startsWith("1")));
            assertTrue(out.lines().anyMatch(l -> l.trim().startsWith("2")));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "override", "list", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "override", "list", "Riverside Park");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }
}
