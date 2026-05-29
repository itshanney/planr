package org.leagueplan.planr.command;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.leagueplan.planr.PlanrApp;
import org.leagueplan.planr.model.DayOfWeekWindow;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import org.leagueplan.planr.scheduler.SchedulerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "config",
    description = "Manage league-level configuration.",
    subcommands = {
      ConfigCommand.SetCmd.class,
      ConfigCommand.ShowCmd.class,
      ConfigDowCommand.class,
      ConfigBlockdayCommand.class
    },
    mixinStandardHelpOptions = true)
public class ConfigCommand implements Runnable {

  @ParentCommand PlanrApp app;
  @Spec CommandSpec spec;

  static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }

  @Command(name = "set", description = "Set league-level configuration values.")
  static class SetCmd implements Callable<Integer> {

    @ParentCommand ConfigCommand parent;

    @Option(
        names = "--sunrise",
        paramLabel = "<HH:mm>",
        description = "Default field open time (league-level sunrise).")
    String sunriseStr;

    @Option(
        names = "--sunset",
        paramLabel = "<HH:mm>",
        description = "Default field close time (league-level sunset).")
    String sunsetStr;

    @Option(names = "--start", paramLabel = "<YYYY-MM-DD>", description = "Season start date.")
    String startStr;

    @Option(names = "--end", paramLabel = "<YYYY-MM-DD>", description = "Season end date.")
    String endStr;

    @Option(
        names = "--max-games-per-week",
        paramLabel = "<N>",
        description =
            "Max games any team may be scheduled per week (default: "
                + SchedulerService.DEFAULT_MAX_GAMES_PER_WEEK
                + ").")
    String maxGamesPerWeekStr;

    @Option(
        names = "--rest-days",
        paramLabel = "<N>",
        description =
            "Minimum rest days required between a team's games (default: "
                + SchedulerService.DEFAULT_MIN_REST_DAYS
                + ").")
    String restDaysStr;

    @Option(
        names = "--field-buffer-minutes",
        paramLabel = "<N>",
        description =
            "Minutes of dead time added after each game/practice before the field is"
                + " re-available (default: "
                + SchedulerService.DEFAULT_FIELD_BUFFER_MINUTES
                + ").")
    String fieldBufferMinutesStr;

    @Option(
        names = "--grid-minutes",
        paramLabel = "<N>",
        description =
            "Interval in minutes between generated game/practice start times."
                + " Must be a positive integer that evenly divides 60 (default: "
                + SchedulerService.DEFAULT_GRID_MINUTES
                + ").")
    String gridMinutesStr;

    @Override
    public Integer call() {
      if (sunriseStr == null
          && sunsetStr == null
          && startStr == null
          && endStr == null
          && maxGamesPerWeekStr == null
          && restDaysStr == null
          && fieldBufferMinutesStr == null
          && gridMinutesStr == null) {
        System.err.println(
            "Error: At least one of --sunrise, --sunset, --start, --end, "
                + "--max-games-per-week, --rest-days, --field-buffer-minutes, "
                + "or --grid-minutes must be provided.");
        return 1;
      }

      LocalTime sunrise = null;
      if (sunriseStr != null) {
        try {
          sunrise = LocalTime.parse(sunriseStr, TIME_FORMAT);
        } catch (DateTimeParseException e) {
          System.err.printf(
              "Error: Invalid time format \"%s\". Expected HH:mm (e.g., 07:30).%n", sunriseStr);
          return 1;
        }
      }

      LocalTime sunset = null;
      if (sunsetStr != null) {
        try {
          sunset = LocalTime.parse(sunsetStr, TIME_FORMAT);
        } catch (DateTimeParseException e) {
          System.err.printf(
              "Error: Invalid time format \"%s\". Expected HH:mm (e.g., 20:00).%n", sunsetStr);
          return 1;
        }
      }

      if (sunrise != null && sunset != null && !sunset.isAfter(sunrise)) {
        System.err.println("Error: Sunset time must be after sunrise time.");
        return 1;
      }

      LocalDate seasonStart = null;
      if (startStr != null) {
        try {
          seasonStart = LocalDate.parse(startStr);
        } catch (DateTimeParseException e) {
          System.err.printf("Error: Invalid date format \"%s\". Expected YYYY-MM-DD.%n", startStr);
          return 1;
        }
      }

      LocalDate seasonEnd = null;
      if (endStr != null) {
        try {
          seasonEnd = LocalDate.parse(endStr);
        } catch (DateTimeParseException e) {
          System.err.printf("Error: Invalid date format \"%s\". Expected YYYY-MM-DD.%n", endStr);
          return 1;
        }
      }

      if (seasonStart != null && seasonEnd != null && !seasonEnd.isAfter(seasonStart)) {
        System.err.println("Error: Season end date must be after season start date.");
        return 1;
      }

      Integer maxGamesPerWeek = null;
      if (maxGamesPerWeekStr != null) {
        try {
          maxGamesPerWeek = Integer.parseInt(maxGamesPerWeekStr);
          if (maxGamesPerWeek < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) {
          System.err.printf(
              "Error: --max-games-per-week must be a positive integer (got \"%s\").%n",
              maxGamesPerWeekStr);
          return 1;
        }
      }

      Integer restDays = null;
      if (restDaysStr != null) {
        try {
          restDays = Integer.parseInt(restDaysStr);
          if (restDays < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
          System.err.printf(
              "Error: --rest-days must be a non-negative integer (got \"%s\").%n", restDaysStr);
          return 1;
        }
      }

      Integer fieldBufferMinutes = null;
      if (fieldBufferMinutesStr != null) {
        try {
          fieldBufferMinutes = Integer.parseInt(fieldBufferMinutesStr);
          if (fieldBufferMinutes < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
          System.err.printf(
              "Error: --field-buffer-minutes must be a non-negative integer (got \"%s\").%n",
              fieldBufferMinutesStr);
          return 1;
        }
      }

      Integer gridMinutes = null;
      if (gridMinutesStr != null) {
        try {
          gridMinutes = Integer.parseInt(gridMinutesStr);
          if (gridMinutes <= 0 || 60 % gridMinutes != 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
          System.err.printf(
              "Error: --grid-minutes must be a positive integer that evenly divides 60"
                  + " (got \"%s\").%n",
              gridMinutesStr);
          return 1;
        }
      }

      try {
        League league = parent.app.store.load();
        LeagueConfig existing = (league.config() != null) ? league.config() : LeagueConfig.empty();

        LeagueConfig updated =
            new LeagueConfig(
                (sunrise != null) ? sunrise : existing.sunriseTime(),
                (sunset != null) ? sunset : existing.sunsetTime(),
                (seasonStart != null) ? seasonStart : existing.seasonStart(),
                (seasonEnd != null) ? seasonEnd : existing.seasonEnd(),
                existing.dowWindows(),
                existing.blockedDays(),
                (maxGamesPerWeek != null) ? maxGamesPerWeek : existing.maxGamesPerWeek(),
                (restDays != null) ? restDays : existing.minRestDays(),
                (fieldBufferMinutes != null) ? fieldBufferMinutes : existing.fieldBufferMinutes(),
                (gridMinutes != null) ? gridMinutes : existing.gridMinutes());

        parent.app.store.save(league.withConfig(updated));
        System.out.println("League config updated.");
        return 0;
      } catch (IOException e) {
        System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
        return 2;
      }
    }
  }

  @Command(name = "show", description = "Show the current league configuration.")
  static class ShowCmd implements Callable<Integer> {

    @ParentCommand ConfigCommand parent;

    @Override
    public Integer call() {
      try {
        League league = parent.app.store.load();
        LeagueConfig config = league.config();

        System.out.println("League Configuration");
        System.out.println("--------------------");
        System.out.printf(
            "Sunrise:          %s%n",
            (config == null || config.sunriseTime() == null)
                ? "(not set)"
                : config.sunriseTime().format(TIME_FORMAT));
        System.out.printf(
            "Sunset:           %s%n",
            (config == null || config.sunsetTime() == null)
                ? "(not set)"
                : config.sunsetTime().format(TIME_FORMAT));
        System.out.printf(
            "Season start:     %s%n",
            (config == null || config.seasonStart() == null)
                ? "(not set)"
                : config.seasonStart().toString());
        System.out.printf(
            "Season end:       %s%n",
            (config == null || config.seasonEnd() == null)
                ? "(not set)"
                : config.seasonEnd().toString());

        Integer maxGamesPerWeek = (config != null) ? config.maxGamesPerWeek() : null;
        if (maxGamesPerWeek == null) {
          System.out.printf(
              "Max games/week:   %d (default)%n", SchedulerService.DEFAULT_MAX_GAMES_PER_WEEK);
        } else {
          System.out.printf("Max games/week:   %d%n", maxGamesPerWeek);
        }

        Integer minRestDays = (config != null) ? config.minRestDays() : null;
        if (minRestDays == null) {
          System.out.printf(
              "Min rest days:    %d (default)%n", SchedulerService.DEFAULT_MIN_REST_DAYS);
        } else {
          System.out.printf("Min rest days:    %d%n", minRestDays);
        }

        Integer fieldBufferMinutes = (config != null) ? config.fieldBufferMinutes() : null;
        if (fieldBufferMinutes == null) {
          System.out.printf(
              "Field buffer:     %d min (default)%n",
              SchedulerService.DEFAULT_FIELD_BUFFER_MINUTES);
        } else {
          System.out.printf("Field buffer:     %d min%n", fieldBufferMinutes);
        }

        Integer gridMinutes = (config != null) ? config.gridMinutes() : null;
        if (gridMinutes == null) {
          System.out.printf(
              "Grid interval:    %d min (default)%n", SchedulerService.DEFAULT_GRID_MINUTES);
        } else {
          System.out.printf("Grid interval:    %d min%n", gridMinutes);
        }

        System.out.println();
        System.out.println("Day-of-week windows:");
        List<DayOfWeekWindow> windows = (config != null) ? config.dowWindows() : List.of();
        if (windows.isEmpty()) {
          System.out.println("  (none)");
        } else {
          windows.stream()
              .sorted(Comparator.comparingInt(w -> w.day().getValue()))
              .forEach(
                  w ->
                      System.out.printf(
                          "  %s: %s – %s%n",
                          DayParser.displayName(w.day()),
                          w.openStart().format(TIME_FORMAT),
                          w.openEnd().format(TIME_FORMAT)));
        }

        System.out.println();
        System.out.println("Blocked days of week:");
        List<DayOfWeek> blocked = (config != null) ? config.blockedDays() : List.of();
        if (blocked.isEmpty()) {
          System.out.println("  (none)");
        } else {
          blocked.stream()
              .sorted(Comparator.comparingInt(DayOfWeek::getValue))
              .forEach(d -> System.out.printf("  %s%n", DayParser.displayName(d)));
        }

        return 0;
      } catch (IOException e) {
        System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
        return 2;
      }
    }
  }
}
