package org.leagueplan.planr.command;

import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.FieldBlock;
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
    name = "block",
    description = "Manage date-specific field blocks.",
    subcommands = {
        FieldBlockCommand.AddCmd.class,
        FieldBlockCommand.EditCmd.class,
        FieldBlockCommand.DeleteCmd.class,
        FieldBlockCommand.ListCmd.class
    },
    mixinStandardHelpOptions = true
)
public class FieldBlockCommand implements Runnable {

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

    static void printInvalidBlockError(Field field, int blockNumber) {
        if (field.blocks().isEmpty()) {
            System.err.printf("Error: Block #%d not found for \"%s\" (no blocks exist).%n",
                blockNumber, field.name());
        } else {
            System.err.printf("Error: Block #%d not found for \"%s\" (1–%d are valid).%n",
                blockNumber, field.name(), field.blocks().size());
        }
    }

    static void printSeasonWarning(League league, LocalDate date) {
        LeagueConfig config = league.config();
        if (config == null || config.seasonStart() == null || config.seasonEnd() == null) return;
        if (date.isBefore(config.seasonStart()) || date.isAfter(config.seasonEnd())) {
            System.out.printf(
                "Warning: Block date %s is outside the configured season (%s to %s). Block saved.%n",
                date, config.seasonStart(), config.seasonEnd());
        }
    }

    // --- Add ---

    @Command(name = "add", description = "Add a date-specific block to a field.")
    static class AddCmd implements Callable<Integer> {

        @ParentCommand FieldBlockCommand parent;

        @Parameters(index = "0", paramLabel = "<field>", description = "Field name.")
        String fieldName;

        @Option(names = "--date", required = true, paramLabel = "<YYYY-MM-DD>",
                description = "Date of the block.")
        String dateStr;

        @Option(names = "--start", required = true, paramLabel = "<HH:mm>",
                description = "Block start time.")
        String startStr;

        @Option(names = "--end", required = true, paramLabel = "<HH:mm>",
                description = "Block end time.")
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
                System.err.printf("Error: Invalid time \"%s\". Use HH:mm format (e.g., 09:00).%n", startStr);
                return 1;
            }
            Optional<LocalTime> end = parseTime(endStr);
            if (end.isEmpty()) {
                System.err.printf("Error: Invalid time \"%s\". Use HH:mm format (e.g., 12:00).%n", endStr);
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
                FieldBlock block = new FieldBlock(UUID.randomUUID(), date.get(), start.get(), end.get());
                Field updated = fieldOpt.get().withBlockAdded(block);
                parent.fieldCmd.app.store.save(league.withFieldReplaced(fieldOpt.get().id(), updated));
                int blockNumber = updated.blocks().size();
                System.out.printf("Block #%d added to \"%s\" (%s, %s–%s).%n",
                    blockNumber, fieldOpt.get().name(), date.get(),
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

    @Command(name = "edit", description = "Edit a field block.")
    static class EditCmd implements Callable<Integer> {

        @ParentCommand FieldBlockCommand parent;

        @Parameters(index = "0", paramLabel = "<field>", description = "Field name.")
        String fieldName;

        @Parameters(index = "1", paramLabel = "<block-number>",
                description = "1-based block number from 'planr field block list'.")
        int blockNumber;

        @Option(names = "--date", paramLabel = "<YYYY-MM-DD>", description = "New date.")
        String newDateStr;

        @Option(names = "--start", paramLabel = "<HH:mm>", description = "New start time.")
        String newStartStr;

        @Option(names = "--end", paramLabel = "<HH:mm>", description = "New end time.")
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
                int idx = blockNumber - 1;
                if (idx < 0 || idx >= field.blocks().size()) {
                    printInvalidBlockError(field, blockNumber);
                    return 1;
                }
                FieldBlock existing = field.blocks().get(idx);

                // Merge: provided option wins; existing value kept otherwise.
                LocalDate effectiveDate = newDate.orElse(existing.date());
                LocalTime effectiveStart = newStart.orElse(existing.startTime());
                LocalTime effectiveEnd = newEnd.orElse(existing.endTime());

                if (!effectiveEnd.isAfter(effectiveStart)) {
                    System.err.println("Error: End time must be after start time.");
                    return 1;
                }

                FieldBlock replacement = new FieldBlock(existing.id(), effectiveDate, effectiveStart, effectiveEnd);
                Field updated = field.withBlockReplaced(idx, replacement);
                parent.fieldCmd.app.store.save(league.withFieldReplaced(field.id(), updated));
                System.out.printf("Block #%d on \"%s\" updated.%n", blockNumber, field.name());
                printSeasonWarning(league, effectiveDate);
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- Delete ---

    @Command(name = "delete", description = "Delete a field block.")
    static class DeleteCmd implements Callable<Integer> {

        @ParentCommand FieldBlockCommand parent;

        @Parameters(index = "0", paramLabel = "<field>", description = "Field name.")
        String fieldName;

        @Parameters(index = "1", paramLabel = "<block-number>",
                description = "1-based block number from 'planr field block list'.")
        int blockNumber;

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
                int idx = blockNumber - 1;
                if (idx < 0 || idx >= field.blocks().size()) {
                    printInvalidBlockError(field, blockNumber);
                    return 1;
                }
                Field updated = field.withBlockRemoved(idx);
                parent.fieldCmd.app.store.save(league.withFieldReplaced(field.id(), updated));
                System.out.printf("Block #%d on \"%s\" deleted.%n", blockNumber, field.name());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- List ---

    @Command(name = "list", description = "List all blocks for a field.")
    static class ListCmd implements Callable<Integer> {

        @ParentCommand FieldBlockCommand parent;

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
                if (field.blocks().isEmpty()) {
                    System.out.printf(
                        "No blocks for \"%s\". Use 'planr field block add' to create one.%n",
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
            String fmt = "%-3s  %-10s  %-5s  %-5s%n";
            System.out.printf(fmt, "#", "DATE", "START", "END");
            System.out.printf(fmt, "-", "----------", "-----", "-----");
            for (int i = 0; i < field.blocks().size(); i++) {
                FieldBlock b = field.blocks().get(i);
                System.out.printf(fmt,
                    i + 1,
                    b.date().toString(),
                    b.startTime().format(TIME_FORMAT),
                    b.endTime().format(TIME_FORMAT));
            }
        }
    }
}
