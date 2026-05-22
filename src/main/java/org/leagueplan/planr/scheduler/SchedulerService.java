package org.leagueplan.planr.scheduler;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
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
import java.util.StringJoiner;
import java.util.UUID;

public class SchedulerService {

    private static final int BUFFER_MINUTES = 15;
    private static final int GRID_MINUTES = 15;
    private static final int SOLVER_TIME_LIMIT_SECONDS = 60;

    private record GameVar(BoolVar var, Fixture fixture, Slot slot) {}

    /**
     * Phase 2: assigns dates, times, and fields to every game in the confirmed team schedule.
     * Season dates are read from league.config(). Fixtures are read from league.teamSchedule().
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

        Map<UUID, List<Fixture>> fixturesByDiv = toFixturesByDiv(league.teamSchedule());
        Map<UUID, List<Slot>> slotsByDiv =
            enumerateAllSlots(league, config.seasonStart(), config.seasonEnd());

        String infeasibilityMsg = checkSimpleFeasibility(league, fixturesByDiv, slotsByDiv);
        if (infeasibilityMsg != null) {
            return ScheduleResult.failure(infeasibilityMsg);
        }

        return buildAndSolve(league, fixturesByDiv, slotsByDiv);
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

    // --- Pre-solve feasibility check ---

    private String checkSimpleFeasibility(
            League league,
            Map<UUID, List<Fixture>> fixturesByDiv,
            Map<UUID, List<Slot>> slotsByDiv) {

        StringJoiner lines = new StringJoiner("%n");
        boolean anyShortfall = false;

        for (Map.Entry<UUID, List<Fixture>> entry : fixturesByDiv.entrySet()) {
            UUID divId = entry.getKey();
            int fixtures = entry.getValue().size();
            int slots = slotsByDiv.getOrDefault(divId, List.of()).size();
            String divName = divisionName(league, divId);
            if (slots < fixtures) {
                lines.add(String.format("  %s: %d games required, %d slots available across the season window.",
                    divName, fixtures, slots));
                anyShortfall = true;
            } else {
                lines.add(String.format("  %s: %d games required, %d slots available (OK).", divName, fixtures, slots));
            }
        }

        if (!anyShortfall) return null;

        return "Error: Cannot generate a valid schedule. Insufficient field availability:%n"
            + lines
            + "%nAdd more field availability or extend the season date range.";
    }

    // --- CP-SAT model construction and solve ---

    private ScheduleResult buildAndSolve(
            League league,
            Map<UUID, List<Fixture>> fixturesByDiv,
            Map<UUID, List<Slot>> slotsByDiv) {

        CpModel model = new CpModel();

        List<GameVar> allGameVars = new ArrayList<>();
        // Keyed by fixture.gameId() so duplicate matchup directions (fill games) remain distinct.
        Map<UUID, List<GameVar>> byFixture = new HashMap<>();
        Map<String, List<GameVar>> byFieldDate = new HashMap<>();
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

                    String fieldDateKey = slot.fieldId() + "|" + slot.date();
                    byFieldDate.computeIfAbsent(fieldDateKey, k -> new ArrayList<>()).add(gv);

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

        // C1: Each fixture assigned exactly once
        for (List<GameVar> vars : byFixture.values()) {
            Literal[] lits = vars.stream().map(gv -> (Literal) gv.var()).toArray(Literal[]::new);
            model.addExactlyOne(lits);
        }

        // C2: No two games on the same field overlap (including 15-min buffer after each game)
        for (List<GameVar> fieldDateVars : byFieldDate.values()) {
            for (int i = 0; i < fieldDateVars.size(); i++) {
                GameVar a = fieldDateVars.get(i);
                for (int j = i + 1; j < fieldDateVars.size(); j++) {
                    GameVar b = fieldDateVars.get(j);
                    if (timeSlotsConflict(a, b)) {
                        model.addAtMostOne(new Literal[]{a.var(), b.var()});
                    }
                }
            }
        }

        // C3: No team plays more than once on the same calendar day
        for (List<GameVar> teamDayVars : byTeamDate.values()) {
            if (teamDayVars.size() > 1) {
                Literal[] lits = teamDayVars.stream().map(gv -> (Literal) gv.var()).toArray(Literal[]::new);
                model.addAtMostOne(lits);
            }
        }

        // Objective: minimize the maximum number of games any team plays in a single ISO week
        int totalFixtures = fixturesByDiv.values().stream().mapToInt(List::size).sum();
        IntVar maxWeekLoad = model.newIntVar(0, totalFixtures, "max_week_load");
        for (List<GameVar> weekVars : byTeamWeek.values()) {
            if (!weekVars.isEmpty()) {
                Literal[] lits = weekVars.stream().map(gv -> (Literal) gv.var()).toArray(Literal[]::new);
                model.addLessOrEqual(LinearExpr.sum(lits), maxWeekLoad);
            }
        }
        model.minimize(maxWeekLoad);

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(SOLVER_TIME_LIMIT_SECONDS);
        CpSolverStatus status = solver.solve(model);

        if (status == CpSolverStatus.INFEASIBLE) {
            return ScheduleResult.failure(
                "Error: Cannot generate a valid schedule given the current constraints. "
                + "Try adding more field availability or extending the season date range.");
        }
        if (status == CpSolverStatus.UNKNOWN) {
            return ScheduleResult.failure(
                "Error: Schedule generation timed out after 60 seconds without finding a valid schedule. "
                + "Reduce the number of teams, extend the season, or add more field availability.");
        }

        List<ScheduledGame> games = new ArrayList<>();
        for (GameVar gv : allGameVars) {
            if (solver.booleanValue(gv.var())) {
                games.add(buildScheduledGame(league, gv.fixture(), gv.slot()));
            }
        }
        games.sort(Comparator.comparing(ScheduledGame::date)
            .thenComparing(ScheduledGame::startTime)
            .thenComparing(ScheduledGame::fieldName));

        return ScheduleResult.success(games, status == CpSolverStatus.OPTIMAL);
    }

    private boolean timeSlotsConflict(GameVar a, GameVar b) {
        int aStart = a.slot().startMinutes();
        int aEnd = aStart + a.fixture().gameDurationMinutes() + BUFFER_MINUTES;
        int bStart = b.slot().startMinutes();
        int bEnd = bStart + b.fixture().gameDurationMinutes() + BUFFER_MINUTES;
        return aStart < bEnd && bStart < aEnd;
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
}
