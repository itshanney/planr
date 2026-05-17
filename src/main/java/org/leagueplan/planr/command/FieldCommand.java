package org.leagueplan.planr.command;

import org.leagueplan.planr.PlanrApp;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(
    name = "field",
    description = "Manage baseball fields.",
    subcommands = {
        FieldCommand.AddCmd.class,
        FieldCommand.EditCmd.class,
        FieldCommand.DeleteCmd.class,
        FieldCommand.ListCmd.class,
        FieldBlockCommand.class,
        FieldOverrideCommand.class
    },
    mixinStandardHelpOptions = true
)
public class FieldCommand implements Runnable {

    @ParentCommand PlanrApp app;
    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(name = "add", description = "Add a new field.")
    static class AddCmd implements Callable<Integer> {

        @ParentCommand FieldCommand parent;

        @Parameters(index = "0", paramLabel = "<name>", description = "Field name.")
        String name;

        @Option(names = "--address", paramLabel = "<address>",
                description = "Optional address or location description.")
        String address;

        @Override
        public Integer call() {
            if (name.isBlank()) {
                System.err.println("Error: Field name cannot be empty.");
                return 1;
            }
            try {
                League league = parent.app.store.load();
                if (league.hasField(name)) {
                    System.err.printf("Error: Field \"%s\" already exists.%n", name);
                    return 1;
                }
                Field field = new Field(UUID.randomUUID(), name, address, List.of(), List.of());
                parent.app.store.save(league.withFieldAdded(field));
                System.out.printf("Field \"%s\" added.%n", name);
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "edit", description = "Edit a field's name or address.")
    static class EditCmd implements Callable<Integer> {

        @ParentCommand FieldCommand parent;

        @Parameters(index = "0", paramLabel = "<name>", description = "Current field name.")
        String name;

        @Option(names = "--name", paramLabel = "<new-name>", description = "New field name.")
        String newName;

        @Option(names = "--address", paramLabel = "<address>",
                description = "New address; pass empty string to clear.")
        String newAddress;

        @Override
        public Integer call() {
            if (newName == null && newAddress == null) {
                System.err.println("Error: At least one of --name or --address must be provided.");
                return 1;
            }
            if (newName != null && newName.isBlank()) {
                System.err.println("Error: Field name cannot be empty.");
                return 1;
            }
            try {
                League league = parent.app.store.load();
                var existing = league.findField(name);
                if (existing.isEmpty()) {
                    System.err.printf("Error: Field \"%s\" not found.%n", name);
                    return 1;
                }
                if (newName != null && !newName.equalsIgnoreCase(name) && league.hasField(newName)) {
                    System.err.printf("Error: Field \"%s\" already exists.%n", newName);
                    return 1;
                }
                Field updated = applyEdits(existing.get(), newName, newAddress);
                parent.app.store.save(league.withFieldReplaced(existing.get().id(), updated));
                System.out.printf("Field \"%s\" updated.%n", updated.name());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        private Field applyEdits(Field field, String newName, String newAddress) {
            String resolvedName = (newName != null) ? newName : field.name();
            // Empty string clears the address; null means unchanged.
            String resolvedAddress = (newAddress == null) ? field.address()
                                   : (newAddress.isBlank() ? null : newAddress);
            return new Field(field.id(), resolvedName, resolvedAddress, field.blocks(), field.dateOverrides());
        }
    }

    @Command(name = "delete", description = "Delete a field and all its blocks and overrides.")
    static class DeleteCmd implements Callable<Integer> {

        @ParentCommand FieldCommand parent;

        @Parameters(index = "0", paramLabel = "<name>", description = "Field name.")
        String name;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                var existing = league.findField(name);
                if (existing.isEmpty()) {
                    System.err.printf("Error: Field \"%s\" not found.%n", name);
                    return 1;
                }
                int blockCount = existing.get().blocks().size();
                int overrideCount = existing.get().dateOverrides().size();
                parent.app.store.save(league.withFieldRemoved(existing.get().id()));
                System.out.printf("Field \"%s\" deleted (%d block(s), %d override(s) removed).%n",
                    existing.get().name(), blockCount, overrideCount);
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "list", description = "List all fields.")
    static class ListCmd implements Callable<Integer> {

        @ParentCommand FieldCommand parent;

        @Override
        public Integer call() {
            try {
                League league = parent.app.store.load();
                if (league.fields().isEmpty()) {
                    System.out.println("No fields configured. Use 'planr field add' to create one.");
                    return 0;
                }
                printTable(league.fields());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        private void printTable(List<Field> fields) {
            int nameWidth = Math.max("NAME".length(),
                fields.stream().mapToInt(f -> f.name().length()).max().orElse(0));
            int addressWidth = Math.max("ADDRESS".length(),
                fields.stream()
                    .mapToInt(f -> f.address() == null ? "(none)".length() : f.address().length())
                    .max().orElse(0));
            int blocksWidth = Math.max("BLOCKS".length(),
                fields.stream()
                    .mapToInt(f -> String.valueOf(f.blocks().size()).length())
                    .max().orElse(0));
            int overridesWidth = Math.max("OVERRIDES".length(),
                fields.stream()
                    .mapToInt(f -> String.valueOf(f.dateOverrides().size()).length())
                    .max().orElse(0));

            String fmt = "%-" + nameWidth + "s    %-" + addressWidth + "s    %-"
                + blocksWidth + "s    %-" + overridesWidth + "s%n";
            System.out.printf(fmt, "NAME", "ADDRESS", "BLOCKS", "OVERRIDES");
            System.out.printf(fmt, "-".repeat(nameWidth), "-".repeat(addressWidth),
                "-".repeat(blocksWidth), "-".repeat(overridesWidth));
            fields.forEach(f -> System.out.printf(fmt,
                f.name(),
                f.address() == null ? "(none)" : f.address(),
                f.blocks().size(),
                f.dateOverrides().size()));
        }
    }
}
