package org.leagueplan.planr.command;

import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.Schedule;
import org.leagueplan.planr.model.ScheduleState;
import org.leagueplan.planr.model.ScheduleStatus;
import org.leagueplan.planr.model.ScheduledGame;
import org.leagueplan.planr.model.Team;
import org.leagueplan.planr.model.TeamGame;
import org.leagueplan.planr.model.TeamSchedule;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(
    name = "game",
    description = "Manage individual games on a schedule.",
    subcommands = { ScheduleGameCommand.OverrideCmd.class, ScheduleGameCommand.EditHomeAwayCmd.class },
    mixinStandardHelpOptions = true
)
public class ScheduleGameCommand implements Runnable {

    @ParentCommand ScheduleCommand scheduleCmd;
    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(name = "override", description = "Override an individual game on a finalized schedule.")
    static class OverrideCmd implements Callable<Integer> {

        @ParentCommand ScheduleGameCommand parent;

        @Parameters(index = "0", paramLabel = "<game-number>",
                    description = "1-based game number from 'planr schedule view'.")
        int gameNumber;

        @Option(names = "--date", paramLabel = "<YYYY-MM-DD>", description = "New game date.")
        String newDateStr;

        @Option(names = "--start", paramLabel = "<HH:mm>", description = "New start time.")
        String newStartStr;

        @Option(names = "--field", paramLabel = "<name>", description = "New field name.")
        String newFieldName;

        @Option(names = "--home", paramLabel = "<team>", description = "New home team name.")
        String newHomeName;

        @Option(names = "--away", paramLabel = "<team>", description = "New away team name.")
        String newAwayName;

        @Override
        public Integer call() {
            if (newDateStr == null && newStartStr == null && newFieldName == null
                    && newHomeName == null && newAwayName == null) {
                System.err.println("Error: At least one override option must be provided.");
                return 1;
            }

            try {
                League league = parent.scheduleCmd.app.store.load();
                Schedule schedule = league.schedule();

                if (schedule == null || schedule.status() != ScheduleStatus.FINALIZED) {
                    System.err.println(
                        "Error: 'game override' requires a finalized schedule. "
                        + "Run 'planr schedule finalize' first.");
                    return 1;
                }

                int zeroIndex = gameNumber - 1;
                if (zeroIndex < 0 || zeroIndex >= schedule.games().size()) {
                    System.err.printf("Error: Game #%d not found (1–%d are valid).%n",
                        gameNumber, schedule.games().size());
                    return 1;
                }
                ScheduledGame game = schedule.games().get(zeroIndex);

                // Parse and validate optional overrides
                LocalDate newDate = null;
                if (newDateStr != null) {
                    try {
                        newDate = LocalDate.parse(newDateStr);
                    } catch (DateTimeParseException e) {
                        System.err.printf("Error: Invalid date \"%s\". Use YYYY-MM-DD format.%n", newDateStr);
                        return 1;
                    }
                }

                LocalTime newStart = null;
                if (newStartStr != null) {
                    try {
                        newStart = LocalTime.parse(newStartStr, FieldOverrideCommand.TIME_FORMAT);
                    } catch (DateTimeParseException e) {
                        System.err.printf("Error: Invalid time \"%s\". Use HH:mm format.%n", newStartStr);
                        return 1;
                    }
                }

                UUID newFieldId = null;
                String resolvedFieldName = null;
                if (newFieldName != null) {
                    Optional<Field> fieldOpt = league.findField(newFieldName);
                    if (fieldOpt.isEmpty()) {
                        System.err.printf("Error: Field \"%s\" not found.%n", newFieldName);
                        return 1;
                    }
                    newFieldId = fieldOpt.get().id();
                    resolvedFieldName = fieldOpt.get().name();
                }

                UUID newHomeId = null;
                String resolvedHomeName = null;
                if (newHomeName != null) {
                    Optional<Team> team = resolveTeam(league, newHomeName, game.divisionId());
                    if (team.isEmpty()) {
                        System.err.printf(
                            "Error: Team \"%s\" not found. Specify the team name as it appears in 'planr schedule view'.%n",
                            newHomeName);
                        return 1;
                    }
                    newHomeId = team.get().id();
                    resolvedHomeName = team.get().name();
                }

                UUID newAwayId = null;
                String resolvedAwayName = null;
                if (newAwayName != null) {
                    Optional<Team> team = resolveTeam(league, newAwayName, game.divisionId());
                    if (team.isEmpty()) {
                        System.err.printf(
                            "Error: Team \"%s\" not found. Specify the team name as it appears in 'planr schedule view'.%n",
                            newAwayName);
                        return 1;
                    }
                    newAwayId = team.get().id();
                    resolvedAwayName = team.get().name();
                }

                ScheduledGame updated = game.withOverride(
                    newDate, newStart, newFieldId, resolvedFieldName,
                    newHomeId, resolvedHomeName, newAwayId, resolvedAwayName);

                Schedule updatedSchedule = schedule.withGameReplaced(zeroIndex, updated);

                // Non-blocking conflict warning: check for field overlap on same date
                LocalDate checkDate = updated.date();
                UUID checkFieldId = updated.fieldId();
                for (int i = 0; i < updatedSchedule.games().size(); i++) {
                    if (i == zeroIndex) continue;
                    ScheduledGame other = updatedSchedule.games().get(i);
                    if (!other.date().equals(checkDate) || !other.fieldId().equals(checkFieldId)) continue;
                    if (gamesConflict(updated, other)) {
                        System.err.printf(
                            "Warning: Game #%d now conflicts with game #%d at %s on %s%n"
                            + "         (overlapping times including the 15-minute buffer). "
                            + "Game #%d saved anyway.%n",
                            gameNumber, i + 1, updated.fieldName(), checkDate, gameNumber);
                        break;
                    }
                }

                parent.scheduleCmd.app.store.save(league.withSchedule(updatedSchedule));
                System.out.printf("Game #%d updated.%n", gameNumber);
                return 0;

            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        private Optional<Team> resolveTeam(League league, String name, UUID preferredDivisionId) {
            // Prefer the team in the same division as the game being overridden
            Optional<Team> inSameDivision = league.divisions().stream()
                .filter(d -> d.id().equals(preferredDivisionId))
                .flatMap(d -> d.teams().stream())
                .filter(t -> t.name().equalsIgnoreCase(name))
                .findFirst();
            if (inSameDivision.isPresent()) return inSameDivision;

            return league.divisions().stream()
                .flatMap(d -> d.teams().stream())
                .filter(t -> t.name().equalsIgnoreCase(name))
                .findFirst();
        }

        private boolean gamesConflict(ScheduledGame a, ScheduledGame b) {
            int aStart = a.startTime().getHour() * 60 + a.startTime().getMinute();
            int aEnd = aStart + a.gameDurationMinutes() + 15;
            int bStart = b.startTime().getHour() * 60 + b.startTime().getMinute();
            int bEnd = bStart + b.gameDurationMinutes() + 15;
            return aStart < bEnd && bStart < aEnd;
        }
    }

    @Command(name = "edit",
             description = "Swap home/away for a game in the team schedule or draft.")
    static class EditHomeAwayCmd implements Callable<Integer> {

        @ParentCommand ScheduleGameCommand parent;

        @Parameters(index = "0", paramLabel = "<game-number>",
                    description = "1-based game number from 'planr schedule view'.")
        int gameNumber;

        @Option(names = "--home", required = true, paramLabel = "<team>",
                description = "Team name to designate as home.")
        String teamName;

        @Override
        public Integer call() {
            try {
                League league = parent.scheduleCmd.app.store.load();
                ScheduleState state = ScheduleState.of(league);

                if (state == ScheduleState.NONE) {
                    System.err.println(
                        "Error: No team schedule found. Run 'planr schedule generate' first.");
                    return 1;
                }
                if (state == ScheduleState.FINALIZED) {
                    System.err.println(
                        "Error: Schedule is finalized. Use 'planr schedule game override' "
                        + "to modify individual games.");
                    return 1;
                }

                TeamSchedule teamSchedule = league.teamSchedule();
                if (teamSchedule == null) {
                    System.err.println("Error: No team schedule found.");
                    return 1;
                }

                java.util.Optional<TeamGame> gameOpt = teamSchedule.findGame(gameNumber);
                if (gameOpt.isEmpty()) {
                    System.err.printf("Error: Game #%d not found.%n", gameNumber);
                    return 1;
                }
                TeamGame game = gameOpt.get();

                boolean isCurrentHome = game.homeTeamName().equalsIgnoreCase(teamName);
                boolean isCurrentAway = game.awayTeamName().equalsIgnoreCase(teamName);

                if (!isCurrentHome && !isCurrentAway) {
                    System.err.printf(
                        "Error: Team \"%s\" is not playing in game #%d "
                        + "(home: %s, away: %s).%n",
                        teamName, gameNumber, game.homeTeamName(), game.awayTeamName());
                    return 1;
                }

                if (isCurrentHome) {
                    System.out.printf(
                        "Game #%d: %s is already the home team. No change made.%n",
                        gameNumber, game.homeTeamName());
                    return 0;
                }

                TeamGame swapped = game.withSwappedHomeAway();
                TeamSchedule updated = teamSchedule.withGameReplaced(gameNumber, swapped);
                parent.scheduleCmd.app.store.save(league.withTeamSchedule(updated));
                System.out.printf(
                    "Game #%d updated: %s (home) vs %s (away).%n",
                    gameNumber, swapped.homeTeamName(), swapped.awayTeamName());
                return 0;

            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }
}
