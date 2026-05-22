package org.leagueplan.planr.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LeagueTest {

    private static final UUID DIV_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private Division majors;
    private League league;

    @BeforeEach
    void setUp() {
        majors = new Division(DIV_ID, "Majors", 120, 0, List.of());
        league = new League(1, null, List.of(majors), List.of(), null, null);
    }

    // --- empty() ---

    @Test
    @DisplayName("empty() returns current version with no divisions or fields")
    void empty_hasCurrentVersionAndNoContent() {
        League empty = League.empty();
        assertEquals(4, empty.version());
        assertTrue(empty.divisions().isEmpty());
        assertTrue(empty.fields().isEmpty());
    }

    // --- findDivision ---

    @Test
    @DisplayName("findDivision returns the division on an exact name match")
    void findDivision_returnsMatchForExactName() {
        var result = league.findDivision("Majors");
        assertTrue(result.isPresent());
        assertEquals(DIV_ID, result.get().id());
    }

    @Test
    @DisplayName("findDivision is case-insensitive")
    void findDivision_isCaseInsensitive() {
        assertTrue(league.findDivision("majors").isPresent());
        assertTrue(league.findDivision("MAJORS").isPresent());
        assertTrue(league.findDivision("MaJoRs").isPresent());
    }

    @Test
    @DisplayName("findDivision returns empty for an unknown name")
    void findDivision_returnsEmptyForUnknownName() {
        assertTrue(league.findDivision("NonExistent").isEmpty());
    }

    // --- hasDivision ---

    @Test
    @DisplayName("hasDivision returns true for an existing division")
    void hasDivision_trueForExistingDivision() {
        assertTrue(league.hasDivision("Majors"));
    }

    @Test
    @DisplayName("hasDivision returns false for a missing division")
    void hasDivision_falseForMissingDivision() {
        assertFalse(league.hasDivision("Coast"));
    }

    // --- withDivisionAdded ---

    @Test
    @DisplayName("withDivisionAdded appends the division and preserves existing ones")
    void withDivisionAdded_appendsDivision() {
        Division aaa = new Division(UUID.randomUUID(), "AAA", 90, 0, List.of());
        League updated = league.withDivisionAdded(aaa);
        assertEquals(2, updated.divisions().size());
        assertTrue(updated.hasDivision("Majors"));
        assertTrue(updated.hasDivision("AAA"));
    }

    @Test
    @DisplayName("withDivisionAdded does not mutate the original league")
    void withDivisionAdded_isImmutable() {
        league.withDivisionAdded(new Division(UUID.randomUUID(), "AAA", 90, 0, List.of()));
        assertEquals(1, league.divisions().size());
    }

    @Test
    @DisplayName("withDivisionAdded preserves the version number")
    void withDivisionAdded_preservesVersion() {
        League updated = league.withDivisionAdded(new Division(UUID.randomUUID(), "AAA", 90, 0, List.of()));
        assertEquals(1, updated.version());
    }

    // --- withDivisionReplaced ---

    @Test
    @DisplayName("withDivisionReplaced swaps only the targeted division")
    void withDivisionReplaced_replacesTarget() {
        Division replacement = new Division(DIV_ID, "Majors-Updated", 150, 0, List.of());
        League updated = league.withDivisionReplaced(DIV_ID, replacement);
        assertEquals(1, updated.divisions().size());
        assertEquals("Majors-Updated", updated.divisions().get(0).name());
        assertEquals(150, updated.divisions().get(0).gameDurationMinutes());
    }

    @Test
    @DisplayName("withDivisionReplaced leaves untargeted divisions unchanged")
    void withDivisionReplaced_preservesOtherDivisions() {
        UUID aaaId = UUID.randomUUID();
        League twoDiv = league.withDivisionAdded(new Division(aaaId, "AAA", 90, 0, List.of()));
        League updated = twoDiv.withDivisionReplaced(DIV_ID, new Division(DIV_ID, "Majors-Updated", 150, 0, List.of()));
        assertEquals(2, updated.divisions().size());
        assertTrue(updated.hasDivision("AAA"));
        assertTrue(updated.hasDivision("Majors-Updated"));
        assertFalse(updated.hasDivision("Majors"));
    }

    // --- withDivisionRemoved ---

    @Test
    @DisplayName("withDivisionRemoved removes the targeted division by ID")
    void withDivisionRemoved_removesTarget() {
        League updated = league.withDivisionRemoved(DIV_ID);
        assertTrue(updated.divisions().isEmpty());
    }

    @Test
    @DisplayName("withDivisionRemoved leaves untargeted divisions unchanged")
    void withDivisionRemoved_preservesOtherDivisions() {
        UUID aaaId = UUID.randomUUID();
        League twoDiv = league.withDivisionAdded(new Division(aaaId, "AAA", 90, 0, List.of()));
        League updated = twoDiv.withDivisionRemoved(DIV_ID);
        assertEquals(1, updated.divisions().size());
        assertTrue(updated.hasDivision("AAA"));
    }

    @Test
    @DisplayName("withDivisionRemoved preserves the version number")
    void withDivisionRemoved_preservesVersion() {
        assertEquals(1, league.withDivisionRemoved(DIV_ID).version());
    }
}
