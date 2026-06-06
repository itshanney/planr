package org.leagueplan.planr.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.Team;
import org.leagueplan.planr.model.TeamGame;
import org.leagueplan.planr.model.TeamSchedule;

public class TeamScheduleService {

  private record RawGame(Team home, Team away, Division division) {}

  public TeamScheduleResult generate(League league) {
    List<String> errors = validatePreconditions(league);
    if (!errors.isEmpty()) {
      return new TeamScheduleResult.Failure(String.join("\n", errors));
    }

    List<Division> eligible =
        league.divisions().stream().filter(d -> d.teams().size() >= 2).toList();

    Map<UUID, Integer> gameCount = new HashMap<>();
    for (Division div : eligible) {
      for (Team team : div.teams()) {
        gameCount.put(team.id(), 0);
      }
    }

    List<RawGame> ordered = new ArrayList<>();
    List<String> cycleLogs = new ArrayList<>();

    for (Division div : eligible) {
      int n = div.teams().size();
      int target = div.targetGamesPerTeam();
      // Each full cycle gives every team exactly N-1 games (one opponent per round).
      int fullCycles = target / (n - 1);
      int remainder = target % (n - 1);

      // Pad to even count for the circle method: odd divisions get a null bye-slot.
      List<Team> slots = new ArrayList<>(div.teams());
      if (n % 2 != 0) slots.add(null);
      int m = slots.size();

      Team fixed = slots.get(0);
      // Rotation is never reset between cycles, preserving home/away balance across boundaries.
      List<Team> rotating = new ArrayList<>(slots.subList(1, m));

      int globalRound = 0;
      // Single full cycle with no partial is the minimum (target == N-1): emit no logs.
      boolean suppressLogs = (fullCycles == 1 && remainder == 0);

      for (int c = 1; c <= fullCycles; c++) {
        for (int r = 0; r < m - 1; r++) {
          addRound(buildRound(fixed, rotating, globalRound, div, m), ordered, gameCount);
          rotating.add(0, rotating.remove(rotating.size() - 1));
          globalRound++;
        }
        if (!suppressLogs) {
          cycleLogs.add(formatLog("Cycle " + c + " complete", div, gameCount));
        }
      }

      if (remainder > 0) {
        for (int r = 0; r < remainder; r++) {
          addRound(buildRound(fixed, rotating, globalRound, div, m), ordered, gameCount);
          rotating.add(0, rotating.remove(rotating.size() - 1));
          globalRound++;
        }
        cycleLogs.add(
            formatLog(
                "Partial cycle (" + remainder + " of " + (m - 1) + " rounds) complete",
                div,
                gameCount));
      }
    }

    List<TeamGame> teamGames = new ArrayList<>();
    for (int i = 0; i < ordered.size(); i++) {
      RawGame rg = ordered.get(i);
      teamGames.add(
          new TeamGame(
              UUID.randomUUID(),
              i + 1,
              rg.home().id(),
              rg.home().name(),
              rg.away().id(),
              rg.away().name(),
              rg.division().id(),
              rg.division().name(),
              rg.division().gameDurationMinutes()));
    }

    return new TeamScheduleResult.Success(new TeamSchedule(teamGames), cycleLogs);
  }

  // --- Round-robin generation ---

  /**
   * Generates one round of the circle method. Uses globalRound (not the cycle-local index) so
   * home/away alternation continues correctly across cycle boundaries.
   */
  private List<RawGame> buildRound(
      Team fixed, List<Team> rotating, int globalRound, Division div, int m) {
    List<RawGame> round = new ArrayList<>();
    for (int specI = 0; specI < m / 2; specI++) {
      Team left, right;
      if (specI == 0) {
        left = fixed;
        right = rotating.get(m - 2);
      } else {
        left = rotating.get(specI - 1);
        right = rotating.get(m - 2 - specI);
      }
      if (left == null || right == null) continue; // discard bye-week pairings
      boolean leftIsHome = ((specI + globalRound) % 2 == 0);
      round.add(new RawGame(leftIsHome ? left : right, leftIsHome ? right : left, div));
    }
    return round;
  }

  private void addRound(List<RawGame> round, List<RawGame> ordered, Map<UUID, Integer> gameCount) {
    for (RawGame g : round) {
      ordered.add(g);
      gameCount.merge(g.home().id(), 1, Integer::sum);
      gameCount.merge(g.away().id(), 1, Integer::sum);
    }
  }

  private String formatLog(String prefix, Division div, Map<UUID, Integer> gameCount) {
    StringJoiner sj = new StringJoiner(", ");
    div.teams().stream()
        .sorted(Comparator.comparing(Team::name))
        .forEach(t -> sj.add(t.name() + " " + gameCount.get(t.id())));
    return prefix + ": " + sj;
  }

  // --- Helpers ---

  private List<String> validatePreconditions(League league) {
    List<String> errors = new ArrayList<>();

    if (league.config() == null
        || league.config().seasonStart() == null
        || league.config().seasonEnd() == null) {
      errors.add(
          "Error: Season start and end dates must be configured before generating "
              + "a team schedule. Run 'planr config set --start <date> --end <date>'.");
    }

    boolean anyEligible = league.divisions().stream().anyMatch(d -> d.teams().size() >= 2);
    if (!anyEligible) {
      errors.add(
          "Error: At least one division with 2 or more teams is required "
              + "to generate a team schedule.");
      return errors; // remaining checks need eligible divisions
    }

    for (Division div : league.divisions()) {
      int n = div.teams().size();
      if (n < 2) continue;
      int minimumTarget = n - 1;
      if (div.targetGamesPerTeam() < minimumTarget) {
        errors.add(
            String.format(
                "Error: Division \"%s\" target of %d games per team is less than the minimum of %d"
                    + " required for a single round-robin with %d teams. Minimum target is %d.",
                div.name(), div.targetGamesPerTeam(), minimumTarget, n, minimumTarget));
      }
    }

    return errors;
  }
}
