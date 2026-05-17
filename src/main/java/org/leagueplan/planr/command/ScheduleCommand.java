package org.leagueplan.planr.command;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.leagueplan.planr.PlanrApp;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import org.leagueplan.planr.model.Schedule;
import org.leagueplan.planr.model.ScheduleStatus;
import org.leagueplan.planr.model.ScheduledGame;
import org.leagueplan.planr.scheduler.ScheduleResult;
import org.leagueplan.planr.scheduler.SchedulerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(
    name = "schedule",
    description = "Manage the league schedule.",
    subcommands = {
        ScheduleCommand.GenerateCmd.class,
        ScheduleCommand.StatusCmd.class,
        ScheduleCommand.FinalizeCmd.class,
        ScheduleCommand.ViewCmd.class,
        ScheduleCommand.ExportCmd.class,
        ScheduleGameCommand.class
    },
    mixinStandardHelpOptions = true
)
public class ScheduleCommand implements Runnable {

    @ParentCommand PlanrApp app;
    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    // --- Generate ---

    @Command(name = "generate", description = "Generate a draft schedule.")
    static class GenerateCmd implements Callable<Integer> {

        @ParentCommand ScheduleCommand parent;

        @Option(names = "--start", required = true, paramLabel = "<YYYY-MM-DD>",
                description = "Season start date.")
        String startStr;

        @Option(names = "--end", required = true, paramLabel = "<YYYY-MM-DD>",
                description = "Season end date.")
        String endStr;

        @Override
        public Integer call() {
            LocalDate seasonStart, seasonEnd;
            try {
                seasonStart = LocalDate.parse(startStr);
                seasonEnd = LocalDate.parse(endStr);
            } catch (DateTimeParseException e) {
                System.err.println("Error: Dates must be in YYYY-MM-DD format.");
                return 1;
            }
            if (!seasonEnd.isAfter(seasonStart)) {
                System.err.println("Error: Season end date must be after the start date.");
                return 1;
            }

            try {
                League league = parent.app.store.load();

                if (league.schedule() != null && league.schedule().status() == ScheduleStatus.FINALIZED) {
                    System.err.println(
                        "Error: A finalized schedule exists and cannot be regenerated. "
                        + "Use 'planr schedule game override' to adjust individual games.");
                    return 1;
                }

                // Precondition: at least one division with ≥2 teams
                boolean hasEligibleDivision = league.divisions().stream()
                    .anyMatch(d -> d.teams().size() >= 2);
                if (!hasEligibleDivision) {
                    System.err.println(
                        "Error: Schedule generation requires at least one division with 2 or more teams "
                        + "and at least one field.");
                    return 1;
                }

                // Precondition: at least one field configured
                if (league.fields().isEmpty()) {
                    System.err.println(
                        "Error: Schedule generation requires at least one division with 2 or more teams "
                        + "and at least one field.");
                    return 1;
                }

                // Precondition: league config has sunrise and sunset set
                LeagueConfig config = league.config();
                if (config == null || config.sunriseTime() == null || config.sunsetTime() == null) {
                    System.err.println(
                        "Error: Schedule generation requires league config with sunrise and sunset times. "
                        + "Run 'planr config set --sunrise HH:mm --sunset HH:mm' first.");
                    return 1;
                }

                System.out.println("Generating schedule, this may take up to 60 seconds...");

                ScheduleResult result = new SchedulerService().generate(league, seasonStart, seasonEnd);

                if (result instanceof ScheduleResult.Failure f) {
                    System.err.println(f.message());
                    return 1;
                }

                ScheduleResult.Success success = (ScheduleResult.Success) result;
                Schedule schedule = new Schedule(
                    ScheduleStatus.DRAFT, seasonStart, seasonEnd, success.games());
                parent.app.store.save(league.withSchedule(schedule));

                long divCount = success.games().stream()
                    .map(ScheduledGame::divisionId).distinct().count();
                String qualifier = success.optimal() ? "optimal distribution" : "good distribution — optimizer ran for 60s";
                System.out.printf(
                    "Draft schedule generated: %d games across %d division(s) (%s).%n"
                    + "Run 'planr schedule view' to review.%n",
                    success.games().size(), divCount, qualifier);
                return 0;

            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- Status ---

    @Command(name = "status", description = "Show the current schedule status and summary.")
    static class StatusCmd implements Callable<Integer> {

        @ParentCommand ScheduleCommand parent;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                Schedule schedule = league.schedule();
                if (schedule == null) {
                    System.out.println(
                        "No schedule generated yet. Run 'planr schedule generate' to create one.");
                    return 0;
                }

                System.out.printf("Status:        %s%n", schedule.status());
                System.out.printf("Season:        %s to %s%n", schedule.seasonStart(), schedule.seasonEnd());
                System.out.printf("Total games:   %d%n", schedule.games().size());

                Map<String, Long> countByDivision = schedule.games().stream()
                    .collect(Collectors.groupingBy(ScheduledGame::divisionName, Collectors.counting()));
                countByDivision.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> System.out.printf("  %-20s %d games%n", e.getKey() + ":", e.getValue()));

                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- Finalize ---

    @Command(name = "finalize", description = "Promote a draft schedule to finalized state.")
    static class FinalizeCmd implements Callable<Integer> {

        @ParentCommand ScheduleCommand parent;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                Schedule schedule = league.schedule();

                if (schedule == null || schedule.status() != ScheduleStatus.DRAFT) {
                    System.err.println("Error: No draft schedule to finalize.");
                    return 1;
                }

                System.out.print(
                    "Warning: Finalizing the schedule is irreversible. "
                    + "The schedule will be locked and cannot be regenerated. "
                    + "Type 'yes' to confirm: ");
                System.out.flush();

                String input;
                try (Scanner sc = new Scanner(System.in)) {
                    input = sc.nextLine().trim();
                }
                if (!input.equals("yes")) {
                    System.out.println("Finalization cancelled.");
                    return 0;
                }

                Schedule finalized = schedule.withStatus(ScheduleStatus.FINALIZED);
                parent.app.store.save(league.withSchedule(finalized));
                System.out.printf(
                    "Schedule finalized. %d games locked. "
                    + "Use 'planr schedule game override' for individual adjustments.%n",
                    finalized.games().size());
                return 0;

            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- View ---

    @Command(name = "view", description = "View the schedule, with optional filters.")
    static class ViewCmd implements Callable<Integer> {

        @ParentCommand ScheduleCommand parent;

        @Option(names = "--division", paramLabel = "<name>", description = "Filter by division.")
        String divisionFilter;

        @Option(names = "--team", paramLabel = "<name>", description = "Filter by team.")
        String teamFilter;

        @Option(names = "--field", paramLabel = "<name>", description = "Filter by field.")
        String fieldFilter;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                Schedule schedule = league.schedule();
                if (schedule == null) {
                    System.err.println(
                        "Error: No schedule generated yet. Run 'planr schedule generate' to create one.");
                    return 1;
                }

                if (divisionFilter != null && league.findDivision(divisionFilter).isEmpty()) {
                    System.err.printf("Error: Division \"%s\" not found.%n", divisionFilter);
                    return 1;
                }
                if (fieldFilter != null && league.findField(fieldFilter).isEmpty()) {
                    System.err.printf("Error: Field \"%s\" not found.%n", fieldFilter);
                    return 1;
                }
                if (teamFilter != null) {
                    boolean teamExists = league.divisions().stream()
                        .flatMap(d -> d.teams().stream())
                        .anyMatch(t -> t.name().equalsIgnoreCase(teamFilter));
                    if (!teamExists) {
                        System.err.printf("Error: Team \"%s\" not found.%n", teamFilter);
                        return 1;
                    }
                }

                System.out.printf("Schedule status: %s | Season: %s to %s%n%n",
                    schedule.status(), schedule.seasonStart(), schedule.seasonEnd());

                List<ScheduledGame> games = schedule.games();
                List<ScheduledGame> filtered = new ArrayList<>();
                for (int i = 0; i < games.size(); i++) {
                    ScheduledGame g = games.get(i);
                    if (divisionFilter != null && !g.divisionName().equalsIgnoreCase(divisionFilter)) continue;
                    if (fieldFilter != null && !g.fieldName().equalsIgnoreCase(fieldFilter)) continue;
                    if (teamFilter != null
                            && !g.homeTeamName().equalsIgnoreCase(teamFilter)
                            && !g.awayTeamName().equalsIgnoreCase(teamFilter)) continue;
                    filtered.add(g);
                }

                if (filtered.isEmpty()) {
                    System.out.println("No games match the specified filter.");
                    return 0;
                }

                // Build display index labels: 1-based position in the full games list, with * for overrides
                List<String> labels = new ArrayList<>();
                for (ScheduledGame g : filtered) {
                    int pos = games.indexOf(g) + 1;
                    labels.add(g.overridden() ? pos + "*" : String.valueOf(pos));
                }

                printTable(filtered, labels);
                return 0;

            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        private void printTable(List<ScheduledGame> games, List<String> labels) {
            int numW = Math.max(1, labels.stream().mapToInt(String::length).max().orElse(1));
            int dateW = "DATE".length();
            int startW = "START".length();
            int fieldW = Math.max("FIELD".length(),
                games.stream().mapToInt(g -> g.fieldName().length()).max().orElse(0));
            int homeW = Math.max("HOME".length(),
                games.stream().mapToInt(g -> g.homeTeamName().length()).max().orElse(0));
            int awayW = Math.max("AWAY".length(),
                games.stream().mapToInt(g -> g.awayTeamName().length()).max().orElse(0));
            int divW = Math.max("DIVISION".length(),
                games.stream().mapToInt(g -> g.divisionName().length()).max().orElse(0));

            String fmt = "%-" + numW + "s  %-" + dateW + "s  %-" + startW + "s  %-"
                + fieldW + "s  %-" + homeW + "s  %-" + awayW + "s  %-" + divW + "s%n";
            System.out.printf(fmt, "#", "DATE", "START", "FIELD", "HOME", "AWAY", "DIVISION");
            System.out.printf(fmt,
                "-".repeat(numW), "-".repeat(dateW), "-".repeat(startW),
                "-".repeat(fieldW), "-".repeat(homeW), "-".repeat(awayW), "-".repeat(divW));

            for (int i = 0; i < games.size(); i++) {
                ScheduledGame g = games.get(i);
                System.out.printf(fmt,
                    labels.get(i), g.date(), g.startTime(), g.fieldName(),
                    g.homeTeamName(), g.awayTeamName(), g.divisionName());
            }
        }
    }

    // --- Export ---

    @Command(name = "export", description = "Export the schedule as a JSON array to stdout.")
    static class ExportCmd implements Callable<Integer> {

        @ParentCommand ScheduleCommand parent;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                Schedule schedule = league.schedule();
                if (schedule == null) {
                    System.err.println("Error: No schedule generated yet.");
                    return 1;
                }

                ObjectMapper mapper = new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .registerModule(new JavaTimeModule());

                String statusStr = schedule.status().name().toLowerCase();
                List<ExportGame> exports = schedule.games().stream()
                    .map(g -> new ExportGame(
                        g.date().toString(),
                        // LocalTime formatted as HH:mm via JavaTimeModule with no timestamps
                        String.format("%02d:%02d", g.startTime().getHour(), g.startTime().getMinute()),
                        g.fieldName(), g.homeTeamName(), g.awayTeamName(), g.divisionName(), statusStr))
                    .toList();

                System.out.println(mapper.writeValueAsString(exports));
                System.err.printf("Exported %d games.%n", exports.size());
                return 0;

            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        record ExportGame(
            String date,
            @JsonProperty("start_time") String startTime,
            @JsonProperty("field_name") String fieldName,
            @JsonProperty("home_team") String homeTeam,
            @JsonProperty("away_team") String awayTeam,
            @JsonProperty("division_name") String divisionName,
            String status
        ) {}
    }
}
