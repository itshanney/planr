package org.leagueplan.planr.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

public record LeagueConfig(
    LocalTime sunriseTime,
    LocalTime sunsetTime,
    LocalDate seasonStart,
    LocalDate seasonEnd,
    List<DayOfWeekWindow> dowWindows,
    List<DayOfWeek> blockedDays,
    Integer maxGamesPerWeek,
    Integer minRestDays,
    Integer fieldBufferMinutes,
    Integer gridMinutes) {
  public LeagueConfig {
    dowWindows = (dowWindows == null) ? List.of() : dowWindows;
    blockedDays = (blockedDays == null) ? List.of() : blockedDays;
    // maxGamesPerWeek, minRestDays, fieldBufferMinutes, and gridMinutes remain null to
    // distinguish "unset" from "set to default"
  }

  public static LeagueConfig empty() {
    return new LeagueConfig(null, null, null, null, List.of(), List.of(), null, null, null, null);
  }

  public LeagueConfig withDowWindowSet(DayOfWeekWindow window) {
    List<DayOfWeekWindow> updated =
        Stream.concat(dowWindows.stream().filter(w -> w.day() != window.day()), Stream.of(window))
            .toList();
    return new LeagueConfig(
        sunriseTime,
        sunsetTime,
        seasonStart,
        seasonEnd,
        updated,
        blockedDays,
        maxGamesPerWeek,
        minRestDays,
        fieldBufferMinutes,
        gridMinutes);
  }

  public LeagueConfig withDowWindowRemoved(DayOfWeek day) {
    return new LeagueConfig(
        sunriseTime,
        sunsetTime,
        seasonStart,
        seasonEnd,
        dowWindows.stream().filter(w -> w.day() != day).toList(),
        blockedDays,
        maxGamesPerWeek,
        minRestDays,
        fieldBufferMinutes,
        gridMinutes);
  }

  public LeagueConfig withBlockedDayAdded(DayOfWeek day) {
    return new LeagueConfig(
        sunriseTime,
        sunsetTime,
        seasonStart,
        seasonEnd,
        dowWindows,
        Stream.concat(blockedDays.stream(), Stream.of(day)).toList(),
        maxGamesPerWeek,
        minRestDays,
        fieldBufferMinutes,
        gridMinutes);
  }

  public LeagueConfig withBlockedDayRemoved(DayOfWeek day) {
    return new LeagueConfig(
        sunriseTime,
        sunsetTime,
        seasonStart,
        seasonEnd,
        dowWindows,
        blockedDays.stream().filter(d -> d != day).toList(),
        maxGamesPerWeek,
        minRestDays,
        fieldBufferMinutes,
        gridMinutes);
  }

  public LeagueConfig withMaxGamesPerWeek(Integer n) {
    return new LeagueConfig(
        sunriseTime,
        sunsetTime,
        seasonStart,
        seasonEnd,
        dowWindows,
        blockedDays,
        n,
        minRestDays,
        fieldBufferMinutes,
        gridMinutes);
  }

  public LeagueConfig withMinRestDays(Integer n) {
    return new LeagueConfig(
        sunriseTime,
        sunsetTime,
        seasonStart,
        seasonEnd,
        dowWindows,
        blockedDays,
        maxGamesPerWeek,
        n,
        fieldBufferMinutes,
        gridMinutes);
  }

  public LeagueConfig withFieldBufferMinutes(Integer n) {
    return new LeagueConfig(
        sunriseTime,
        sunsetTime,
        seasonStart,
        seasonEnd,
        dowWindows,
        blockedDays,
        maxGamesPerWeek,
        minRestDays,
        n,
        gridMinutes);
  }

  public LeagueConfig withGridMinutes(Integer n) {
    return new LeagueConfig(
        sunriseTime,
        sunsetTime,
        seasonStart,
        seasonEnd,
        dowWindows,
        blockedDays,
        maxGamesPerWeek,
        minRestDays,
        fieldBufferMinutes,
        n);
  }
}
