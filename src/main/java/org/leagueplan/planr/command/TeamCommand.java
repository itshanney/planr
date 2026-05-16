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
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(
    name = "team",
    description = "Manage teams within a division.",
    subcommands = {
        TeamCommand.AddCmd.class,
        TeamCommand.EditCmd.class,
        TeamCommand.DeleteCmd.class,
        TeamCommand.ListCmd.class
    },
    mixinStandardHelpOptions = true
)
public class TeamCommand implements Runnable {

    @ParentCommand PlanrApp app;
    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(name = "add", description = "Add a team to a division.")
    static class AddCmd implements Callable<Integer> {

        @ParentCommand TeamCommand parent;

        @Parameters(index = "0", paramLabel = "<division>", description = "Division name.")
        String divisionName;

        @Parameters(index = "1", paramLabel = "<team>", description = "Team name.")
        String teamName;

        @Override
        public Integer call() {
            if (teamName.isBlank()) {
                System.err.println("Error: Team name cannot be empty.");
                return 1;
            }
            try {
                League league = parent.app.store.load();
                var division = league.findDivision(divisionName);
                if (division.isEmpty()) {
                    System.err.printf("Error: Division \"%s\" not found.%n", divisionName);
                    return 1;
                }
                if (division.get().hasTeam(teamName)) {
                    System.err.printf("Error: Team \"%s\" already exists in division \"%s\".%n",
                        teamName, division.get().name());
                    return 1;
                }
                Team newTeam = new Team(UUID.randomUUID(), teamName);
                Division updatedDivision = division.get().withTeamAdded(newTeam);
                parent.app.store.save(league.withDivisionReplaced(division.get().id(), updatedDivision));
                System.out.printf("Team \"%s\" added to division \"%s\".%n", teamName, division.get().name());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "edit", description = "Rename a team.")
    static class EditCmd implements Callable<Integer> {

        @ParentCommand TeamCommand parent;

        @Parameters(index = "0", paramLabel = "<division>", description = "Division name.")
        String divisionName;

        @Parameters(index = "1", paramLabel = "<team>", description = "Current team name.")
        String teamName;

        @Option(names = "--name", required = true, paramLabel = "<new-name>", description = "New team name.")
        String newName;

        @Override
        public Integer call() {
            if (newName.isBlank()) {
                System.err.println("Error: Team name cannot be empty.");
                return 1;
            }
            try {
                League league = parent.app.store.load();
                var division = league.findDivision(divisionName);
                if (division.isEmpty()) {
                    System.err.printf("Error: Division \"%s\" not found.%n", divisionName);
                    return 1;
                }
                var team = division.get().findTeam(teamName);
                if (team.isEmpty()) {
                    System.err.printf("Error: Team \"%s\" not found in division \"%s\".%n",
                        teamName, division.get().name());
                    return 1;
                }
                if (!newName.equalsIgnoreCase(teamName) && division.get().hasTeam(newName)) {
                    System.err.printf("Error: Team \"%s\" already exists in division \"%s\".%n",
                        newName, division.get().name());
                    return 1;
                }
                Team updated = new Team(team.get().id(), newName);
                Division updatedDivision = division.get().withTeamReplaced(team.get().id(), updated);
                parent.app.store.save(league.withDivisionReplaced(division.get().id(), updatedDivision));
                System.out.printf("Team \"%s\" renamed to \"%s\" in division \"%s\".%n",
                    team.get().name(), newName, division.get().name());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "delete", description = "Remove a team from a division.")
    static class DeleteCmd implements Callable<Integer> {

        @ParentCommand TeamCommand parent;

        @Parameters(index = "0", paramLabel = "<division>", description = "Division name.")
        String divisionName;

        @Parameters(index = "1", paramLabel = "<team>", description = "Team name.")
        String teamName;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                var division = league.findDivision(divisionName);
                if (division.isEmpty()) {
                    System.err.printf("Error: Division \"%s\" not found.%n", divisionName);
                    return 1;
                }
                var team = division.get().findTeam(teamName);
                if (team.isEmpty()) {
                    System.err.printf("Error: Team \"%s\" not found in division \"%s\".%n",
                        teamName, division.get().name());
                    return 1;
                }
                Division updatedDivision = division.get().withTeamRemoved(team.get().id());
                parent.app.store.save(league.withDivisionReplaced(division.get().id(), updatedDivision));
                System.out.printf("Team \"%s\" removed from division \"%s\".%n",
                    team.get().name(), division.get().name());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "list", description = "List all teams in a division, sorted alphabetically.")
    static class ListCmd implements Callable<Integer> {

        @ParentCommand TeamCommand parent;

        @Parameters(index = "0", paramLabel = "<division>", description = "Division name.")
        String divisionName;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                var division = league.findDivision(divisionName);
                if (division.isEmpty()) {
                    System.err.printf("Error: Division \"%s\" not found.%n", divisionName);
                    return 1;
                }
                if (division.get().teams().isEmpty()) {
                    System.out.printf(
                        "No teams in division \"%s\". Use 'planr team add' to create one.%n",
                        division.get().name());
                    return 0;
                }
                division.get().teams().stream()
                    .sorted(Comparator.comparing(t -> t.name().toLowerCase()))
                    .forEach(t -> System.out.println(t.name()));
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }
}
