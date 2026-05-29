package org.leagueplan.planr.command;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.leagueplan.planr.PlanrApp;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.PracticeSchedule;
import org.leagueplan.planr.model.PracticeSlot;
import org.leagueplan.planr.model.PracticeState;
import org.leagueplan.planr.model.Team;
import org.leagueplan.planr.scheduler.PracticeScheduleResult;
import org.leagueplan.planr.scheduler.SchedulerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "practice",
    description = "Manage pre-season practice scheduling.",
    subcommands = {
      PracticeCommand.GenerateCmd.class,
      PracticeCommand.AssignCmd.class,
      PracticeCommand.ViewCmd.class,
      PracticeCommand.ClearCmd.class
    },
    mixinStandardHelpOptions = true)
public class PracticeCommand implements Runnable {

  @ParentCommand PlanrApp app;
  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }

  // --- Generate ---

  @Command(
      name = "generate",
      description = "Generate practice slots for all configured divisions (Phase 1).")
  static class GenerateCmd implements Callable<Integer> {

    @ParentCommand PracticeCommand parent;

    @Override
    public Integer call() {
      try {
        League league = parent.app.store.load();

        int divisionsProcessed = 0;
        int totalSlots = 0;

        for (Division division : league.divisions()) {
          if (!division.isPracticeConfigured()) {
            System.out.printf("Skipping %s: practice configuration incomplete.%n", division.name());
            continue;
          }
          if (league.findPracticeSchedule(division.id()).isPresent()) {
            System.out.printf(
                "Skipping %s: practices already generated. "
                    + "Run 'planr practice clear --division %s' to reset.%n",
                division.name(), division.name());
            continue;
          }
          if (division.teams().isEmpty()) {
            System.out.printf("Skipping %s: no teams configured.%n", division.name());
            continue;
          }

          int teamCount = division.teams().size();
          int practiceCount = division.practiceCount();
          List<PracticeSlot> slots = new ArrayList<>();
          for (Team team : division.teams()) {
            for (int p = 1; p <= practiceCount; p++) {
              slots.add(new PracticeSlot(UUID.randomUUID(), team.id(), p, null, null, null));
            }
          }

          PracticeSchedule ps = new PracticeSchedule(division.id(), PracticeState.GENERATED, slots);
          league = league.withPracticeScheduleAdded(ps);

          System.out.printf(
              "Generated %d practice slots for %s (%d teams × %d practices).%n",
              teamCount * practiceCount, division.name(), teamCount, practiceCount);
          divisionsProcessed++;
          totalSlots += slots.size();
        }

        if (divisionsProcessed == 0) {
          System.err.println(
              "Error: No divisions qualify for practice generation. "
                  + "Configure practice settings with 'planr division edit'.");
          return 1;
        }

        parent.app.store.save(league);
        System.out.printf(
            "Practice generation complete: %d division(s) processed, %d total slots created.%n",
            divisionsProcessed, totalSlots);
        return 0;

      } catch (IOException e) {
        System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
        return 2;
      }
    }
  }

  // --- Assign ---

  @Command(
      name = "assign",
      description = "Assign field/time slots to all practice schedules (Phase 2).")
  static class AssignCmd implements Callable<Integer> {

    @ParentCommand PracticeCommand parent;

    @Override
    public Integer call() {
      try {
        League league = parent.app.store.load();

        List<PracticeSchedule> schedules = league.practiceSchedules();
        if (schedules.isEmpty()) {
          System.err.println(
              "Error: No practice schedules found. Run 'planr practice generate' first.");
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

        int totalSlots = schedules.stream().mapToInt(ps -> ps.slots().size()).sum();
        int effectiveWeekCap =
            (league.config().maxGamesPerWeek() != null)
                ? league.config().maxGamesPerWeek()
                : SchedulerService.DEFAULT_MAX_GAMES_PER_WEEK;
        int effectiveRestDays =
            (league.config().minRestDays() != null)
                ? league.config().minRestDays()
                : SchedulerService.DEFAULT_MIN_REST_DAYS;

        System.out.printf(
            "Practice field assignment: %d slot(s) across %d division(s).%n",
            totalSlots, schedules.size());
        System.out.printf(
            "Scheduling constraints: max %d practice(s)/week per team, "
                + "min %d rest day(s) between practices.%n",
            effectiveWeekCap, effectiveRestDays);
        System.out.print(
            "Begin field assignment? This may take up to 5 minutes. " + "Type 'yes' to continue: ");
        System.out.flush();

        String input;
        try (Scanner sc = new Scanner(System.in)) {
          input = sc.nextLine().trim();
        }
        if (!input.equals("yes")) {
          System.out.println("Cancelled.");
          return 0;
        }

        // Clear all existing assignments before re-solving.
        League cleared = league;
        for (PracticeSchedule ps : schedules) {
          List<PracticeSlot> clearedSlots =
              ps.slots().stream().map(PracticeSlot::withAssignmentCleared).toList();
          cleared =
              cleared.withPracticeScheduleReplaced(ps.divisionId(), ps.withSlots(clearedSlots));
        }

        System.out.printf(
            "[0:00] Practice field assignment started. " + "%d slot(s) across %d division(s).%n",
            totalSlots, schedules.size());
        System.out.flush();

        SchedulerService schedulerService = new SchedulerService();
        PracticeScheduleResult result =
            schedulerService.assignPractices(cleared, cleared.practiceSchedules());

        if (result instanceof PracticeScheduleResult.Failure f) {
          System.err.println(f.message());
          return 1;
        }

        PracticeScheduleResult.Success success = (PracticeScheduleResult.Success) result;

        // Write assignments back into PracticeSlot entries; transition all to ASSIGNED.
        League updated = cleared;
        for (PracticeSchedule ps : cleared.practiceSchedules()) {
          List<PracticeSlot> updatedSlots =
              ps.slots().stream()
                  .map(
                      slot -> {
                        var slotResult = success.assignmentsBySlotId().get(slot.slotId());
                        return slotResult != null
                            ? slot.withAssignment(
                                slotResult.date(), slotResult.startTime(), slotResult.fieldId())
                            : slot;
                      })
                  .toList();
          updated =
              updated.withPracticeScheduleReplaced(
                  ps.divisionId(), ps.withSlots(updatedSlots).withState(PracticeState.ASSIGNED));
        }
        parent.app.store.save(updated);

        ScheduleCommand.printConstraintSummary(success.divisionSummaries());

        int sAssigned = success.assignmentsBySlotId().size();
        int divCount = schedules.size();
        System.out.printf(
            "Practice field assignment complete: %d/%d slots assigned across %d division(s).%n",
            sAssigned, totalSlots, divCount);
        return 0;

      } catch (IOException e) {
        System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
        return 2;
      }
    }
  }

  // --- View ---

  @Command(
      name = "view",
      description = "View practice status. Use --division for full slot detail.")
  static class ViewCmd implements Callable<Integer> {

    @ParentCommand PracticeCommand parent;

    @Option(
        names = "--division",
        paramLabel = "<name>",
        description = "Division name (omit for summary of all divisions).")
    String divisionName;

    @Override
    public Integer call() {
      try {
        League league = parent.app.store.load();

        if (divisionName == null) {
          printSummary(league);
          return 0;
        }

        Optional<Division> divOpt = league.findDivision(divisionName);
        if (divOpt.isEmpty()) {
          System.err.printf("Error: Division \"%s\" not found.%n", divisionName);
          return 1;
        }
        Division division = divOpt.get();
        Optional<PracticeSchedule> psOpt = league.findPracticeSchedule(division.id());
        if (psOpt.isEmpty()) {
          System.err.printf(
              "Error: No practice schedule exists for \"%s\". "
                  + "Run 'planr practice generate' first.%n",
              division.name());
          return 1;
        }

        PracticeSchedule ps = psOpt.get();
        System.out.printf(
            "Division: %s | State: %s | Period: %s to %s%n%n",
            division.name(), ps.state(), division.practiceStart(), division.practiceEnd());
        printDetail(ps, division, league);
        return 0;

      } catch (IOException e) {
        System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
        return 2;
      }
    }

    private void printSummary(League league) {
      List<Division> all =
          league.divisions().stream()
              .sorted(Comparator.comparing(Division::name, String.CASE_INSENSITIVE_ORDER))
              .toList();
      if (all.isEmpty()) {
        System.out.println("No divisions configured.");
        return;
      }

      int divW =
          Math.max(
              "DIVISION".length(), all.stream().mapToInt(d -> d.name().length()).max().orElse(0));
      int stateW = Math.max("STATE".length(), "NOT_CONFIGURED".length());
      int assignedW = "ASSIGNED".length();
      int totalW = "TOTAL".length();

      String fmt = "%-" + divW + "s  %-" + stateW + "s  %" + assignedW + "s  %" + totalW + "s%n";
      System.out.printf(fmt, "DIVISION", "STATE", "ASSIGNED", "TOTAL");
      System.out.printf(
          fmt, "-".repeat(divW), "-".repeat(stateW), "-".repeat(assignedW), "-".repeat(totalW));

      for (Division div : all) {
        Optional<PracticeSchedule> psOpt = league.findPracticeSchedule(div.id());
        String state;
        String assigned;
        String total;
        if (psOpt.isPresent()) {
          PracticeSchedule ps = psOpt.get();
          state = ps.state().name();
          assigned =
              String.valueOf(ps.slots().stream().filter(s -> s.assignedDate() != null).count());
          total = String.valueOf(ps.slots().size());
        } else if (div.isPracticeConfigured()) {
          state = "NOT_STARTED";
          assigned = "--";
          total = "--";
        } else {
          state = "NOT_CONFIGURED";
          assigned = "--";
          total = "--";
        }
        System.out.printf(fmt, div.name(), state, assigned, total);
      }
    }

    private void printDetail(PracticeSchedule ps, Division division, League league) {
      Comparator<PracticeSlot> slotOrder =
          Comparator.<PracticeSlot, LocalDate>comparing(
                  PracticeSlot::assignedDate, Comparator.nullsLast(Comparator.naturalOrder()))
              .thenComparing(
                  PracticeSlot::assignedStartTime,
                  Comparator.nullsLast(Comparator.naturalOrder()))
              .thenComparing(
                  s -> resolveTeamName(division, s.teamId()), String.CASE_INSENSITIVE_ORDER);
      List<PracticeSlot> slots = ps.slots().stream().sorted(slotOrder).toList();

      int teamW =
          Math.max(
              "TEAM".length(),
              slots.stream()
                  .mapToInt(s -> resolveTeamName(division, s.teamId()).length())
                  .max()
                  .orElse(0));
      int pracW =
          Math.max(
              "PRACTICE".length(),
              slots.stream()
                  .mapToInt(s -> pracLabel(s, division.practiceCount()).length())
                  .max()
                  .orElse(0));
      int dateW = Math.max("DATE".length(), "UNASSIGNED".length());
      int timeW = Math.max("TIME".length(), 5);
      int fieldW =
          Math.max(
              "FIELD".length(),
              slots.stream()
                  .filter(s -> s.assignedFieldId() != null)
                  .mapToInt(s -> resolveFieldName(league, s.assignedFieldId()).length())
                  .max()
                  .orElse(0));

      String fmt =
          "%-" + teamW + "s  %-" + pracW + "s  %-" + dateW + "s  %-" + timeW + "s  %-" + fieldW
              + "s%n";
      System.out.printf(fmt, "TEAM", "PRACTICE", "DATE", "TIME", "FIELD");
      System.out.printf(
          fmt,
          "-".repeat(teamW),
          "-".repeat(pracW),
          "-".repeat(dateW),
          "-".repeat(timeW),
          "-".repeat(fieldW));

      for (PracticeSlot slot : slots) {
        String teamName = resolveTeamName(division, slot.teamId());
        String prac = pracLabel(slot, division.practiceCount());
        String date;
        String time;
        String field;
        if (slot.assignedDate() != null) {
          date = slot.assignedDate().toString();
          time =
              String.format(
                  "%02d:%02d",
                  slot.assignedStartTime().getHour(), slot.assignedStartTime().getMinute());
          field = resolveFieldName(league, slot.assignedFieldId());
        } else {
          date = "UNASSIGNED";
          time = "--";
          field = "--";
        }
        System.out.printf(fmt, teamName, prac, date, time, field);
      }
    }

    private static String pracLabel(PracticeSlot slot, Integer totalCount) {
      return slot.slotNumber() + " of " + (totalCount != null ? totalCount : "?");
    }

    private static String resolveTeamName(Division division, UUID teamId) {
      return division.teams().stream()
          .filter(t -> t.id().equals(teamId))
          .findFirst()
          .map(Team::name)
          .orElse("[unknown]");
    }

    private static String resolveFieldName(League league, UUID fieldId) {
      if (fieldId == null) return "--";
      return league.fields().stream()
          .filter(f -> f.id().equals(fieldId))
          .findFirst()
          .map(Field::name)
          .orElse("[unknown]");
    }
  }

  // --- Clear ---

  @Command(
      name = "clear",
      description = "Remove a division's practice schedule and reset to NOT_STARTED.")
  static class ClearCmd implements Callable<Integer> {

    @ParentCommand PracticeCommand parent;

    @Option(
        names = "--division",
        required = true,
        paramLabel = "<name>",
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

        Optional<PracticeSchedule> psOpt = league.findPracticeSchedule(division.id());
        if (psOpt.isEmpty()) {
          System.err.printf("Error: No practice schedule exists for \"%s\".%n", division.name());
          return 1;
        }

        PracticeSchedule ps = psOpt.get();
        long assigned = ps.slots().stream().filter(s -> s.assignedDate() != null).count();

        System.out.printf(
            "Warning: This will permanently remove the practice schedule for \"%s\" "
                + "(state: %s, %d slot(s), %d assigned). "
                + "Type 'yes' to confirm: ",
            division.name(), ps.state(), ps.slots().size(), assigned);
        System.out.flush();

        String input;
        try (Scanner sc = new Scanner(System.in)) {
          input = sc.nextLine().trim();
        }
        if (!input.equals("yes")) {
          System.out.println("Cancelled.");
          return 0;
        }

        parent.app.store.save(league.withPracticeScheduleRemoved(division.id()));
        System.out.printf("Practice schedule for \"%s\" cleared.%n", division.name());
        return 0;

      } catch (IOException e) {
        System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
        return 2;
      }
    }
  }
}
