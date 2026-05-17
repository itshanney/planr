package org.leagueplan.planr.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DivisionTest {

    private static final UUID DIV_ID  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TEAM_A  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM_B  = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private Team blueJays;
    private Division division;

    @BeforeEach
    void setUp() {
        blueJays = new Team(TEAM_A, "Blue Jays");
        division = new Division(DIV_ID, "Majors", 120, 0, List.of(blueJays));
    }

    // --- findTeam ---

    @Test
    @DisplayName("findTeam returns the team on an exact name match")
    void findTeam_returnsMatchForExactName() {
        var result = division.findTeam("Blue Jays");
        assertTrue(result.isPresent());
        assertEquals(TEAM_A, result.get().id());
    }

    @Test
    @DisplayName("findTeam is case-insensitive")
    void findTeam_isCaseInsensitive() {
        assertTrue(division.findTeam("blue jays").isPresent());
        assertTrue(division.findTeam("BLUE JAYS").isPresent());
        assertTrue(division.findTeam("Blue JAYS").isPresent());
    }

    @Test
    @DisplayName("findTeam returns empty for an unknown name")
    void findTeam_returnsEmptyForUnknownName() {
        assertTrue(division.findTeam("Cardinals").isEmpty());
    }

    // --- hasTeam ---

    @Test
    @DisplayName("hasTeam returns true when the team exists")
    void hasTeam_trueForExistingTeam() {
        assertTrue(division.hasTeam("Blue Jays"));
    }

    @Test
    @DisplayName("hasTeam returns false when the team does not exist")
    void hasTeam_falseForMissingTeam() {
        assertFalse(division.hasTeam("Cardinals"));
    }

    // --- withTeamAdded ---

    @Test
    @DisplayName("withTeamAdded appends the team and preserves existing ones")
    void withTeamAdded_appendsTeam() {
        Team cardinals = new Team(TEAM_B, "Cardinals");
        Division updated = division.withTeamAdded(cardinals);
        assertEquals(2, updated.teams().size());
        assertTrue(updated.hasTeam("Blue Jays"));
        assertTrue(updated.hasTeam("Cardinals"));
    }

    @Test
    @DisplayName("withTeamAdded does not mutate the original division")
    void withTeamAdded_isImmutable() {
        division.withTeamAdded(new Team(TEAM_B, "Cardinals"));
        assertEquals(1, division.teams().size());
    }

    @Test
    @DisplayName("withTeamAdded preserves division metadata")
    void withTeamAdded_preservesDivisionMetadata() {
        Division updated = division.withTeamAdded(new Team(TEAM_B, "Cardinals"));
        assertEquals(DIV_ID, updated.id());
        assertEquals("Majors", updated.name());
        assertEquals(120, updated.gameDurationMinutes());
    }

    // --- withTeamReplaced ---

    @Test
    @DisplayName("withTeamReplaced swaps only the targeted team")
    void withTeamReplaced_replacesTarget() {
        Team royals = new Team(TEAM_A, "Royals");
        Division updated = division.withTeamReplaced(TEAM_A, royals);
        assertEquals(1, updated.teams().size());
        assertEquals("Royals", updated.teams().get(0).name());
        assertEquals(TEAM_A, updated.teams().get(0).id());
    }

    @Test
    @DisplayName("withTeamReplaced leaves untargeted teams unchanged")
    void withTeamReplaced_preservesOtherTeams() {
        Team cardinals = new Team(TEAM_B, "Cardinals");
        Division twoTeams = division.withTeamAdded(cardinals);
        Division updated = twoTeams.withTeamReplaced(TEAM_A, new Team(TEAM_A, "Royals"));
        assertEquals(2, updated.teams().size());
        assertTrue(updated.hasTeam("Cardinals"));
        assertTrue(updated.hasTeam("Royals"));
        assertFalse(updated.hasTeam("Blue Jays"));
    }

    // --- withTeamRemoved ---

    @Test
    @DisplayName("withTeamRemoved removes the targeted team")
    void withTeamRemoved_removesTarget() {
        Division updated = division.withTeamRemoved(TEAM_A);
        assertTrue(updated.teams().isEmpty());
    }

    @Test
    @DisplayName("withTeamRemoved leaves untargeted teams unchanged")
    void withTeamRemoved_preservesOtherTeams() {
        Team cardinals = new Team(TEAM_B, "Cardinals");
        Division twoTeams = division.withTeamAdded(cardinals);
        Division updated = twoTeams.withTeamRemoved(TEAM_A);
        assertEquals(1, updated.teams().size());
        assertTrue(updated.hasTeam("Cardinals"));
    }

    @Test
    @DisplayName("withTeamRemoved preserves division metadata")
    void withTeamRemoved_preservesDivisionMetadata() {
        Division updated = division.withTeamRemoved(TEAM_A);
        assertEquals(DIV_ID, updated.id());
        assertEquals("Majors", updated.name());
        assertEquals(120, updated.gameDurationMinutes());
    }
}
