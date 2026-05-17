package org.leagueplan.planr.model;

import java.time.LocalDate;
import java.time.LocalTime;

public record LeagueConfig(
    LocalTime sunriseTime,
    LocalTime sunsetTime,
    LocalDate seasonStart,
    LocalDate seasonEnd
) {
    public static LeagueConfig empty() {
        return new LeagueConfig(null, null, null, null);
    }
}
