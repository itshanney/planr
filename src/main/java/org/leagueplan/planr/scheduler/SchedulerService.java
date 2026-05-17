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

    public ScheduleResult generate(League league, LocalDate seasonStart, LocalDate seasonEnd) {
        Loader.loadNativeLibraries();

        Map<UUID, List<Fixture>> fixturesByDiv = generateAllFixtures(league);
        Map<UUID, List<Slot>> slotsByDiv = enumerateAllSlots(league, seasonStart, seasonEnd);

        String infeasibilityMsg = checkSimpleFeasibility(league, fixturesByDiv, slotsByDiv);
        if (infeasibilityMsg != null) {
            return ScheduleResult.failure(infeasibilityMsg);
        }

        return buildAndSolve(league, fixturesByDiv, slotsByDiv);
    }

    // --- Fixture generation (circle method) ---

    private Map<UUID, List<Fixture>> generateAllFixtures(League league) {
        Map<UUID, List<Fixture>> result = new LinkedHashMap<>();
        for (Division div : league.divisions()) {
            if (div.teams().size() < 2) continue;
            result.put(div.id(), generateFixturesForDivision(div));
        }
        return result;
    }

    private List<Fixture> generateFixturesForDivision(Division div) {
        List<UUID> ids = new ArrayList<>();
        for (var t : div.teams()) ids.add(t.id());

        int n = ids.size();
        boolean hasBye = (n % 2 != 0);
        if (hasBye) {
            ids.add(null); // null = bye week placeholder
            n++;
        }

        List<Fixture> firstHalf = new ArrayList<>();
        // Rotating list of all team IDs except the fixed first one
        List<UUID> rotating = new ArrayList<>(ids.subList(1, n));
        UUID fixed = ids.get(0);

        for (int round = 0; round < n - 1; round++) {
            boolean fixedIsHome = (round % 2 == 0);

            // Fixed team paired with the last entry in the rotating list
            UUID opp = rotating.get(n - 2);
            if (fixed != null && opp != null) {
                firstHalf.add(fixedIsHome
                    ? new Fixture(fixed, opp, div.id(), div.gameDurationMinutes())
                    : new Fixture(opp, fixed, div.id(), div.gameDurationMinutes()));
            }

            // Remaining positions paired top-to-bottom
            for (int i = 0; i < (n - 2) / 2; i++) {
                UUID top = rotating.get(i);
                UUID bot = rotating.get(n - 3 - i);
                if (top != null && bot != null) {
                    firstHalf.add((round % 2 == 0)
                        ? new Fixture(top, bot, div.id(), div.gameDurationMinutes())
                        : new Fixture(bot, top, div.id(), div.gameDurationMinutes()));
                }
            }

            // Rotate: bring last entry to front
            UUID last = rotating.remove(rotating.size() - 1);
            rotating.add(0, last);
        }

        // Second half: swap home/away for each first-half fixture (double round-robin)
        List<Fixture> all = new ArrayList<>(firstHalf);
        for (Fixture f : firstHalf) {
            all.add(new Fixture(f.awayTeamId(), f.homeTeamId(), f.divisionId(), f.gameDurationMinutes()));
        }
        return all;
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
            return slotsByDiv; // no open window configured — all slot lists remain empty
        }

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            final LocalDate currentDate = date;
            for (Field field : league.fields()) {
                // Effective open window: per-date override takes priority over league defaults.
                LocalTime openStart = config.sunriseTime();
                LocalTime openEnd = config.sunsetTime();
                for (FieldDateOverride override : field.dateOverrides()) {
                    if (override.date().equals(currentDate)) {
                        openStart = override.openStart();
                        openEnd = override.openEnd();
                        break;
                    }
                }

                // Collect blocks for this field on this date.
                List<FieldBlock> dateBlocks = field.blocks().stream()
                    .filter(b -> b.date().equals(currentDate))
                    .toList();

                // Available sub-windows after subtracting blocks from open window.
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

        // All (fixture, slot) -> BoolVar pairs, flat list plus indexes
        List<GameVar> allGameVars = new ArrayList<>();
        Map<Fixture, List<GameVar>> byFixture = new HashMap<>();
        // Key: fieldId + "|" + date
        Map<String, List<GameVar>> byFieldDate = new HashMap<>();
        // Key: teamId + "|" + date
        Map<String, List<GameVar>> byTeamDate = new HashMap<>();
        // Key: teamId + "|" + weekYear (ISO week of year + year)
        Map<String, List<GameVar>> byTeamWeek = new HashMap<>();

        int varIndex = 0;
        for (Map.Entry<UUID, List<Fixture>> divEntry : fixturesByDiv.entrySet()) {
            UUID divId = divEntry.getKey();
            List<Fixture> fixtures = divEntry.getValue();
            List<Slot> slots = slotsByDiv.getOrDefault(divId, List.of());

            for (Fixture fixture : fixtures) {
                byFixture.put(fixture, new ArrayList<>());
                for (Slot slot : slots) {
                    BoolVar var = model.newBoolVar("g" + varIndex++);
                    GameVar gv = new GameVar(var, fixture, slot);
                    allGameVars.add(gv);
                    byFixture.get(fixture).add(gv);

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

        // FEASIBLE or OPTIMAL: extract assigned games
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
