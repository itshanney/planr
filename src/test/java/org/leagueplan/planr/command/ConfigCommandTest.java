package org.leagueplan.planr.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

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
            int exit = execute("config", "set",
                "--sunrise", "07:00", "--sunset", "20:00",
                "--start", "2026-06-01", "--end", "2026-08-31");
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
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("config", "set", "--sunrise", "07:00");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
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
            execute("config", "set", "--sunrise", "08:00", "--sunset", "18:00",
                "--start", "2026-06-01", "--end", "2026-08-31");
            execute("config", "show");
            String out = stdout();
            assertTrue(out.contains("08:00"));
            assertTrue(out.contains("18:00"));
            assertTrue(out.contains("2026-06-01"));
            assertTrue(out.contains("2026-08-31"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("config", "show");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }
}
