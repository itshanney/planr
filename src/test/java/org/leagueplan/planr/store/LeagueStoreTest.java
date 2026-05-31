package org.leagueplan.planr.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.Team;

/**
 * Testability note: LeagueStore.DATA_DIR is a static final field evaluated at class-load time from
 * System.getProperty("user.home"). The Gradle test task sets user.home to build/test-home before
 * the JVM starts, so DATA_DIR resolves to a build-scoped path that is safe to write and clean
 * between tests. Tests cannot inject a per-test temp directory because the field is not injectable.
 * The recommended fix is to add a package-private LeagueStore(Path dataDir) constructor so tests
 * can supply a @TempDir directly.
 */
class LeagueStoreTest {

  // Mirrors LeagueStore's static final paths; kept in sync manually.
  private static final Path DATA_DIR = Path.of(System.getProperty("user.home"), ".planr");
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
  @DisplayName("load() on a missing file creates and returns an empty league at current version")
  void load_createsEmptyLeagueWhenFileAbsent() throws IOException {
    League league = store.load();
    assertTrue(league.divisions().isEmpty());
    assertTrue(league.fields().isEmpty());
    assertEquals(10, league.version());
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
    assertTrue(loaded.fields().isEmpty());
    assertEquals(10, loaded.version());
  }

  @Test
  @DisplayName("save() and load() round-trips all division fields including UUID")
  void saveAndLoad_roundTripsDivisionFields() throws IOException {
    UUID divId = UUID.randomUUID();
    store.save(
        new League(
            4,
            null,
            List.of(new Division(divId, "Majors", 120, 0, List.of(), null, null, null, null, null)),
            List.of(),
            null,
            null,
            List.of(),
            List.of()));

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
    Division division =
        new Division(UUID.randomUUID(), "Majors", 120, 0, List.of(team), null, null, null, null, null);
    store.save(new League(4, null, List.of(division), List.of(), null, null, List.of(), List.of()));

    List<Team> loadedTeams = store.load().divisions().get(0).teams();
    assertEquals(1, loadedTeams.size());
    assertEquals(teamId, loadedTeams.get(0).id());
    assertEquals("Blue Jays", loadedTeams.get(0).name());
  }

  @Test
  @DisplayName("save() and load() preserves insertion order of multiple divisions")
  void saveAndLoad_preservesDivisionOrder() throws IOException {
    List<Division> divisions =
        List.of(
            new Division(UUID.randomUUID(), "Majors", 120, 0, List.of(), null, null, null, null, null),
            new Division(UUID.randomUUID(), "AAA", 90, 0, List.of(), null, null, null, null, null),
            new Division(UUID.randomUUID(), "Coast", 90, 0, List.of(), null, null, null, null, null),
            new Division(UUID.randomUUID(), "T-Ball", 60, 0, List.of(), null, null, null, null, null));
    store.save(new League(4, null, divisions, List.of(), null, null, List.of(), List.of()));

    List<Division> loaded = store.load().divisions();
    assertEquals(4, loaded.size());
    assertEquals("Majors", loaded.get(0).name());
    assertEquals("AAA", loaded.get(1).name());
    assertEquals("Coast", loaded.get(2).name());
    assertEquals("T-Ball", loaded.get(3).name());
  }

  @Test
  @DisplayName("save() and load() round-trips a division name containing special characters")
  void saveAndLoad_roundTripsSpecialCharactersInName() throws IOException {
    Division division =
        new Division(
            UUID.randomUUID(), "T-Ball & Rookie", 60, 0, List.of(), null, null, null, null, null);
    store.save(new League(4, null, List.of(division), List.of(), null, null, List.of(), List.of()));
    assertEquals("T-Ball & Rookie", store.load().divisions().get(0).name());
  }

  // --- schema migrations ---

  @Test
  @DisplayName("load() migrates a v2 file all the way to v6")
  void load_migratesV2ToV6() throws IOException {
    String v2Json =
        """
        {
          "version": 2,
          "divisions": [],
          "fields": []
        }
        """;
    Files.createDirectories(DATA_DIR);
    Files.writeString(LEAGUE_FILE, v2Json);

    League loaded = store.load();
    assertEquals(10, loaded.version());
    assertNull(loaded.schedule());
    assertTrue(loaded.divisions().isEmpty());
    assertTrue(loaded.fields().isEmpty());
  }

  @Test
  @DisplayName("load() writes the migrated v6 file back to disk")
  void load_writesMigratedV6FileToDisk() throws IOException {
    String v2Json =
        """
        {
          "version": 2,
          "divisions": [],
          "fields": []
        }
        """;
    Files.createDirectories(DATA_DIR);
    Files.writeString(LEAGUE_FILE, v2Json);

    store.load();

    League onDisk = new LeagueStore().load();
    assertEquals(10, onDisk.version());
  }

  @Test
  @DisplayName("load() migrates a v1 file all the way to v6 in a single call")
  void load_migratesV1ToV6InSingleLoad() throws IOException {
    String v1Json =
        """
        {
          "version": 1,
          "divisions": []
        }
        """;
    Files.createDirectories(DATA_DIR);
    Files.writeString(LEAGUE_FILE, v1Json);

    League loaded = store.load();
    assertEquals(10, loaded.version());
    assertTrue(loaded.fields().isEmpty());
    assertNull(loaded.schedule());
  }

  @Test
  @DisplayName("load() migrates a v3 file to v6 with empty blocks and dateOverrides on each field")
  void load_migratesV3ToV6ClearingFieldCollections() throws IOException {
    UUID fieldId = UUID.randomUUID();
    // v3 fields did not have blocks/dateOverrides; unknown keys are silently dropped by Jackson.
    String v3Json =
        """
        {
          "version": 3,
          "divisions": [],
          "fields": [
            {
              "id": "%s",
              "name": "Riverside Park",
              "address": null,
              "availabilityWindows": [{"date": "2026-06-01", "start": "09:00", "end": "18:00"}]
            }
          ]
        }
        """
            .formatted(fieldId);
    Files.createDirectories(DATA_DIR);
    Files.writeString(LEAGUE_FILE, v3Json);

    League loaded = store.load();
    assertEquals(10, loaded.version());
    assertEquals(1, loaded.fields().size());
    Field field = loaded.fields().get(0);
    assertEquals(fieldId, field.id());
    assertEquals("Riverside Park", field.name());
    assertTrue(field.blocks().isEmpty());
    assertTrue(field.dateOverrides().isEmpty());
  }

  @Test
  @DisplayName("load() prints a migration warning to stderr when upgrading a pre-v4 file")
  void load_printsMigrationWarningToStderrOnPreV4File() throws IOException {
    String v3Json =
        """
        {
          "version": 3,
          "divisions": [],
          "fields": []
        }
        """;
    Files.createDirectories(DATA_DIR);
    Files.writeString(LEAGUE_FILE, v3Json);

    ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(errContent));
    try {
      store.load();
    } finally {
      System.setErr(originalErr);
    }

    assertTrue(errContent.toString().contains("Field availability windows"));
  }

  @Test
  @DisplayName(
      "load() does not print the migration warning when loading a natively-created v4 file")
  void load_noPrintWarningForNativeV4File() throws IOException {
    store.save(League.empty()); // creates a v4 file directly

    ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(errContent));
    try {
      new LeagueStore().load();
    } finally {
      System.setErr(originalErr);
    }

    assertFalse(errContent.toString().contains("Field availability windows"));
  }

  @Test
  @DisplayName(
      "load() does not print the migration warning on a subsequent load of an already-v4 file")
  void load_doesNotPrintWarningOnSubsequentLoadOfV4File() throws IOException {
    String v3Json =
        """
        {
          "version": 3,
          "divisions": [],
          "fields": []
        }
        """;
    Files.createDirectories(DATA_DIR);
    Files.writeString(LEAGUE_FILE, v3Json);

    // First load upgrades to v4 and prints the warning.
    store.load();

    // Second load: file is already v4 — no warning should appear.
    ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(errContent));
    try {
      new LeagueStore().load();
    } finally {
      System.setErr(originalErr);
    }

    assertFalse(errContent.toString().contains("Field availability windows"));
  }

  // --- v4 → v6 migration via v5 ---

  @Test
  @DisplayName("load() migrates a v4 file (no dowWindows/blockedDays) to v6")
  void load_migratesV4ToV6() throws IOException {
    String v4Json =
        """
        {
          "version": 4,
          "config": {
            "sunriseTime": "09:00",
            "sunsetTime": "18:00",
            "seasonStart": "2026-06-01",
            "seasonEnd": "2026-08-31"
          },
          "divisions": [],
          "fields": []
        }
        """;
    Files.createDirectories(DATA_DIR);
    Files.writeString(LEAGUE_FILE, v4Json);

    League loaded = store.load();
    assertEquals(10, loaded.version());
    assertNotNull(loaded.config());
    assertTrue(
        loaded.config().dowWindows().isEmpty(),
        "missing dowWindows key should deserialize as empty list");
    assertTrue(
        loaded.config().blockedDays().isEmpty(),
        "missing blockedDays key should deserialize as empty list");
  }

  @Test
  @DisplayName(
      "load() writes the migrated v6 file back to disk so a second load does not re-migrate")
  void load_writesMigratedV6FileToDiskForV4File() throws IOException {
    String v4Json =
        """
        {
          "version": 4,
          "divisions": [],
          "fields": []
        }
        """;
    Files.createDirectories(DATA_DIR);
    Files.writeString(LEAGUE_FILE, v4Json);

    store.load();

    League onDisk = new LeagueStore().load();
    assertEquals(10, onDisk.version());
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
      Files.walk(DATA_DIR).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }
}
