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
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.FieldBlock;
import org.leagueplan.planr.model.FieldDateOverride;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import org.leagueplan.planr.model.ScheduledGame;
import org.leagueplan.planr.model.TeamGame;
import org.leagueplan.planr.model.TeamSchedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SchedulerService {

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
                LocalTime openStart = config.sunriseTime();
                LocalTime openEnd = config.sunsetTime();
                for (FieldDateOverride override : field.dateOverrides()) {
                    if (override.date().equals(date)) {
                        openStart = override.openStart();
                        openEnd = override.openEnd();
                        break;
                    }
                }

                final LocalDate currentDate = date;
                List<FieldBlock> dateBlocks = field.blocks().stream()
                    .filter(b -> b.date().equals(currentDate))
                    .toList();

                for (LocalTime[] window : subtractBlocks(openStart, openEnd, dateBlocks)) {
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
                LocalTime openStart = config.sunriseTime();
                LocalTime openEnd = config.sunsetTime();
                for (FieldDateOverride override : field.dateOverrides()) {
                    if (override.date().equals(currentDate)) {
                        openStart = override.openStart();
                        openEnd = override.openEnd();
                        break;
                    }
                }

                List<FieldBlock> dateBlocks = field.blocks().stream()
                    .filter(b -> b.date().equals(currentDate))
                    .toList();

                List<LocalTime[]> available = subtractBlocks(openStart, openEnd, dateBlocks);

                for (UUID divId : slotsByDiv.keySet()) {
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

        // Week-load constraint: track max games any team plays in a single ISO week
        IntVar maxWeekLoad = model.newIntVar(0, totalFixtures, "max_week_load");
        for (List<GameVar> weekVars : byTeamWeek.values()) {
            if (!weekVars.isEmpty()) {
                Literal[] lits = weekVars.stream().map(gv -> (Literal) gv.var()).toArray(Literal[]::new);
                model.addLessOrEqual(LinearExpr.sum(lits), maxWeekLoad);
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
