package org.leagueplan.planr.scheduler;

import org.leagueplan.planr.model.TeamSchedule;

import java.util.List;

public sealed interface TeamScheduleResult
        permits TeamScheduleResult.Success, TeamScheduleResult.Failure {

    record Success(TeamSchedule schedule, List<String> fillRoundLogs)
            implements TeamScheduleResult {}

    record Failure(String message) implements TeamScheduleResult {}
}
