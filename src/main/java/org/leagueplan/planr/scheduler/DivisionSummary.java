package org.leagueplan.planr.scheduler;

public record DivisionSummary(
    String divisionName,
    int gamesRequested,
    int gamesAssigned,
    int slotsAvailable
) {
    public boolean targetMet() {
        return gamesAssigned == gamesRequested;
    }
}
