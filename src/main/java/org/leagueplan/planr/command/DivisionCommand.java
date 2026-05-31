package org.leagueplan.planr.command;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.leagueplan.planr.PlanrApp;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.League;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "division",
    description = "Manage divisions.",
    subcommands = {
      DivisionCommand.AddCmd.class,
      DivisionCommand.EditCmd.class,
      DivisionCommand.DeleteCmd.class,
      DivisionCommand.ListCmd.class
    },
    mixinStandardHelpOptions = true)
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

    @Option(
        names = "--duration",
        required = true,
        paramLabel = "<minutes>",
        description = "Game duration in minutes.")
    int duration;

    @Option(
        names = "--target",
        required = true,
        paramLabel = "<n>",
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
        System.err.printf(
            "Error: Target games per team must be a positive integer (got: %d).%n", target);
        return 1;
      }
      try {
        League league = parent.app.store.load();
        if (league.hasDivision(name)) {
          System.err.printf("Error: Division \"%s\" already exists.%n", name);
          return 1;
        }
        Division division =
            new Division(
                UUID.randomUUID(), name, duration, target, List.of(), null, null, null, null, null);
        parent.app.store.save(league.withDivisionAdded(division));
        System.out.printf(
            "Division \"%s\" added (%d min/game, target %d games/team).%n", name, duration, target);
        return 0;
      } catch (IOException e) {
        System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
        return 2;
      }
    }
  }

  @Command(name = "edit", description = "Edit an existing division.")
  static class EditCmd implements Callable<Integer> {

    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    @ParentCommand DivisionCommand parent;

    @Parameters(index = "0", paramLabel = "<name>", description = "Current division name.")
    String name;

    @Option(names = "--name", paramLabel = "<new-name>", description = "New division name.")
    String newName;

    @Option(
        names = "--duration",
        paramLabel = "<minutes>",
        description = "New game duration in minutes.")
    Integer newDuration;

    @Option(names = "--target", paramLabel = "<n>", description = "New target games per team.")
    Integer newTarget;

    @Option(
        names = "--practice-count",
        paramLabel = "<n>",
        description = "Number of practices per team before the season.")
    Integer newPracticeCount;

    @Option(
        names = "--practice-duration-minutes",
        paramLabel = "<minutes>",
        description = "Practice slot duration in minutes.")
    Integer newPracticeDurationMinutes;

    @Option(
        names = "--practice-start",
        paramLabel = "<YYYY-MM-DD>",
        description = "Practice window start date (inclusive).")
    String newPracticeStartStr;

    @Option(
        names = "--practice-end",
        paramLabel = "<YYYY-MM-DD>",
        description = "Practice window end date (inclusive).")
    String newPracticeEndStr;

    @Option(
        names = "--curfew-time",
        paramLabel = "<HH:mm>",
        description = "Latest game/practice start time for this division.")
    String newCurfewTimeStr;

    @Option(
        names = "--no-curfew-time",
        description = "Remove the curfew constraint from this division.")
    boolean clearCurfewTime;

    @Override
    public Integer call() {
      if (newName == null
          && newDuration == null
          && newTarget == null
          && newPracticeCount == null
          && newPracticeDurationMinutes == null
          && newPracticeStartStr == null
          && newPracticeEndStr == null
          && newCurfewTimeStr == null
          && !clearCurfewTime) {
        System.err.println(
            "Error: At least one of --name, --duration, --target, "
                + "--practice-count, --practice-duration-minutes, --practice-start, "
                + "--practice-end, --curfew-time, or --no-curfew-time must be provided.");
        return 1;
      }
      if (newCurfewTimeStr != null && clearCurfewTime) {
        System.err.println("Error: --curfew-time and --no-curfew-time are mutually exclusive.");
        return 1;
      }
      if (newName != null && newName.isBlank()) {
        System.err.println("Error: Division name cannot be empty.");
        return 1;
      }
      if (newDuration != null && newDuration <= 0) {
        System.err.printf(
            "Error: Game duration must be a positive integer, got %d.%n", newDuration);
        return 1;
      }
      if (newTarget != null && newTarget < 1) {
        System.err.printf(
            "Error: Target games per team must be a positive integer (got: %d).%n", newTarget);
        return 1;
      }
      if (newPracticeCount != null && newPracticeCount < 1) {
        System.err.printf(
            "Error: --practice-count must be a positive integer (got: %d).%n", newPracticeCount);
        return 1;
      }
      if (newPracticeDurationMinutes != null && newPracticeDurationMinutes < 1) {
        System.err.printf(
            "Error: --practice-duration-minutes must be a positive integer (got: %d).%n",
            newPracticeDurationMinutes);
        return 1;
      }

      LocalDate newPracticeStart = null;
      LocalDate newPracticeEnd = null;
      if (newPracticeStartStr != null) {
        try {
          newPracticeStart = LocalDate.parse(newPracticeStartStr.trim());
        } catch (DateTimeParseException e) {
          System.err.printf(
              "Error: Invalid date \"%s\" for --practice-start. Expected YYYY-MM-DD.%n",
              newPracticeStartStr);
          return 1;
        }
      }
      if (newPracticeEndStr != null) {
        try {
          newPracticeEnd = LocalDate.parse(newPracticeEndStr.trim());
        } catch (DateTimeParseException e) {
          System.err.printf(
              "Error: Invalid date \"%s\" for --practice-end. Expected YYYY-MM-DD.%n",
              newPracticeEndStr);
          return 1;
        }
      }

      LocalTime newCurfewTime = null;
      if (newCurfewTimeStr != null) {
        try {
          newCurfewTime = LocalTime.parse(newCurfewTimeStr.trim(), TIME_FORMAT);
        } catch (DateTimeParseException e) {
          System.err.printf(
              "Error: Invalid time \"%s\" for --curfew-time. Expected HH:mm.%n", newCurfewTimeStr);
          return 1;
        }
      }

      try {
        League league = parent.app.store.load();
        var existing = league.findDivision(name);
        if (existing.isEmpty()) {
          System.err.printf("Error: Division \"%s\" not found.%n", name);
          return 1;
        }
        Division division = existing.get();

        if (newName != null && !newName.equalsIgnoreCase(name) && league.hasDivision(newName)) {
          System.err.printf("Error: Division \"%s\" already exists.%n", newName);
          return 1;
        }

        // Resolve effective practice dates by falling back to stored values.
        LocalDate effectiveStart =
            (newPracticeStart != null) ? newPracticeStart : division.practiceStart();
        LocalDate effectiveEnd = (newPracticeEnd != null) ? newPracticeEnd : division.practiceEnd();

        if (effectiveStart != null
            && effectiveEnd != null
            && effectiveEnd.isBefore(effectiveStart)) {
          System.err.printf(
              "Error: --practice-end (%s) must not be before --practice-start (%s).%n",
              effectiveEnd, effectiveStart);
          return 1;
        }

        LocalDate seasonStart = (league.config() != null) ? league.config().seasonStart() : null;
        if (seasonStart != null) {
          if (effectiveStart != null && !effectiveStart.isBefore(seasonStart)) {
            System.err.printf(
                "Error: --practice-start (%s) must be before seasonStart (%s).%n",
                effectiveStart, seasonStart);
            return 1;
          }
          if (effectiveEnd != null && !effectiveEnd.isBefore(seasonStart)) {
            System.err.printf(
                "Error: --practice-end (%s) must be before seasonStart (%s).%n",
                effectiveEnd, seasonStart);
            return 1;
          }
        }

        Division updated =
            applyEdits(
                division,
                newName,
                newDuration,
                newTarget,
                newPracticeCount,
                newPracticeDurationMinutes,
                newPracticeStart,
                newPracticeEnd,
                newCurfewTime,
                clearCurfewTime);
        parent.app.store.save(league.withDivisionReplaced(division.id(), updated));
        System.out.printf("Division \"%s\" updated.%n", updated.name());
        return 0;
      } catch (IOException e) {
        System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
        return 2;
      }
    }

    private Division applyEdits(
        Division division,
        String newName,
        Integer newDuration,
        Integer newTarget,
        Integer newPracticeCount,
        Integer newPracticeDurationMinutes,
        LocalDate newPracticeStart,
        LocalDate newPracticeEnd,
        LocalTime newCurfewTime,
        boolean clearCurfewTime) {
      String resolvedName = (newName != null) ? newName : division.name();
      int resolvedDuration = (newDuration != null) ? newDuration : division.gameDurationMinutes();
      int resolvedTarget = (newTarget != null) ? newTarget : division.targetGamesPerTeam();
      Integer resolvedCount =
          (newPracticeCount != null) ? newPracticeCount : division.practiceCount();
      Integer resolvedPracMin =
          (newPracticeDurationMinutes != null)
              ? newPracticeDurationMinutes
              : division.practiceDurationMinutes();
      LocalDate resolvedStart =
          (newPracticeStart != null) ? newPracticeStart : division.practiceStart();
      LocalDate resolvedEnd = (newPracticeEnd != null) ? newPracticeEnd : division.practiceEnd();
      LocalTime resolvedCurfewTime;
      if (clearCurfewTime) {
        resolvedCurfewTime = null;
      } else if (newCurfewTime != null) {
        resolvedCurfewTime = newCurfewTime;
      } else {
        resolvedCurfewTime = division.curfewTime();
      }
      return new Division(
          division.id(),
          resolvedName,
          resolvedDuration,
          resolvedTarget,
          division.teams(),
          resolvedCount,
          resolvedPracMin,
          resolvedStart,
          resolvedEnd,
          resolvedCurfewTime);
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
              "Error: Division \"%s\" has %d team(s). Remove all teams before deleting the"
                  + " division.%n",
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

    private void printTable(List<Division> divisions) {
      int nameW =
          Math.max(
              "DIVISION".length(),
              divisions.stream().mapToInt(d -> d.name().length()).max().orElse(0));
      int durationW =
          Math.max(
              "DURATION".length(),
              divisions.stream()
                  .mapToInt(d -> (d.gameDurationMinutes() + " min").length())
                  .max()
                  .orElse(0));
      int targetW =
          Math.max(
              "TARGET".length(),
              divisions.stream().mapToInt(d -> targetLabel(d).length()).max().orElse(0));
      int teamsW =
          Math.max(
              "TEAMS".length(),
              divisions.stream()
                  .mapToInt(d -> String.valueOf(d.teams().size()).length())
                  .max()
                  .orElse(0));
      int pracCountW =
          Math.max(
              "PRAC_COUNT".length(),
              divisions.stream().mapToInt(d -> pracCountLabel(d).length()).max().orElse(0));
      int pracMinW =
          Math.max(
              "PRAC_MIN".length(),
              divisions.stream().mapToInt(d -> pracMinLabel(d).length()).max().orElse(0));
      int pracStartW =
          Math.max(
              "PRAC_START".length(),
              divisions.stream()
                  .mapToInt(d -> pracDateLabel(d.practiceStart()).length())
                  .max()
                  .orElse(0));
      int pracEndW =
          Math.max(
              "PRAC_END".length(),
              divisions.stream()
                  .mapToInt(d -> pracDateLabel(d.practiceEnd()).length())
                  .max()
                  .orElse(0));
      int curfewW =
          Math.max(
              "CURFEW".length(),
              divisions.stream().mapToInt(d -> curfewLabel(d).length()).max().orElse(0));

      String fmt =
          "%-"
              + nameW
              + "s    %-"
              + durationW
              + "s    %-"
              + targetW
              + "s    %-"
              + teamsW
              + "s    %-"
              + pracCountW
              + "s    %-"
              + pracMinW
              + "s    %-"
              + pracStartW
              + "s    %-"
              + pracEndW
              + "s    %-"
              + curfewW
              + "s%n";

      System.out.printf(
          fmt,
          "DIVISION",
          "DURATION",
          "TARGET",
          "TEAMS",
          "PRAC_COUNT",
          "PRAC_MIN",
          "PRAC_START",
          "PRAC_END",
          "CURFEW");
      System.out.printf(
          fmt,
          "-".repeat(nameW),
          "-".repeat(durationW),
          "-".repeat(targetW),
          "-".repeat(teamsW),
          "-".repeat(pracCountW),
          "-".repeat(pracMinW),
          "-".repeat(pracStartW),
          "-".repeat(pracEndW),
          "-".repeat(curfewW));

      divisions.forEach(
          d ->
              System.out.printf(
                  fmt,
                  d.name(),
                  d.gameDurationMinutes() + " min",
                  targetLabel(d),
                  d.teams().size(),
                  pracCountLabel(d),
                  pracMinLabel(d),
                  pracDateLabel(d.practiceStart()),
                  pracDateLabel(d.practiceEnd()),
                  curfewLabel(d)));

      long unconfigured = divisions.stream().filter(d -> d.targetGamesPerTeam() == 0).count();
      if (unconfigured > 0) {
        System.out.printf(
            "%nWarning: %d division(s) have no target configured. "
                + "Set with 'planr division edit <name> --target <n>'.%n",
            unconfigured);
      }
    }

    private String targetLabel(Division d) {
      return d.targetGamesPerTeam() == 0 ? "0*" : String.valueOf(d.targetGamesPerTeam());
    }

    private String pracCountLabel(Division d) {
      return d.practiceCount() != null ? String.valueOf(d.practiceCount()) : "--";
    }

    private String pracMinLabel(Division d) {
      return d.practiceDurationMinutes() != null ? d.practiceDurationMinutes() + " min" : "--";
    }

    private String pracDateLabel(java.time.LocalDate date) {
      return date != null ? date.toString() : "--";
    }

    private String curfewLabel(Division d) {
      return d.curfewTime() != null ? d.curfewTime().toString() : "--";
    }
  }
}
