package org.leagueplan.planr.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class FieldBlockCommandTest extends CommandTestBase {

    @BeforeEach
    void addField() {
        execute("field", "add", "Riverside Park");
    }

    // -------------------------------------------------------------------------
    // field block add
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field block add")
    class Add {

        @Test
        @DisplayName("exits 0 and prints confirmation on success")
        void success() {
            int exit = execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "12:00");
            assertEquals(0, exit);
            String out = stdout();
            assertTrue(out.contains("#1"));
            assertTrue(out.contains("Riverside Park"));
            assertTrue(out.contains("2026-06-07"));
            assertTrue(out.contains("10:00"));
            assertTrue(out.contains("12:00"));
        }

        @Test
        @DisplayName("block number increments with each add")
        void blockNumberIncrements() {
            execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "12:00");
            execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-14", "--start", "10:00", "--end", "12:00");
            assertTrue(stdout().contains("#2"));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "block", "add", "NonExistent",
                "--date", "2026-06-07", "--start", "10:00", "--end", "12:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when date format is invalid")
        void failsOnInvalidDate() {
            int exit = execute("field", "block", "add", "Riverside Park",
                "--date", "06/07/2026", "--start", "10:00", "--end", "12:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid date"));
        }

        @Test
        @DisplayName("exits 1 when start time format is invalid")
        void failsOnInvalidStartTime() {
            int exit = execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10am", "--end", "12:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid time"));
        }

        @Test
        @DisplayName("exits 1 when end time format is invalid")
        void failsOnInvalidEndTime() {
            int exit = execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "noon");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid time"));
        }

        @Test
        @DisplayName("exits 1 when end time equals start time")
        void failsWhenEndEqualsStart() {
            int exit = execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "10:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("End time must be after start time"));
        }

        @Test
        @DisplayName("exits 1 when end time is before start time")
        void failsWhenEndBeforeStart() {
            int exit = execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "12:00", "--end", "10:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("End time must be after start time"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "12:00");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field block edit
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field block edit")
    class Edit {

        @BeforeEach
        void addBlock() {
            execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "12:00");
        }

        @Test
        @DisplayName("exits 0 and prints confirmation when updating the date")
        void updatesDateSuccessfully() {
            int exit = execute("field", "block", "edit", "Riverside Park", "1",
                "--date", "2026-06-14");
            assertEquals(0, exit);
            assertTrue(stdout().contains("updated"));
        }

        @Test
        @DisplayName("updated date appears in list")
        void updatedDateAppearsInList() {
            execute("field", "block", "edit", "Riverside Park", "1", "--date", "2026-06-14");
            execute("field", "block", "list", "Riverside Park");
            assertTrue(stdout().contains("2026-06-14"));
            assertFalse(stdout().contains("2026-06-07"));
        }

        @Test
        @DisplayName("exits 0 when updating start time only")
        void updatesStartTimeSuccessfully() {
            assertEquals(0, execute("field", "block", "edit", "Riverside Park", "1", "--start", "11:00"));
        }

        @Test
        @DisplayName("unspecified fields are preserved from the existing block")
        void preservesExistingValues() {
            execute("field", "block", "edit", "Riverside Park", "1", "--start", "11:00");
            execute("field", "block", "list", "Riverside Park");
            String out = stdout();
            assertTrue(out.contains("11:00"));
            assertTrue(out.contains("12:00"));
            assertTrue(out.contains("2026-06-07"));
        }

        @Test
        @DisplayName("exits 1 when no options are provided")
        void failsWhenNoOptionsProvided() {
            int exit = execute("field", "block", "edit", "Riverside Park", "1");
            assertEquals(1, exit);
            assertTrue(stderr().contains("At least one of"));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "block", "edit", "NonExistent", "1", "--date", "2026-06-14");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when block number is out of range")
        void failsWhenBlockNumberOutOfRange() {
            int exit = execute("field", "block", "edit", "Riverside Park", "99", "--date", "2026-06-14");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when resulting end time would not be after start time")
        void failsWhenEffectiveEndNotAfterStart() {
            int exit = execute("field", "block", "edit", "Riverside Park", "1", "--end", "09:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("End time must be after start time"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "block", "edit", "Riverside Park", "1", "--date", "2026-06-14");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field block delete
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field block delete")
    class Delete {

        @BeforeEach
        void addBlock() {
            execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "12:00");
        }

        @Test
        @DisplayName("exits 0 and prints confirmation on success")
        void success() {
            int exit = execute("field", "block", "delete", "Riverside Park", "1");
            assertEquals(0, exit);
            assertTrue(stdout().contains("deleted"));
        }

        @Test
        @DisplayName("deleted block is absent from list")
        void deletedBlockAbsentFromList() {
            execute("field", "block", "delete", "Riverside Park", "1");
            execute("field", "block", "list", "Riverside Park");
            assertTrue(stdout().contains("No blocks"));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "block", "delete", "NonExistent", "1");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when block number is out of range")
        void failsWhenBlockNumberOutOfRange() {
            int exit = execute("field", "block", "delete", "Riverside Park", "99");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "block", "delete", "Riverside Park", "1");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field block list
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field block list")
    class ListCmd {

        @Test
        @DisplayName("exits 0 and shows guidance when field has no blocks")
        void noBlocksShowsMessage() {
            int exit = execute("field", "block", "list", "Riverside Park");
            assertEquals(0, exit);
            assertTrue(stdout().contains("No blocks"));
        }

        @Test
        @DisplayName("shows column headers when blocks exist")
        void showsColumnHeaders() {
            execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "12:00");
            execute("field", "block", "list", "Riverside Park");
            String out = stdout();
            assertTrue(out.contains("DATE"));
            assertTrue(out.contains("START"));
            assertTrue(out.contains("END"));
        }

        @Test
        @DisplayName("shows correct 1-based index numbers for each block")
        void showsCorrectIndexNumbers() {
            execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "12:00");
            execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-14", "--start", "10:00", "--end", "12:00");
            execute("field", "block", "list", "Riverside Park");
            String out = stdout();
            assertTrue(out.lines().anyMatch(l -> l.trim().startsWith("1")));
            assertTrue(out.lines().anyMatch(l -> l.trim().startsWith("2")));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "block", "list", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "block", "list", "Riverside Park");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }
}
