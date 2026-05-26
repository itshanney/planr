package org.leagueplan.planr.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record Playoff(
    UUID divisionId,
    LocalDate startDate,
    LocalDate endDate,
    PlayoffState state,
    List<PlayoffGame> games
) {
    public Playoff {
        games = (games == null) ? List.of() : games;
    }

    public Playoff withState(PlayoffState newState) {
        return new Playoff(divisionId, startDate, endDate, newState, games);
    }

    public Playoff withGames(List<PlayoffGame> newGames) {
        return new Playoff(divisionId, startDate, endDate, state, newGames);
    }
}
