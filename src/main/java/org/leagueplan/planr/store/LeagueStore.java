package org.leagueplan.planr.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.leagueplan.planr.model.League;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class LeagueStore {

    private static final Path DATA_DIR = Path.of(System.getProperty("user.home"), ".planr");
    private static final Path LEAGUE_FILE = DATA_DIR.resolve("league.json");
    private static final Path LEAGUE_FILE_TMP = DATA_DIR.resolve("league.json.tmp");

    private final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        // tolerate unknown fields so older planr versions can read files written by newer ones
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public League load() throws IOException {
        if (!Files.exists(LEAGUE_FILE)) {
            League empty = League.empty();
            save(empty);
            return empty;
        }
        return mapper.readValue(LEAGUE_FILE.toFile(), League.class);
    }

    public void save(League league) throws IOException {
        Files.createDirectories(DATA_DIR);
        mapper.writeValue(LEAGUE_FILE_TMP.toFile(), league);
        // Write to a temp file then rename so a crash mid-write never corrupts the only copy.
        Files.move(LEAGUE_FILE_TMP, LEAGUE_FILE,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
    }
}
