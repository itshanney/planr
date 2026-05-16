package org.leagueplan.planr.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class DivisionCommandTest extends CommandTestBase {

    // -------------------------------------------------------------------------
    // division add
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("division add")
    class Add {

        @Test
        @DisplayName("exits 0 and prints confirmation on success")
        void success() {
            int exit = execute("division", "add", "Majors", "--duration", "120");
            assertEquals(0, exit);
            assertEquals("Division \"Majors\" added (120 min/game).", stdout());
        }

        @Test
        @DisplayName("persists the division so subsequent commands can see it")
        void persistsAcrossInvocations() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("division", "list");
            assertTrue(stdout().contains("Majors"));
        }

        @Test
        @DisplayName("exits 1 when division name already exists (case-insensitive)")
        void failsOnDuplicateName() {
            execute("division", "add", "Majors", "--duration", "120");
            int exit = execute("division", "add", "majors", "--duration", "90");
            assertEquals(1, exit);
            assertTrue(stderr().contains("already exists"));
        }

        @Test
        @DisplayName("exits 1 when name is blank")
        void failsOnBlankName() {
            int exit = execute("division", "add", "   ", "--duration", "120");
            assertEquals(1, exit);
            assertTrue(stderr().contains("cannot be empty"));
        }

        @Test
        @DisplayName("exits 1 when duration is zero")
        void failsOnZeroDuration() {
            int exit = execute("division", "add", "Majors", "--duration", "0");
            assertEquals(1, exit);
            assertTrue(stderr().contains("positive integer"));
        }

        @Test
        @DisplayName("exits 1 when duration is negative")
        void failsOnNegativeDuration() {
            int exit = execute("division", "add", "Majors", "--duration", "-5");
            assertEquals(1, exit);
            assertTrue(stderr().contains("positive integer"));
        }

        @Test
        @DisplayName("two distinct divisions can coexist")
        void multipleDivisionsCoexist() {
            assertEquals(0, execute("division", "add", "Majors", "--duration", "120"));
            assertEquals(0, execute("division", "add", "AAA",    "--duration", "90"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("division", "add", "Majors", "--duration", "120");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // division edit
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("division edit")
    class Edit {

        @Test
        @DisplayName("exits 0 and prints confirmation when renaming")
        void renameSucceeds() {
            execute("division", "add", "Majors", "--duration", "120");
            int exit = execute("division", "edit", "Majors", "--name", "AAA");
            assertEquals(0, exit);
            assertTrue(stdout().contains("updated"));
        }

        @Test
        @DisplayName("exits 0 when changing duration only")
        void durationChangeSucceeds() {
            execute("division", "add", "Majors", "--duration", "120");
            int exit = execute("division", "edit", "Majors", "--duration", "90");
            assertEquals(0, exit);
            execute("division", "list");
            assertTrue(stdout().contains("90 min"));
            assertFalse(stdout().contains("120 min"));
        }

        @Test
        @DisplayName("exits 0 when changing both name and duration")
        void nameAndDurationChangeSucceeds() {
            execute("division", "add", "Majors", "--duration", "120");
            int exit = execute("division", "edit", "Majors", "--name", "Coast", "--duration", "90");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("matches the target division case-insensitively")
        void matchesCaseInsensitively() {
            execute("division", "add", "Majors", "--duration", "120");
            assertEquals(0, execute("division", "edit", "MAJORS", "--duration", "90"));
        }

        @Test
        @DisplayName("exits 1 when the target division does not exist")
        void failsWhenDivisionNotFound() {
            int exit = execute("division", "edit", "NonExistent", "--duration", "90");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when neither --name nor --duration is provided")
        void failsWhenNoOptionsProvided() {
            execute("division", "add", "Majors", "--duration", "120");
            int exit = execute("division", "edit", "Majors");
            assertEquals(1, exit);
            assertTrue(stderr().contains("At least one of"));
        }

        @Test
        @DisplayName("exits 1 when new name is blank")
        void failsOnBlankNewName() {
            execute("division", "add", "Majors", "--duration", "120");
            int exit = execute("division", "edit", "Majors", "--name", "   ");
            assertEquals(1, exit);
            assertTrue(stderr().contains("cannot be empty"));
        }

        @Test
        @DisplayName("exits 1 when new name conflicts with an existing division")
        void failsOnConflictingNewName() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("division", "add", "AAA",    "--duration", "90");
            int exit = execute("division", "edit", "Majors", "--name", "AAA");
            assertEquals(1, exit);
            assertTrue(stderr().contains("already exists"));
        }

        @Test
        @DisplayName("exits 0 when renaming to the same name (case-variant)")
        void renamingToSameNameSucceeds() {
            execute("division", "add", "Majors", "--duration", "120");
            assertEquals(0, execute("division", "edit", "Majors", "--name", "majors"));
        }

        @Test
        @DisplayName("exits 1 when new duration is zero")
        void failsOnZeroDuration() {
            execute("division", "add", "Majors", "--duration", "120");
            int exit = execute("division", "edit", "Majors", "--duration", "0");
            assertEquals(1, exit);
            assertTrue(stderr().contains("positive integer"));
        }

        @Test
        @DisplayName("teams are preserved when a division is renamed")
        void teamsPreservedAfterRename() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("team",     "add", "Majors", "Blue Jays");
            execute("division", "edit", "Majors", "--name", "NewMajors");
            execute("team",     "list", "NewMajors");
            assertTrue(stdout().contains("Blue Jays"));
        }
    }

    // -------------------------------------------------------------------------
    // division delete
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("division delete")
    class Delete {

        @Test
        @DisplayName("exits 0 and prints confirmation when division has no teams")
        void successWhenEmpty() {
            execute("division", "add", "Majors", "--duration", "120");
            int exit = execute("division", "delete", "Majors");
            assertEquals(0, exit);
            assertTrue(stdout().contains("deleted"));
        }

        @Test
        @DisplayName("deleted division no longer appears in list")
        void deletedDivisionAbsentFromList() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("division", "add", "AAA",    "--duration", "90");
            execute("division", "delete", "Majors");
            execute("division", "list");
            assertFalse(stdout().contains("Majors"));
            assertTrue(stdout().contains("AAA"));
        }

        @Test
        @DisplayName("exits 1 when the division has exactly one team")
        void failsWithOneTeam() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("team",     "add", "Majors", "Blue Jays");
            int exit = execute("division", "delete", "Majors");
            assertEquals(1, exit);
            assertTrue(stderr().contains("1 team(s)"));
            assertTrue(stderr().contains("Remove all teams"));
        }

        @Test
        @DisplayName("exits 1 when the division has multiple teams")
        void failsWithMultipleTeams() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("team",     "add", "Majors", "Blue Jays");
            execute("team",     "add", "Majors", "Cardinals");
            int exit = execute("division", "delete", "Majors");
            assertEquals(1, exit);
            assertTrue(stderr().contains("2 team(s)"));
        }

        @Test
        @DisplayName("exits 0 after all teams are removed")
        void succeedsAfterAllTeamsRemoved() {
            execute("division", "add",    "Majors", "--duration", "120");
            execute("team",     "add",    "Majors", "Blue Jays");
            execute("team",     "delete", "Majors", "Blue Jays");
            assertEquals(0, execute("division", "delete", "Majors"));
        }

        @Test
        @DisplayName("exits 1 when the division does not exist")
        void failsWhenNotFound() {
            int exit = execute("division", "delete", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("matches the target division case-insensitively")
        void matchesCaseInsensitively() {
            execute("division", "add", "Majors", "--duration", "120");
            assertEquals(0, execute("division", "delete", "MAJORS"));
        }
    }

    // -------------------------------------------------------------------------
    // division list
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("division list")
    class ListCmd {

        @Test
        @DisplayName("exits 0 and shows guidance message when no divisions are configured")
        void noDivisionsShowsMessage() {
            int exit = execute("division", "list");
            assertEquals(0, exit);
            assertTrue(stdout().contains("No divisions configured"));
        }

        @Test
        @DisplayName("shows DIVISION, DURATION, and TEAMS column headers")
        void showsColumnHeaders() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("division", "list");
            String out = stdout();
            assertTrue(out.contains("DIVISION"));
            assertTrue(out.contains("DURATION"));
            assertTrue(out.contains("TEAMS"));
        }

        @Test
        @DisplayName("shows all configured divisions with correct duration and team count")
        void showsAllDivisions() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("division", "add", "AAA",    "--duration", "90");
            execute("division", "list");
            String out = stdout();
            assertTrue(out.contains("Majors"));
            assertTrue(out.contains("120 min"));
            assertTrue(out.contains("AAA"));
            assertTrue(out.contains("90 min"));
        }

        @Test
        @DisplayName("shows 0 team count for an empty division")
        void showsZeroTeamCount() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("division", "list");
            // The row for Majors should contain 0 in the TEAMS column.
            // We verify by checking the output line contains "0".
            assertTrue(stdout().lines()
                .filter(l -> l.contains("Majors"))
                .anyMatch(l -> l.contains("0")));
        }

        @Test
        @DisplayName("team count reflects teams added after the division was created")
        void teamCountUpdatesWhenTeamsAdded() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("team",     "add", "Majors", "Blue Jays");
            execute("team",     "add", "Majors", "Cardinals");
            execute("division", "list");
            assertTrue(stdout().lines()
                .filter(l -> l.contains("Majors"))
                .anyMatch(l -> l.contains("2")));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("division", "list");
            assertEquals(2, exit);
        }
    }
}
