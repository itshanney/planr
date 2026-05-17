package org.leagueplan.planr.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record FieldDateOverride(
    UUID id,
    LocalDate date,
    LocalTime openStart,
    LocalTime openEnd
) {}
