package org.leagueplan.planr.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public record Division(UUID id, String name, int gameDurationMinutes, List<Team> teams) {

    public Optional<Team> findTeam(String name) {
        return teams.stream()
            .filter(t -> t.name().equalsIgnoreCase(name))
            .findFirst();
    }

    public boolean hasTeam(String name) {
        return findTeam(name).isPresent();
    }

    public Division withTeamAdded(Team team) {
        return new Division(id, this.name, gameDurationMinutes,
            Stream.concat(teams.stream(), Stream.of(team)).toList());
    }

    public Division withTeamReplaced(UUID teamId, Team replacement) {
        return new Division(id, this.name, gameDurationMinutes,
            teams.stream().map(t -> t.id().equals(teamId) ? replacement : t).toList());
    }

    public Division withTeamRemoved(UUID teamId) {
        return new Division(id, this.name, gameDurationMinutes,
            teams.stream().filter(t -> !t.id().equals(teamId)).toList());
    }
}
