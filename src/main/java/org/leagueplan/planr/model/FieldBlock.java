package org.leagueplan.planr.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record FieldBlock(
    UUID id,
    LocalDate date,
    LocalTime startTime,
    LocalTime endTime
) {}
