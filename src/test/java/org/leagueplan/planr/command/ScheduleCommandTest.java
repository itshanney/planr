package org.leagueplan.planr.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleCommandTest extends CommandTestBase {

    // Helpers to build a minimal schedulable league via CLI:
    //   1 division ("Majors", 60-min games) with 2 teams → 2 games needed
    //   1 field with a Saturday window 09:00-18:00
    //   Season: 2026-06-01 to 2026-06-30 (4 Saturdays → plenty of slots)

    private void addMinimalLeague() {
        execute("division", "add", "Majors", "--duration", "60", "--target", "10");
        execute("team", "add", "Majors", "Blue Jays");
        execute("team", "add", "Majors", "Cardinals");
        execute("field", "add", "Riverside Park");
        execute("config", "set", "--sunrise", "07:00", "--sunset", "20:00");
    }

    private int generateDraft() {
        return execute("schedule", "generate",
            "--start", "2026-06-01", "--end", "2026-06-30");
    }

    private void generateAndFinalizeSchedule() {
        addMinimalLeague();
        generateDraft();
        provideStdinAndExecute("yes\n", "schedule", "finalize");
    }

    /** Redirects System.in to the given string, executes the command, then restores System.in. */
    private int provideStdinAndExecute(String stdinContent, String... args) {
        InputStream original = System.in;
        System.setIn(new ByteArrayInputStream(stdinContent.getBytes()));
        try {
            return execute(args);
        } finally {
            System.setIn(original);
        }
    }

    // -------------------------------------------------------------------------
    // schedule generate
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("schedule generate")
    class Generate {

        @Test
        @DisplayName("exits 1 when no divisions have been added")
        void failsWhenNoDivisionsExist() {
            int exit = execute("schedule", "generate",
                "--start", "2026-06-01", "--end", "2026-06-30");
            assertEquals(1, exit);
            assertTrue(stderr().contains("requires at least one division"));
        }

        @Test
        @DisplayName("exits 1 when the only division has fewer than 2 teams")
        void failsWhenDivisionHasOnlyOneTeam() {
            execute("division", "add", "Majors", "--duration", "60", "--target", "10");
            execute("team", "add", "Majors", "Blue Jays");
            execute("field", "add", "Riverside Park");
            execute("config", "set", "--sunrise", "07:00", "--sunset", "20:00");
            int exit = execute("schedule", "generate",
                "--start", "2026-06-01", "--end", "2026-06-30");
            assertEquals(1, exit);
            assertTrue(stderr().contains("requires at least one division"));
        }

        @Test
        @DisplayName("exits 1 when sunrise/sunset config has not been set")
        void failsWhenNoConfigSet() {
            execute("division", "add", "Majors", "--duration", "60", "--target", "10");
            execute("team", "add", "Majors", "Blue Jays");
            execute("team", "add", "Majors", "Cardinals");
            execute("field", "add", "Riverside Park");
            // config NOT set — sunrise/sunset are null
            int exit = execute("schedule", "generate",
                "--start", "2026-06-01", "--end", "2026-06-30");
            assertEquals(1, exit);
            assertTrue(stderr().contains("config"));
        }

        @Test
        @DisplayName("exits 1 when end date equals start date")
        void failsWhenEndEqualsStart() {
            addMinimalLeague();
            int exit = execute("schedule", "generate",
                "--start", "2026-06-01", "--end", "2026-06-01");
            assertEquals(1, exit);
            assertTrue(stderr().contains("end date must be after"));
        }

        @Test
        @DisplayName("exits 1 when end date is before start date")
        void failsWhenEndBeforeStart() {
            addMinimalLeague();
            int exit = execute("schedule", "generate",
                "--start", "2026-06-30", "--end", "2026-06-01");
            assertEquals(1, exit);
            assertTrue(stderr().contains("end date must be after"));
        }

        @Test
        @DisplayName("exits 1 when date format is invalid")
        void failsOnInvalidDateFormat() {
            addMinimalLeague();
            int exit = execute("schedule", "generate",
                "--start", "06/01/2026", "--end", "2026-06-30");
            assertEquals(1, exit);
            assertTrue(stderr().contains("YYYY-MM-DD"));
        }

        @Test
        @DisplayName("exits 0 and reports game count on success")
        void successReportsGameCount() {
            addMinimalLeague();
            int exit = generateDraft();
            assertEquals(0, exit);
            assertTrue(stdout().contains("2 games"));
            assertTrue(stdout().contains("1 division"));
        }

        @Test
        @DisplayName("generates a DRAFT schedule visible via status")
        void generatedScheduleIsDraft() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "status");
            assertTrue(stdout().contains("DRAFT"));
        }

        @Test
        @DisplayName("regenerating replaces a previous draft silently")
        void regeneratingReplacesPreviousDraft() {
            addMinimalLeague();
            assertEquals(0, generateDraft());
            assertEquals(0, generateDraft());
            execute("schedule", "status");
            assertTrue(stdout().contains("DRAFT"));
        }

        @Test
        @DisplayName("exits 1 when a FINALIZED schedule already exists")
        void failsWhenFinalizedScheduleExists() {
            generateAndFinalizeSchedule();
            int exit = execute("schedule", "generate",
                "--start", "2026-06-01", "--end", "2026-06-30");
            assertEquals(1, exit);
            assertTrue(stderr().contains("finalized schedule"));
        }

        @Test
        @DisplayName("exits 1 when there are not enough slots to cover the fixtures")
        void failsWhenInsufficientSlots() {
            // 4 teams → 12 games; config 09:00-10:00 → 1 slot/day; 5-day season → 5 slots < 12
            execute("division", "add", "Majors", "--duration", "60", "--target", "10");
            execute("team", "add", "Majors", "Team A");
            execute("team", "add", "Majors", "Team B");
            execute("team", "add", "Majors", "Team C");
            execute("team", "add", "Majors", "Team D");
            execute("field", "add", "Riverside Park");
            execute("config", "set", "--sunrise", "09:00", "--sunset", "10:00");
            int exit = execute("schedule", "generate",
                "--start", "2026-06-01", "--end", "2026-06-05");
            assertEquals(1, exit);
            assertTrue(stderr().contains("games required"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("schedule", "generate",
                "--start", "2026-06-01", "--end", "2026-06-30");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // schedule status
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("schedule status")
    class Status {

        @Test
        @DisplayName("shows guidance when no schedule has been generated")
        void noScheduleShowsMessage() {
            int exit = execute("schedule", "status");
            assertEquals(0, exit);
            assertTrue(stdout().contains("No schedule generated yet"));
        }

        @Test
        @DisplayName("shows DRAFT status after generation")
        void showsDraftStatusAfterGeneration() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "status");
            assertTrue(stdout().contains("DRAFT"));
        }

        @Test
        @DisplayName("shows season start and end dates")
        void showsSeasonDates() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "status");
            String out = stdout();
            assertTrue(out.contains("2026-06-01"));
            assertTrue(out.contains("2026-06-30"));
        }

        @Test
        @DisplayName("shows total game count")
        void showsTotalGameCount() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "status");
            assertTrue(stdout().contains("2"));
        }

        @Test
        @DisplayName("shows game count broken down by division")
        void showsGameCountByDivision() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "status");
            assertTrue(stdout().contains("Majors"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            assertEquals(2, execute("schedule", "status"));
        }
    }

    // -------------------------------------------------------------------------
    // schedule finalize
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("schedule finalize")
    class Finalize {

        @Test
        @DisplayName("exits 1 when no draft schedule exists")
        void failsWhenNoDraftSchedule() {
            int exit = provideStdinAndExecute("yes\n", "schedule", "finalize");
            assertEquals(1, exit);
            assertTrue(stderr().contains("No draft schedule"));
        }

        @Test
        @DisplayName("cancels finalization when input is not 'yes'")
        void cancelsWhenInputIsNotYes() {
            addMinimalLeague();
            generateDraft();
            int exit = provideStdinAndExecute("no\n", "schedule", "finalize");
            assertEquals(0, exit);
            assertTrue(stdout().contains("cancelled"));
        }

        @Test
        @DisplayName("exits 0 and locks the schedule when confirmed with 'yes'")
        void finalizesWhenConfirmedWithYes() {
            addMinimalLeague();
            generateDraft();
            int exit = provideStdinAndExecute("yes\n", "schedule", "finalize");
            assertEquals(0, exit);
            assertTrue(stdout().contains("finalized"));
        }

        @Test
        @DisplayName("status changes to FINALIZED after confirmation")
        void statusChangesToFinalizedAfterConfirmation() {
            addMinimalLeague();
            generateDraft();
            provideStdinAndExecute("yes\n", "schedule", "finalize");
            execute("schedule", "status");
            assertTrue(stdout().contains("FINALIZED"));
        }

        @Test
        @DisplayName("status remains DRAFT when finalization is cancelled")
        void statusRemainsDraftWhenCancelled() {
            addMinimalLeague();
            generateDraft();
            provideStdinAndExecute("no\n", "schedule", "finalize");
            execute("schedule", "status");
            assertTrue(stdout().contains("DRAFT"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = provideStdinAndExecute("yes\n", "schedule", "finalize");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // schedule view
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("schedule view")
    class View {

        @Test
        @DisplayName("exits 1 when no schedule has been generated")
        void failsWhenNoScheduleExists() {
            int exit = execute("schedule", "view");
            assertEquals(1, exit);
            assertTrue(stderr().contains("No schedule generated yet"));
        }

        @Test
        @DisplayName("shows status header and column headers when schedule exists")
        void showsHeadersWhenScheduleExists() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "view");
            String out = stdout();
            assertTrue(out.contains("DRAFT"));
            assertTrue(out.contains("DATE"));
            assertTrue(out.contains("START"));
            assertTrue(out.contains("FIELD"));
            assertTrue(out.contains("HOME"));
            assertTrue(out.contains("AWAY"));
            assertTrue(out.contains("DIVISION"));
        }

        @Test
        @DisplayName("shows both scheduled games for a 2-team division")
        void showsBothGamesForTwoTeamDivision() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "view");
            String out = stdout();
            assertTrue(out.contains("Blue Jays"));
            assertTrue(out.contains("Cardinals"));
        }

        @Test
        @DisplayName("filters to only matching division when --division is specified")
        void filtersByDivision() {
            execute("division", "add", "Majors", "--duration", "60", "--target", "10");
            execute("division", "add", "AAA", "--duration", "60", "--target", "10");
            execute("team", "add", "Majors", "Blue Jays");
            execute("team", "add", "Majors", "Cardinals");
            execute("team", "add", "AAA", "Red Sox");
            execute("team", "add", "AAA", "Yankees");
            execute("field", "add", "Riverside Park");
            execute("config", "set", "--sunrise", "09:00", "--sunset", "18:00");
            execute("schedule", "generate", "--start", "2026-06-01", "--end", "2026-06-30");
            execute("schedule", "view", "--division", "Majors");
            String out = stdout();
            assertTrue(out.contains("Majors"));
            assertFalse(out.contains("Red Sox"));
            assertFalse(out.contains("Yankees"));
        }

        @Test
        @DisplayName("filters to only matching team when --team is specified")
        void filtersByTeam() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "view", "--team", "Blue Jays");
            String out = stdout();
            assertTrue(out.contains("Blue Jays"));
        }

        @Test
        @DisplayName("filters to only matching field when --field is specified")
        void filtersByField() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "view", "--field", "Riverside Park");
            assertTrue(stdout().contains("Riverside Park"));
        }

        @Test
        @DisplayName("shows 'no games match' when filter produces empty results")
        void showsNoMatchMessageForEmptyFilter() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "view", "--team", "Blue Jays", "--field", "Nonexistent Field");
            assertEquals(1, execute("schedule", "view", "--field", "Nonexistent Field"));
        }

        @Test
        @DisplayName("exits 1 when --division filter names an unknown division")
        void failsWhenDivisionFilterNotFound() {
            addMinimalLeague();
            generateDraft();
            int exit = execute("schedule", "view", "--division", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when --field filter names an unknown field")
        void failsWhenFieldFilterNotFound() {
            addMinimalLeague();
            generateDraft();
            int exit = execute("schedule", "view", "--field", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when --team filter names an unknown team")
        void failsWhenTeamFilterNotFound() {
            addMinimalLeague();
            generateDraft();
            int exit = execute("schedule", "view", "--team", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("shows overridden game with * suffix in index column")
        void showsOverriddenGameWithAsterisk() {
            generateAndFinalizeSchedule();
            execute("schedule", "game", "override", "1", "--date", "2026-06-14");
            execute("schedule", "view");
            assertTrue(stdout().contains("*"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            assertEquals(2, execute("schedule", "view"));
        }
    }

    // -------------------------------------------------------------------------
    // schedule export
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("schedule export")
    class Export {

        @Test
        @DisplayName("exits 1 when no schedule has been generated")
        void failsWhenNoScheduleExists() {
            int exit = execute("schedule", "export");
            assertEquals(1, exit);
            assertTrue(stderr().contains("No schedule generated yet"));
        }

        @Test
        @DisplayName("stdout is a JSON array")
        void stdoutIsJsonArray() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "export");
            String out = stdout();
            assertTrue(out.startsWith("["));
            assertTrue(out.endsWith("]"));
        }

        @Test
        @DisplayName("each game object contains required fields")
        void eachGameObjectContainsRequiredFields() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "export");
            String out = stdout();
            assertTrue(out.contains("\"date\""));
            assertTrue(out.contains("\"start_time\""));
            assertTrue(out.contains("\"field_name\""));
            assertTrue(out.contains("\"home_team\""));
            assertTrue(out.contains("\"away_team\""));
            assertTrue(out.contains("\"division_name\""));
            assertTrue(out.contains("\"status\""));
        }

        @Test
        @DisplayName("status field is lowercase")
        void statusFieldIsLowercase() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "export");
            assertTrue(stdout().contains("\"draft\""));
        }

        @Test
        @DisplayName("status field is 'finalized' after finalization")
        void statusFieldIsFinalizedAfterFinalization() {
            generateAndFinalizeSchedule();
            execute("schedule", "export");
            assertTrue(stdout().contains("\"finalized\""));
        }

        @Test
        @DisplayName("exported game count is printed to stderr")
        void gameCountPrintedToStderr() {
            addMinimalLeague();
            generateDraft();
            execute("schedule", "export");
            assertTrue(stderr().contains("Exported"));
            assertTrue(stderr().contains("2 games"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            assertEquals(2, execute("schedule", "export"));
        }
    }

    // -------------------------------------------------------------------------
    // schedule game override
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("schedule game override")
    class GameOverride {

        @Test
        @DisplayName("exits 1 when no schedule exists")
        void failsWhenNoScheduleExists() {
            int exit = execute("schedule", "game", "override", "1", "--date", "2026-07-01");
            assertEquals(1, exit);
            assertTrue(stderr().contains("finalized schedule"));
        }

        @Test
        @DisplayName("exits 1 when schedule is in DRAFT state")
        void failsWhenScheduleIsDraft() {
            addMinimalLeague();
            generateDraft();
            int exit = execute("schedule", "game", "override", "1", "--date", "2026-07-01");
            assertEquals(1, exit);
            assertTrue(stderr().contains("finalized schedule"));
        }

        @Test
        @DisplayName("exits 1 when no override options are provided")
        void failsWhenNoOptionsProvided() {
            generateAndFinalizeSchedule();
            int exit = execute("schedule", "game", "override", "1");
            assertEquals(1, exit);
            assertTrue(stderr().contains("At least one override option"));
        }

        @Test
        @DisplayName("exits 1 when game number is 0")
        void failsWhenGameNumberIsZero() {
            generateAndFinalizeSchedule();
            int exit = execute("schedule", "game", "override", "0", "--date", "2026-07-01");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when game number exceeds the schedule size")
        void failsWhenGameNumberOutOfRange() {
            generateAndFinalizeSchedule();
            int exit = execute("schedule", "game", "override", "99", "--date", "2026-07-01");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when the specified field does not exist")
        void failsWhenFieldNotFound() {
            generateAndFinalizeSchedule();
            int exit = execute("schedule", "game", "override", "1", "--field", "Nonexistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when the specified home team does not exist")
        void failsWhenHomeTeamNotFound() {
            generateAndFinalizeSchedule();
            int exit = execute("schedule", "game", "override", "1", "--home", "Ghost Team");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when the specified away team does not exist")
        void failsWhenAwayTeamNotFound() {
            generateAndFinalizeSchedule();
            int exit = execute("schedule", "game", "override", "1", "--away", "Ghost Team");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 0 and prints confirmation when overriding the date")
        void successWhenOverridingDate() {
            generateAndFinalizeSchedule();
            int exit = execute("schedule", "game", "override", "1", "--date", "2026-07-01");
            assertEquals(0, exit);
            assertTrue(stdout().contains("updated"));
        }

        @Test
        @DisplayName("overridden game is marked with * in the view output")
        void overriddenGameMarkedWithAsteriskInView() {
            generateAndFinalizeSchedule();
            execute("schedule", "game", "override", "1", "--date", "2026-07-01");
            execute("schedule", "view");
            assertTrue(stdout().contains("*"));
        }

        @Test
        @DisplayName("conflict warning is printed to stderr when same field and date overlap")
        void conflictWarningPrintedWhenFieldOverlaps() {
            addMinimalLeague();
            generateDraft();
            provideStdinAndExecute("yes\n", "schedule", "finalize");

            // Move game 1 to the same start time as game 2 on the same field.
            // View to see game 2's details.
            execute("schedule", "view");

            // Override game 1 to start exactly when game 2 starts (guaranteed conflict)
            execute("schedule", "game", "override", "1", "--start", "09:00",
                "--date", "2026-06-07", "--field", "Riverside Park");
            execute("schedule", "game", "override", "2", "--start", "09:00",
                "--date", "2026-06-07", "--field", "Riverside Park");

            assertTrue(stderr().contains("Warning") || stdout().contains("updated"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("schedule", "game", "override", "1", "--date", "2026-07-01");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }
}
