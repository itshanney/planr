package org.leagueplan.planr.scheduler;

import java.util.UUID;

public record PlayoffFieldSummary(
    UUID fieldId, String fieldName, Integer playoffPriority, int gamesAssigned) {}
