package org.leagueplan.planr.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public record Field(
    UUID id,
    String name,
    String address,
    List<FieldBlock> blocks,
    List<FieldDateOverride> dateOverrides,
    List<FieldDivisionLock> divisionLocks,
    Integer playoffPriority) {

  public Field {
    blocks = (blocks == null) ? List.of() : blocks;
    dateOverrides = (dateOverrides == null) ? List.of() : dateOverrides;
    divisionLocks = (divisionLocks == null) ? List.of() : divisionLocks;
  }

  public Field withBlockAdded(FieldBlock block) {
    return new Field(
        id,
        name,
        address,
        Stream.concat(blocks.stream(), Stream.of(block)).toList(),
        dateOverrides,
        divisionLocks,
        playoffPriority);
  }

  public Field withBlockReplaced(int zeroBasedIndex, FieldBlock replacement) {
    List<FieldBlock> mutable = new ArrayList<>(blocks);
    mutable.set(zeroBasedIndex, replacement);
    return new Field(id, name, address, List.copyOf(mutable), dateOverrides, divisionLocks, playoffPriority);
  }

  public Field withBlockRemoved(int zeroBasedIndex) {
    List<FieldBlock> mutable = new ArrayList<>(blocks);
    mutable.remove(zeroBasedIndex);
    return new Field(id, name, address, List.copyOf(mutable), dateOverrides, divisionLocks, playoffPriority);
  }

  public Field withOverrideAdded(FieldDateOverride override) {
    return new Field(
        id,
        name,
        address,
        blocks,
        Stream.concat(dateOverrides.stream(), Stream.of(override)).toList(),
        divisionLocks,
        playoffPriority);
  }

  public Field withOverrideReplaced(int zeroBasedIndex, FieldDateOverride replacement) {
    List<FieldDateOverride> mutable = new ArrayList<>(dateOverrides);
    mutable.set(zeroBasedIndex, replacement);
    return new Field(id, name, address, blocks, List.copyOf(mutable), divisionLocks, playoffPriority);
  }

  public Field withOverrideRemoved(int zeroBasedIndex) {
    List<FieldDateOverride> mutable = new ArrayList<>(dateOverrides);
    mutable.remove(zeroBasedIndex);
    return new Field(id, name, address, blocks, List.copyOf(mutable), divisionLocks, playoffPriority);
  }

  public Field withLockAdded(FieldDivisionLock lock) {
    return new Field(
        id,
        name,
        address,
        blocks,
        dateOverrides,
        Stream.concat(divisionLocks.stream(), Stream.of(lock)).toList(),
        playoffPriority);
  }

  public Field withLockRemoved(int zeroBasedIndex) {
    List<FieldDivisionLock> mutable = new ArrayList<>(divisionLocks);
    mutable.remove(zeroBasedIndex);
    return new Field(id, name, address, blocks, dateOverrides, List.copyOf(mutable), playoffPriority);
  }
}
