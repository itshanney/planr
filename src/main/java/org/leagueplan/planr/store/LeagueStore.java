package org.leagueplan.planr.store;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;

public class LeagueStore {

  private static final Path DATA_DIR = Path.of(System.getProperty("user.home"), ".planr");
  private static final Path LEAGUE_FILE = DATA_DIR.resolve("league.json");
  private static final Path LEAGUE_FILE_TMP = DATA_DIR.resolve("league.json.tmp");

  private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

  private final ObjectMapper mapper;

  public LeagueStore() {
    // Custom HH:mm serialization overrides jsr310's default HH:mm:ss for LocalTime.
    SimpleModule localTimeModule = new SimpleModule();
    localTimeModule.addSerializer(
        LocalTime.class,
        new StdSerializer<>(LocalTime.class) {
          @Override
          public void serialize(LocalTime value, JsonGenerator gen, SerializerProvider provider)
              throws IOException {
            gen.writeString(value.format(TIME_FORMAT));
          }
        });
    localTimeModule.addDeserializer(
        LocalTime.class,
        new StdDeserializer<>(LocalTime.class) {
          @Override
          public LocalTime deserialize(JsonParser p, DeserializationContext ctxt)
              throws IOException {
            return LocalTime.parse(p.getText(), TIME_FORMAT);
          }
        });

    mapper =
        new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .registerModule(new JavaTimeModule())
            .registerModule(localTimeModule); // registered last so it wins for LocalTime
  }

  public League load() throws IOException {
    if (!Files.exists(LEAGUE_FILE)) {
      League empty = League.empty();
      save(empty);
      return empty;
    }
    League league = mapper.readValue(LEAGUE_FILE.toFile(), League.class);
    if (league.version() == 1) {
      league = new League(2, null, league.divisions(), List.of(), null, null, List.of(), List.of());
      save(league);
    }
    if (league.version() == 2) {
      league =
          new League(
              3, null, league.divisions(), league.fields(), null, null, List.of(), List.of());
      save(league);
    }
    if (league.version() < 4) {
      List<Field> migratedFields =
          league.fields().stream()
              .map(f -> new Field(f.id(), f.name(), f.address(), List.of(), List.of(), List.of()))
              .toList();
      LeagueConfig config = LeagueConfig.empty();
      league =
          new League(
              4,
              config,
              league.divisions(),
              migratedFields,
              null,
              league.schedule(),
              List.of(),
              List.of());
      save(league);
      System.err.println(
          "Warning: Field availability windows from a previous version "
              + "have been removed. Please configure field blocks for the new season.");
    }
    // v4→v5: adds dowWindows and blockedDays to LeagueConfig. LeagueConfig's compact constructor
    // already normalizes the null fields from old JSON to empty lists; this block just stamps v5
    // so the migration does not re-run. A v5 file loaded by a v4 binary will silently ignore
    // the new fields (FAIL_ON_UNKNOWN_PROPERTIES is disabled), but they will be lost on next save.
    if (league.version() < 5) {
      league =
          new League(
              5,
              league.config(),
              league.divisions(),
              league.fields(),
              league.teamSchedule(),
              league.schedule(),
              List.of(),
              List.of());
      save(league);
    }
    // v5→v6: adds maxGamesPerWeek and minRestDays to LeagueConfig, and divisionLocks to Field.
    // Both new fields are absent from old JSON; compact constructors normalize them to null /
    // List.of()
    // respectively, so no data transformation is required — this block only stamps the version.
    if (league.version() < 6) {
      league =
          new League(
              6,
              league.config(),
              league.divisions(),
              league.fields(),
              league.teamSchedule(),
              league.schedule(),
              List.of(),
              List.of());
      save(league);
    }
    // v6→v7: adds playoffs list to League. Absent from old JSON; compact constructor normalizes
    // null to List.of(), so no data transformation needed — this block only stamps the version.
    if (league.version() < 7) {
      league =
          new League(
              7,
              league.config(),
              league.divisions(),
              league.fields(),
              league.teamSchedule(),
              league.schedule(),
              league.playoffs(),
              List.of());
      save(league);
    }
    // v7→v8: adds practiceSchedules list to League. Absent from old JSON; compact constructor
    // normalizes null to List.of(), so no data transformation needed — this block only stamps
    // the version.
    if (league.version() < 8) {
      league =
          new League(
              8,
              league.config(),
              league.divisions(),
              league.fields(),
              league.teamSchedule(),
              league.schedule(),
              league.playoffs(),
              league.practiceSchedules());
      save(league);
    }
    // v8→v9: adds fieldBufferMinutes and gridMinutes to LeagueConfig. Absent from old JSON;
    // null is the valid "unset" sentinel for both fields — this block only stamps the version.
    if (league.version() < 9) {
      league =
          new League(
              9,
              league.config(),
              league.divisions(),
              league.fields(),
              league.teamSchedule(),
              league.schedule(),
              league.playoffs(),
              league.practiceSchedules());
      save(league);
    }
    return league;
  }

  public void save(League league) throws IOException {
    Files.createDirectories(DATA_DIR);
    mapper.writeValue(LEAGUE_FILE_TMP.toFile(), league);
    // Write to a temp file then rename so a crash mid-write never corrupts the only copy.
    Files.move(
        LEAGUE_FILE_TMP,
        LEAGUE_FILE,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }
}
