package org.leagueplan.planr.command;

import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.FieldDateOverride;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(
    name = "override",
    description = "Manage per-date open window overrides for a field.",
    subcommands = {
        FieldOverrideCommand.AddCmd.class,
        FieldOverrideCommand.EditCmd.class,
        FieldOverrideCommand.DeleteCmd.class,
        FieldOverrideCommand.ListCmd.class
    },
    mixinStandardHelpOptions = true
)
public class FieldOverrideCommand implements Runnable {

    @ParentCommand FieldCommand fieldCmd;
    @Spec CommandSpec spec;

    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    static Optional<LocalDate> parseDate(String input) {
        if (input == null) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(input.trim()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    static Optional<LocalTime> parseTime(String input) {
        if (input == null) return Optional.empty();
        try {
            return Optional.of(LocalTime.parse(input.trim(), TIME_FORMAT));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    static void printInvalidOverrideError(Field field, int overrideNumber) {
        if (field.dateOverrides().isEmpty()) {
            System.err.printf("Error: Override #%d not found for \"%s\" (no overrides exist).%n",
                overrideNumber, field.name());
        } else {
            System.err.printf("Error: Override #%d not found for \"%s\" (1–%d are valid).%n",
                overrideNumber, field.name(), field.dateOverrides().size());
        }
    }

    static void printSeasonWarning(League league, LocalDate date) {
        LeagueConfig config = league.config();
        if (config == null || config.seasonStart() == null || config.seasonEnd() == null) return;
        if (date.isBefore(config.seasonStart()) || date.isAfter(config.seasonEnd())) {
            System.out.printf(
                "Warning: Override date %s is outside the configured season (%s to %s). Override saved.%n",
                date, config.seasonStart(), config.seasonEnd());
        }
    }

    // --- Add ---

    @Command(name = "add", description = "Add a per-date open window override to a field.")
    static class AddCmd implements Callable<Integer> {

        @ParentCommand FieldOverrideCommand parent;

        @Parameters(index = "0", paramLabel = "<field>", description = "Field name.")
        String fieldName;

        @Option(names = "--date", required = true, paramLabel = "<YYYY-MM-DD>",
                description = "Date of the override.")
        String dateStr;

        @Option(names = "--start", required = true, paramLabel = "<HH:mm>",
                description = "Override open start time.")
        String startStr;

        @Option(names = "--end", required = true, paramLabel = "<HH:mm>",
                description = "Override open end time.")
        String endStr;

        @Override
        public Integer call() {
            Optional<LocalDate> date = parseDate(dateStr);
            if (date.isEmpty()) {
                System.err.printf("Error: Invalid date \"%s\". Expected YYYY-MM-DD.%n", dateStr);
                return 1;
            }
            Optional<LocalTime> start = parseTime(startStr);
            if (start.isEmpty()) {
                System.err.printf("Error: Invalid time \"%s\". Use HH:mm format (e.g., 10:00).%n", startStr);
                return 1;
            }
            Optional<LocalTime> end = parseTime(endStr);
            if (end.isEmpty()) {
                System.err.printf("Error: Invalid time \"%s\". Use HH:mm format (e.g., 16:00).%n", endStr);
                return 1;
            }
            if (!end.get().isAfter(start.get())) {
                System.err.println("Error: End time must be after start time.");
                return 1;
            }
            try {
                League league = parent.fieldCmd.app.store.load();
                Optional<Field> fieldOpt = league.findField(fieldName);
                if (fieldOpt.isEmpty()) {
                    System.err.printf("Error: Field \"%s\" not found.%n", fieldName);
                    return 1;
                }
                Field field = fieldOpt.get();

                // Check for duplicate date
                for (int i = 0; i < field.dateOverrides().size(); i++) {
                    if (field.dateOverrides().get(i).date().equals(date.get())) {
                        System.err.printf(
                            "Error: An override for %s already exists on \"%s\" (override #%d). "
                            + "Use 'planr field override edit' to change it.%n",
                            date.get(), field.name(), i + 1);
                        return 1;
                    }
                }

                FieldDateOverride override = new FieldDateOverride(
                    UUID.randomUUID(), date.get(), start.get(), end.get());
                Field updated = field.withOverrideAdded(override);
                parent.fieldCmd.app.store.save(league.withFieldReplaced(field.id(), updated));
                int overrideNumber = updated.dateOverrides().size();
                System.out.printf("Override #%d added to \"%s\" (%s, open %s–%s).%n",
                    overrideNumber, field.name(), date.get(),
                    start.get().format(TIME_FORMAT), end.get().format(TIME_FORMAT));
                printSeasonWarning(league, date.get());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- Edit ---

    @Command(name = "edit", description = "Edit a per-date open window override.")
    static class EditCmd implements Callable<Integer> {

        @ParentCommand FieldOverrideCommand parent;

        @Parameters(index = "0", paramLabel = "<field>", description = "Field name.")
        String fieldName;

        @Parameters(index = "1", paramLabel = "<override-number>",
                description = "1-based override number from 'planr field override list'.")
        int overrideNumber;

        @Option(names = "--date", paramLabel = "<YYYY-MM-DD>", description = "New date.")
        String newDateStr;

        @Option(names = "--start", paramLabel = "<HH:mm>", description = "New open start time.")
        String newStartStr;

        @Option(names = "--end", paramLabel = "<HH:mm>", description = "New open end time.")
        String newEndStr;

        @Override
        public Integer call() {
            if (newDateStr == null && newStartStr == null && newEndStr == null) {
                System.err.println(
                    "Error: At least one of --date, --start, or --end must be provided.");
                return 1;
            }

            Optional<LocalDate> newDate = Optional.empty();
            if (newDateStr != null) {
                newDate = parseDate(newDateStr);
                if (newDate.isEmpty()) {
                    System.err.printf("Error: Invalid date \"%s\". Expected YYYY-MM-DD.%n", newDateStr);
                    return 1;
                }
            }
            Optional<LocalTime> newStart = Optional.empty();
            if (newStartStr != null) {
                newStart = parseTime(newStartStr);
                if (newStart.isEmpty()) {
                    System.err.printf("Error: Invalid time \"%s\". Use HH:mm format.%n", newStartStr);
                    return 1;
                }
            }
            Optional<LocalTime> newEnd = Optional.empty();
            if (newEndStr != null) {
                newEnd = parseTime(newEndStr);
                if (newEnd.isEmpty()) {
                    System.err.printf("Error: Invalid time \"%s\". Use HH:mm format.%n", newEndStr);
                    return 1;
                }
            }

            try {
                League league = parent.fieldCmd.app.store.load();
                Optional<Field> fieldOpt = league.findField(fieldName);
                if (fieldOpt.isEmpty()) {
                    System.err.printf("Error: Field \"%s\" not found.%n", fieldName);
                    return 1;
                }
                Field field = fieldOpt.get();
                int idx = overrideNumber - 1;
                if (idx < 0 || idx >= field.dateOverrides().size()) {
                    printInvalidOverrideError(field, overrideNumber);
                    return 1;
                }
                FieldDateOverride existing = field.dateOverrides().get(idx);

                LocalDate effectiveDate = newDate.orElse(existing.date());
                LocalTime effectiveStart = newStart.orElse(existing.openStart());
                LocalTime effectiveEnd = newEnd.orElse(existing.openEnd());

                if (!effectiveEnd.isAfter(effectiveStart)) {
                    System.err.println("Error: End time must be after start time.");
                    return 1;
                }

                // Check date uniqueness against other overrides (exclude this one)
                if (newDate.isPresent()) {
                    for (int i = 0; i < field.dateOverrides().size(); i++) {
                        if (i != idx && field.dateOverrides().get(i).date().equals(effectiveDate)) {
                            System.err.printf(
                                "Error: An override for %s already exists on \"%s\" (override #%d). "
                                + "Dates must be unique per field.%n",
                                effectiveDate, field.name(), i + 1);
                            return 1;
                        }
                    }
                }

                FieldDateOverride replacement = new FieldDateOverride(
                    existing.id(), effectiveDate, effectiveStart, effectiveEnd);
                Field updated = field.withOverrideReplaced(idx, replacement);
                parent.fieldCmd.app.store.save(league.withFieldReplaced(field.id(), updated));
                System.out.printf("Override #%d on \"%s\" updated.%n", overrideNumber, field.name());
                printSeasonWarning(league, effectiveDate);
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- Delete ---

    @Command(name = "delete", description = "Delete a per-date open window override.")
    static class DeleteCmd implements Callable<Integer> {

        @ParentCommand FieldOverrideCommand parent;

        @Parameters(index = "0", paramLabel = "<field>", description = "Field name.")
        String fieldName;

        @Parameters(index = "1", paramLabel = "<override-number>",
                description = "1-based override number from 'planr field override list'.")
        int overrideNumber;

        @Override
        public Integer call() {
            try {
                League league = parent.fieldCmd.app.store.load();
                Optional<Field> fieldOpt = league.findField(fieldName);
                if (fieldOpt.isEmpty()) {
                    System.err.printf("Error: Field \"%s\" not found.%n", fieldName);
                    return 1;
                }
                Field field = fieldOpt.get();
                int idx = overrideNumber - 1;
                if (idx < 0 || idx >= field.dateOverrides().size()) {
                    printInvalidOverrideError(field, overrideNumber);
                    return 1;
                }
                Field updated = field.withOverrideRemoved(idx);
                parent.fieldCmd.app.store.save(league.withFieldReplaced(field.id(), updated));
                System.out.printf("Override #%d on \"%s\" deleted.%n", overrideNumber, field.name());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- List ---

    @Command(name = "list", description = "List all per-date open window overrides for a field.")
    static class ListCmd implements Callable<Integer> {

        @ParentCommand FieldOverrideCommand parent;

        @Parameters(index = "0", paramLabel = "<field>", description = "Field name.")
        String fieldName;

        @Override
        public Integer call() {
            try {
                League league = parent.fieldCmd.app.store.load();
                Optional<Field> fieldOpt = league.findField(fieldName);
                if (fieldOpt.isEmpty()) {
                    System.err.printf("Error: Field \"%s\" not found.%n", fieldName);
                    return 1;
                }
                Field field = fieldOpt.get();
                if (field.dateOverrides().isEmpty()) {
                    System.out.printf(
                        "No date overrides for \"%s\". Use 'planr field override add' to create one.%n",
                        field.name());
                    return 0;
                }
                printTable(field);
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        private void printTable(Field field) {
            String fmt = "%-3s  %-10s  %-10s  %-8s%n";
            System.out.printf(fmt, "#", "DATE", "OPEN START", "OPEN END");
            System.out.printf(fmt, "-", "----------", "----------", "--------");
            for (int i = 0; i < field.dateOverrides().size(); i++) {
                FieldDateOverride o = field.dateOverrides().get(i);
                System.out.printf(fmt,
                    i + 1,
                    o.date().toString(),
                    o.openStart().format(TIME_FORMAT),
                    o.openEnd().format(TIME_FORMAT));
            }
        }
    }
}
