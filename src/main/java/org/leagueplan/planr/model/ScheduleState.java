package org.leagueplan.planr.model;

public enum ScheduleState {
    NONE, TEAM_SCHEDULE, DRAFT, FINALIZED;

    public static ScheduleState of(League league) {
        if (league.schedule() != null) {
            return league.schedule().status() == ScheduleStatus.FINALIZED
                ? FINALIZED : DRAFT;
        }
        if (league.teamSchedule() != null) return TEAM_SCHEDULE;
        return NONE;
    }
}
