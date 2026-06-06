package org.leagueplan.planr;

import org.leagueplan.planr.command.CalendarCommand;
import org.leagueplan.planr.command.ConfigCommand;
import org.leagueplan.planr.command.DivisionCommand;
import org.leagueplan.planr.command.FieldCommand;
import org.leagueplan.planr.command.PlayoffCommand;
import org.leagueplan.planr.command.PracticeCommand;
import org.leagueplan.planr.command.ScheduleCommand;
import org.leagueplan.planr.command.TeamCommand;
import org.leagueplan.planr.store.LeagueStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
    name = "planr",
    subcommands = {
      DivisionCommand.class,
      TeamCommand.class,
      FieldCommand.class,
      ScheduleCommand.class,
      ConfigCommand.class,
      PlayoffCommand.class,
      PracticeCommand.class,
      CalendarCommand.class,
      CommandLine.HelpCommand.class
    },
    description = "League Planner — schedule management for little league organizers.",
    mixinStandardHelpOptions = true,
    version = "planr 0.14.0")
public class PlanrApp implements Runnable {

  @Spec CommandSpec spec;

  public final LeagueStore store = new LeagueStore();

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new PlanrApp()).execute(args);
    System.exit(exitCode);
  }
}
