package org.leagueplan.planr.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.FieldBlock;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.TeamSchedule;
import org.leagueplan.planr.model.LeagueConfig;
import org.leagueplan.planr.model.Playoff;
import org.leagueplan.planr.model.PlayoffGame;
import org.leagueplan.planr.model.PlayoffState;
import org.leagueplan.planr.model.Team;

/**
 * Tests for playoff field priority (Field.playoffPriority) in SchedulerService.assignPlayoffs().
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC 17: When ranked fields have sufficient capacity, all playoff games go to ranked fields
 *   <li>AC 18: When ranked fields are insufficient, solver falls back to unranked fields
 *   <li>AC 19: assignPlayoffs() result includes a per-field summary (PlayoffFieldSummary)
 *   <li>AC 20: Fields with equal priority rank are treated equivalently
 *   <li>AC 21: Regular-season assign() is unaffected by playoff priority
 * </ul>
 *
 * <p>All tests use a 2-team double-elimination playoff (3 real game slots) with a narrow date
 * window so the CP-SAT solver finishes in milliseconds.
 */
class SchedulerServiceFieldPriorityTest {

  private static final LocalTime OPEN = LocalTime.of(9, 0);
  private static final LocalTime CLOSE = LocalTime.of(18, 0);
  // 3-day window: enough capacity for a 2-team bracket (3 real games) on a single field
  private static final LocalDate START = LocalDate.of(2026, 6, 1);
  private static final LocalDate END = LocalDate.of(2026, 6, 5);

  private final SchedulerService service = new SchedulerService();

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Team team(String name) {
    return new Team(UUID.randomUUID(), name);
  }

  private static Division division(String name, int gameDuration) {
    return new Division(
        UUID.randomUUID(),
        name,
        gameDuration,
        2,
        List.of(team("Blue Jays"), team("Cardinals")),
        null,
        null,
        null,
        null,
        null);
  }

  private static Field field(String name, Integer playoffPriority) {
    return new Field(UUID.randomUUID(), name, null, List.of(), List.of(), List.of(), playoffPriority);
  }

  /**
   * A field that is blocked for every day in the playoff window except one slot. Having only ~1
   * available slot ensures the ranked field's capacity is exhausted after 1 game, forcing fallback.
   */
  private static Field fieldBlockedExceptFirstSlot(String name, Integer playoffPriority) {
    // Block from 09:30 onward each day, leaving only 09:00 as the sole slot for a 60-min game
    List<FieldBlock> blocks = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      blocks.add(
          new FieldBlock(UUID.randomUUID(), START.plusDays(i), LocalTime.of(9, 30), CLOSE));
    }
    return new Field(UUID.randomUUID(), name, null, blocks, List.of(), List.of(), playoffPriority);
  }

  private static LeagueConfig config() {
    return new LeagueConfig(OPEN, CLOSE, START, END, List.of(), List.of(), null, null, null, 30);
  }

  /**
   * Builds a 2-team double-elimination playoff using PlayoffBracketService.
   *
   * <p>A 2-team bracket produces: 1 W-R1 game, 1 Championship final, 1 conditional Championship
   * game — 3 real game slots total (0 byes).
   */
  private static Playoff twoTeamPlayoff(UUID divisionId) {
    List<String> seeds = List.of("Blue Jays", "Cardinals");
    List<PlayoffBracketService.BracketSlot> bracketSlots =
        new PlayoffBracketService().generateBracket(seeds);
    List<PlayoffGame> games =
        bracketSlots.stream().map(PlayoffBracketService::toPlayoffGame).toList();
    return new Playoff(divisionId, START, END, PlayoffState.GENERATED, games);
  }

  private static League leagueFor(Division division, Field... fields) {
    LeagueConfig cfg = config();
    Playoff playoff = twoTeamPlayoff(division.id());
    return new League(
        10, cfg, List.of(division), List.of(fields), null, null, List.of(playoff), List.of());
  }

  // ---------------------------------------------------------------------------
  // Field summary presence and content (AC 19)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("PlayoffFieldSummary is populated in the result")
  class FieldSummaryPresence {

    @Test
    @DisplayName("result contains a non-empty field summary when games are assigned")
    void resultContainsFieldSummaryWhenGamesAssigned() {
      Division div = division("Majors", 60);
      League league = leagueFor(div, field("Riverside", null));

      PlayoffScheduleResult result =
          service.assignPlayoffs(league, league.playoffs());

      assertInstanceOf(PlayoffScheduleResult.Success.class, result);
      PlayoffScheduleResult.Success success = (PlayoffScheduleResult.Success) result;
      assertFalse(
          success.fieldSummaries().isEmpty(),
          "at least one field should appear in the field summary");
    }

    @Test
    @DisplayName("field summary entry has the correct field name and a non-zero game count")
    void fieldSummaryEntryHasCorrectNameAndGameCount() {
      Division div = division("Majors", 60);
      Field f = field("Riverside", null);
      League league = leagueFor(div, f);

      PlayoffScheduleResult.Success success =
          (PlayoffScheduleResult.Success) service.assignPlayoffs(league, league.playoffs());

      PlayoffFieldSummary summary = success.fieldSummaries().get(0);
      assertEquals("Riverside", summary.fieldName());
      assertTrue(summary.gamesAssigned() > 0);
    }

    @Test
    @DisplayName("field summary marks an unranked field with null playoff priority")
    void unrankedFieldHasNullPriorityInSummary() {
      Division div = division("Majors", 60);
      League league = leagueFor(div, field("Riverside", null));

      PlayoffScheduleResult.Success success =
          (PlayoffScheduleResult.Success) service.assignPlayoffs(league, league.playoffs());

      PlayoffFieldSummary riverside =
          success.fieldSummaries().stream()
              .filter(s -> s.fieldName().equals("Riverside"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("Riverside not in field summary"));
      assertNull(riverside.playoffPriority(), "unranked field should have null playoffPriority");
    }

    @Test
    @DisplayName("field summary marks a ranked field with its configured priority value")
    void rankedFieldHasConfiguredPriorityInSummary() {
      Division div = division("Majors", 60);
      League league = leagueFor(div, field("Westfield", 1));

      PlayoffScheduleResult.Success success =
          (PlayoffScheduleResult.Success) service.assignPlayoffs(league, league.playoffs());

      PlayoffFieldSummary westfield =
          success.fieldSummaries().stream()
              .filter(s -> s.fieldName().equals("Westfield"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("Westfield not in field summary"));
      assertEquals(1, westfield.playoffPriority());
    }

    @Test
    @DisplayName("field summary is sorted by priority rank ascending, with nulls last")
    void fieldSummaryIsSortedByPriorityNullsLast() {
      Division div = division("Majors", 60);
      // 3 fields: priority 2, priority 1, unranked — solver gets both games on P1/P2
      Field p1 = field("Alpha P1", 1);
      Field p2 = field("Beta P2", 2);
      Field unranked = field("Gamma Unranked", null);
      League league = leagueFor(div, p1, p2, unranked);

      PlayoffScheduleResult.Success success =
          (PlayoffScheduleResult.Success) service.assignPlayoffs(league, league.playoffs());

      List<PlayoffFieldSummary> summaries = success.fieldSummaries();

      // Only fields that received games appear. With priority preference, games should go to ranked
      // fields first. Just verify the ordering is correct for whichever fields appear.
      for (int i = 0; i < summaries.size() - 1; i++) {
        Integer current = summaries.get(i).playoffPriority();
        Integer next = summaries.get(i + 1).playoffPriority();
        if (current != null && next != null) {
          assertTrue(
              current <= next,
              "ranked fields should be sorted ascending: found " + current + " before " + next);
        } else if (current == null) {
          // null (unranked) should only appear at the end
          fail("unranked field at position " + i + " precedes another field — should be last");
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Ranked fields preferred when capacity is sufficient (AC 17)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ranked fields are preferred over unranked when both have capacity (AC 17)")
  class RankedFieldPreferred {

    @Test
    @DisplayName("all games go to the ranked field when it has sufficient capacity")
    void allGamesGoToRankedFieldWhenCapacitySufficient() {
      Division div = division("Majors", 60);
      Field ranked = field("Premier Field", 1);
      Field unranked = field("Secondary Field", null);
      League league = leagueFor(div, ranked, unranked);

      PlayoffScheduleResult.Success success =
          (PlayoffScheduleResult.Success) service.assignPlayoffs(league, league.playoffs());

      Map<UUID, Slot> assignments = success.assignmentsByGameId();
      assertFalse(assignments.isEmpty(), "some games should be assigned");

      // Every assigned game should be on the ranked field
      for (Slot slot : assignments.values()) {
        assertEquals(
            ranked.id(),
            slot.fieldId(),
            "game on field " + slot.fieldName() + " should be on Premier Field (ranked P1)");
      }
    }

    @Test
    @DisplayName("ranked field appears in summary; unranked field does not when ranked has capacity")
    void unrankedFieldNotInSummaryWhenRankedHasCapacity() {
      Division div = division("Majors", 60);
      Field ranked = field("Premier Field", 1);
      Field unranked = field("Secondary Field", null);
      League league = leagueFor(div, ranked, unranked);

      PlayoffScheduleResult.Success success =
          (PlayoffScheduleResult.Success) service.assignPlayoffs(league, league.playoffs());

      boolean unrankedUsed =
          success.fieldSummaries().stream()
              .anyMatch(s -> s.fieldName().equals("Secondary Field"));
      assertFalse(
          unrankedUsed, "unranked field should not appear in summary when ranked has capacity");
    }

    @Test
    @DisplayName("higher-priority field (rank 1) is preferred over lower-priority field (rank 2)")
    void rank1IsPreferredOverRank2() {
      Division div = division("Majors", 60);
      Field p1 = field("Premier Field", 1);
      Field p2 = field("Secondary Field", 2);
      League league = leagueFor(div, p1, p2);

      PlayoffScheduleResult.Success success =
          (PlayoffScheduleResult.Success) service.assignPlayoffs(league, league.playoffs());

      Map<UUID, Slot> assignments = success.assignmentsByGameId();
      assertFalse(assignments.isEmpty());

      // All games should prefer the rank-1 field
      for (Slot slot : assignments.values()) {
        assertEquals(
            p1.id(),
            slot.fieldId(),
            "game assigned to " + slot.fieldName() + " should be on rank-1 field");
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Fallback to unranked when ranked capacity exhausted (AC 18)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("falls back to unranked fields when ranked capacity is exhausted (AC 18)")
  class FallbackToUnranked {

    @Test
    @DisplayName("solver assigns some games to unranked field when ranked field has only 1 slot")
    void solverUsesUnrankedWhenRankedIsAtCapacity() {
      Division div = division("Majors", 60);
      // Ranked field blocked heavily — only 1 slot available total
      Field ranked = fieldBlockedExceptFirstSlot("Premier Field", 1);
      // Unranked field is wide open
      Field unranked = field("Secondary Field", null);
      League league = leagueFor(div, ranked, unranked);

      PlayoffScheduleResult.Success success =
          (PlayoffScheduleResult.Success) service.assignPlayoffs(league, league.playoffs());

      Map<UUID, Slot> assignments = success.assignmentsByGameId();
      assertFalse(assignments.isEmpty(), "some games should be assigned");

      long gamesOnUnranked =
          assignments.values().stream()
              .filter(s -> s.fieldId().equals(unranked.id()))
              .count();
      assertTrue(
          gamesOnUnranked > 0,
          "at least one game must fall back to the unranked field when ranked is at capacity");
    }

    @Test
    @DisplayName("solver does not fail when ranked field is at capacity and unranked has room")
    void solverSucceedsWhenRankedAtCapacityAndUnrankedHasRoom() {
      Division div = division("Majors", 60);
      Field ranked = fieldBlockedExceptFirstSlot("Premier Field", 1);
      Field unranked = field("Secondary Field", null);
      League league = leagueFor(div, ranked, unranked);

      PlayoffScheduleResult result = service.assignPlayoffs(league, league.playoffs());

      assertInstanceOf(
          PlayoffScheduleResult.Success.class,
          result,
          "solver should succeed with fallback to unranked — not return Failure");
    }
  }

  // ---------------------------------------------------------------------------
  // Fields with equal rank treated equivalently (AC 20)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("fields with equal rank are treated equivalently (AC 20)")
  class EqualRankTreatment {

    @Test
    @DisplayName("games are distributed between two rank-1 fields without error")
    void twoRank1FieldsAreUsedWithoutError() {
      Division div = division("Majors", 60);
      // Both fields have the same rank — solver can use either
      Field f1 = field("Alpha", 1);
      Field f2 = field("Beta", 1);
      League league = leagueFor(div, f1, f2);

      PlayoffScheduleResult result = service.assignPlayoffs(league, league.playoffs());

      assertInstanceOf(PlayoffScheduleResult.Success.class, result);
      PlayoffScheduleResult.Success success = (PlayoffScheduleResult.Success) result;
      assertFalse(success.assignmentsByGameId().isEmpty());
    }

    @Test
    @DisplayName("no unranked fields appear in the summary when all capacity comes from equal-rank fields")
    void noUnrankedInSummaryWhenAllCapacityIsRanked() {
      Division div = division("Majors", 60);
      Field f1 = field("Alpha", 1);
      Field f2 = field("Beta", 1);
      Field unranked = field("Unranked", null);
      League league = leagueFor(div, f1, f2, unranked);

      PlayoffScheduleResult.Success success =
          (PlayoffScheduleResult.Success) service.assignPlayoffs(league, league.playoffs());

      boolean unrankedUsed =
          success.fieldSummaries().stream()
              .anyMatch(s -> s.fieldName().equals("Unranked"));
      assertFalse(
          unrankedUsed,
          "unranked field should not receive games when two equal-rank fields have sufficient capacity");
    }
  }

  // ---------------------------------------------------------------------------
  // No ranked fields — solver behaves as before (AC 21 / backward compat)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("no ranked fields — solver works normally")
  class NoRankedFields {

    @Test
    @DisplayName("assignPlayoffs succeeds and assigns games when all fields are unranked")
    void solverSucceedsWhenAllFieldsUnranked() {
      Division div = division("Majors", 60);
      League league = leagueFor(div, field("Field A", null), field("Field B", null));

      PlayoffScheduleResult result = service.assignPlayoffs(league, league.playoffs());

      assertInstanceOf(PlayoffScheduleResult.Success.class, result);
      assertFalse(
          ((PlayoffScheduleResult.Success) result).assignmentsByGameId().isEmpty());
    }

    @Test
    @DisplayName("field summaries still populated when no field has a priority rank")
    void fieldSummaryPopulatedWhenAllUnranked() {
      Division div = division("Majors", 60);
      League league = leagueFor(div, field("Field A", null));

      PlayoffScheduleResult.Success success =
          (PlayoffScheduleResult.Success) service.assignPlayoffs(league, league.playoffs());

      assertFalse(success.fieldSummaries().isEmpty());
      success.fieldSummaries().forEach(s -> assertNull(s.playoffPriority()));
    }
  }

  // ---------------------------------------------------------------------------
  // Regular-season assign is unaffected by playoff priority (AC 21)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("playoff priority does not affect regular-season assign() (AC 21)")
  class RegularSeasonUnaffected {

    @Test
    @DisplayName("assign() produces the same game count whether or not fields have playoff priority")
    void regularSeasonAssignIsIdenticalWithOrWithoutPriority() {
      Division div = division("Majors", 60);
      LeagueConfig cfg = config();

      // Build two identical leagues: one field with priority 1, one without
      Field ranked = field("Field A", 1);
      Field unranked = new Field(
          ranked.id(), // same UUID for same identity
          ranked.name(),
          ranked.address(),
          ranked.blocks(),
          ranked.dateOverrides(),
          ranked.divisionLocks(),
          null); // strip the priority

      League baseRanked =
          new League(10, cfg, List.of(div), List.of(ranked), null, null, List.of(), List.of());

      TeamSchedule ts =
          ((TeamScheduleResult.Success) new TeamScheduleService().generate(baseRanked)).schedule();

      League leagueRanked =
          new League(10, cfg, List.of(div), List.of(ranked), ts, null, List.of(), List.of());
      League leagueUnranked =
          new League(10, cfg, List.of(div), List.of(unranked), ts, null, List.of(), List.of());

      ScheduleResult resultRanked = service.assign(leagueRanked);
      ScheduleResult resultUnranked = service.assign(leagueUnranked);

      assertInstanceOf(ScheduleResult.Success.class, resultRanked);
      assertInstanceOf(ScheduleResult.Success.class, resultUnranked);

      int countRanked = ((ScheduleResult.Success) resultRanked).games().size();
      int countUnranked = ((ScheduleResult.Success) resultUnranked).games().size();

      assertEquals(
          countRanked,
          countUnranked,
          "playoff priority must not change the number of regular-season games assigned");
    }
  }
}
