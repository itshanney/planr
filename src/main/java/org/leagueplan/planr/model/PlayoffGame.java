package org.leagueplan.planr.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record PlayoffGame(
    UUID gameId,
    String round,
    BracketSide bracketSide,
    String positionA,
    String positionB,
    LocalDate assignedDate,
    LocalTime assignedStartTime,
    UUID assignedFieldId,
    boolean isConditional,
    boolean isBye
) {
    public PlayoffGame withAssignment(LocalDate date, LocalTime startTime, UUID fieldId) {
        return new PlayoffGame(gameId, round, bracketSide, positionA, positionB,
            date, startTime, fieldId, isConditional, isBye);
    }

    public PlayoffGame withAssignmentCleared() {
        return new PlayoffGame(gameId, round, bracketSide, positionA, positionB,
            null, null, null, isConditional, isBye);
    }
}
