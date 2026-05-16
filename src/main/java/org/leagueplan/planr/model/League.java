package org.leagueplan.planr.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public record League(int version, List<Division> divisions) {

    private static final int CURRENT_VERSION = 1;

    public static League empty() {
        return new League(CURRENT_VERSION, List.of());
    }

    public Optional<Division> findDivision(String name) {
        return divisions.stream()
            .filter(d -> d.name().equalsIgnoreCase(name))
            .findFirst();
    }

    public boolean hasDivision(String name) {
        return findDivision(name).isPresent();
    }

    public League withDivisionAdded(Division division) {
        return new League(version, Stream.concat(divisions.stream(), Stream.of(division)).toList());
    }

    public League withDivisionReplaced(UUID id, Division replacement) {
        return new League(version, divisions.stream()
            .map(d -> d.id().equals(id) ? replacement : d)
            .toList());
    }

    public League withDivisionRemoved(UUID id) {
        return new League(version, divisions.stream()
            .filter(d -> !d.id().equals(id))
            .toList());
    }
}
