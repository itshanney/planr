package org.leagueplan.planr.model;

import java.util.UUID;

public record TeamGame(
    UUID id,
    int gameNumber,
    UUID homeTeamId,
    String homeTeamName,
    UUID awayTeamId,
    String awayTeamName,
    UUID divisionId,
    String divisionName,
    int gameDurationMinutes
) {
    public TeamGame withSwappedHomeAway() {
        return new TeamGame(id, gameNumber,
            awayTeamId, awayTeamName,
            homeTeamId, homeTeamName,
            divisionId, divisionName,
            gameDurationMinutes);
    }
}
