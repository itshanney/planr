package org.leagueplan.planr.store;

import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testability note: LeagueStore.DATA_DIR is a static final field evaluated at class-load time
 * from System.getProperty("user.home"). The Gradle test task sets user.home to build/test-home
 * before the JVM starts, so DATA_DIR resolves to a build-scoped path that is safe to write and
 * clean between tests. Tests cannot inject a per-test temp directory because the field is
 * not injectable. The recommended fix is to add a package-private LeagueStore(Path dataDir)
 * constructor so tests can supply a @TempDir directly.
 */
class LeagueStoreTest {

    // Mirrors LeagueStore's static final paths; kept in sync manually.
    private static final Path DATA_DIR   = Path.of(System.getProperty("user.home"), ".planr");
    private static final Path LEAGUE_FILE = DATA_DIR.resolve("league.json");

    private LeagueStore store;

    @BeforeEach
    void setUp() throws IOException {
        deleteTestData();
        store = new LeagueStore();
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteTestData();
    }

    // --- load ---

    @Test
    @DisplayName("load() on a missing file creates and returns an empty league")
    void load_createsEmptyLeagueWhenFileAbsent() throws IOException {
        League league = store.load();
        assertTrue(league.divisions().isEmpty());
        assertEquals(1, league.version());
    }

    @Test
    @DisplayName("load() creates league.json when it does not exist")
    void load_createsFileWhenAbsent() throws IOException {
        assertFalse(Files.exists(LEAGUE_FILE));
        store.load();
        assertTrue(Files.exists(LEAGUE_FILE));
    }

    @Test
    @DisplayName("load() throws IOException for a corrupted JSON file")
    void load_throwsIOExceptionForCorruptedJson() throws IOException {
        Files.createDirectories(DATA_DIR);
        Files.writeString(LEAGUE_FILE, "{ this is not valid json }");
        assertThrows(IOException.class, () -> store.load());
    }

    // --- save / load round-trips ---

    @Test
    @DisplayName("save() and load() round-trips an empty league")
    void saveAndLoad_roundTripsEmptyLeague() throws IOException {
        store.save(League.empty());
        League loaded = store.load();
        assertTrue(loaded.divisions().isEmpty());
        assertEquals(1, loaded.version());
    }

    @Test
    @DisplayName("save() and load() round-trips all division fields including UUID")
    void saveAndLoad_roundTripsDivisionFields() throws IOException {
        UUID divId = UUID.randomUUID();
        store.save(new League(1, List.of(new Division(divId, "Majors", 120, List.of()))));

        League loaded = store.load();
        assertEquals(1, loaded.divisions().size());
        Division div = loaded.divisions().get(0);
        assertEquals(divId, div.id());
        assertEquals("Majors", div.name());
        assertEquals(120, div.gameDurationMinutes());
    }

    @Test
    @DisplayName("save() and load() round-trips teams nested within a division")
    void saveAndLoad_roundTripsTeams() throws IOException {
        UUID teamId = UUID.randomUUID();
        Team team = new Team(teamId, "Blue Jays");
        Division division = new Division(UUID.randomUUID(), "Majors", 120, List.of(team));
        store.save(new League(1, List.of(division)));

        List<Team> loadedTeams = store.load().divisions().get(0).teams();
        assertEquals(1, loadedTeams.size());
        assertEquals(teamId, loadedTeams.get(0).id());
        assertEquals("Blue Jays", loadedTeams.get(0).name());
    }

    @Test
    @DisplayName("save() and load() preserves insertion order of multiple divisions")
    void saveAndLoad_preservesDivisionOrder() throws IOException {
        List<Division> divisions = List.of(
            new Division(UUID.randomUUID(), "Majors", 120, List.of()),
            new Division(UUID.randomUUID(), "AAA",    90,  List.of()),
            new Division(UUID.randomUUID(), "Coast",  90,  List.of()),
            new Division(UUID.randomUUID(), "T-Ball", 60,  List.of())
        );
        store.save(new League(1, divisions));

        List<Division> loaded = store.load().divisions();
        assertEquals(4, loaded.size());
        assertEquals("Majors", loaded.get(0).name());
        assertEquals("AAA",    loaded.get(1).name());
        assertEquals("Coast",  loaded.get(2).name());
        assertEquals("T-Ball", loaded.get(3).name());
    }

    @Test
    @DisplayName("save() and load() round-trips a division name containing special characters")
    void saveAndLoad_roundTripsSpecialCharactersInName() throws IOException {
        Division division = new Division(UUID.randomUUID(), "T-Ball & Rookie", 60, List.of());
        store.save(new League(1, List.of(division)));
        assertEquals("T-Ball & Rookie", store.load().divisions().get(0).name());
    }

    // --- atomic write behaviour ---

    @Test
    @DisplayName("save() does not leave a .tmp file behind on success")
    void save_doesNotLeaveTemporaryFile() throws IOException {
        store.save(League.empty());
        assertFalse(Files.exists(DATA_DIR.resolve("league.json.tmp")));
    }

    @Test
    @DisplayName("save() creates the data directory if it does not exist")
    void save_createsDataDirectoryIfAbsent() throws IOException {
        assertFalse(Files.exists(DATA_DIR));
        store.save(League.empty());
        assertTrue(Files.exists(DATA_DIR));
    }

    // --- helpers ---

    private void deleteTestData() throws IOException {
        if (Files.exists(DATA_DIR)) {
            Files.walk(DATA_DIR)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
        }
    }
}
