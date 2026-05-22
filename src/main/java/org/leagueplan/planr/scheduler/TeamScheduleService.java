package org.leagueplan.planr.scheduler;

import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.Team;
import org.leagueplan.planr.model.TeamGame;
import org.leagueplan.planr.model.TeamSchedule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

public class TeamScheduleService {

    /**
     * Intermediate representation before global game numbers are assigned.
     * Holds the two teams and the division they belong to.
     */
    private record RawGame(Team home, Team away, Division division) {}

    public TeamScheduleResult generate(League league) {
        List<String> errors = validatePreconditions(league);
        if (!errors.isEmpty()) {
            return new TeamScheduleResult.Failure(String.join("\n", errors));
        }

        List<Division> eligible = league.divisions().stream()
            .filter(d -> d.teams().size() >= 2)
            .toList();

        // Per-team counters for home/away balance tracking (keyed by team UUID).
        Map<UUID, Integer> homeCount = new HashMap<>();
        Map<UUID, Integer> awayCount = new HashMap<>();
        Map<UUID, Integer> gameCount = new HashMap<>();
        // Each team's division target, looked up once here to avoid repeated stream searches.
        Map<UUID, Integer> targetByTeam = new HashMap<>();

        for (Division div : eligible) {
            for (Team team : div.teams()) {
                homeCount.put(team.id(), 0);
                awayCount.put(team.id(), 0);
                gameCount.put(team.id(), 0);
                targetByTeam.put(team.id(), div.targetGamesPerTeam());
            }
        }

        // Build single round-robin for each division (pass A only).
        // Each division produces a list of rounds; counters are accumulated from actual output below.
        Map<UUID, List<List<RawGame>>> roundRobinByDiv = new LinkedHashMap<>();
        for (Division div : eligible) {
            roundRobinByDiv.put(div.id(), buildRoundRobinRounds(div));
        }

        // Ordered game list: all round-robin games (div 0 all rounds, div 1 all rounds, ...)
        List<RawGame> ordered = new ArrayList<>();
        for (Division div : eligible) {
            for (List<RawGame> round : roundRobinByDiv.get(div.id())) {
                ordered.addAll(round);
                for (RawGame g : round) {
                    homeCount.merge(g.home().id(), 1, Integer::sum);
                    awayCount.merge(g.away().id(), 1, Integer::sum);
                    gameCount.merge(g.home().id(), 1, Integer::sum);
                    gameCount.merge(g.away().id(), 1, Integer::sum);
                }
            }
        }

        // Fill rounds: continue until all teams have reached their division's target,
        // or until no more pairs can be formed (parity makes exact target unreachable).
        List<String> fillRoundLogs = new ArrayList<>();
        int fillRound = 0;
        boolean anyProgress;
        do {
            anyProgress = false;
            fillRound++;
            List<RawGame> roundFillGames = new ArrayList<>();

            for (Division div : eligible) {
                List<Team> needsGame = div.teams().stream()
                    .filter(t -> gameCount.get(t.id()) < div.targetGamesPerTeam())
                    .sorted(Comparator.comparingInt((Team t) -> gameCount.get(t.id()))
                        .thenComparing(t -> t.id().toString()))
                    .toList();

                if (needsGame.size() < 2) continue;

                // Pair greedily: highest-deficit teams first (sorted above), index 0+1, 2+3, ...
                for (int i = 0; i + 1 < needsGame.size(); i += 2) {
                    Team a = needsGame.get(i);
                    Team b = needsGame.get(i + 1);

                    // Give home advantage to the team with the greater away-over-home imbalance.
                    // A positive imbalance means this team has played more away games than home.
                    int aImbalance = awayCount.get(a.id()) - homeCount.get(a.id());
                    int bImbalance = awayCount.get(b.id()) - homeCount.get(b.id());
                    Team home = (aImbalance >= bImbalance) ? a : b;
                    Team away = (home == a) ? b : a;

                    roundFillGames.add(new RawGame(home, away, div));
                    homeCount.merge(home.id(), 1, Integer::sum);
                    awayCount.merge(away.id(), 1, Integer::sum);
                    gameCount.merge(home.id(), 1, Integer::sum);
                    gameCount.merge(away.id(), 1, Integer::sum);
                    anyProgress = true;
                }

                // Log current game count for every team in this division after the fill round.
                StringJoiner sj = new StringJoiner(", ");
                div.teams().stream()
                    .sorted(Comparator.comparing(Team::name))
                    .forEach(t -> sj.add(t.name() + " " + gameCount.get(t.id())));
                fillRoundLogs.add(String.format("Fill round %d complete: %s", fillRound, sj));
            }

            ordered.addAll(roundFillGames);

        } while (anyTeamBelowTarget(eligible, gameCount, targetByTeam) && anyProgress);

        // Assign stable, 1-based game numbers after all games are collected.
        List<TeamGame> teamGames = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            RawGame rg = ordered.get(i);
            teamGames.add(new TeamGame(
                UUID.randomUUID(),
                i + 1,
                rg.home().id(), rg.home().name(),
                rg.away().id(), rg.away().name(),
                rg.division().id(), rg.division().name(),
                rg.division().gameDurationMinutes()
            ));
        }

        return new TeamScheduleResult.Success(new TeamSchedule(teamGames), fillRoundLogs);
    }

    // --- Round-robin generation ---

    /**
     * Produces a single round-robin (pass A only) for one division, as a list of rounds.
     * Each entry is one round's worth of games. N*(N-1)/2 games total across all rounds.
     * Home/away balance is approximately even per team; exact counts must be tracked from output.
     */
    private List<List<RawGame>> buildRoundRobinRounds(Division div) {
        List<Team> teams = new ArrayList<>(div.teams());
        int n = teams.size();

        // Add a null bye-slot for odd team counts so the circle method stays even.
        if (n % 2 != 0) teams.add(null);
        int m = teams.size(); // guaranteed even

        Team fixed = teams.get(0);
        List<Team> rotating = new ArrayList<>(teams.subList(1, m));

        List<List<RawGame>> rounds = new ArrayList<>();

        for (int r = 0; r < m - 1; r++) {
            List<RawGame> round = new ArrayList<>();

            // specI is the circle-table index (0 = fixed team's row, 1..M/2-1 = column pairs).
            // Home/away formula: left is home when (specI + r) is even, otherwise right is home.
            for (int specI = 0; specI < m / 2; specI++) {
                Team left, right;
                if (specI == 0) {
                    left = fixed;
                    right = rotating.get(m - 2); // circle[M-1] = rotating[M-2]
                } else {
                    left = rotating.get(specI - 1);      // circle[specI]
                    right = rotating.get(m - 2 - specI); // circle[M-1-specI]
                }

                if (left == null || right == null) continue; // discard bye-week pairings

                boolean leftIsHome = ((specI + r) % 2 == 0);
                Team home = leftIsHome ? left : right;
                Team away = leftIsHome ? right : left;
                round.add(new RawGame(home, away, div));
            }

            rounds.add(round);

            // Advance the circle: bring the last element of rotating to the front.
            rotating.add(0, rotating.remove(rotating.size() - 1));
        }

        return rounds;
    }

    // --- Helpers ---

    private boolean anyTeamBelowTarget(
            List<Division> eligible,
            Map<UUID, Integer> gameCount,
            Map<UUID, Integer> targetByTeam) {
        return eligible.stream()
            .flatMap(d -> d.teams().stream())
            .anyMatch(t -> gameCount.get(t.id()) < targetByTeam.get(t.id()));
    }

    private List<String> validatePreconditions(League league) {
        List<String> errors = new ArrayList<>();

        if (league.config() == null
                || league.config().seasonStart() == null
                || league.config().seasonEnd() == null) {
            errors.add("Error: Season start and end dates must be configured before generating "
                + "a team schedule. Run 'planr config set --start <date> --end <date>'.");
        }

        boolean anyEligible = league.divisions().stream().anyMatch(d -> d.teams().size() >= 2);
        if (!anyEligible) {
            errors.add("Error: At least one division with 2 or more teams is required "
                + "to generate a team schedule.");
            return errors; // remaining checks need eligible divisions
        }

        // Validate that each division's target is at least N-1 (minimum for one full round-robin).
        for (Division div : league.divisions()) {
            int n = div.teams().size();
            if (n < 2) continue;
            int minimumTarget = n - 1;
            if (div.targetGamesPerTeam() < minimumTarget) {
                errors.add(String.format(
                    "Error: Division \"%s\" target of %d games per team is less than the minimum "
                    + "of %d required for a single round-robin with %d teams. Minimum target is %d.",
                    div.name(), div.targetGamesPerTeam(), minimumTarget, n, minimumTarget));
            }
        }

        return errors;
    }
}
