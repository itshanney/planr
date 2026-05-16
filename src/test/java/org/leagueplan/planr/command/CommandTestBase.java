package org.leagueplan.planr.command;

import org.leagueplan.planr.PlanrApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Base class for end-to-end command tests.
 *
 * Each test gets a clean data directory and isolated stdout/stderr capture. The data directory
 * is build/test-home/.planr (controlled by the user.home system property set in build.gradle).
 * Tests must run serially (maxParallelForks = 1) because all tests share the same file path.
 */
abstract class CommandTestBase {

    private static final Path DATA_DIR = Path.of(System.getProperty("user.home"), ".planr");

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUpBase() throws IOException {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
        cleanDataDir();
    }

    @AfterEach
    void tearDownBase() throws IOException {
        System.setOut(originalOut);
        System.setErr(originalErr);
        cleanDataDir();
    }

    /** Executes a planr command, resets captured output, and returns the exit code. */
    protected int execute(String... args) {
        outContent.reset();
        errContent.reset();
        return new CommandLine(new PlanrApp()).execute(args);
    }

    protected String stdout() {
        return outContent.toString().trim();
    }

    protected String stderr() {
        return errContent.toString().trim();
    }

    /** Writes corrupt JSON to league.json to simulate an unreadable data file. */
    protected void corruptLeagueFile() throws IOException {
        Files.createDirectories(DATA_DIR);
        Files.writeString(DATA_DIR.resolve("league.json"), "{ not valid json }");
    }

    private void cleanDataDir() throws IOException {
        if (Files.exists(DATA_DIR)) {
            Files.walk(DATA_DIR)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
        }
    }
}
