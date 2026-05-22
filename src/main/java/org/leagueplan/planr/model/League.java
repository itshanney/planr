package org.leagueplan.planr.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public record League(
    int version,
    LeagueConfig config,
    List<Division> divisions,
    List<Field> fields,
    TeamSchedule teamSchedule,
    Schedule schedule
) {

    private static final int CURRENT_VERSION = 4;

    public League {
        divisions = (divisions == null) ? List.of() : divisions;
        fields = (fields == null) ? List.of() : fields;
    }

    public static League empty() {
        return new League(CURRENT_VERSION, LeagueConfig.empty(), List.of(), List.of(), null, null);
    }

    // --- Config mutations ---

    public League withConfig(LeagueConfig newConfig) {
        return new League(version, newConfig, divisions, fields, teamSchedule, schedule);
    }

    // --- Division queries ---

    public Optional<Division> findDivision(String name) {
        return divisions.stream()
            .filter(d -> d.name().equalsIgnoreCase(name))
            .findFirst();
    }

    public boolean hasDivision(String name) {
        return findDivision(name).isPresent();
    }

    // --- Division mutations ---

    public League withDivisionAdded(Division division) {
        return new League(version, config,
            Stream.concat(divisions.stream(), Stream.of(division)).toList(),
            fields, teamSchedule, schedule);
    }

    public League withDivisionReplaced(UUID id, Division replacement) {
        return new League(version, config,
            divisions.stream().map(d -> d.id().equals(id) ? replacement : d).toList(),
            fields, teamSchedule, schedule);
    }

    public League withDivisionRemoved(UUID id) {
        return new League(version, config,
            divisions.stream().filter(d -> !d.id().equals(id)).toList(),
            fields, teamSchedule, schedule);
    }

    // --- Field queries ---

    public Optional<Field> findField(String name) {
        return fields.stream()
            .filter(f -> f.name().equalsIgnoreCase(name))
            .findFirst();
    }

    public boolean hasField(String name) {
        return findField(name).isPresent();
    }

    // --- Field mutations ---

    public League withFieldAdded(Field field) {
        return new League(version, config, divisions,
            Stream.concat(fields.stream(), Stream.of(field)).toList(), teamSchedule, schedule);
    }

    public League withFieldReplaced(UUID id, Field replacement) {
        return new League(version, config, divisions,
            fields.stream().map(f -> f.id().equals(id) ? replacement : f).toList(),
            teamSchedule, schedule);
    }

    public League withFieldRemoved(UUID id) {
        return new League(version, config, divisions,
            fields.stream().filter(f -> !f.id().equals(id)).toList(), teamSchedule, schedule);
    }

    // --- TeamSchedule mutations ---

    public League withTeamSchedule(TeamSchedule newTeamSchedule) {
        return new League(version, config, divisions, fields, newTeamSchedule, schedule);
    }

    /** Discards both the team schedule and any draft/finalized schedule. Used when re-running Phase 1. */
    public League withTeamScheduleCleared() {
        return new League(version, config, divisions, fields, null, null);
    }

    // --- Schedule mutations ---

    public League withSchedule(Schedule newSchedule) {
        return new League(version, config, divisions, fields, teamSchedule, newSchedule);
    }
}
