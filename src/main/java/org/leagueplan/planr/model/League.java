package org.leagueplan.planr.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public record League(int version, LeagueConfig config, List<Division> divisions, List<Field> fields, Schedule schedule) {

    private static final int CURRENT_VERSION = 4;

    public League {
        divisions = (divisions == null) ? List.of() : divisions;
        fields = (fields == null) ? List.of() : fields;
    }

    public static League empty() {
        return new League(CURRENT_VERSION, LeagueConfig.empty(), List.of(), List.of(), null);
    }

    // --- Config mutations ---

    public League withConfig(LeagueConfig newConfig) {
        return new League(version, newConfig, divisions, fields, schedule);
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
            fields, schedule);
    }

    public League withDivisionReplaced(UUID id, Division replacement) {
        return new League(version, config,
            divisions.stream().map(d -> d.id().equals(id) ? replacement : d).toList(),
            fields, schedule);
    }

    public League withDivisionRemoved(UUID id) {
        return new League(version, config,
            divisions.stream().filter(d -> !d.id().equals(id)).toList(),
            fields, schedule);
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
            Stream.concat(fields.stream(), Stream.of(field)).toList(), schedule);
    }

    public League withFieldReplaced(UUID id, Field replacement) {
        return new League(version, config, divisions,
            fields.stream().map(f -> f.id().equals(id) ? replacement : f).toList(), schedule);
    }

    public League withFieldRemoved(UUID id) {
        return new League(version, config, divisions,
            fields.stream().filter(f -> !f.id().equals(id)).toList(), schedule);
    }

    // --- Schedule mutations ---

    public League withSchedule(Schedule newSchedule) {
        return new League(version, config, divisions, fields, newSchedule);
    }
}
