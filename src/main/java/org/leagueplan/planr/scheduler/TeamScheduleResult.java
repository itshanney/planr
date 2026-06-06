package org.leagueplan.planr.scheduler;

import java.util.List;
import org.leagueplan.planr.model.TeamSchedule;

public sealed interface TeamScheduleResult
    permits TeamScheduleResult.Success, TeamScheduleResult.Failure {

  record Success(TeamSchedule schedule, List<String> cycleLogs) implements TeamScheduleResult {}

  record Failure(String message) implements TeamScheduleResult {}
}
