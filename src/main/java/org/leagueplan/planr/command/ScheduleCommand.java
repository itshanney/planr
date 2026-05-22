package org.leagueplan.planr.command;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.leagueplan.planr.PlanrApp;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.Schedule;
import org.leagueplan.planr.model.ScheduleState;
import org.leagueplan.planr.model.ScheduleStatus;
import org.leagueplan.planr.model.ScheduledGame;
import org.leagueplan.planr.model.TeamGame;
import org.leagueplan.planr.scheduler.ScheduleResult;
import org.leagueplan.planr.scheduler.SchedulerService;
import org.leagueplan.planr.scheduler.TeamScheduleResult;
import org.leagueplan.planr.scheduler.TeamScheduleService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(
    name = "schedule",
    description = "Manage the league schedule.",
    subcommands = {
        ScheduleCommand.GenerateMatchupsCmd.class,
        ScheduleCommand.AssignCmd.class,
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

    // --- Phase 1: Generate team schedule ---

    @Command(name = "generate", description = "Generate the team schedule (matchups with home/away assignments).")
    static class GenerateMatchupsCmd implements Callable<Integer> {

        @ParentCommand ScheduleCommand parent;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                ScheduleState state = ScheduleState.of(league);

                if (state == ScheduleState.FINALIZED) {
                    System.err.println("Error: A finalized schedule exists. Phase 1 cannot be re-run. "
                        + "Use 'planr schedule game override' for individual adjustments.");
                    return 1;
                }

                if (state == ScheduleState.DRAFT || state == ScheduleState.TEAM_SCHEDULE) {
                    System.out.print("Warning: Re-running Phase 1 will discard the existing team schedule "
                        + "and any draft schedule. Type 'yes' to continue: ");
                    System.out.flush();
                    String input;
                    try (Scanner sc = new Scanner(System.in)) {
                        input = sc.nextLine().trim();
                    }
                    if (!input.equals("yes")) {
                        System.out.println("Cancelled.");
                        return 0;
                    }
                }

                TeamScheduleResult result = new TeamScheduleService().generate(league);

                if (result instanceof TeamScheduleResult.Failure f) {
                    System.err.println(f.message());
                    return 1;
                }

                TeamScheduleResult.Success success = (TeamScheduleResult.Success) result;

                // Print fill round progress logs before the table
                success.fillRoundLogs().forEach(System.out::println);
                if (!success.fillRoundLogs().isEmpty()) System.out.println();

                long divCount = success.schedule().games().stream()
                    .map(TeamGame::divisionId).distinct().count();
                System.out.printf("Team schedule generated: %d games across %d division(s).%n%n",
                    success.schedule().games().size(), divCount);

                printTeamScheduleTable(success.schedule().games());

                System.out.println();
                System.out.println("Review the matchups. Run 'planr schedule game edit <#> --home <team>' to adjust home/away.");
                System.out.println("Run 'planr schedule assign' when ready to assign dates, times, and fields.");

                parent.app.store.save(league.withTeamScheduleCleared()
                    .withTeamSchedule(success.schedule()));
                return 0;

            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- Phase 2: Assign dates, times, and fields ---

    @Command(name = "assign", description = "Assign dates, times, and fields to the confirmed team schedule.")
    static class AssignCmd implements Callable<Integer> {

        @ParentCommand ScheduleCommand parent;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                ScheduleState state = ScheduleState.of(league);

                if (state == ScheduleState.NONE) {
                    System.err.println("Error: No team schedule found. Run 'planr schedule generate' first.");
                    return 1;
                }
                if (state == ScheduleState.FINALIZED) {
                    System.err.println("Error: A finalized schedule exists. "
                        + "Run 'planr schedule game override' for individual adjustments.");
                    return 1;
                }
                if (league.fields().isEmpty()) {
                    System.err.println("Error: At least one field must be configured before field assignment. "
                        + "Run 'planr field add'.");
                    return 1;
                }
                if (league.config() == null
                        || league.config().seasonStart() == null
                        || league.config().seasonEnd() == null) {
                    System.err.println("Error: Season start and end dates must be configured. "
                        + "Run 'planr config set --start <date> --end <date>'.");
                    return 1;
                }

                // Display condensed team schedule summary
                List<TeamGame> games = league.teamSchedule().games();
                Map<String, Long> byDiv = games.stream()
                    .collect(Collectors.groupingBy(TeamGame::divisionName, Collectors.counting()));
                String divSummary = byDiv.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + " " + e.getValue())
                    .collect(Collectors.joining(", "));
                System.out.printf("Team schedule: %d games across %d division(s) (%s).%n",
                    games.size(), byDiv.size(), divSummary);

                // Per-division feasibility estimate
                SchedulerService schedulerService = new SchedulerService();
                for (Map.Entry<String, Long> entry : byDiv.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList()) {
                    String divName = entry.getKey();
                    long gameCount = entry.getValue();
                    // Find division UUID and game duration from team schedule
                    games.stream()
                        .filter(g -> g.divisionName().equals(divName))
                        .findFirst()
                        .ifPresent(sample -> {
                            int estimated = schedulerService.estimateAvailableSlots(
                                league, sample.divisionId(), sample.gameDurationMinutes());
                            if (estimated < gameCount) {
                                System.out.printf(
                                    "Warning: %s division has %d games but only ~%d slots "
                                    + "estimated in the season window. "
                                    + "Field assignment may produce a partial schedule.%n",
                                    divName, gameCount, estimated);
                            }
                        });
                }

                System.out.print("Confirm this team schedule and begin field assignment? "
                    + "This may take up to 5 minutes. Type 'yes' to continue: ");
                System.out.flush();
                String input;
                try (Scanner sc = new Scanner(System.in)) {
                    input = sc.nextLine().trim();
                }
                if (!input.equals("yes")) {
                    System.out.println("Cancelled.");
                    return 0;
                }

                System.out.println("Generating schedule, this may take up to 60 seconds...");

                ScheduleResult result = schedulerService.assign(league);

                if (result instanceof ScheduleResult.Failure f) {
                    System.err.println(f.message());
                    return 1;
                }

                ScheduleResult.Success success = (ScheduleResult.Success) result;
                Schedule schedule = new Schedule(
                    ScheduleStatus.DRAFT,
                    league.config().seasonStart(),
                    league.config().seasonEnd(),
                    success.games());
                parent.app.store.save(league.withSchedule(schedule));

                long divCount = success.games().stream()
                    .map(ScheduledGame::divisionId).distinct().count();
                String qualifier = success.optimal()
                    ? "optimal distribution"
                    : "good distribution — optimizer ran for 60s";
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
                ScheduleState state = ScheduleState.of(league);

                switch (state) {
                    case NONE -> {
                        System.out.println(
                            "No schedule generated yet. Run 'planr schedule generate' to start.");
                    }
                    case TEAM_SCHEDULE -> {
                        List<TeamGame> games = league.teamSchedule().games();
                        Map<String, Long> byDiv = games.stream()
                            .collect(Collectors.groupingBy(TeamGame::divisionName, Collectors.counting()));
                        System.out.println("Status:       TEAM_SCHEDULE");
                        System.out.printf("Total games:  %d%n", games.size());
                        byDiv.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(e -> {
                                String divName = e.getKey();
                                long count = e.getValue();
                                league.findDivision(divName).ifPresentOrElse(
                                    div -> System.out.printf(
                                        "  %-22s %d games (%d target per team, %d teams)%n",
                                        divName + ":", count,
                                        div.targetGamesPerTeam(), div.teams().size()),
                                    () -> System.out.printf("  %-22s %d games%n", divName + ":", count));
                            });
                        System.out.println(
                            "Phase 2 not yet run. Run 'planr schedule assign' to assign dates, times, and fields.");
                    }
                    case DRAFT, FINALIZED -> {
                        Schedule schedule = league.schedule();
                        System.out.printf("Status:        %s%n", schedule.status());
                        System.out.printf("Season:        %s to %s%n",
                            schedule.seasonStart(), schedule.seasonEnd());
                        System.out.printf("Total games:   %d%n", schedule.games().size());
                        Map<String, Long> countByDivision = schedule.games().stream()
                            .collect(Collectors.groupingBy(ScheduledGame::divisionName, Collectors.counting()));
                        countByDivision.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(e -> System.out.printf("  %-20s %d games%n",
                                e.getKey() + ":", e.getValue()));
                    }
                }
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

        @Option(names = "--team-schedule", description = "Show the team schedule (matchups only, no dates or fields). Available in TEAM_SCHEDULE and DRAFT states.")
        boolean teamScheduleView;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                ScheduleState state = ScheduleState.of(league);

                if (state == ScheduleState.NONE) {
                    System.err.println(
                        "Error: No schedule generated yet. Run 'planr schedule generate' to create one.");
                    return 1;
                }

                // Team schedule view: either explicitly requested, or the only view available
                if (teamScheduleView || state == ScheduleState.TEAM_SCHEDULE) {
                    if (state == ScheduleState.NONE) {
                        System.err.println(
                            "Error: No team schedule found. Run 'planr schedule generate' first.");
                        return 1;
                    }
                    if (league.teamSchedule() == null) {
                        System.err.println(
                            "Error: No team schedule available. Run 'planr schedule generate' first.");
                        return 1;
                    }
                    if (teamScheduleView && (divisionFilter != null || teamFilter != null || fieldFilter != null)) {
                        System.out.println(
                            "Note: Filters are not applicable in team schedule view (no dates or fields assigned yet).");
                    }
                    System.out.println("Schedule status: TEAM_SCHEDULE");
                    System.out.println();
                    printTeamScheduleTable(league.teamSchedule().games());
                    return 0;
                }

                // Full schedule view (DRAFT or FINALIZED)
                Schedule schedule = league.schedule();
                if (schedule == null) {
                    System.err.println(
                        "Error: No draft or finalized schedule exists. Run 'planr schedule assign' first.");
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
                for (ScheduledGame g : games) {
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

                List<String> labels = new ArrayList<>();
                for (ScheduledGame g : filtered) {
                    int pos = games.indexOf(g) + 1;
                    labels.add(g.overridden() ? pos + "*" : String.valueOf(pos));
                }

                printFullScheduleTable(filtered, labels);
                return 0;

            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        private void printFullScheduleTable(List<ScheduledGame> games, List<String> labels) {
            int numW = Math.max(1, labels.stream().mapToInt(String::length).max().orElse(1));
            int dateW = 10;
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

        @Option(names = "--team-schedule",
                description = "Export the team schedule (matchups only). Available in TEAM_SCHEDULE and DRAFT states.")
        boolean exportTeamSchedule;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                ScheduleState state = ScheduleState.of(league);

                if (state == ScheduleState.NONE) {
                    System.err.println("Error: No schedule generated yet.");
                    return 1;
                }

                ObjectMapper mapper = new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .registerModule(new JavaTimeModule());

                // Team schedule export: explicitly requested, or only option in TEAM_SCHEDULE state
                if (exportTeamSchedule || state == ScheduleState.TEAM_SCHEDULE) {
                    if (league.teamSchedule() == null) {
                        System.err.println("Error: No team schedule found. Run 'planr schedule generate' first.");
                        return 1;
                    }
                    if (exportTeamSchedule && state == ScheduleState.FINALIZED) {
                        System.err.println(
                            "Error: Team schedule export is not available for a finalized schedule.");
                        return 1;
                    }

                    List<ExportTeamGame> exports = league.teamSchedule().games().stream()
                        .map(g -> new ExportTeamGame(
                            g.gameNumber(), g.homeTeamName(), g.awayTeamName(), g.divisionName()))
                        .toList();
                    System.out.println(mapper.writeValueAsString(exports));
                    System.err.printf("Exported %d games (team schedule).%n", exports.size());
                    return 0;
                }

                // Full schedule export
                Schedule schedule = league.schedule();
                if (schedule == null) {
                    System.err.println("Error: No draft or finalized schedule exists. "
                        + "Run 'planr schedule assign' first, or use --team-schedule to export matchups.");
                    return 1;
                }

                String statusStr = schedule.status().name().toLowerCase();
                List<ExportGame> exports = schedule.games().stream()
                    .map(g -> new ExportGame(
                        g.date().toString(),
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

        record ExportTeamGame(
            @JsonProperty("game_number") int gameNumber,
            @JsonProperty("home_team") String homeTeam,
            @JsonProperty("away_team") String awayTeam,
            @JsonProperty("division_name") String divisionName
        ) {}
    }

    // --- Shared table rendering ---

    static void printTeamScheduleTable(List<TeamGame> games) {
        int numW = Math.max(1, String.valueOf(games.size()).length());
        int homeW = Math.max(18,
            games.stream().mapToInt(g -> g.homeTeamName().length()).max().orElse(0));
        int awayW = Math.max(18,
            games.stream().mapToInt(g -> g.awayTeamName().length()).max().orElse(0));
        int divW = Math.max(8,
            games.stream().mapToInt(g -> g.divisionName().length()).max().orElse(0));

        String fmt = "%-" + numW + "s  %-" + homeW + "s  %-" + awayW + "s  %-" + divW + "s%n";
        System.out.printf(fmt, "#", "HOME", "AWAY", "DIVISION");
        System.out.printf(fmt,
            "-".repeat(numW), "-".repeat(homeW), "-".repeat(awayW), "-".repeat(divW));

        for (TeamGame g : games) {
            System.out.printf(fmt,
                g.gameNumber(), g.homeTeamName(), g.awayTeamName(), g.divisionName());
        }
    }
}
