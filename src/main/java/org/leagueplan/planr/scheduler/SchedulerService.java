package org.leagueplan.planr.scheduler;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverSolutionCallback;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.Literal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.leagueplan.planr.model.DayOfWeekWindow;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.FieldBlock;
import org.leagueplan.planr.model.FieldDateOverride;
import org.leagueplan.planr.model.FieldDivisionLock;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import org.leagueplan.planr.model.Playoff;
import org.leagueplan.planr.model.PlayoffGame;
import org.leagueplan.planr.model.PracticeSchedule;
import org.leagueplan.planr.model.PracticeSlot;
import org.leagueplan.planr.model.ScheduledGame;
import org.leagueplan.planr.model.TeamGame;
import org.leagueplan.planr.model.TeamSchedule;

public class SchedulerService {

  public static final int DEFAULT_MAX_GAMES_PER_WEEK = 2;
  public static final int DEFAULT_MIN_REST_DAYS = 1;
  public static final int DEFAULT_FIELD_BUFFER_MINUTES = 0;
  public static final int DEFAULT_GRID_MINUTES = 30;

  private static final int SOLVER_TIME_LIMIT_SECONDS = 300;

  private record GameVar(BoolVar var, Fixture fixture, Slot slot) {}

  /**
   * Phase 2: assigns dates, times, and fields to every game in the confirmed team schedule. Saves a
   * partial Draft when insufficient slots prevent full assignment.
   */
  public ScheduleResult assign(League league) {
    if (league.teamSchedule() == null || league.teamSchedule().games().isEmpty()) {
      return ScheduleResult.failure(
          "Error: No team schedule found. Run 'planr schedule generate' first.");
    }

    LeagueConfig config = league.config();
    if (config == null || config.seasonStart() == null || config.seasonEnd() == null) {
      return ScheduleResult.failure(
          "Error: Season start and end dates must be configured. "
              + "Run 'planr config set --start <date> --end <date>'.");
    }

    Loader.loadNativeLibraries();

    long startMs = System.currentTimeMillis();

    Map<UUID, List<Fixture>> fixturesByDiv = toFixturesByDiv(league.teamSchedule());
    Map<UUID, List<Slot>> slotsByDiv =
        enumerateAllSlots(league, config.seasonStart(), config.seasonEnd());
    Map<UUID, Integer> slotCounts = computeSlotCounts(slotsByDiv);

    emitFeasibilityCheckLine(startMs, fixturesByDiv, slotCounts, league);

    return buildAndSolve(league, fixturesByDiv, slotsByDiv, slotCounts, startMs);
  }

  // --- Fixture conversion ---

  private Map<UUID, List<Fixture>> toFixturesByDiv(TeamSchedule teamSchedule) {
    Map<UUID, List<Fixture>> result = new LinkedHashMap<>();
    for (TeamGame game : teamSchedule.games()) {
      result
          .computeIfAbsent(game.divisionId(), k -> new ArrayList<>())
          .add(
              new Fixture(
                  game.id(),
                  game.homeTeamId(),
                  game.awayTeamId(),
                  game.divisionId(),
                  game.gameDurationMinutes()));
    }
    return result;
  }

  // --- Slot estimation (used by the assign command for the pre-confirm feasibility warning) ---

  /**
   * Returns an upper-bound estimate of how many games could fit in the season window for the given
   * division. Walks every date in the configured season, subtracts field blocks, and counts slots
   * using the same grid as the solver. Applies the division's curfew if set. Does not invoke the
   * solver.
   */
  public int estimateAvailableSlots(League league, UUID divisionId, int gameDurationMinutes) {
    LeagueConfig config = league.config();
    if (config == null
        || config.seasonStart() == null
        || config.seasonEnd() == null
        || config.sunriseTime() == null
        || config.sunsetTime() == null) {
      return 0;
    }

    int bufferMinutes =
        (config.fieldBufferMinutes() != null)
            ? config.fieldBufferMinutes()
            : DEFAULT_FIELD_BUFFER_MINUTES;
    int gridMinutes = (config.gridMinutes() != null) ? config.gridMinutes() : DEFAULT_GRID_MINUTES;

    LocalTime curfew = divisionCurfew(league, divisionId);

    int total = 0;
    for (LocalDate date = config.seasonStart();
        !date.isAfter(config.seasonEnd());
        date = date.plusDays(1)) {

      for (Field field : league.fields()) {
        if (isFieldLockedToOtherDivision(field, date, divisionId)) continue;
        if (isDivisionPinnedElsewhere(league, field, date, divisionId)) continue;
        LocalTime[] openWindow = resolveOpenWindow(config, field, date);
        if (openWindow == null) continue;

        final LocalDate currentDate = date;
        List<FieldBlock> dateBlocks =
            field.blocks().stream().filter(b -> b.date().equals(currentDate)).toList();

        for (LocalTime[] window : subtractBlocks(openWindow[0], openWindow[1], dateBlocks)) {
          LocalTime slotStart = window[0];
          while (!slotStart.plusMinutes(gameDurationMinutes + bufferMinutes).isAfter(window[1])) {
            if (curfew != null && slotStart.isAfter(curfew)) break;
            total++;
            slotStart = slotStart.plusMinutes(gridMinutes);
          }
        }
      }
    }
    return total;
  }

  // --- Slot enumeration ---

  private Map<UUID, List<Slot>> enumerateAllSlots(League league, LocalDate start, LocalDate end) {
    Map<UUID, List<Slot>> slotsByDiv = new HashMap<>();
    for (Division div : league.divisions()) {
      if (div.teams().size() >= 2) {
        slotsByDiv.put(div.id(), new ArrayList<>());
      }
    }
    if (slotsByDiv.isEmpty()) return slotsByDiv;

    LeagueConfig config = league.config();
    if (config == null || config.sunriseTime() == null || config.sunsetTime() == null) {
      return slotsByDiv;
    }

    int gridMinutes = (config.gridMinutes() != null) ? config.gridMinutes() : DEFAULT_GRID_MINUTES;

    for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
      final LocalDate currentDate = date;
      for (Field field : league.fields()) {
        LocalTime[] openWindow = resolveOpenWindow(config, field, currentDate);
        if (openWindow == null) continue;

        List<FieldBlock> dateBlocks =
            field.blocks().stream().filter(b -> b.date().equals(currentDate)).toList();

        List<LocalTime[]> available = subtractBlocks(openWindow[0], openWindow[1], dateBlocks);

        for (UUID divId : slotsByDiv.keySet()) {
          if (isFieldLockedToOtherDivision(field, currentDate, divId)) continue;
          if (isDivisionPinnedElsewhere(league, field, currentDate, divId)) continue;
          int duration = divisionDuration(league, divId);
          LocalTime curfew = divisionCurfew(league, divId);
          for (LocalTime[] window : available) {
            LocalTime slotStart = window[0];
            while (!slotStart.plusMinutes(duration).isAfter(window[1])) {
              if (curfew != null && slotStart.isAfter(curfew)) break;
              slotsByDiv.get(divId).add(new Slot(currentDate, field.id(), field.name(), slotStart));
              slotStart = slotStart.plusMinutes(gridMinutes);
            }
          }
        }
      }
    }
    return slotsByDiv;
  }

  private boolean isFieldLockedToOtherDivision(Field field, LocalDate date, UUID divisionId) {
    for (FieldDivisionLock lock : field.divisionLocks()) {
      if (!lock.divisionId().equals(divisionId)
          && !date.isBefore(lock.startDate())
          && !date.isAfter(lock.endDate())) {
        return true;
      }
    }
    return false;
  }

  // When a division owns a lock on some other field for this date, it is pinned to that
  // field and must not receive slots from currentField. This enforces the bidirectional
  // lock contract: the owning division plays only on its locked field during the lock period.
  private boolean isDivisionPinnedElsewhere(
      League league, Field currentField, LocalDate date, UUID divisionId) {
    return league.fields().stream()
        .filter(f -> !f.id().equals(currentField.id()))
        .anyMatch(
            f ->
                f.divisionLocks().stream()
                    .anyMatch(
                        lock ->
                            lock.divisionId().equals(divisionId)
                                && !date.isBefore(lock.startDate())
                                && !date.isAfter(lock.endDate())));
  }

  /**
   * Resolves the open time window for a specific field and date using the four-level precedence
   * rule: FieldDateOverride → blocked day → day-of-week window → global sunrise/sunset. Returns
   * null when the date is a blocked day with no field-level override (no slots possible).
   */
  private LocalTime[] resolveOpenWindow(LeagueConfig config, Field field, LocalDate date) {
    DayOfWeek dow = date.getDayOfWeek();

    // Priority 1: field-specific date override wins over everything, including blocked days.
    for (FieldDateOverride override : field.dateOverrides()) {
      if (override.date().equals(date)) {
        return new LocalTime[] {override.openStart(), override.openEnd()};
      }
    }

    // Priority 2: league-wide blocked day (no override present).
    if (config.blockedDays().contains(dow)) {
      return null;
    }

    // Priority 3: day-of-week window replaces global sunrise/sunset for this day.
    for (DayOfWeekWindow w : config.dowWindows()) {
      if (w.day() == dow) {
        return new LocalTime[] {w.openStart(), w.openEnd()};
      }
    }

    // Priority 4: global sunrise/sunset.
    return new LocalTime[] {config.sunriseTime(), config.sunsetTime()};
  }

  private Map<UUID, Integer> computeSlotCounts(Map<UUID, List<Slot>> slotsByDiv) {
    Map<UUID, Integer> counts = new HashMap<>();
    slotsByDiv.forEach((divId, slots) -> counts.put(divId, slots.size()));
    return counts;
  }

  private List<LocalTime[]> subtractBlocks(
      LocalTime openStart, LocalTime openEnd, List<FieldBlock> blocks) {
    List<LocalTime[]> available = new ArrayList<>();
    available.add(new LocalTime[] {openStart, openEnd});
    for (FieldBlock block : blocks) {
      List<LocalTime[]> remaining = new ArrayList<>();
      for (LocalTime[] window : available) {
        LocalTime wStart = window[0];
        LocalTime wEnd = window[1];
        boolean blockOverlaps = block.startTime().isBefore(wEnd) && block.endTime().isAfter(wStart);
        if (!blockOverlaps) {
          remaining.add(window);
          continue;
        }
        if (block.startTime().isAfter(wStart)) {
          remaining.add(new LocalTime[] {wStart, block.startTime()});
        }
        if (block.endTime().isBefore(wEnd)) {
          remaining.add(new LocalTime[] {block.endTime(), wEnd});
        }
      }
      available = remaining;
    }
    return available;
  }

  private int divisionDuration(League league, UUID divId) {
    return league.divisions().stream()
        .filter(d -> d.id().equals(divId))
        .findFirst()
        .map(Division::gameDurationMinutes)
        .orElseThrow();
  }

  private LocalTime divisionCurfew(League league, UUID divId) {
    return league.divisions().stream()
        .filter(d -> d.id().equals(divId))
        .findFirst()
        .map(Division::curfewTime)
        .orElse(null);
  }

  // --- Progress output ---

  private void emitFeasibilityCheckLine(
      long startMs,
      Map<UUID, List<Fixture>> fixturesByDiv,
      Map<UUID, Integer> slotCounts,
      League league) {
    int elapsed = (int) ((System.currentTimeMillis() - startMs) / 1000);

    List<String> deficits =
        fixturesByDiv.entrySet().stream()
            .filter(e -> slotCounts.getOrDefault(e.getKey(), 0) < e.getValue().size())
            .map(
                e ->
                    divisionName(league, e.getKey())
                        + " deficit ("
                        + e.getValue().size()
                        + " games, "
                        + slotCounts.getOrDefault(e.getKey(), 0)
                        + " slots)")
            .toList();

    if (deficits.isEmpty()) {
      int totalFixtures = fixturesByDiv.values().stream().mapToInt(List::size).sum();
      System.out.printf(
          "[%d:%02d] Feasibility check passed. Solver started. %d games across %d division(s).%n",
          elapsed / 60, elapsed % 60, totalFixtures, fixturesByDiv.size());
    } else {
      System.out.printf(
          "[%d:%02d] Feasibility check: %s. Solver started.%n",
          elapsed / 60, elapsed % 60, String.join(", ", deficits));
    }
    System.out.flush();
  }

  // --- CP-SAT model construction and solve ---

  private ScheduleResult buildAndSolve(
      League league,
      Map<UUID, List<Fixture>> fixturesByDiv,
      Map<UUID, List<Slot>> slotsByDiv,
      Map<UUID, Integer> slotCounts,
      long startMs) {

    // Pre-solve curfew feasibility guard: fail fast when a curfewed division has no slots.
    for (UUID divId : fixturesByDiv.keySet()) {
      if (slotsByDiv.getOrDefault(divId, List.of()).isEmpty()) {
        Division div = findDivisionById(league, divId);
        if (div != null && div.curfewTime() != null) {
          return ScheduleResult.failure(
              String.format(
                  "Error: Division \"%s\" has no available slots after applying curfew %s. "
                      + "Expand the season window or relax the curfew.",
                  div.name(), div.curfewTime()));
        }
      }
    }

    LeagueConfig config = league.config();
    int bufferMinutes =
        (config != null && config.fieldBufferMinutes() != null)
            ? config.fieldBufferMinutes()
            : DEFAULT_FIELD_BUFFER_MINUTES;
    int gridMinutes =
        (config != null && config.gridMinutes() != null)
            ? config.gridMinutes()
            : DEFAULT_GRID_MINUTES;

    CpModel model = new CpModel();

    List<GameVar> allGameVars = new ArrayList<>();
    Map<UUID, List<GameVar>> byFixture = new LinkedHashMap<>();
    // byFieldTick: key = "fieldId|date|startMinuteOfTick" → vars active at that tick
    // Used for C2 (field non-overlap). Bounded to numFields × numDays × ticksPerDay constraints,
    // avoiding the O(N²) pairwise explosion that occurs with a per-(field,date) grouping.
    Map<String, List<BoolVar>> byFieldTick = new HashMap<>();
    Map<String, List<GameVar>> byTeamDate = new HashMap<>();
    Map<String, List<GameVar>> byTeamWeek = new HashMap<>();

    int varIndex = 0;
    for (Map.Entry<UUID, List<Fixture>> divEntry : fixturesByDiv.entrySet()) {
      UUID divId = divEntry.getKey();
      List<Fixture> fixtures = divEntry.getValue();
      List<Slot> slots = slotsByDiv.getOrDefault(divId, List.of());

      for (Fixture fixture : fixtures) {
        byFixture.put(fixture.gameId(), new ArrayList<>());
        for (Slot slot : slots) {
          BoolVar var = model.newBoolVar("g" + varIndex++);
          GameVar gv = new GameVar(var, fixture, slot);
          allGameVars.add(gv);
          byFixture.get(fixture.gameId()).add(gv);

          // Register this game under every grid tick it would occupy (start through end-exclusive)
          int gameStart = slot.startMinutes();
          int gameEnd = gameStart + fixture.gameDurationMinutes() + bufferMinutes;
          String fieldDate = slot.fieldId() + "|" + slot.date() + "|";
          for (int t = gameStart; t < gameEnd; t += gridMinutes) {
            byFieldTick.computeIfAbsent(fieldDate + t, k -> new ArrayList<>()).add(var);
          }

          String homeKey = fixture.homeTeamId() + "|" + slot.date();
          String awayKey = fixture.awayTeamId() + "|" + slot.date();
          byTeamDate.computeIfAbsent(homeKey, k -> new ArrayList<>()).add(gv);
          byTeamDate.computeIfAbsent(awayKey, k -> new ArrayList<>()).add(gv);

          var weekFields = WeekFields.ISO;
          String weekKey =
              slot.date().get(weekFields.weekOfWeekBasedYear())
                  + "|"
                  + slot.date().get(weekFields.weekBasedYear());
          byTeamWeek
              .computeIfAbsent(fixture.homeTeamId() + "|" + weekKey, k -> new ArrayList<>())
              .add(gv);
          byTeamWeek
              .computeIfAbsent(fixture.awayTeamId() + "|" + weekKey, k -> new ArrayList<>())
              .add(gv);
        }
      }
    }

    // C1: Each fixture assigned at most once; isAssigned[f] tracks whether fixture f was placed
    Map<UUID, BoolVar> isAssigned = new LinkedHashMap<>();
    for (Map.Entry<UUID, List<GameVar>> entry : byFixture.entrySet()) {
      UUID fixtureId = entry.getKey();
      List<GameVar> vars = entry.getValue();
      Literal[] lits = vars.stream().map(gv -> (Literal) gv.var()).toArray(Literal[]::new);
      model.addAtMostOne(lits);

      BoolVar assigned = model.newBoolVar("a" + varIndex++);
      // assigned == sum(lits): since addAtMostOne ensures sum ∈ {0,1}, this equality holds
      model.addEquality(assigned, LinearExpr.sum(lits));
      isAssigned.put(fixtureId, assigned);
    }

    // C2: At each 15-min tick, at most one game may be active on a given field.
    // One constraint per (field, date, tick) — bounded by numFields × numDays × ticksPerDay.
    for (List<BoolVar> tickVars : byFieldTick.values()) {
      if (tickVars.size() > 1) {
        Literal[] lits = tickVars.stream().map(v -> (Literal) v).toArray(Literal[]::new);
        model.addAtMostOne(lits);
      }
    }

    // C3: No team plays more than once on the same calendar day
    for (List<GameVar> teamDayVars : byTeamDate.values()) {
      if (teamDayVars.size() > 1) {
        Literal[] lits = teamDayVars.stream().map(gv -> (Literal) gv.var()).toArray(Literal[]::new);
        model.addAtMostOne(lits);
      }
    }

    int totalFixtures = fixturesByDiv.values().stream().mapToInt(List::size).sum();

    // Track total assigned count as an IntVar for the objective
    Literal[] allAssignedLits =
        isAssigned.values().stream().map(v -> (Literal) v).toArray(Literal[]::new);
    IntVar totalAssignedVar = model.newIntVar(0, totalFixtures, "total_assigned");
    model.addEquality(totalAssignedVar, LinearExpr.sum(allAssignedLits));

    int weekCap =
        (config != null && config.maxGamesPerWeek() != null)
            ? config.maxGamesPerWeek()
            : DEFAULT_MAX_GAMES_PER_WEEK;
    int restDays =
        (config != null && config.minRestDays() != null)
            ? config.minRestDays()
            : DEFAULT_MIN_REST_DAYS;

    // C4: No team plays more than weekCap games in a single ISO week (hard cap).
    // maxWeekLoad is kept as a soft secondary objective to incentivize even distribution
    // within the cap.
    IntVar maxWeekLoad = model.newIntVar(0, weekCap, "max_week_load");
    for (List<GameVar> weekVars : byTeamWeek.values()) {
      if (!weekVars.isEmpty()) {
        Literal[] lits = weekVars.stream().map(gv -> (Literal) gv.var()).toArray(Literal[]::new);
        model.addLessOrEqual(LinearExpr.sum(lits), LinearExpr.constant(weekCap));
        model.addLessOrEqual(LinearExpr.sum(lits), maxWeekLoad);
      }
    }

    // C5: Each team must have at least restDays calendar days between any two games.
    // C3 already enforces the same-day case, so only r >= 1 is needed here.
    if (restDays > 0) {
      for (Map.Entry<String, List<GameVar>> entry : byTeamDate.entrySet()) {
        if (entry.getValue().isEmpty()) continue;
        String[] parts = entry.getKey().split("\\|", 2);
        String teamIdStr = parts[0];
        LocalDate date = LocalDate.parse(parts[1]);

        for (int r = 1; r <= restDays; r++) {
          List<GameVar> nextDayVars =
              byTeamDate.getOrDefault(teamIdStr + "|" + date.plusDays(r), List.of());
          if (nextDayVars.isEmpty()) continue;

          List<Literal> combined = new ArrayList<>();
          entry.getValue().forEach(gv -> combined.add(gv.var()));
          nextDayVars.forEach(gv -> combined.add(gv.var()));
          if (combined.size() > 1) {
            model.addAtMostOne(combined.toArray(Literal[]::new));
          }
        }
      }
    }

    // Objective: maximize assigned games (primary), then minimize week-load imbalance (secondary).
    // bigM > max(maxWeekLoad) guarantees lexicographic dominance ordering.
    long bigM = (long) totalFixtures + 1;
    model.maximize(
        LinearExpr.weightedSum(
            new IntVar[] {totalAssignedVar, maxWeekLoad}, new long[] {bigM, -1L}));

    CpSolver solver = new CpSolver();
    solver.getParameters().setMaxTimeInSeconds(SOLVER_TIME_LIMIT_SECONDS);
    ProgressCallback callback = new ProgressCallback(SOLVER_TIME_LIMIT_SECONDS);
    CpSolverStatus status = solver.solve(model, callback);

    if (status == CpSolverStatus.UNKNOWN) {
      return ScheduleResult.failure(
          "Error: Solver timed out without assigning any games. "
              + "Try extending the season or adding more field availability.");
    }
    if (status == CpSolverStatus.INFEASIBLE) {
      return ScheduleResult.failure(
          "Error: Solver returned an unexpected result. Please report this bug.");
    }

    // Collect assigned games and per-division assignment counts
    List<ScheduledGame> games = new ArrayList<>();
    Map<UUID, Long> assignedByDiv = new HashMap<>();
    for (GameVar gv : allGameVars) {
      if (solver.booleanValue(gv.var())) {
        games.add(buildScheduledGame(league, gv.fixture(), gv.slot()));
        assignedByDiv.merge(gv.fixture().divisionId(), 1L, Long::sum);
      }
    }
    games.sort(
        Comparator.comparing(ScheduledGame::date)
            .thenComparing(ScheduledGame::startTime)
            .thenComparing(ScheduledGame::fieldName));

    // Emit solver-complete progress line
    int elapsed = (int) ((System.currentTimeMillis() - startMs) / 1000);
    String completionStatus =
        (games.size() == totalFixtures ? "target-met" : "partial")
            + (status == CpSolverStatus.OPTIMAL ? ", optimal" : "");
    System.out.printf(
        "[%d:%02d] Solver complete. %d of %d games assigned (%s).%n",
        elapsed / 60, elapsed % 60, games.size(), totalFixtures, completionStatus);
    System.out.flush();

    // Assemble per-division summaries, sorted alphabetically for consistent output
    List<DivisionSummary> summaries =
        fixturesByDiv.entrySet().stream()
            .map(
                e -> {
                  UUID divId = e.getKey();
                  int requested = e.getValue().size();
                  int assigned = assignedByDiv.getOrDefault(divId, 0L).intValue();
                  int slots = slotCounts.getOrDefault(divId, 0);
                  return new DivisionSummary(
                      divisionName(league, divId), requested, assigned, slots);
                })
            .sorted(Comparator.comparing(DivisionSummary::divisionName))
            .toList();

    boolean targetMet = games.size() == totalFixtures;
    return ScheduleResult.success(games, status == CpSolverStatus.OPTIMAL, targetMet, summaries);
  }

  private ScheduledGame buildScheduledGame(League league, Fixture fixture, Slot slot) {
    return new ScheduledGame(
        UUID.randomUUID(),
        slot.date(),
        slot.startTime(),
        slot.fieldId(),
        slot.fieldName(),
        fixture.homeTeamId(),
        resolveTeamName(league, fixture.homeTeamId()),
        fixture.awayTeamId(),
        resolveTeamName(league, fixture.awayTeamId()),
        fixture.divisionId(),
        divisionName(league, fixture.divisionId()),
        fixture.gameDurationMinutes(),
        false);
  }

  private String resolveTeamName(League league, UUID teamId) {
    return league.divisions().stream()
        .flatMap(d -> d.teams().stream())
        .filter(t -> t.id().equals(teamId))
        .findFirst()
        .map(t -> t.name())
        .orElse("[unknown]");
  }

  private String divisionName(League league, UUID divId) {
    return league.divisions().stream()
        .filter(d -> d.id().equals(divId))
        .findFirst()
        .map(Division::name)
        .orElse("[unknown]");
  }

  private Division findDivisionById(League league, UUID divId) {
    return league.divisions().stream().filter(d -> d.id().equals(divId)).findFirst().orElse(null);
  }

  // --- Playoff field assignment ---

  /**
   * Assigns field/time slots to all real (non-bye) playoff games across all provided playoffs in a
   * single CP-SAT solve. Returns assignmentsByGameId mapping PlayoffGame.gameId → Slot.
   */
  public PlayoffScheduleResult assignPlayoffs(League league, List<Playoff> playoffs) {
    Loader.loadNativeLibraries();

    long startMs = System.currentTimeMillis();

    LocalDate start = playoffs.get(0).startDate();
    LocalDate end = playoffs.get(0).endDate();

    // Build fixtures from non-bye playoff games; pseudo-UUIDs stand in for unknown teams.
    Map<UUID, List<Fixture>> fixturesByDiv = new LinkedHashMap<>();
    // Track gameId → divisionId for result assembly
    Map<UUID, UUID> gameIdToDivId = new HashMap<>();

    for (Playoff playoff : playoffs) {
      UUID divId = playoff.divisionId();
      int duration = divisionDuration(league, divId);
      List<Fixture> fixtures = new ArrayList<>();
      for (PlayoffGame game : playoff.games()) {
        if (game.isBye()) continue;
        UUID pseudoHome =
            UUID.nameUUIDFromBytes(("pA:" + game.gameId()).getBytes(StandardCharsets.UTF_8));
        UUID pseudoAway =
            UUID.nameUUIDFromBytes(("pB:" + game.gameId()).getBytes(StandardCharsets.UTF_8));
        fixtures.add(new Fixture(game.gameId(), pseudoHome, pseudoAway, divId, duration));
        gameIdToDivId.put(game.gameId(), divId);
      }
      if (!fixtures.isEmpty()) {
        fixturesByDiv.put(divId, fixtures);
      }
    }

    if (fixturesByDiv.isEmpty()) {
      return PlayoffScheduleResult.failure(
          "Error: No real game slots to assign (all games are byes).");
    }

    Set<UUID> playoffDivIds = fixturesByDiv.keySet();
    Map<UUID, List<Slot>> slotsByDiv = enumeratePlayoffSlots(league, start, end, playoffDivIds);
    Map<UUID, Integer> slotCounts = computeSlotCounts(slotsByDiv);

    int totalFixtures = fixturesByDiv.values().stream().mapToInt(List::size).sum();
    int elapsed0 = (int) ((System.currentTimeMillis() - startMs) / 1000);
    System.out.printf(
        "[%d:%02d] Feasibility check: %d game slots across %d division(s). Solver started.%n",
        elapsed0 / 60, elapsed0 % 60, totalFixtures, fixturesByDiv.size());
    System.out.flush();

    return buildAndSolvePlayoffs(league, fixturesByDiv, slotsByDiv, slotCounts, startMs);
  }

  private Map<UUID, List<Slot>> enumeratePlayoffSlots(
      League league, LocalDate start, LocalDate end, Set<UUID> divisionIds) {
    Map<UUID, List<Slot>> slotsByDiv = new HashMap<>();
    for (UUID divId : divisionIds) {
      slotsByDiv.put(divId, new ArrayList<>());
    }

    LeagueConfig config = league.config();
    if (config == null || config.sunriseTime() == null || config.sunsetTime() == null) {
      return slotsByDiv;
    }

    int gridMinutes = (config.gridMinutes() != null) ? config.gridMinutes() : DEFAULT_GRID_MINUTES;

    for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
      final LocalDate currentDate = date;
      for (Field field : league.fields()) {
        LocalTime[] openWindow = resolveOpenWindow(config, field, currentDate);
        if (openWindow == null) continue;

        List<FieldBlock> dateBlocks =
            field.blocks().stream().filter(b -> b.date().equals(currentDate)).toList();
        List<LocalTime[]> available = subtractBlocks(openWindow[0], openWindow[1], dateBlocks);

        for (UUID divId : divisionIds) {
          if (isFieldLockedToOtherDivision(field, currentDate, divId)) continue;
          if (isDivisionPinnedElsewhere(league, field, currentDate, divId)) continue;
          int duration = divisionDuration(league, divId);
          LocalTime curfew = divisionCurfew(league, divId);
          for (LocalTime[] window : available) {
            LocalTime slotStart = window[0];
            while (!slotStart.plusMinutes(duration).isAfter(window[1])) {
              if (curfew != null && slotStart.isAfter(curfew)) break;
              slotsByDiv.get(divId).add(new Slot(currentDate, field.id(), field.name(), slotStart));
              slotStart = slotStart.plusMinutes(gridMinutes);
            }
          }
        }
      }
    }
    return slotsByDiv;
  }

  private PlayoffScheduleResult buildAndSolvePlayoffs(
      League league,
      Map<UUID, List<Fixture>> fixturesByDiv,
      Map<UUID, List<Slot>> slotsByDiv,
      Map<UUID, Integer> slotCounts,
      long startMs) {

    // Pre-solve curfew feasibility guard: fail fast when a curfewed division has no slots.
    for (UUID divId : fixturesByDiv.keySet()) {
      if (slotsByDiv.getOrDefault(divId, List.of()).isEmpty()) {
        Division div = findDivisionById(league, divId);
        if (div != null && div.curfewTime() != null) {
          return PlayoffScheduleResult.failure(
              String.format(
                  "Error: Division \"%s\" has no available slots after applying curfew %s. "
                      + "Expand the playoff window or relax the curfew.",
                  div.name(), div.curfewTime()));
        }
      }
    }

    LeagueConfig config = league.config();
    int bufferMinutes =
        (config != null && config.fieldBufferMinutes() != null)
            ? config.fieldBufferMinutes()
            : DEFAULT_FIELD_BUFFER_MINUTES;
    int gridMinutes =
        (config != null && config.gridMinutes() != null)
            ? config.gridMinutes()
            : DEFAULT_GRID_MINUTES;

    // Compute per-field priority scores for the CP-SAT objective.
    // Score = (maxRank - rank + 1) for ranked fields; 0 for unranked.
    // This ensures rank 1 always scores highest; all unranked fields are equivalent and lowest.
    long maxFieldRank =
        league.fields().stream()
            .map(Field::playoffPriority)
            .filter(p -> p != null)
            .mapToLong(Integer::longValue)
            .max()
            .orElse(0L);
    Map<UUID, Integer> fieldScore = buildFieldScoreMap(league.fields(), maxFieldRank);

    CpModel model = new CpModel();

    List<GameVar> allGameVars = new ArrayList<>();
    Map<UUID, List<GameVar>> byFixture = new LinkedHashMap<>();
    Map<String, List<BoolVar>> byFieldTick = new HashMap<>();
    Map<String, List<GameVar>> byTeamDate = new HashMap<>();
    Map<String, List<GameVar>> byTeamWeek = new HashMap<>();

    int varIndex = 0;
    for (Map.Entry<UUID, List<Fixture>> divEntry : fixturesByDiv.entrySet()) {
      UUID divId = divEntry.getKey();
      List<Fixture> fixtures = divEntry.getValue();
      List<Slot> slots = slotsByDiv.getOrDefault(divId, List.of());

      for (Fixture fixture : fixtures) {
        byFixture.put(fixture.gameId(), new ArrayList<>());
        for (Slot slot : slots) {
          BoolVar var = model.newBoolVar("g" + varIndex++);
          GameVar gv = new GameVar(var, fixture, slot);
          allGameVars.add(gv);
          byFixture.get(fixture.gameId()).add(gv);

          int gameStart = slot.startMinutes();
          int gameEnd = gameStart + fixture.gameDurationMinutes() + bufferMinutes;
          String fieldDate = slot.fieldId() + "|" + slot.date() + "|";
          for (int t = gameStart; t < gameEnd; t += gridMinutes) {
            byFieldTick.computeIfAbsent(fieldDate + t, k -> new ArrayList<>()).add(var);
          }

          String homeKey = fixture.homeTeamId() + "|" + slot.date();
          String awayKey = fixture.awayTeamId() + "|" + slot.date();
          byTeamDate.computeIfAbsent(homeKey, k -> new ArrayList<>()).add(gv);
          byTeamDate.computeIfAbsent(awayKey, k -> new ArrayList<>()).add(gv);

          var weekFields = WeekFields.ISO;
          String weekKey =
              slot.date().get(weekFields.weekOfWeekBasedYear())
                  + "|"
                  + slot.date().get(weekFields.weekBasedYear());
          byTeamWeek
              .computeIfAbsent(fixture.homeTeamId() + "|" + weekKey, k -> new ArrayList<>())
              .add(gv);
          byTeamWeek
              .computeIfAbsent(fixture.awayTeamId() + "|" + weekKey, k -> new ArrayList<>())
              .add(gv);
        }
      }
    }

    // C1: each fixture assigned at most once
    Map<UUID, BoolVar> isAssigned = new LinkedHashMap<>();
    for (Map.Entry<UUID, List<GameVar>> entry : byFixture.entrySet()) {
      UUID fixtureId = entry.getKey();
      List<GameVar> vars = entry.getValue();
      Literal[] lits = vars.stream().map(gv -> (Literal) gv.var()).toArray(Literal[]::new);
      model.addAtMostOne(lits);
      BoolVar assigned = model.newBoolVar("a" + varIndex++);
      model.addEquality(assigned, LinearExpr.sum(lits));
      isAssigned.put(fixtureId, assigned);
    }

    // C2: field non-overlap per grid tick
    for (List<BoolVar> tickVars : byFieldTick.values()) {
      if (tickVars.size() > 1) {
        Literal[] lits = tickVars.stream().map(v -> (Literal) v).toArray(Literal[]::new);
        model.addAtMostOne(lits);
      }
    }

    // C3: no team plays twice on the same day (pseudo-UUIDs ensure this only fires for real
    // conflicts)
    for (List<GameVar> teamDayVars : byTeamDate.values()) {
      if (teamDayVars.size() > 1) {
        Literal[] lits = teamDayVars.stream().map(gv -> (Literal) gv.var()).toArray(Literal[]::new);
        model.addAtMostOne(lits);
      }
    }

    int totalFixtures = fixturesByDiv.values().stream().mapToInt(List::size).sum();

    Literal[] allAssignedLits =
        isAssigned.values().stream().map(v -> (Literal) v).toArray(Literal[]::new);
    IntVar totalAssignedVar = model.newIntVar(0, totalFixtures, "total_assigned");
    model.addEquality(totalAssignedVar, LinearExpr.sum(allAssignedLits));

    int weekCap =
        (config != null && config.maxGamesPerWeek() != null)
            ? config.maxGamesPerWeek()
            : DEFAULT_MAX_GAMES_PER_WEEK;
    int restDays =
        (config != null && config.minRestDays() != null)
            ? config.minRestDays()
            : DEFAULT_MIN_REST_DAYS;

    // C4: max games per week per team
    IntVar maxWeekLoad = model.newIntVar(0, weekCap, "max_week_load");
    for (List<GameVar> weekVars : byTeamWeek.values()) {
      if (!weekVars.isEmpty()) {
        Literal[] lits = weekVars.stream().map(gv -> (Literal) gv.var()).toArray(Literal[]::new);
        model.addLessOrEqual(LinearExpr.sum(lits), LinearExpr.constant(weekCap));
        model.addLessOrEqual(LinearExpr.sum(lits), maxWeekLoad);
      }
    }

    // C5: min rest days between games
    if (restDays > 0) {
      for (Map.Entry<String, List<GameVar>> entry : byTeamDate.entrySet()) {
        if (entry.getValue().isEmpty()) continue;
        String[] parts = entry.getKey().split("\\|", 2);
        String teamIdStr = parts[0];
        LocalDate date = LocalDate.parse(parts[1]);
        for (int r = 1; r <= restDays; r++) {
          List<GameVar> nextDayVars =
              byTeamDate.getOrDefault(teamIdStr + "|" + date.plusDays(r), List.of());
          if (nextDayVars.isEmpty()) continue;
          List<Literal> combined = new ArrayList<>();
          entry.getValue().forEach(gv -> combined.add(gv.var()));
          nextDayVars.forEach(gv -> combined.add(gv.var()));
          if (combined.size() > 1) {
            model.addAtMostOne(combined.toArray(Literal[]::new));
          }
        }
      }
    }

    // Build field priority score IntVar.
    // 3-tier weighted objective: assignments >> priority score >> week-load balance.
    // W1 dominates: one extra assignment always beats any priority or weekload gain.
    // W2 dominates weekLoad: any priority gain beats any weekload change within cap.
    long maxTotalPriority = maxFieldRank * totalFixtures;
    IntVar totalPriorityScore =
        model.newIntVar(0, Math.max(1L, maxTotalPriority), "total_priority");
    IntVar[] priorityVars =
        allGameVars.stream().map(gv -> (IntVar) gv.var()).toArray(IntVar[]::new);
    long[] priorityScores =
        allGameVars.stream()
            .mapToLong(gv -> fieldScore.getOrDefault(gv.slot().fieldId(), 0))
            .toArray();
    model.addEquality(totalPriorityScore, LinearExpr.weightedSum(priorityVars, priorityScores));

    long W2 = (long) weekCap + 1L;
    long W1 = Math.max((long) totalFixtures + 1L, maxFieldRank * totalFixtures * W2 + weekCap + 1L);

    model.maximize(
        LinearExpr.weightedSum(
            new IntVar[] {totalAssignedVar, totalPriorityScore, maxWeekLoad},
            new long[] {W1, W2, -1L}));

    CpSolver solver = new CpSolver();
    solver.getParameters().setMaxTimeInSeconds(SOLVER_TIME_LIMIT_SECONDS);
    ProgressCallback callback = new ProgressCallback(SOLVER_TIME_LIMIT_SECONDS);
    CpSolverStatus status = solver.solve(model, callback);

    if (status == CpSolverStatus.UNKNOWN) {
      return PlayoffScheduleResult.failure(
          "Error: Solver timed out without assigning any games. "
              + "Try extending the playoff window or adding more field availability.");
    }
    if (status == CpSolverStatus.INFEASIBLE) {
      return PlayoffScheduleResult.failure(
          "Error: Solver returned an unexpected result. Please report this bug.");
    }

    Map<UUID, Slot> assignmentsByGameId = new LinkedHashMap<>();
    Map<UUID, Long> assignedByDiv = new HashMap<>();
    for (GameVar gv : allGameVars) {
      if (solver.booleanValue(gv.var())) {
        assignmentsByGameId.put(gv.fixture().gameId(), gv.slot());
        assignedByDiv.merge(gv.fixture().divisionId(), 1L, Long::sum);
      }
    }

    int elapsed = (int) ((System.currentTimeMillis() - startMs) / 1000);
    int assigned = assignmentsByGameId.size();
    String completionStatus =
        (assigned == totalFixtures ? "target-met" : "partial")
            + (status == CpSolverStatus.OPTIMAL ? ", optimal" : "");
    System.out.printf(
        "[%d:%02d] Solver complete. %d of %d game slots assigned (%s).%n",
        elapsed / 60, elapsed % 60, assigned, totalFixtures, completionStatus);
    System.out.flush();

    List<DivisionSummary> summaries =
        fixturesByDiv.entrySet().stream()
            .map(
                e -> {
                  UUID divId = e.getKey();
                  int requested = e.getValue().size();
                  int assignedCount = assignedByDiv.getOrDefault(divId, 0L).intValue();
                  int slots = slotCounts.getOrDefault(divId, 0);
                  return new DivisionSummary(
                      divisionName(league, divId), requested, assignedCount, slots);
                })
            .sorted(Comparator.comparing(DivisionSummary::divisionName))
            .toList();

    // Build per-field utilization summary for output.
    Map<UUID, Integer> gamesPerField = new HashMap<>();
    for (Slot slot : assignmentsByGameId.values()) {
      gamesPerField.merge(slot.fieldId(), 1, Integer::sum);
    }
    List<PlayoffFieldSummary> fieldSummaries =
        league.fields().stream()
            .filter(f -> gamesPerField.containsKey(f.id()))
            .map(
                f ->
                    new PlayoffFieldSummary(
                        f.id(), f.name(), f.playoffPriority(), gamesPerField.get(f.id())))
            .sorted(
                Comparator.comparing(
                        PlayoffFieldSummary::playoffPriority,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(PlayoffFieldSummary::fieldName))
            .toList();

    return PlayoffScheduleResult.success(
        assignmentsByGameId, status == CpSolverStatus.OPTIMAL, summaries, fieldSummaries);
  }

  /**
   * Returns a score for each field based on its playoff priority rank. Rank 1 scores highest;
   * unranked fields score 0.
   */
  private Map<UUID, Integer> buildFieldScoreMap(List<Field> fields, long maxRank) {
    Map<UUID, Integer> scores = new HashMap<>();
    for (Field f : fields) {
      int score = (f.playoffPriority() != null) ? (int) (maxRank - f.playoffPriority() + 1) : 0;
      scores.put(f.id(), score);
    }
    return scores;
  }

  // --- Practice field assignment ---

  private record PracticeVar(BoolVar var, PracticeFixture fixture, Slot slot) {}

  /**
   * Assigns field/time slots to all practice slots across the provided PracticeSchedule list in a
   * single CP-SAT solve. Each practice involves one team; there is no opponent.
   */
  public PracticeScheduleResult assignPractices(League league, List<PracticeSchedule> schedules) {
    Loader.loadNativeLibraries();

    long startMs = System.currentTimeMillis();

    Map<UUID, List<PracticeFixture>> fixturesByDiv = new LinkedHashMap<>();
    Map<UUID, LocalDate[]> divisionWindows = new HashMap<>();

    for (PracticeSchedule ps : schedules) {
      UUID divId = ps.divisionId();
      int duration = divisionPracticeDuration(league, divId);
      List<PracticeFixture> fixtures = new ArrayList<>();
      for (PracticeSlot slot : ps.slots()) {
        fixtures.add(new PracticeFixture(slot.slotId(), slot.teamId(), divId, duration));
      }
      if (!fixtures.isEmpty()) {
        fixturesByDiv.put(divId, fixtures);
      }
      Division div =
          league.divisions().stream().filter(d -> d.id().equals(divId)).findFirst().orElseThrow();
      divisionWindows.put(divId, new LocalDate[] {div.practiceStart(), div.practiceEnd()});
    }

    if (fixturesByDiv.isEmpty()) {
      return PracticeScheduleResult.failure(
          "Error: No practice slots found. Run 'planr practice generate' first.");
    }

    Map<UUID, List<Slot>> slotsByDiv =
        enumeratePracticeSlots(league, divisionWindows, fixturesByDiv.keySet());
    Map<UUID, Integer> slotCounts = computeSlotCounts(slotsByDiv);

    int totalFixtures = fixturesByDiv.values().stream().mapToInt(List::size).sum();
    int elapsed0 = (int) ((System.currentTimeMillis() - startMs) / 1000);
    System.out.printf(
        "[%d:%02d] Feasibility check: %d practice slots across %d division(s). Solver started.%n",
        elapsed0 / 60, elapsed0 % 60, totalFixtures, fixturesByDiv.size());
    System.out.flush();

    return buildAndSolvePractices(league, fixturesByDiv, slotsByDiv, slotCounts, startMs);
  }

  private Map<UUID, List<Slot>> enumeratePracticeSlots(
      League league, Map<UUID, LocalDate[]> divisionWindows, Set<UUID> divisionIds) {
    Map<UUID, List<Slot>> slotsByDiv = new HashMap<>();
    for (UUID divId : divisionIds) {
      slotsByDiv.put(divId, new ArrayList<>());
    }

    LeagueConfig config = league.config();
    if (config == null || config.sunriseTime() == null || config.sunsetTime() == null) {
      return slotsByDiv;
    }

    int gridMinutes = (config.gridMinutes() != null) ? config.gridMinutes() : DEFAULT_GRID_MINUTES;

    // Iterate the union of all practice windows to avoid redundant field/date passes.
    LocalDate globalStart =
        divisionWindows.values().stream()
            .map(w -> w[0])
            .min(Comparator.naturalOrder())
            .orElse(null);
    LocalDate globalEnd =
        divisionWindows.values().stream()
            .map(w -> w[1])
            .max(Comparator.naturalOrder())
            .orElse(null);
    if (globalStart == null || globalEnd == null) return slotsByDiv;

    for (LocalDate date = globalStart; !date.isAfter(globalEnd); date = date.plusDays(1)) {
      final LocalDate currentDate = date;
      for (Field field : league.fields()) {
        LocalTime[] openWindow = resolveOpenWindow(config, field, currentDate);
        if (openWindow == null) continue;

        List<FieldBlock> dateBlocks =
            field.blocks().stream().filter(b -> b.date().equals(currentDate)).toList();
        List<LocalTime[]> available = subtractBlocks(openWindow[0], openWindow[1], dateBlocks);

        for (UUID divId : divisionIds) {
          LocalDate[] window = divisionWindows.get(divId);
          if (currentDate.isBefore(window[0]) || currentDate.isAfter(window[1])) continue;
          if (isFieldLockedToOtherDivision(field, currentDate, divId)) continue;
          if (isDivisionPinnedElsewhere(league, field, currentDate, divId)) continue;

          int duration = divisionPracticeDuration(league, divId);
          LocalTime curfew = divisionCurfew(league, divId);
          for (LocalTime[] avWindow : available) {
            LocalTime slotStart = avWindow[0];
            while (!slotStart.plusMinutes(duration).isAfter(avWindow[1])) {
              if (curfew != null && slotStart.isAfter(curfew)) break;
              slotsByDiv.get(divId).add(new Slot(currentDate, field.id(), field.name(), slotStart));
              slotStart = slotStart.plusMinutes(gridMinutes);
            }
          }
        }
      }
    }
    return slotsByDiv;
  }

  private PracticeScheduleResult buildAndSolvePractices(
      League league,
      Map<UUID, List<PracticeFixture>> fixturesByDiv,
      Map<UUID, List<Slot>> slotsByDiv,
      Map<UUID, Integer> slotCounts,
      long startMs) {

    // Pre-solve curfew feasibility guard: fail fast when a curfewed division has no slots.
    for (UUID divId : fixturesByDiv.keySet()) {
      if (slotsByDiv.getOrDefault(divId, List.of()).isEmpty()) {
        Division div = findDivisionById(league, divId);
        if (div != null && div.curfewTime() != null) {
          return PracticeScheduleResult.failure(
              String.format(
                  "Error: Division \"%s\" has no available slots after applying curfew %s. "
                      + "Expand the practice window or relax the curfew.",
                  div.name(), div.curfewTime()));
        }
      }
    }

    LeagueConfig config = league.config();
    int bufferMinutes =
        (config != null && config.fieldBufferMinutes() != null)
            ? config.fieldBufferMinutes()
            : DEFAULT_FIELD_BUFFER_MINUTES;
    int gridMinutes =
        (config != null && config.gridMinutes() != null)
            ? config.gridMinutes()
            : DEFAULT_GRID_MINUTES;

    CpModel model = new CpModel();

    List<PracticeVar> allPracticeVars = new ArrayList<>();
    Map<UUID, List<PracticeVar>> byFixture = new LinkedHashMap<>();
    Map<String, List<BoolVar>> byFieldTick = new HashMap<>();
    Map<String, List<PracticeVar>> byTeamDate = new HashMap<>();
    Map<String, List<PracticeVar>> byTeamWeek = new HashMap<>();

    int varIndex = 0;
    for (Map.Entry<UUID, List<PracticeFixture>> divEntry : fixturesByDiv.entrySet()) {
      UUID divId = divEntry.getKey();
      List<PracticeFixture> fixtures = divEntry.getValue();
      List<Slot> slots = slotsByDiv.getOrDefault(divId, List.of());

      for (PracticeFixture fixture : fixtures) {
        byFixture.put(fixture.slotId(), new ArrayList<>());
        for (Slot slot : slots) {
          BoolVar var = model.newBoolVar("p" + varIndex++);
          PracticeVar pv = new PracticeVar(var, fixture, slot);
          allPracticeVars.add(pv);
          byFixture.get(fixture.slotId()).add(pv);

          int practiceStart = slot.startMinutes();
          int practiceEnd = practiceStart + fixture.durationMinutes() + bufferMinutes;
          String fieldDate = slot.fieldId() + "|" + slot.date() + "|";
          for (int t = practiceStart; t < practiceEnd; t += gridMinutes) {
            byFieldTick.computeIfAbsent(fieldDate + t, k -> new ArrayList<>()).add(var);
          }

          // Single team per practice — register under one key, not home + away.
          String teamKey = fixture.teamId() + "|" + slot.date();
          byTeamDate.computeIfAbsent(teamKey, k -> new ArrayList<>()).add(pv);

          var weekFields = WeekFields.ISO;
          String weekKey =
              slot.date().get(weekFields.weekOfWeekBasedYear())
                  + "|"
                  + slot.date().get(weekFields.weekBasedYear());
          byTeamWeek
              .computeIfAbsent(fixture.teamId() + "|" + weekKey, k -> new ArrayList<>())
              .add(pv);
        }
      }
    }

    // C1: Each practice slot assigned at most once.
    Map<UUID, BoolVar> isAssigned = new LinkedHashMap<>();
    for (Map.Entry<UUID, List<PracticeVar>> entry : byFixture.entrySet()) {
      UUID slotId = entry.getKey();
      List<PracticeVar> vars = entry.getValue();
      Literal[] lits = vars.stream().map(pv -> (Literal) pv.var()).toArray(Literal[]::new);
      model.addAtMostOne(lits);
      BoolVar assigned = model.newBoolVar("a" + varIndex++);
      model.addEquality(assigned, LinearExpr.sum(lits));
      isAssigned.put(slotId, assigned);
    }

    // C2: At each grid tick, at most one practice may be active on a given field.
    for (List<BoolVar> tickVars : byFieldTick.values()) {
      if (tickVars.size() > 1) {
        Literal[] lits = tickVars.stream().map(v -> (Literal) v).toArray(Literal[]::new);
        model.addAtMostOne(lits);
      }
    }

    // C3: No team practices twice on the same calendar day.
    for (List<PracticeVar> teamDayVars : byTeamDate.values()) {
      if (teamDayVars.size() > 1) {
        Literal[] lits = teamDayVars.stream().map(pv -> (Literal) pv.var()).toArray(Literal[]::new);
        model.addAtMostOne(lits);
      }
    }

    int totalFixtures = fixturesByDiv.values().stream().mapToInt(List::size).sum();

    Literal[] allAssignedLits =
        isAssigned.values().stream().map(v -> (Literal) v).toArray(Literal[]::new);
    IntVar totalAssignedVar = model.newIntVar(0, totalFixtures, "total_assigned");
    model.addEquality(totalAssignedVar, LinearExpr.sum(allAssignedLits));

    int weekCap =
        (config != null && config.maxGamesPerWeek() != null)
            ? config.maxGamesPerWeek()
            : DEFAULT_MAX_GAMES_PER_WEEK;
    int restDays =
        (config != null && config.minRestDays() != null)
            ? config.minRestDays()
            : DEFAULT_MIN_REST_DAYS;

    // C4: No team exceeds weekCap practices in a single ISO week.
    IntVar maxWeekLoad = model.newIntVar(0, weekCap, "max_week_load");
    for (List<PracticeVar> weekVars : byTeamWeek.values()) {
      if (!weekVars.isEmpty()) {
        Literal[] lits = weekVars.stream().map(pv -> (Literal) pv.var()).toArray(Literal[]::new);
        model.addLessOrEqual(LinearExpr.sum(lits), LinearExpr.constant(weekCap));
        model.addLessOrEqual(LinearExpr.sum(lits), maxWeekLoad);
      }
    }

    // C5: Min rest days between consecutive practices for the same team.
    if (restDays > 0) {
      for (Map.Entry<String, List<PracticeVar>> entry : byTeamDate.entrySet()) {
        if (entry.getValue().isEmpty()) continue;
        String[] parts = entry.getKey().split("\\|", 2);
        String teamIdStr = parts[0];
        LocalDate date = LocalDate.parse(parts[1]);
        for (int r = 1; r <= restDays; r++) {
          List<PracticeVar> nextDayVars =
              byTeamDate.getOrDefault(teamIdStr + "|" + date.plusDays(r), List.of());
          if (nextDayVars.isEmpty()) continue;
          List<Literal> combined = new ArrayList<>();
          entry.getValue().forEach(pv -> combined.add(pv.var()));
          nextDayVars.forEach(pv -> combined.add(pv.var()));
          if (combined.size() > 1) {
            model.addAtMostOne(combined.toArray(Literal[]::new));
          }
        }
      }
    }

    long bigM = (long) totalFixtures + 1;
    model.maximize(
        LinearExpr.weightedSum(
            new IntVar[] {totalAssignedVar, maxWeekLoad}, new long[] {bigM, -1L}));

    CpSolver solver = new CpSolver();
    solver.getParameters().setMaxTimeInSeconds(SOLVER_TIME_LIMIT_SECONDS);
    ProgressCallback callback = new ProgressCallback(SOLVER_TIME_LIMIT_SECONDS);
    CpSolverStatus status = solver.solve(model, callback);

    if (status == CpSolverStatus.UNKNOWN) {
      return PracticeScheduleResult.failure(
          "Error: Solver timed out without assigning any practices. "
              + "Try extending the practice window or adding more field availability.");
    }
    if (status == CpSolverStatus.INFEASIBLE) {
      return PracticeScheduleResult.failure(
          "Error: Solver returned an unexpected result. Please report this bug.");
    }

    Map<UUID, Slot> assignmentsBySlotId = new LinkedHashMap<>();
    Map<UUID, Long> assignedByDiv = new HashMap<>();
    for (PracticeVar pv : allPracticeVars) {
      if (solver.booleanValue(pv.var())) {
        assignmentsBySlotId.put(pv.fixture().slotId(), pv.slot());
        assignedByDiv.merge(pv.fixture().divisionId(), 1L, Long::sum);
      }
    }

    int elapsed = (int) ((System.currentTimeMillis() - startMs) / 1000);
    String completionStatus =
        (assignmentsBySlotId.size() == totalFixtures ? "target-met" : "partial")
            + (status == CpSolverStatus.OPTIMAL ? ", optimal" : "");
    System.out.printf(
        "[%d:%02d] Solver complete. %d of %d practice slots assigned (%s).%n",
        elapsed / 60, elapsed % 60, assignmentsBySlotId.size(), totalFixtures, completionStatus);
    System.out.flush();

    List<DivisionSummary> summaries =
        fixturesByDiv.entrySet().stream()
            .map(
                e -> {
                  UUID divId = e.getKey();
                  int requested = e.getValue().size();
                  int assignedCount = assignedByDiv.getOrDefault(divId, 0L).intValue();
                  int slots = slotCounts.getOrDefault(divId, 0);
                  return new DivisionSummary(
                      divisionName(league, divId), requested, assignedCount, slots);
                })
            .sorted(Comparator.comparing(DivisionSummary::divisionName))
            .toList();

    return PracticeScheduleResult.success(
        assignmentsBySlotId, status == CpSolverStatus.OPTIMAL, summaries);
  }

  private int divisionPracticeDuration(League league, UUID divId) {
    return league.divisions().stream()
        .filter(d -> d.id().equals(divId))
        .findFirst()
        .map(Division::practiceDurationMinutes)
        .orElseThrow();
  }

  // --- Progress callback ---

  private static class ProgressCallback extends CpSolverSolutionCallback {

    private final int timeLimitSeconds;
    private int lastMilestonePct = 0;

    ProgressCallback(int timeLimitSeconds) {
      this.timeLimitSeconds = timeLimitSeconds;
    }

    @Override
    public void onSolutionCallback() {
      if (timeLimitSeconds <= 0) return;
      int elapsedSec = (int) wallTime();
      int pct = (int) (wallTime() / timeLimitSeconds * 100);
      for (int milestone : new int[] {25, 50, 75}) {
        if (pct >= milestone && lastMilestonePct < milestone) {
          System.out.printf(
              "[%d:%02d] Solver progress: ~%d%% of time budget used.%n",
              elapsedSec / 60, elapsedSec % 60, milestone);
          System.out.flush();
          lastMilestonePct = milestone;
        }
      }
    }
  }
}
