package org.leagueplan.planr.scheduler;

import org.leagueplan.planr.model.ScheduledGame;

import java.util.List;

public sealed interface ScheduleResult permits ScheduleResult.Success, ScheduleResult.Failure {

    record Success(
        List<ScheduledGame> games,
        boolean optimal,
        boolean targetMet,
        List<DivisionSummary> divisionSummaries
    ) implements ScheduleResult {}

    record Failure(String message) implements ScheduleResult {}

    static Success success(List<ScheduledGame> games, boolean optimal, boolean targetMet,
            List<DivisionSummary> divisionSummaries) {
        return new Success(games, optimal, targetMet, divisionSummaries);
    }

    static Failure failure(String message) {
        return new Failure(message);
    }
}
