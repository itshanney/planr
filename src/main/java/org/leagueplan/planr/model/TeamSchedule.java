package org.leagueplan.planr.model;

import java.util.List;
import java.util.Optional;

public record TeamSchedule(List<TeamGame> games) {

    public TeamSchedule {
        games = (games == null) ? List.of() : games;
    }

    public TeamSchedule withGameReplaced(int gameNumber, TeamGame replacement) {
        List<TeamGame> updated = games.stream()
            .map(g -> g.gameNumber() == gameNumber ? replacement : g)
            .toList();
        return new TeamSchedule(updated);
    }

    public Optional<TeamGame> findGame(int gameNumber) {
        return games.stream()
            .filter(g -> g.gameNumber() == gameNumber)
            .findFirst();
    }
}
