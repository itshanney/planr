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
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.DayOfWeekWindow;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.FieldBlock;
import org.leagueplan.planr.model.FieldDateOverride;
import org.leagueplan.planr.model.FieldDivisionLock;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import org.leagueplan.planr.model.Playoff;
import org.leagueplan.planr.model.PlayoffGame;
import org.leagueplan.planr.model.ScheduledGame;
import org.leagueplan.planr.model.TeamGame;
import org.leagueplan.planr.model.TeamSchedule;

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

public class SchedulerService {

    public static final int DEFAULT_MAX_GAMES_PER_WEEK = 2;
    public static final int DEFAULT_MIN_REST_DAYS = 1;

    private static final int BUFFER_MINUTES = 15;
    private static final int GRID_MINUTES = 15;
    private static final int SOLVER_TIME_LIMIT_SECONDS = 300;

    private record GameVar(BoolVar var, Fixture fixture, Slot slot) {}

    /**
     * Phase 2: assigns dates, times, and fields to every game in the confirmed team schedule.
     * Saves a partial Draft when insufficient slots prevent full assignment.
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
            result.computeIfAbsent(game.divisionId(), k -> new ArrayList<>())
                .add(new Fixture(
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
     * Returns an upper-bound estimate of how many games could fit in the season window for the
     * given division. Walks every date in the configured season, subtracts field blocks, and
     * counts slots using the same 15-minute grid as the solver. Does not invoke the solver.
     */
    public int estimateAvailableSlots(League league, UUID divisionId, int gameDurationMinutes) {
        LeagueConfig config = league.config();
        if (config == null || config.seasonStart() == null || config.seasonEnd() == null
                || config.sunriseTime() == null || config.sunsetTime() == null) {
            return 0;
        }

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
                List<FieldBlock> dateBlocks = field.blocks().stream()
                    .filter(b -> b.date().equals(currentDate))
                    .toList();

                for (LocalTime[] window : subtractBlocks(openWindow[0], openWindow[1], dateBlocks)) {
                    LocalTime slotStart = window[0];
                    while (!slotStart.plusMinutes(gameDurationMinutes).isAfter(window[1])) {
                        total++;
                        slotStart = slotStart.plusMinutes(GRID_MINUTES);
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

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            final LocalDate currentDate = date;
            for (Field field : league.fields()) {
                LocalTime[] openWindow = resolveOpenWindow(config, field, currentDate);
                if (openWindow == null) continue;

                List<FieldBlock> dateBlocks = field.blocks().stream()
                    .filter(b -> b.date().equals(currentDate))
                    .toList();

                List<LocalTime[]> available = subtractBlocks(openWindow[0], openWindow[1], dateBlocks);

                for (UUID divId : slotsByDiv.keySet()) {
                    if (isFieldLockedToOtherDivision(field, currentDate, divId)) continue;
                    if (isDivisionPinnedElsewhere(league, field, currentDate, divId)) continue;
                    int duration = divisionDuration(league, divId);
                    for (LocalTime[] window : available) {
                        LocalTime slotStart = window[0];
                        while (!slotStart.plusMinutes(duration).isAfter(window[1])) {
                            slotsByDiv.get(divId).add(
                                new Slot(currentDate, field.id(), field.name(), slotStart));
                            slotStart = slotStart.plusMinutes(GRID_MINUTES);
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
    private boolean isDivisionPinnedElsewhere(League league, Field currentField,
                                               LocalDate date, UUID divisionId) {
        return league.fields().stream()
            .filter(f -> !f.id().equals(currentField.id()))
            .anyMatch(f -> f.divisionLocks().stream()
                .anyMatch(lock -> lock.divisionId().equals(divisionId)
                    && !date.isBefore(lock.startDate())
                    && !date.isAfter(lock.endDate())));
    }

    /**
     * Resolves the open time window for a specific field and date using the four-level precedence
     * rule: FieldDateOverride → blocked day → day-of-week window → global sunrise/sunset.
     * Returns null when the date is a blocked day with no field-level override (no slots possible).
     */
    private LocalTime[] resolveOpenWindow(LeagueConfig config, Field field, LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();

        // Priority 1: field-specific date override wins over everything, including blocked days.
        for (FieldDateOverride override : field.dateOverrides()) {
            if (override.date().equals(date)) {
                return new LocalTime[]{override.openStart(), override.openEnd()};
            }
        }

        // Priority 2: league-wide blocked day (no override present).
        if (config.blockedDays().contains(dow)) {
            return null;
        }

        // Priority 3: day-of-week window replaces global sunrise/sunset for this day.
        for (DayOfWeekWindow w : config.dowWindows()) {
            if (w.day() == dow) {
                return new LocalTime[]{w.openStart(), w.openEnd()};
            }
        }

        // Priority 4: global sunrise/sunset.
        return new LocalTime[]{config.sunriseTime(), config.sunsetTime()};
    }

    private Map<UUID, Integer> computeSlotCounts(Map<UUID, List<Slot>> slotsByDiv) {
        Map<UUID, Integer> counts = new HashMap<>();
        slotsByDiv.forEach((divId, slots) -> counts.put(divId, slots.size()));
        return counts;
    }

    private List<LocalTime[]> subtractBlocks(LocalTime openStart, LocalTime openEnd,
            List<FieldBlock> blocks) {
        List<LocalTime[]> available = new ArrayList<>();
        available.add(new LocalTime[]{openStart, openEnd});
        for (FieldBlock block : blocks) {
            List<LocalTime[]> remaining = new ArrayList<>();
            for (LocalTime[] window : available) {
                LocalTime wStart = window[0];
                LocalTime wEnd = window[1];
                boolean blockOverlaps = block.startTime().isBefore(wEnd)
                    && block.endTime().isAfter(wStart);
                if (!blockOverlaps) {
                    remaining.add(window);
                    continue;
                }
                if (block.startTime().isAfter(wStart)) {
                    remaining.add(new LocalTime[]{wStart, block.startTime()});
                }
                if (block.endTime().isBefore(wEnd)) {
                    remaining.add(new LocalTime[]{block.endTime(), wEnd});
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

    // --- Progress output ---

    private void emitFeasibilityCheckLine(long startMs, Map<UUID, List<Fixture>> fixturesByDiv,
            Map<UUID, Integer> slotCounts, League league) {
        int elapsed = (int)((System.currentTimeMillis() - startMs) / 1000);

        List<String> deficits = fixturesByDiv.entrySet().stream()
            .filter(e -> slotCounts.getOrDefault(e.getKey(), 0) < e.getValue().size())
            .map(e -> divisionName(league, e.getKey())
                + " deficit (" + e.getValue().size() + " games, "
                + slotCounts.getOrDefault(e.getKey(), 0) + " slots)")
            .toList();

        if (deficits.isEmpty()) {
            int totalFixtures = fixturesByDiv.values().stream().mapToInt(List::size).sum();
            System.out.printf("[%d:%02d] Feasibility check passed. Solver started. %d games across %d division(s).%n",
                elapsed / 60, elapsed % 60, totalFixtures, fixturesByDiv.size());
        } else {
            System.out.printf("[%d:%02d] Feasibility check: %s. Solver started.%n",
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

                    // Register this game under every 15-min tick it would occupy (start through end-exclusive)
                    int gameStart = slot.startMinutes();
                    int gameEnd = gameStart + fixture.gameDurationMinutes() + BUFFER_MINUTES;
                    String fieldDate = slot.fieldId() + "|" + slot.date() + "|";
                    for (int t = gameStart; t < gameEnd; t += GRID_MINUTES) {
                        byFieldTick.computeIfAbsent(fieldDate + t, k -> new ArrayList<>()).add(var);
                    }

                    String homeKey = fixture.homeTeamId() + "|" + slot.date();
                    String awayKey = fixture.awayTeamId() + "|" + slot.date();
                    byTeamDate.computeIfAbsent(homeKey, k -> new ArrayList<>()).add(gv);
                    byTeamDate.computeIfAbsent(awayKey, k -> new ArrayList<>()).add(gv);

                    var weekFields = WeekFields.ISO;
                    String weekKey = slot.date().get(weekFields.weekOfWeekBasedYear())
                        + "|" + slot.date().get(weekFields.weekBasedYear());
                    byTeamWeek.computeIfAbsent(fixture.homeTeamId() + "|" + weekKey, k -> new ArrayList<>()).add(gv);
                    byTeamWeek.computeIfAbsent(fixture.awayTeamId() + "|" + weekKey, k -> new ArrayList<>()).add(gv);
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
        Literal[] allAssignedLits = isAssigned.values().stream()
            .map(v -> (Literal) v)
            .toArray(Literal[]::new);
        IntVar totalAssignedVar = model.newIntVar(0, totalFixtures, "total_assigned");
        model.addEquality(totalAssignedVar, LinearExpr.sum(allAssignedLits));

        LeagueConfig config = league.config();
        int weekCap = (config != null && config.maxGamesPerWeek() != null)
            ? config.maxGamesPerWeek() : DEFAULT_MAX_GAMES_PER_WEEK;
        int restDays = (config != null && config.minRestDays() != null)
            ? config.minRestDays() : DEFAULT_MIN_REST_DAYS;

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
        // Each (teamId|date, teamId|(date+r)) pair has at most 2 literals because C3 limits
        // each team to at most one game per day, keeping constraint count manageable.
        if (restDays > 0) {
            for (Map.Entry<String, List<GameVar>> entry : byTeamDate.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                String[] parts = entry.getKey().split("\\|", 2);
                String teamIdStr = parts[0];
                LocalDate date = LocalDate.parse(parts[1]);

                for (int r = 1; r <= restDays; r++) {
                    List<GameVar> nextDayVars = byTeamDate.getOrDefault(
                        teamIdStr + "|" + date.plusDays(r), List.of());
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
        model.maximize(LinearExpr.weightedSum(
            new IntVar[]{totalAssignedVar, maxWeekLoad},
            new long[]{bigM, -1L}
        ));

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
        games.sort(Comparator.comparing(ScheduledGame::date)
            .thenComparing(ScheduledGame::startTime)
            .thenComparing(ScheduledGame::fieldName));

        // Emit solver-complete progress line
        int elapsed = (int)((System.currentTimeMillis() - startMs) / 1000);
        String completionStatus = (games.size() == totalFixtures ? "target-met" : "partial")
            + (status == CpSolverStatus.OPTIMAL ? ", optimal" : "");
        System.out.printf("[%d:%02d] Solver complete. %d of %d games assigned (%s).%n",
            elapsed / 60, elapsed % 60, games.size(), totalFixtures, completionStatus);
        System.out.flush();

        // Assemble per-division summaries, sorted alphabetically for consistent output
        List<DivisionSummary> summaries = fixturesByDiv.entrySet().stream()
            .map(e -> {
                UUID divId = e.getKey();
                int requested = e.getValue().size();
                int assigned = assignedByDiv.getOrDefault(divId, 0L).intValue();
                int slots = slotCounts.getOrDefault(divId, 0);
                return new DivisionSummary(divisionName(league, divId), requested, assigned, slots);
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
            false
        );
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

    // --- Playoff field assignment ---

    /**
     * Assigns field/time slots to all real (non-bye) playoff games across all provided playoffs
     * in a single CP-SAT solve. Returns assignmentsByGameId mapping PlayoffGame.gameId → Slot.
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
                UUID pseudoHome = UUID.nameUUIDFromBytes(
                    ("pA:" + game.gameId()).getBytes(StandardCharsets.UTF_8));
                UUID pseudoAway = UUID.nameUUIDFromBytes(
                    ("pB:" + game.gameId()).getBytes(StandardCharsets.UTF_8));
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
        Map<UUID, List<Slot>> slotsByDiv =
            enumeratePlayoffSlots(league, start, end, playoffDivIds);
        Map<UUID, Integer> slotCounts = computeSlotCounts(slotsByDiv);

        int totalFixtures = fixturesByDiv.values().stream().mapToInt(List::size).sum();
        int elapsed0 = (int)((System.currentTimeMillis() - startMs) / 1000);
        System.out.printf("[%d:%02d] Feasibility check: %d game slots across %d division(s). Solver started.%n",
            elapsed0 / 60, elapsed0 % 60, totalFixtures, fixturesByDiv.size());
        System.out.flush();

        return buildAndSolvePlayoffs(league, fixturesByDiv, slotsByDiv, slotCounts, startMs);
    }

    private Map<UUID, List<Slot>> enumeratePlayoffSlots(League league, LocalDate start, LocalDate end,
            Set<UUID> divisionIds) {
        Map<UUID, List<Slot>> slotsByDiv = new HashMap<>();
        for (UUID divId : divisionIds) {
            slotsByDiv.put(divId, new ArrayList<>());
        }

        LeagueConfig config = league.config();
        if (config == null || config.sunriseTime() == null || config.sunsetTime() == null) {
            return slotsByDiv;
        }

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            final LocalDate currentDate = date;
            for (Field field : league.fields()) {
                LocalTime[] openWindow = resolveOpenWindow(config, field, currentDate);
                if (openWindow == null) continue;

                List<FieldBlock> dateBlocks = field.blocks().stream()
                    .filter(b -> b.date().equals(currentDate))
                    .toList();
                List<LocalTime[]> available = subtractBlocks(openWindow[0], openWindow[1], dateBlocks);

                for (UUID divId : divisionIds) {
                    if (isFieldLockedToOtherDivision(field, currentDate, divId)) continue;
                    if (isDivisionPinnedElsewhere(league, field, currentDate, divId)) continue;
                    int duration = divisionDuration(league, divId);
                    for (LocalTime[] window : available) {
                        LocalTime slotStart = window[0];
                        while (!slotStart.plusMinutes(duration).isAfter(window[1])) {
                            slotsByDiv.get(divId).add(
                                new Slot(currentDate, field.id(), field.name(), slotStart));
                            slotStart = slotStart.plusMinutes(GRID_MINUTES);
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
                    int gameEnd = gameStart + fixture.gameDurationMinutes() + BUFFER_MINUTES;
                    String fieldDate = slot.fieldId() + "|" + slot.date() + "|";
                    for (int t = gameStart; t < gameEnd; t += GRID_MINUTES) {
                        byFieldTick.computeIfAbsent(fieldDate + t, k -> new ArrayList<>()).add(var);
                    }

                    String homeKey = fixture.homeTeamId() + "|" + slot.date();
                    String awayKey = fixture.awayTeamId() + "|" + slot.date();
                    byTeamDate.computeIfAbsent(homeKey, k -> new ArrayList<>()).add(gv);
                    byTeamDate.computeIfAbsent(awayKey, k -> new ArrayList<>()).add(gv);

                    var weekFields = WeekFields.ISO;
                    String weekKey = slot.date().get(weekFields.weekOfWeekBasedYear())
                        + "|" + slot.date().get(weekFields.weekBasedYear());
                    byTeamWeek.computeIfAbsent(fixture.homeTeamId() + "|" + weekKey, k -> new ArrayList<>()).add(gv);
                    byTeamWeek.computeIfAbsent(fixture.awayTeamId() + "|" + weekKey, k -> new ArrayList<>()).add(gv);
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

        // C2: field non-overlap per 15-min tick
        for (List<BoolVar> tickVars : byFieldTick.values()) {
            if (tickVars.size() > 1) {
                Literal[] lits = tickVars.stream().map(v -> (Literal) v).toArray(Literal[]::new);
                model.addAtMostOne(lits);
            }
        }

        // C3: no team plays twice on the same day (pseudo-UUIDs ensure this only fires for real conflicts)
        for (List<GameVar> teamDayVars : byTeamDate.values()) {
            if (teamDayVars.size() > 1) {
                Literal[] lits = teamDayVars.stream().map(gv -> (Literal) gv.var()).toArray(Literal[]::new);
                model.addAtMostOne(lits);
            }
        }

        int totalFixtures = fixturesByDiv.values().stream().mapToInt(List::size).sum();

        Literal[] allAssignedLits = isAssigned.values().stream()
            .map(v -> (Literal) v).toArray(Literal[]::new);
        IntVar totalAssignedVar = model.newIntVar(0, totalFixtures, "total_assigned");
        model.addEquality(totalAssignedVar, LinearExpr.sum(allAssignedLits));

        LeagueConfig config = league.config();
        int weekCap = (config != null && config.maxGamesPerWeek() != null)
            ? config.maxGamesPerWeek() : DEFAULT_MAX_GAMES_PER_WEEK;
        int restDays = (config != null && config.minRestDays() != null)
            ? config.minRestDays() : DEFAULT_MIN_REST_DAYS;

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
                    List<GameVar> nextDayVars = byTeamDate.getOrDefault(
                        teamIdStr + "|" + date.plusDays(r), List.of());
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

        long bigM = (long) totalFixtures + 1;
        model.maximize(LinearExpr.weightedSum(
            new IntVar[]{totalAssignedVar, maxWeekLoad},
            new long[]{bigM, -1L}
        ));

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

        int elapsed = (int)((System.currentTimeMillis() - startMs) / 1000);
        int assigned = assignmentsByGameId.size();
        String completionStatus = (assigned == totalFixtures ? "target-met" : "partial")
            + (status == CpSolverStatus.OPTIMAL ? ", optimal" : "");
        System.out.printf("[%d:%02d] Solver complete. %d of %d game slots assigned (%s).%n",
            elapsed / 60, elapsed % 60, assigned, totalFixtures, completionStatus);
        System.out.flush();

        List<DivisionSummary> summaries = fixturesByDiv.entrySet().stream()
            .map(e -> {
                UUID divId = e.getKey();
                int requested = e.getValue().size();
                int assignedCount = assignedByDiv.getOrDefault(divId, 0L).intValue();
                int slots = slotCounts.getOrDefault(divId, 0);
                return new DivisionSummary(divisionName(league, divId), requested, assignedCount, slots);
            })
            .sorted(Comparator.comparing(DivisionSummary::divisionName))
            .toList();

        return PlayoffScheduleResult.success(assignmentsByGameId, status == CpSolverStatus.OPTIMAL, summaries);
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
            int pct = (int)(wallTime() / timeLimitSeconds * 100);
            for (int milestone : new int[]{25, 50, 75}) {
                if (pct >= milestone && lastMilestonePct < milestone) {
                    System.out.printf("[%d:%02d] Solver progress: ~%d%% of time budget used.%n",
                        elapsedSec / 60, elapsedSec % 60, milestone);
                    System.out.flush();
                    lastMilestonePct = milestone;
                }
            }
        }
    }
}
