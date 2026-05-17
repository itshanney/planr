package org.leagueplan.planr.command;

import org.leagueplan.planr.PlanrApp;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.Team;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(
    name = "division",
    description = "Manage divisions.",
    subcommands = {
        DivisionCommand.AddCmd.class,
        DivisionCommand.EditCmd.class,
        DivisionCommand.DeleteCmd.class,
        DivisionCommand.ListCmd.class
    },
    mixinStandardHelpOptions = true
)
public class DivisionCommand implements Runnable {

    @ParentCommand PlanrApp app;
    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(name = "add", description = "Add a new division.")
    static class AddCmd implements Callable<Integer> {

        @ParentCommand DivisionCommand parent;

        @Parameters(index = "0", paramLabel = "<name>", description = "Division name.")
        String name;

        @Option(names = "--duration", required = true, paramLabel = "<minutes>",
                description = "Game duration in minutes.")
        int duration;

        @Option(names = "--target", required = true, paramLabel = "<n>",
                description = "Target number of games per team.")
        int target;

        @Override
        public Integer call() {
            if (name.isBlank()) {
                System.err.println("Error: Division name cannot be empty.");
                return 1;
            }
            if (duration <= 0) {
                System.err.printf("Error: Game duration must be a positive integer, got %d.%n", duration);
                return 1;
            }
            if (target < 1) {
                System.err.printf("Error: Target games per team must be a positive integer (got: %d).%n", target);
                return 1;
            }
            try {
                League league = parent.app.store.load();
                if (league.hasDivision(name)) {
                    System.err.printf("Error: Division \"%s\" already exists.%n", name);
                    return 1;
                }
                Division division = new Division(UUID.randomUUID(), name, duration, target, List.of());
                parent.app.store.save(league.withDivisionAdded(division));
                System.out.printf("Division \"%s\" added (%d min/game, target %d games/team).%n",
                    name, duration, target);
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "edit", description = "Edit an existing division.")
    static class EditCmd implements Callable<Integer> {

        @ParentCommand DivisionCommand parent;

        @Parameters(index = "0", paramLabel = "<name>", description = "Current division name.")
        String name;

        @Option(names = "--name", paramLabel = "<new-name>", description = "New division name.")
        String newName;

        @Option(names = "--duration", paramLabel = "<minutes>", description = "New game duration in minutes.")
        Integer newDuration;

        @Option(names = "--target", paramLabel = "<n>", description = "New target games per team.")
        Integer newTarget;

        @Override
        public Integer call() {
            if (newName == null && newDuration == null && newTarget == null) {
                System.err.println("Error: At least one of --name, --duration, or --target must be provided.");
                return 1;
            }
            if (newName != null && newName.isBlank()) {
                System.err.println("Error: Division name cannot be empty.");
                return 1;
            }
            if (newDuration != null && newDuration <= 0) {
                System.err.printf("Error: Game duration must be a positive integer, got %d.%n", newDuration);
                return 1;
            }
            if (newTarget != null && newTarget < 1) {
                System.err.printf("Error: Target games per team must be a positive integer (got: %d).%n", newTarget);
                return 1;
            }
            try {
                League league = parent.app.store.load();
                var existing = league.findDivision(name);
                if (existing.isEmpty()) {
                    System.err.printf("Error: Division \"%s\" not found.%n", name);
                    return 1;
                }
                if (newName != null && !newName.equalsIgnoreCase(name) && league.hasDivision(newName)) {
                    System.err.printf("Error: Division \"%s\" already exists.%n", newName);
                    return 1;
                }
                Division updated = applyEdits(existing.get(), newName, newDuration, newTarget);
                parent.app.store.save(league.withDivisionReplaced(existing.get().id(), updated));
                System.out.printf("Division \"%s\" updated.%n", updated.name());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        private Division applyEdits(Division division, String newName, Integer newDuration, Integer newTarget) {
            String resolvedName = (newName != null) ? newName : division.name();
            int resolvedDuration = (newDuration != null) ? newDuration : division.gameDurationMinutes();
            int resolvedTarget = (newTarget != null) ? newTarget : division.targetGamesPerTeam();
            return new Division(division.id(), resolvedName, resolvedDuration, resolvedTarget, division.teams());
        }
    }

    @Command(name = "delete", description = "Delete a division (must have no teams).")
    static class DeleteCmd implements Callable<Integer> {

        @ParentCommand DivisionCommand parent;

        @Parameters(index = "0", paramLabel = "<name>", description = "Division name.")
        String name;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                var existing = league.findDivision(name);
                if (existing.isEmpty()) {
                    System.err.printf("Error: Division \"%s\" not found.%n", name);
                    return 1;
                }
                int teamCount = existing.get().teams().size();
                if (teamCount > 0) {
                    System.err.printf(
                        "Error: Division \"%s\" has %d team(s). Remove all teams before deleting the division.%n",
                        existing.get().name(), teamCount);
                    return 1;
                }
                parent.app.store.save(league.withDivisionRemoved(existing.get().id()));
                System.out.printf("Division \"%s\" deleted.%n", existing.get().name());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "list", description = "List all divisions.")
    static class ListCmd implements Callable<Integer> {

        @ParentCommand DivisionCommand parent;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                if (league.divisions().isEmpty()) {
                    System.out.println("No divisions configured. Use 'planr division add' to create one.");
                    return 0;
                }
                printTable(league.divisions());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        private void printTable(java.util.List<Division> divisions) {
            int nameWidth = Math.max(
                "DIVISION".length(),
                divisions.stream().mapToInt(d -> d.name().length()).max().orElse(0));
            int durationWidth = Math.max(
                "DURATION".length(),
                divisions.stream().mapToInt(d -> (d.gameDurationMinutes() + " min").length()).max().orElse(0));
            int targetWidth = Math.max(
                "TARGET".length(),
                divisions.stream().mapToInt(d -> targetLabel(d).length()).max().orElse(0));
            int teamsWidth = Math.max(
                "TEAMS".length(),
                divisions.stream().mapToInt(d -> String.valueOf(d.teams().size()).length()).max().orElse(0));

            String fmt = "%-" + nameWidth + "s    %-" + durationWidth + "s    %-" + targetWidth + "s    %-" + teamsWidth + "s%n";
            System.out.printf(fmt, "DIVISION", "DURATION", "TARGET", "TEAMS");
            System.out.printf(fmt, "-".repeat(nameWidth), "-".repeat(durationWidth),
                "-".repeat(targetWidth), "-".repeat(teamsWidth));
            divisions.forEach(d ->
                System.out.printf(fmt, d.name(), d.gameDurationMinutes() + " min",
                    targetLabel(d), d.teams().size()));

            long unconfigured = divisions.stream().filter(d -> d.targetGamesPerTeam() == 0).count();
            if (unconfigured > 0) {
                System.out.printf("%nWarning: %d division(s) have no target configured. "
                    + "Set with 'planr division edit <name> --target <n>'.%n", unconfigured);
            }
        }

        private String targetLabel(Division d) {
            return d.targetGamesPerTeam() == 0 ? "0*" : String.valueOf(d.targetGamesPerTeam());
        }
    }
}
