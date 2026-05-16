package org.leagueplan.planr.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class TeamCommandTest extends CommandTestBase {

    // Create a division before every team test; JUnit runs parent @BeforeEach first
    // so the data dir is clean before this runs.
    @BeforeEach
    void createDivision() {
        execute("division", "add", "Majors", "--duration", "120");
    }

    // -------------------------------------------------------------------------
    // team add
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("team add")
    class Add {

        @Test
        @DisplayName("exits 0 and prints confirmation on success")
        void success() {
            int exit = execute("team", "add", "Majors", "Blue Jays");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Blue Jays"));
            assertTrue(stdout().contains("Majors"));
        }

        @Test
        @DisplayName("exits 1 when the team name already exists in the division (case-insensitive)")
        void failsOnDuplicateTeamName() {
            execute("team", "add", "Majors", "Blue Jays");
            int exit = execute("team", "add", "Majors", "blue jays");
            assertEquals(1, exit);
            assertTrue(stderr().contains("already exists"));
        }

        @Test
        @DisplayName("exits 1 when the division does not exist")
        void failsWhenDivisionNotFound() {
            int exit = execute("team", "add", "NonExistent", "Blue Jays");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when team name is blank")
        void failsOnBlankTeamName() {
            int exit = execute("team", "add", "Majors", "   ");
            assertEquals(1, exit);
            assertTrue(stderr().contains("cannot be empty"));
        }

        @Test
        @DisplayName("matches the division name case-insensitively")
        void matchesDivisionCaseInsensitively() {
            assertEquals(0, execute("team", "add", "MAJORS", "Blue Jays"));
        }

        @Test
        @DisplayName("allows the same team name in different divisions")
        void sameTeamNameAllowedAcrossDivisions() {
            execute("division", "add", "AAA", "--duration", "90");
            execute("team", "add", "Majors", "Blue Jays");
            int exit = execute("team", "add", "AAA", "Blue Jays");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("persists the team so subsequent commands can see it")
        void persistsAcrossInvocations() {
            execute("team", "add", "Majors", "Blue Jays");
            execute("team", "list", "Majors");
            assertTrue(stdout().contains("Blue Jays"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("team", "add", "Majors", "Blue Jays");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // team edit
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("team edit")
    class Edit {

        @Test
        @DisplayName("exits 0 and prints confirmation on rename")
        void renameSucceeds() {
            execute("team", "add", "Majors", "Blue Jays");
            int exit = execute("team", "edit", "Majors", "Blue Jays", "--name", "Royals");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Royals"));
        }

        @Test
        @DisplayName("renamed team appears under new name in list")
        void renamedTeamAppearsUnderNewName() {
            execute("team", "add",  "Majors", "Blue Jays");
            execute("team", "edit", "Majors", "Blue Jays", "--name", "Royals");
            execute("team", "list", "Majors");
            assertTrue(stdout().contains("Royals"));
            assertFalse(stdout().contains("Blue Jays"));
        }

        @Test
        @DisplayName("exits 1 when the team does not exist in the division")
        void failsWhenTeamNotFound() {
            int exit = execute("team", "edit", "Majors", "NonExistent", "--name", "Royals");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when the division does not exist")
        void failsWhenDivisionNotFound() {
            int exit = execute("team", "edit", "NonExistent", "Blue Jays", "--name", "Royals");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when new name conflicts with an existing team in the division")
        void failsOnConflictingNewName() {
            execute("team", "add", "Majors", "Blue Jays");
            execute("team", "add", "Majors", "Cardinals");
            int exit = execute("team", "edit", "Majors", "Blue Jays", "--name", "Cardinals");
            assertEquals(1, exit);
            assertTrue(stderr().contains("already exists"));
        }

        @Test
        @DisplayName("exits 0 when renaming to the same name (case-variant)")
        void renamingToSameNameSucceeds() {
            execute("team", "add", "Majors", "Blue Jays");
            assertEquals(0, execute("team", "edit", "Majors", "Blue Jays", "--name", "Blue Jays"));
        }

        @Test
        @DisplayName("matches team name case-insensitively")
        void matchesTeamCaseInsensitively() {
            execute("team", "add", "Majors", "Blue Jays");
            assertEquals(0, execute("team", "edit", "Majors", "BLUE JAYS", "--name", "Royals"));
        }

        @Test
        @DisplayName("exits 1 when new name is blank")
        void failsOnBlankNewName() {
            execute("team", "add", "Majors", "Blue Jays");
            int exit = execute("team", "edit", "Majors", "Blue Jays", "--name", "   ");
            assertEquals(1, exit);
            assertTrue(stderr().contains("cannot be empty"));
        }
    }

    // -------------------------------------------------------------------------
    // team delete
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("team delete")
    class Delete {

        @Test
        @DisplayName("exits 0 and prints confirmation on success")
        void success() {
            execute("team", "add",    "Majors", "Blue Jays");
            int exit = execute("team", "delete", "Majors", "Blue Jays");
            assertEquals(0, exit);
            assertTrue(stdout().contains("removed"));
        }

        @Test
        @DisplayName("deleted team no longer appears in list")
        void deletedTeamAbsentFromList() {
            execute("team", "add",    "Majors", "Blue Jays");
            execute("team", "add",    "Majors", "Cardinals");
            execute("team", "delete", "Majors", "Blue Jays");
            execute("team", "list",   "Majors");
            assertFalse(stdout().contains("Blue Jays"));
            assertTrue(stdout().contains("Cardinals"));
        }

        @Test
        @DisplayName("exits 1 when the team does not exist")
        void failsWhenTeamNotFound() {
            int exit = execute("team", "delete", "Majors", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when the division does not exist")
        void failsWhenDivisionNotFound() {
            int exit = execute("team", "delete", "NonExistent", "Blue Jays");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("matches team name case-insensitively")
        void matchesTeamCaseInsensitively() {
            execute("team", "add",    "Majors", "Blue Jays");
            assertEquals(0, execute("team", "delete", "Majors", "BLUE JAYS"));
        }

        @Test
        @DisplayName("deleting all teams allows the division to be deleted")
        void deletingAllTeamsUnblocksDevisionDelete() {
            execute("team", "add",    "Majors", "Blue Jays");
            execute("team", "delete", "Majors", "Blue Jays");
            assertEquals(0, execute("division", "delete", "Majors"));
        }
    }

    // -------------------------------------------------------------------------
    // team list
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("team list")
    class ListCmd {

        @Test
        @DisplayName("exits 0 and shows guidance message when the division has no teams")
        void noTeamsShowsMessage() {
            int exit = execute("team", "list", "Majors");
            assertEquals(0, exit);
            assertTrue(stdout().contains("No teams"));
        }

        @Test
        @DisplayName("shows team names one per line, sorted alphabetically")
        void showsTeamsSortedAlphabetically() {
            execute("team", "add", "Majors", "Zebras");
            execute("team", "add", "Majors", "Aardvarks");
            execute("team", "add", "Majors", "Cardinals");
            execute("team", "list", "Majors");
            String[] lines = stdout().split("\\r?\\n");
            assertEquals(3, lines.length);
            assertEquals("Aardvarks", lines[0]);
            assertEquals("Cardinals", lines[1]);
            assertEquals("Zebras",    lines[2]);
        }

        @Test
        @DisplayName("sorts case-insensitively (lowercase before uppercase within the same letter)")
        void sortsCaseInsensitively() {
            execute("team", "add", "Majors", "zebras");
            execute("team", "add", "Majors", "Aardvarks");
            execute("team", "list", "Majors");
            String[] lines = stdout().split("\\r?\\n");
            assertEquals("Aardvarks", lines[0]);
            assertEquals("zebras",    lines[1]);
        }

        @Test
        @DisplayName("exits 1 when the division does not exist")
        void failsWhenDivisionNotFound() {
            int exit = execute("team", "list", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("team", "list", "Majors");
            assertEquals(2, exit);
        }
    }
}
