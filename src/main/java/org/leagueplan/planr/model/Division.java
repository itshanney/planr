package org.leagueplan.planr.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public record Division(
    UUID id,
    String name,
    int gameDurationMinutes,
    int targetGamesPerTeam,
    List<Team> teams,
    Integer practiceCount,
    Integer practiceDurationMinutes,
    LocalDate practiceStart,
    LocalDate practiceEnd,
    LocalTime curfewTime) {

  public Optional<Team> findTeam(String name) {
    return teams.stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst();
  }

  public boolean hasTeam(String name) {
    return findTeam(name).isPresent();
  }

  public boolean isPracticeConfigured() {
    return practiceCount != null
        && practiceDurationMinutes != null
        && practiceStart != null
        && practiceEnd != null;
  }

  public Division withTeamAdded(Team team) {
    return new Division(
        id,
        this.name,
        gameDurationMinutes,
        targetGamesPerTeam,
        Stream.concat(teams.stream(), Stream.of(team)).toList(),
        practiceCount,
        practiceDurationMinutes,
        practiceStart,
        practiceEnd,
        curfewTime);
  }

  public Division withTeamReplaced(UUID teamId, Team replacement) {
    return new Division(
        id,
        this.name,
        gameDurationMinutes,
        targetGamesPerTeam,
        teams.stream().map(t -> t.id().equals(teamId) ? replacement : t).toList(),
        practiceCount,
        practiceDurationMinutes,
        practiceStart,
        practiceEnd,
        curfewTime);
  }

  public Division withTeamRemoved(UUID teamId) {
    return new Division(
        id,
        this.name,
        gameDurationMinutes,
        targetGamesPerTeam,
        teams.stream().filter(t -> !t.id().equals(teamId)).toList(),
        practiceCount,
        practiceDurationMinutes,
        practiceStart,
        practiceEnd,
        curfewTime);
  }

  public Division withPracticeConfig(
      Integer count, Integer durationMinutes, LocalDate start, LocalDate end) {
    return new Division(
        id,
        name,
        gameDurationMinutes,
        targetGamesPerTeam,
        teams,
        count,
        durationMinutes,
        start,
        end,
        curfewTime);
  }
}
