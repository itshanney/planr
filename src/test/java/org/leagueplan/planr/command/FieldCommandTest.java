package org.leagueplan.planr.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class FieldCommandTest extends CommandTestBase {

    // -------------------------------------------------------------------------
    // field add
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field add")
    class Add {

        @Test
        @DisplayName("exits 0 and prints confirmation without an address")
        void successWithoutAddress() {
            int exit = execute("field", "add", "Riverside Park");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Riverside Park"));
            assertTrue(stdout().contains("added"));
        }

        @Test
        @DisplayName("exits 0 and prints confirmation with an address")
        void successWithAddress() {
            int exit = execute("field", "add", "Riverside Park", "--address", "123 Main St");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Riverside Park"));
        }

        @Test
        @DisplayName("persists the field so subsequent commands can see it")
        void persistsAcrossInvocations() {
            execute("field", "add", "Riverside Park");
            execute("field", "list");
            assertTrue(stdout().contains("Riverside Park"));
        }

        @Test
        @DisplayName("two distinct fields can coexist")
        void multipleFieldsCoexist() {
            assertEquals(0, execute("field", "add", "Riverside Park"));
            assertEquals(0, execute("field", "add", "Eastside Field"));
        }

        @Test
        @DisplayName("exits 1 when field name already exists (case-insensitive)")
        void failsOnDuplicateName() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "add", "riverside park");
            assertEquals(1, exit);
            assertTrue(stderr().contains("already exists"));
        }

        @Test
        @DisplayName("exits 1 when field name is blank")
        void failsOnBlankName() {
            int exit = execute("field", "add", "   ");
            assertEquals(1, exit);
            assertTrue(stderr().contains("cannot be empty"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "add", "Riverside Park");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field edit
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field edit")
    class Edit {

        @Test
        @DisplayName("exits 0 and prints confirmation when renaming")
        void renameSucceeds() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "edit", "Riverside Park", "--name", "Eastside Field");
            assertEquals(0, exit);
            assertTrue(stdout().contains("updated"));
        }

        @Test
        @DisplayName("renamed field appears under new name in list")
        void renamedFieldAppearsUnderNewName() {
            execute("field", "add", "Riverside Park");
            execute("field", "edit", "Riverside Park", "--name", "Eastside Field");
            execute("field", "list");
            assertTrue(stdout().contains("Eastside Field"));
            assertFalse(stdout().contains("Riverside Park"));
        }

        @Test
        @DisplayName("exits 0 when updating address only")
        void addressUpdateSucceeds() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "edit", "Riverside Park", "--address", "456 Oak Ave");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("updated address appears in list")
        void updatedAddressAppearsInList() {
            execute("field", "add", "Riverside Park");
            execute("field", "edit", "Riverside Park", "--address", "456 Oak Ave");
            execute("field", "list");
            assertTrue(stdout().contains("456 Oak Ave"));
        }

        @Test
        @DisplayName("exits 0 when updating both name and address")
        void nameAndAddressUpdateSucceeds() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "edit", "Riverside Park",
                "--name", "Eastside Field", "--address", "789 Elm St");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("empty string for --address clears a previously set address")
        void emptyAddressClearsExistingAddress() {
            execute("field", "add", "Riverside Park", "--address", "123 Main St");
            execute("field", "edit", "Riverside Park", "--address", "");
            execute("field", "list");
            assertFalse(stdout().contains("123 Main St"));
            assertTrue(stdout().contains("(none)"));
        }

        @Test
        @DisplayName("matches the target field name case-insensitively")
        void matchesCaseInsensitively() {
            execute("field", "add", "Riverside Park");
            assertEquals(0, execute("field", "edit", "RIVERSIDE PARK", "--address", "100 River Rd"));
        }

        @Test
        @DisplayName("exits 1 when the target field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "edit", "NonExistent", "--name", "Other");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when neither --name nor --address is provided")
        void failsWhenNoOptionsProvided() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "edit", "Riverside Park");
            assertEquals(1, exit);
            assertTrue(stderr().contains("At least one of"));
        }

        @Test
        @DisplayName("exits 1 when new name conflicts with an existing field (case-insensitive)")
        void failsOnConflictingNewName() {
            execute("field", "add", "Riverside Park");
            execute("field", "add", "Eastside Field");
            int exit = execute("field", "edit", "Riverside Park", "--name", "eastside field");
            assertEquals(1, exit);
            assertTrue(stderr().contains("already exists"));
        }

        @Test
        @DisplayName("exits 1 when new name is blank")
        void failsOnBlankNewName() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "edit", "Riverside Park", "--name", "   ");
            assertEquals(1, exit);
            assertTrue(stderr().contains("cannot be empty"));
        }

        @Test
        @DisplayName("exits 0 when renaming to the same name in a different case")
        void renamingToSameNameCaseVariantSucceeds() {
            execute("field", "add", "Riverside Park");
            assertEquals(0, execute("field", "edit", "Riverside Park", "--name", "riverside park"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "edit", "Riverside Park", "--name", "Other");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field delete
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field delete")
    class Delete {

        @Test
        @DisplayName("exits 0 and shows 0 blocks and 0 overrides when field has none")
        void successWithNone() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "delete", "Riverside Park");
            assertEquals(0, exit);
            String out = stdout();
            assertTrue(out.contains("0 block(s)"));
            assertTrue(out.contains("0 override(s)"));
        }

        @Test
        @DisplayName("reports the correct cascade-deleted block and override counts")
        void successWithBlocksAndOverridesCascades() {
            execute("field", "add", "Riverside Park");
            execute("config", "set", "--sunrise", "07:00", "--sunset", "20:00");
            execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "12:00");
            execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-14", "--start", "10:00", "--end", "12:00");
            execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-21", "--start", "09:00", "--end", "14:00");
            int exit = execute("field", "delete", "Riverside Park");
            assertEquals(0, exit);
            String out = stdout();
            assertTrue(out.contains("2 block(s)"));
            assertTrue(out.contains("1 override(s)"));
        }

        @Test
        @DisplayName("deleted field is absent from list; other fields survive")
        void deletedFieldAbsentFromList() {
            execute("field", "add", "Riverside Park");
            execute("field", "add", "Eastside Field");
            execute("field", "delete", "Riverside Park");
            execute("field", "list");
            assertFalse(stdout().contains("Riverside Park"));
            assertTrue(stdout().contains("Eastside Field"));
        }

        @Test
        @DisplayName("matches the target field name case-insensitively")
        void matchesCaseInsensitively() {
            execute("field", "add", "Riverside Park");
            assertEquals(0, execute("field", "delete", "RIVERSIDE PARK"));
        }

        @Test
        @DisplayName("exits 1 when the field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "delete", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "delete", "Riverside Park");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field list
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field list")
    class ListCmd {

        @Test
        @DisplayName("exits 0 and shows guidance when no fields are configured")
        void noFieldsShowsMessage() {
            int exit = execute("field", "list");
            assertEquals(0, exit);
            assertTrue(stdout().contains("No fields configured"));
        }

        @Test
        @DisplayName("shows NAME, ADDRESS, BLOCKS, and OVERRIDES column headers")
        void showsColumnHeaders() {
            execute("field", "add", "Riverside Park");
            execute("field", "list");
            String out = stdout();
            assertTrue(out.contains("NAME"));
            assertTrue(out.contains("ADDRESS"));
            assertTrue(out.contains("BLOCKS"));
            assertTrue(out.contains("OVERRIDES"));
        }

        @Test
        @DisplayName("shows '(none)' for a field without an address")
        void showsNoneForFieldWithoutAddress() {
            execute("field", "add", "Riverside Park");
            execute("field", "list");
            assertTrue(stdout().contains("(none)"));
        }

        @Test
        @DisplayName("shows the address for a field that has one")
        void showsAddressWhenPresent() {
            execute("field", "add", "Riverside Park", "--address", "123 Main St");
            execute("field", "list");
            assertTrue(stdout().contains("123 Main St"));
        }

        @Test
        @DisplayName("shows 0 block and override counts before any are added")
        void showsZeroCountsInitially() {
            execute("field", "add", "Riverside Park");
            execute("field", "list");
            assertTrue(stdout().lines()
                .filter(l -> l.contains("Riverside Park"))
                .anyMatch(l -> l.contains("0")));
        }

        @Test
        @DisplayName("block count reflects blocks added to the field")
        void blockCountUpdatesAfterBlockAdd() {
            execute("field", "add", "Riverside Park");
            execute("config", "set", "--sunrise", "07:00", "--sunset", "20:00");
            execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "12:00");
            execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-14", "--start", "10:00", "--end", "12:00");
            execute("field", "list");
            assertTrue(stdout().lines()
                .filter(l -> l.contains("Riverside Park"))
                .anyMatch(l -> l.contains("2")));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            assertEquals(2, execute("field", "list"));
        }
    }
}
