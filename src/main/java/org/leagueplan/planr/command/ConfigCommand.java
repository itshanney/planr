package org.leagueplan.planr.command;

import org.leagueplan.planr.PlanrApp;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Callable;

@Command(
    name = "config",
    description = "Manage league-level configuration.",
    subcommands = {
        ConfigCommand.SetCmd.class,
        ConfigCommand.ShowCmd.class
    },
    mixinStandardHelpOptions = true
)
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

        @Option(names = "--sunrise", paramLabel = "<HH:mm>",
                description = "Default field open time (league-level sunrise).")
        String sunriseStr;

        @Option(names = "--sunset", paramLabel = "<HH:mm>",
                description = "Default field close time (league-level sunset).")
        String sunsetStr;

        @Option(names = "--start", paramLabel = "<YYYY-MM-DD>",
                description = "Season start date.")
        String startStr;

        @Option(names = "--end", paramLabel = "<YYYY-MM-DD>",
                description = "Season end date.")
        String endStr;

        @Override
        public Integer call() {
            if (sunriseStr == null && sunsetStr == null && startStr == null && endStr == null) {
                System.err.println(
                    "Error: At least one of --sunrise, --sunset, --start, or --end must be provided.");
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
                    System.err.printf(
                        "Error: Invalid date format \"%s\". Expected YYYY-MM-DD.%n", startStr);
                    return 1;
                }
            }

            LocalDate seasonEnd = null;
            if (endStr != null) {
                try {
                    seasonEnd = LocalDate.parse(endStr);
                } catch (DateTimeParseException e) {
                    System.err.printf(
                        "Error: Invalid date format \"%s\". Expected YYYY-MM-DD.%n", endStr);
                    return 1;
                }
            }

            if (seasonStart != null && seasonEnd != null && !seasonEnd.isAfter(seasonStart)) {
                System.err.println("Error: Season end date must be after season start date.");
                return 1;
            }

            try {
                League league = parent.app.store.load();
                LeagueConfig existing = (league.config() != null) ? league.config() : LeagueConfig.empty();

                LeagueConfig updated = new LeagueConfig(
                    (sunrise != null) ? sunrise : existing.sunriseTime(),
                    (sunset != null) ? sunset : existing.sunsetTime(),
                    (seasonStart != null) ? seasonStart : existing.seasonStart(),
                    (seasonEnd != null) ? seasonEnd : existing.seasonEnd());

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
                System.out.printf("Sunrise:        %s%n",
                    (config == null || config.sunriseTime() == null)
                        ? "(not set)" : config.sunriseTime().format(TIME_FORMAT));
                System.out.printf("Sunset:         %s%n",
                    (config == null || config.sunsetTime() == null)
                        ? "(not set)" : config.sunsetTime().format(TIME_FORMAT));
                System.out.printf("Season start:   %s%n",
                    (config == null || config.seasonStart() == null)
                        ? "(not set)" : config.seasonStart().toString());
                System.out.printf("Season end:     %s%n",
                    (config == null || config.seasonEnd() == null)
                        ? "(not set)" : config.seasonEnd().toString());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }
}
