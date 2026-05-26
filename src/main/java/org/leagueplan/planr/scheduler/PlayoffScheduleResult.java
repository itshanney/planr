package org.leagueplan.planr.scheduler;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result of a playoff field assignment solve.
 * assignmentsByGameId maps PlayoffGame.gameId to the Slot assigned to that game.
 */
public sealed interface PlayoffScheduleResult
        permits PlayoffScheduleResult.Success, PlayoffScheduleResult.Failure {

    record Success(
        Map<UUID, Slot> assignmentsByGameId,
        boolean optimal,
        List<DivisionSummary> divisionSummaries
    ) implements PlayoffScheduleResult {}

    record Failure(String message) implements PlayoffScheduleResult {}

    static Success success(Map<UUID, Slot> assignmentsByGameId, boolean optimal,
            List<DivisionSummary> divisionSummaries) {
        return new Success(assignmentsByGameId, optimal, divisionSummaries);
    }

    static Failure failure(String message) {
        return new Failure(message);
    }
}
