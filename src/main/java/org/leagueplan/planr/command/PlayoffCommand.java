package org.leagueplan.planr.command;

import org.leagueplan.planr.PlanrApp;
import org.leagueplan.planr.model.BracketSide;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.FieldDivisionLock;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.Playoff;
import org.leagueplan.planr.model.PlayoffGame;
import org.leagueplan.planr.model.PlayoffState;
import org.leagueplan.planr.scheduler.DivisionSummary;
import org.leagueplan.planr.scheduler.PlayoffBracketService;
import org.leagueplan.planr.scheduler.PlayoffScheduleResult;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(
    name = "playoff",
    description = "Manage playoff brackets and field assignment.",
    subcommands = {
        PlayoffCommand.GenerateCmd.class,
        PlayoffCommand.AssignCmd.class,
        PlayoffCommand.StatusCmd.class,
        PlayoffCommand.ClearCmd.class
    },
    mixinStandardHelpOptions = true
)
public class PlayoffCommand implements Runnable {

    @ParentCommand PlanrApp app;
    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    // --- Generate ---

    @Command(name = "generate",
             description = "Generate a double elimination bracket for a division.")
    static class GenerateCmd implements Callable<Integer> {

        @ParentCommand PlayoffCommand parent;

        @Option(names = "--division", required = true, paramLabel = "<name>",
                description = "Division name.")
        String divisionName;

        @Option(names = "--start", required = true, paramLabel = "<YYYY-MM-DD>",
                description = "Playoff start date (inclusive).")
        String startStr;

        @Option(names = "--end", required = true, paramLabel = "<YYYY-MM-DD>",
                description = "Playoff end date (inclusive).")
        String endStr;

        @Option(names = "--seeds", required = true, paramLabel = "<team>",
                description = "Team name in seed order. Repeat once per team: "
                        + "--seeds \"Red Sox\" --seeds Yankees --seeds Cubs")
        List<String> seeds;

        @Override
        public Integer call() {
            LocalDate start;
            LocalDate end;
            try {
                start = LocalDate.parse(startStr.trim());
            } catch (DateTimeParseException e) {
                System.err.printf("Error: Invalid date \"%s\". Expected YYYY-MM-DD.%n", startStr);
                return 1;
            }
            try {
                end = LocalDate.parse(endStr.trim());
            } catch (DateTimeParseException e) {
                System.err.printf("Error: Invalid date \"%s\". Expected YYYY-MM-DD.%n", endStr);
                return 1;
            }
            if (end.isBefore(start)) {
                System.err.printf("Error: End date %s must not be before start date %s.%n", end, start);
                return 1;
            }

            try {
                League league = parent.app.store.load();

                Optional<Division> divOpt = league.findDivision(divisionName);
                if (divOpt.isEmpty()) {
                    System.err.printf("Error: Division \"%s\" not found.%n", divisionName);
                    return 1;
                }
                Division division = divOpt.get();

                int actualTeamCount = division.teams().size();
                int seedCount = seeds.size();

                if (seedCount < 2 || seedCount > 16) {
                    System.err.printf(
                        "Error: Team count must be between 2 and 16 inclusive. Got %d.%n", seedCount);
                    return 1;
                }

                if (seedCount != actualTeamCount) {
                    System.err.printf(
                        "Error: --seeds has %d name(s) but division \"%s\" has %d team(s).%n",
                        seedCount, division.name(), actualTeamCount);
                    return 1;
                }

                // Detect duplicates
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                List<String> duplicates = new ArrayList<>();
                for (String seed : seeds) {
                    if (!seen.add(seed.toLowerCase())) duplicates.add(seed);
                }
                if (!duplicates.isEmpty()) {
                    System.err.printf("Error: Duplicate team names in --seeds: %s.%n",
                        duplicates.stream().collect(Collectors.joining(", ")));
                    return 1;
                }

                // Validate each seed name against the division's teams (case-insensitive)
                List<String> unrecognized = seeds.stream()
                    .filter(s -> division.teams().stream()
                        .noneMatch(t -> t.name().equalsIgnoreCase(s)))
                    .toList();
                if (!unrecognized.isEmpty()) {
                    System.err.printf("Error: Unrecognized team name(s) in --seeds: %s.%n",
                        unrecognized.stream().collect(Collectors.joining(", ")));
                    return 1;
                }

                // Check existing playoff
                Optional<Playoff> existing = league.findPlayoff(division.id());
                if (existing.isPresent()) {
                    System.err.printf(
                        "Error: A playoff for \"%s\" already exists (state: %s). "
                        + "Run 'planr playoff clear --division %s' first.%n",
                        division.name(), existing.get().state(), division.name());
                    return 1;
                }

                // Normalize seed names to match division team name casing
                List<String> normalizedSeeds = seeds.stream()
                    .map(s -> division.teams().stream()
                        .filter(t -> t.name().equalsIgnoreCase(s))
                        .findFirst()
                        .map(t -> t.name())
                        .orElse(s))
                    .toList();

                // Generate bracket
                PlayoffBracketService bracketService = new PlayoffBracketService();
                List<PlayoffBracketService.BracketSlot> slots = bracketService.generateBracket(normalizedSeeds);

                List<PlayoffGame> games = slots.stream()
                    .map(PlayoffBracketService::toPlayoffGame)
                    .toList();

                int byes = PlayoffBracketService.nextPowerOfTwo(seedCount) - seedCount;
                long realGameCount = games.stream().filter(g -> !g.isBye()).count();

                Playoff playoff = new Playoff(division.id(), start, end, PlayoffState.GENERATED, games);
                league = league.withPlayoffAdded(playoff);
                parent.app.store.save(league);

                // Print bracket summary table
                printBracketSummary(slots);

                System.out.printf("%nPlayoff generated for %s: %d teams, %d game slots, %d bye(s).%n",
                    division.name(), seedCount, realGameCount, byes);
                return 0;

            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        private void printBracketSummary(List<PlayoffBracketService.BracketSlot> slots) {
            int slotW = Math.max("SLOT".length(), String.valueOf(slots.size()).length());
            int roundW = Math.max("ROUND".length(),
                slots.stream().mapToInt(s -> s.round().length()).max().orElse(0));
            int posAW = Math.max("POSITION A".length(),
                slots.stream().mapToInt(s -> s.positionA().length()).max().orElse(0));
            int posBW = Math.max("POSITION B".length(),
                slots.stream().mapToInt(s -> s.positionB().length()).max().orElse(0));
            int byeW = "BYE".length();

            String fmt = "%-" + slotW + "s  %-" + roundW + "s  %-" + posAW + "s  %-" + posBW + "s  %-" + byeW + "s%n";
            System.out.printf(fmt, "SLOT", "ROUND", "POSITION A", "POSITION B", "BYE");
            System.out.printf(fmt, "-".repeat(slotW), "-".repeat(roundW),
                "-".repeat(posAW), "-".repeat(posBW), "-".repeat(byeW));

            for (int i = 0; i < slots.size(); i++) {
                PlayoffBracketService.BracketSlot s = slots.get(i);
                System.out.printf(fmt,
                    "G" + (i + 1), s.round(), s.positionA(), s.positionB(),
                    s.isBye() ? "YES" : "");
            }
        }
    }

    // --- Assign ---

    @Command(name = "assign",
             description = "Assign field/time slots to all playoff brackets across all divisions.")
    static class AssignCmd implements Callable<Integer> {

        @ParentCommand PlayoffCommand parent;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();

                List<Playoff> playoffs = league.playoffs();
                if (playoffs.isEmpty()) {
                    System.err.println(
                        "Error: No playoff brackets found. Run 'planr playoff generate' first.");
                    return 1;
                }

                // Validate consistent dates across all playoffs
                LocalDate refStart = playoffs.get(0).startDate();
                LocalDate refEnd = playoffs.get(0).endDate();
                boolean datesConsistent = playoffs.stream()
                    .allMatch(p -> p.startDate().equals(refStart) && p.endDate().equals(refEnd));
                if (!datesConsistent) {
                    System.err.println(
                        "Error: Playoff date ranges are inconsistent across divisions. "
                        + "All divisions must share the same start and end dates. "
                        + "Correct them using 'planr playoff clear' and 'planr playoff generate':");
                    for (Playoff p : playoffs) {
                        String divName = divisionName(league, p.divisionId());
                        System.err.printf("  %s: %s to %s%n", divName, p.startDate(), p.endDate());
                    }
                    return 1;
                }

                if (league.fields().isEmpty()) {
                    System.err.println(
                        "Error: At least one field must be configured. Run 'planr field add'.");
                    return 1;
                }
                if (league.config() == null
                        || league.config().sunriseTime() == null
                        || league.config().sunsetTime() == null) {
                    System.err.println(
                        "Error: Field availability times must be configured. "
                        + "Run 'planr config set --sunrise <HH:mm> --sunset <HH:mm>'.");
                    return 1;
                }

                // Clear all existing assignments before re-solving
                League cleared = league;
                for (Playoff p : playoffs) {
                    List<PlayoffGame> clearedGames = p.games().stream()
                        .map(PlayoffGame::withAssignmentCleared)
                        .toList();
                    cleared = cleared.withPlayoffReplaced(p.divisionId(), p.withGames(clearedGames));
                }

                int totalRealGames = playoffs.stream()
                    .flatMap(p -> p.games().stream())
                    .filter(g -> !g.isBye())
                    .mapToInt(g -> 1)
                    .sum();

                int effectiveWeekCap = (league.config().maxGamesPerWeek() != null)
                    ? league.config().maxGamesPerWeek() : SchedulerService.DEFAULT_MAX_GAMES_PER_WEEK;
                int effectiveRestDays = (league.config().minRestDays() != null)
                    ? league.config().minRestDays() : SchedulerService.DEFAULT_MIN_REST_DAYS;

                System.out.printf("Playoff field assignment: %d game slots across %d division(s) "
                    + "(%s to %s).%n",
                    totalRealGames, playoffs.size(), refStart, refEnd);
                System.out.printf(
                    "Scheduling constraints: max %d game(s)/week per team, min %d rest day(s) between games.%n",
                    effectiveWeekCap, effectiveRestDays);
                System.out.print("Begin field assignment? This may take up to 5 minutes. "
                    + "Type 'yes' to continue: ");
                System.out.flush();

                String input;
                try (Scanner sc = new Scanner(System.in)) {
                    input = sc.nextLine().trim();
                }
                if (!input.equals("yes")) {
                    System.out.println("Cancelled.");
                    return 0;
                }

                System.out.printf("[0:00] Playoff field assignment started. "
                    + "%d game slots across %d division(s).%n", totalRealGames, playoffs.size());
                System.out.flush();

                SchedulerService schedulerService = new SchedulerService();
                PlayoffScheduleResult result = schedulerService.assignPlayoffs(cleared, cleared.playoffs());

                if (result instanceof PlayoffScheduleResult.Failure f) {
                    System.err.println(f.message());
                    return 1;
                }

                PlayoffScheduleResult.Success success = (PlayoffScheduleResult.Success) result;

                // Write assignments back into PlayoffGame entries and transition all to ASSIGNED
                League updated = cleared;
                for (Playoff p : cleared.playoffs()) {
                    List<PlayoffGame> updatedGames = p.games().stream()
                        .map(game -> {
                            if (game.isBye()) return game;
                            var slot = success.assignmentsByGameId().get(game.gameId());
                            return slot != null
                                ? game.withAssignment(slot.date(), slot.startTime(), slot.fieldId())
                                : game;
                        })
                        .toList();
                    Playoff updatedPlayoff = new Playoff(
                        p.divisionId(), p.startDate(), p.endDate(),
                        PlayoffState.ASSIGNED, updatedGames);
                    updated = updated.withPlayoffReplaced(p.divisionId(), updatedPlayoff);
                }
                parent.app.store.save(updated);

                ScheduleCommand.printConstraintSummary(success.divisionSummaries());
                printActiveFieldLocks(league, refStart, refEnd);

                int gAssigned = success.assignmentsByGameId().size();
                int gTotal = totalRealGames;
                int divCount = playoffs.size();
                System.out.printf(
                    "Playoff field assignment complete: %d/%d game slots assigned across %d division(s).%n",
                    gAssigned, gTotal, divCount);
                return 0;

            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        private void printActiveFieldLocks(League league, LocalDate start, LocalDate end) {
            List<String> lockLines = new ArrayList<>();
            for (Field field : league.fields()) {
                for (FieldDivisionLock lock : field.divisionLocks()) {
                    if (!lock.startDate().isAfter(end) && !lock.endDate().isBefore(start)) {
                        String divName = FieldLockCommand.resolveDivisionName(league, lock.divisionId());
                        lockLines.add(String.format("  \"%s\" → %s (%s to %s)",
                            field.name(), divName, lock.startDate(), lock.endDate()));
                    }
                }
            }
            if (!lockLines.isEmpty()) {
                System.out.println("Field division locks applied:");
                lockLines.forEach(System.out::println);
            }
        }
    }

    // --- Status ---

    @Command(name = "status",
             description = "View playoff status. Use --division for full bracket detail.")
    static class StatusCmd implements Callable<Integer> {

        @ParentCommand PlayoffCommand parent;

        @Option(names = "--division", paramLabel = "<name>",
                description = "Division name (omit for summary of all divisions).")
        String divisionName;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();

                if (divisionName == null) {
                    // Summary view: all divisions
                    List<Division> allDivisions = league.divisions();
                    if (allDivisions.isEmpty()) {
                        System.out.println("No divisions configured.");
                        return 0;
                    }
                    int divW = Math.max("DIVISION".length(),
                        allDivisions.stream().mapToInt(d -> d.name().length()).max().orElse(0));
                    String fmt = "%-" + divW + "s  %-11s%n";
                    System.out.printf(fmt, "DIVISION", "STATE");
                    System.out.printf(fmt, "-".repeat(divW), "-----------");
                    for (Division div : allDivisions.stream()
                            .sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList()) {
                        String state = league.findPlayoff(div.id())
                            .map(p -> p.state().name())
                            .orElse("NOT_STARTED");
                        System.out.printf(fmt, div.name(), state);
                    }
                    return 0;
                }

                // Full bracket view for one division
                Optional<Division> divOpt = league.findDivision(divisionName);
                if (divOpt.isEmpty()) {
                    System.err.printf("Error: Division \"%s\" not found.%n", divisionName);
                    return 1;
                }
                Division division = divOpt.get();
                Optional<Playoff> playoffOpt = league.findPlayoff(division.id());
                if (playoffOpt.isEmpty()) {
                    System.err.printf(
                        "Error: No playoff exists for \"%s\". "
                        + "Run 'planr playoff generate --division %s' first.%n",
                        division.name(), division.name());
                    return 1;
                }

                Playoff playoff = playoffOpt.get();
                System.out.printf("Division: %s | State: %s | Period: %s to %s%n%n",
                    division.name(), playoff.state(), playoff.startDate(), playoff.endDate());

                printBracketDetail(playoff.games(), league);
                return 0;

            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        private void printBracketDetail(List<PlayoffGame> games, League league) {
            int slotW = Math.max("SLOT".length(), String.valueOf(games.size()).length());
            int roundW = Math.max("ROUND".length(),
                games.stream().mapToInt(g -> g.round().length()).max().orElse(0));
            int posAW = Math.max("POSITION A".length(),
                games.stream().mapToInt(g -> g.positionA().length()).max().orElse(0));
            int posBW = Math.max("POSITION B".length(),
                games.stream().mapToInt(g -> g.positionB().length()).max().orElse(0));
            int assignedW = Math.max("ASSIGNED".length(), "UNASSIGNED".length());

            String fmt = "%-" + slotW + "s  %-" + roundW + "s  %-" + posAW + "s  %-"
                + posBW + "s  %-" + assignedW + "s%n";
            System.out.printf(fmt, "SLOT", "ROUND", "POSITION A", "POSITION B", "ASSIGNED");
            System.out.printf(fmt, "-".repeat(slotW), "-".repeat(roundW),
                "-".repeat(posAW), "-".repeat(posBW), "-".repeat(assignedW));

            for (int i = 0; i < games.size(); i++) {
                PlayoffGame g = games.get(i);
                String assigned;
                if (g.isBye()) {
                    assigned = "BYE";
                } else if (g.assignedDate() != null) {
                    String fieldName = resolveFieldName(league, g.assignedFieldId());
                    assigned = String.format("%s %s %s",
                        g.assignedDate(), g.assignedStartTime(), fieldName);
                } else {
                    assigned = "UNASSIGNED";
                }
                String conditionalFlag = g.isConditional() ? " *" : "";
                System.out.printf(fmt,
                    "G" + (i + 1) + conditionalFlag, g.round(), g.positionA(), g.positionB(), assigned);
            }
            System.out.println();
            System.out.println("* = conditional (championship re-match slot)");
        }

        private static String resolveFieldName(League league, UUID fieldId) {
            if (fieldId == null) return "";
            return league.fields().stream()
                .filter(f -> f.id().equals(fieldId))
                .findFirst()
                .map(f -> f.name())
                .orElse("[unknown]");
        }
    }

    // --- Clear ---

    @Command(name = "clear",
             description = "Remove a division's playoff bracket and reset to NOT_STARTED.")
    static class ClearCmd implements Callable<Integer> {

        @ParentCommand PlayoffCommand parent;

        @Option(names = "--division", required = true, paramLabel = "<name>",
                description = "Division name.")
        String divisionName;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();

                Optional<Division> divOpt = league.findDivision(divisionName);
                if (divOpt.isEmpty()) {
                    System.err.printf("Error: Division \"%s\" not found.%n", divisionName);
                    return 1;
                }
                Division division = divOpt.get();

                Optional<Playoff> playoffOpt = league.findPlayoff(division.id());
                if (playoffOpt.isEmpty()) {
                    System.err.printf(
                        "Error: No playoff exists for \"%s\".%n", division.name());
                    return 1;
                }

                Playoff playoff = playoffOpt.get();
                long realGames = playoff.games().stream().filter(g -> !g.isBye()).count();
                long assigned = playoff.games().stream()
                    .filter(g -> !g.isBye() && g.assignedDate() != null).count();

                System.out.printf(
                    "Warning: This will permanently remove the playoff bracket for \"%s\" "
                    + "(state: %s, %d real game slots, %d assigned). "
                    + "Type 'yes' to confirm: ",
                    division.name(), playoff.state(), realGames, assigned);
                System.out.flush();

                String input;
                try (Scanner sc = new Scanner(System.in)) {
                    input = sc.nextLine().trim();
                }
                if (!input.equals("yes")) {
                    System.out.println("Cancelled.");
                    return 0;
                }

                parent.app.store.save(league.withPlayoffRemoved(division.id()));
                System.out.printf("Playoff for \"%s\" cleared.%n", division.name());
                return 0;

            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- Shared helpers ---

    private static String divisionName(League league, UUID divisionId) {
        return league.divisions().stream()
            .filter(d -> d.id().equals(divisionId))
            .findFirst()
            .map(Division::name)
            .orElse("[unknown]");
    }
}
