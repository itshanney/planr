package org.leagueplan.planr.scheduler;

import org.leagueplan.planr.model.BracketSide;
import org.leagueplan.planr.model.PlayoffGame;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Generates a double-elimination bracket for N teams (2 ≤ N ≤ 16).
 *
 * Bracket size P = next power of 2 ≥ N. B = P - N bye slots are assigned to
 * seeds 1..B in Winners R1. Seeds B+1..N face each other in Winners R1.
 * Subsequent rounds use positional references ("W of G3", "L of G2").
 *
 * Slot counts:
 *   Winners bracket:  P - 1 slots  (including B bye slots)
 *   Losers bracket:   P - 2 slots  (no byes in losers)
 *   Championship:     2 slots      (final + conditional re-match)
 *   Total real game slots: 2N - 1 + 1 conditional = 2N
 *   Total with byes:  2N + B
 */
public class PlayoffBracketService {

    public record BracketSlot(
        UUID gameId,
        String round,
        BracketSide bracketSide,
        String positionA,
        String positionB,
        boolean isConditional,
        boolean isBye
    ) {}

    // Carries a game UUID and its reference prefix ("W of" or "L of") so that
    // L-bracket survivors advancing through the bracket can be referenced correctly
    // regardless of whether they came from a real L-R1 game or bypassed as an orphan
    // loser when the number of real W-R1 games is odd.
    private record GameRef(UUID gameId, String prefix) {
        String label(List<BracketSlot> slots) {
            return prefix + " " + slotLabel(slots, gameId);
        }
    }

    /**
     * Generates all bracket slots for the given seed list.
     * seeds[0] is seed 1 (top seed), seeds[N-1] is seed N.
     */
    public List<BracketSlot> generateBracket(List<String> seeds) {
        int n = seeds.size();
        int p = nextPowerOfTwo(n);
        int byes = p - n;

        List<BracketSlot> slots = new ArrayList<>();

        // --- Winners bracket R1 ---
        // Standard bracket ordering: position 1 vs P, 2 vs P-1, etc.
        // Position indices 1..P; positions n+1..P are Byes.
        // We generate P/2 pairings.
        List<UUID> winnersR1Ids = new ArrayList<>();
        for (int i = 0; i < p / 2; i++) {
            int seedA = i + 1;          // positions 1, 2, 3, ... P/2
            int seedB = p - i;          // positions P, P-1, P-2, ... P/2+1
            boolean byeGame = seedA <= byes || seedB > n;
            // In standard seeding, top seeds get byes: seed 1 vs P (bye), seed 2 vs P-1 (bye), ...
            // seedB > n means that position is a Bye slot.
            String posA = seedA <= n ? seeds.get(seedA - 1) : "BYE";
            String posB = seedB <= n ? seeds.get(seedB - 1) : "BYE";
            boolean isBye = byeGame;

            UUID gameId = UUID.randomUUID();
            winnersR1Ids.add(gameId);
            slots.add(new BracketSlot(gameId, "Winners R1", BracketSide.WINNERS,
                posA, posB, false, isBye));
        }

        // --- Winners bracket subsequent rounds ---
        // Track game IDs by round so we can build positional references
        List<UUID> prevWinnersIds = winnersR1Ids;
        List<List<UUID>> winnerRoundIds = new ArrayList<>();
        winnerRoundIds.add(winnersR1Ids);

        int winnersRound = 2;
        while (prevWinnersIds.size() > 1) {
            List<UUID> roundIds = new ArrayList<>();
            for (int i = 0; i < prevWinnersIds.size(); i += 2) {
                UUID gameId = UUID.randomUUID();
                roundIds.add(gameId);
                UUID srcA = prevWinnersIds.get(i);
                UUID srcB = prevWinnersIds.get(i + 1);
                slots.add(new BracketSlot(gameId, "Winners R" + winnersRound,
                    BracketSide.WINNERS,
                    "W of " + slotLabel(slots, srcA),
                    "W of " + slotLabel(slots, srcB),
                    false, false));
            }
            winnerRoundIds.add(roundIds);
            prevWinnersIds = roundIds;
            winnersRound++;
        }
        // prevWinnersIds now holds the single Winners Final game ID
        UUID winnersFinalId = prevWinnersIds.get(0);

        // --- Losers bracket ---
        // Losers R1: losers from each Winners R1 non-bye pairing feed into losers.
        // Byes produce automatic winners; their "loser" doesn't enter the losers bracket.
        List<UUID> realWinnersR1 = winnersR1Ids.stream()
            .filter(id -> !isById(slots, id))
            .toList();

        // Winners R2, R3, ... (excluding R1 and the Final) drop losers into the L bracket.
        List<List<UUID>> winnersRoundDropIds = new ArrayList<>();
        for (int i = 1; i < winnerRoundIds.size() - 1; i++) {
            winnersRoundDropIds.add(winnerRoundIds.get(i));
        }

        int losersRound = 1;
        int winnersFeedIndex = 0;

        // L-R1: pair real W-R1 losers in pairs. When the count is odd, the last loser
        // has no opponent and bypasses L-R1 entirely, entering the L-R2 feed round
        // directly and referenced as "L of <sourceGame>" rather than "W of <L-R1 game>".
        List<GameRef> prevLosers = new ArrayList<>();
        int realCount = realWinnersR1.size();
        int pairedCount = (realCount / 2) * 2;

        for (int i = 0; i < pairedCount; i += 2) {
            UUID gameId = UUID.randomUUID();
            slots.add(new BracketSlot(gameId, "Losers R" + losersRound, BracketSide.LOSERS,
                "L of " + slotLabel(slots, realWinnersR1.get(i)),
                "L of " + slotLabel(slots, realWinnersR1.get(i + 1)),
                false, false));
            prevLosers.add(new GameRef(gameId, "W of"));
        }
        if (pairedCount > 0) losersRound++;
        if (realCount % 2 == 1) {
            // Orphan: advances without a game, carries "L of" prefix into the next round
            prevLosers.add(new GameRef(realWinnersR1.get(pairedCount), "L of"));
        }

        // Feed rounds: pair surviving L-bracket teams against losers from W bracket rounds.
        // Elimination rounds: pair survivors within the L bracket.
        while (winnersFeedIndex < winnersRoundDropIds.size() || prevLosers.size() > 1) {
            if (winnersFeedIndex < winnersRoundDropIds.size()) {
                // Feed round: each dropper from the W bracket faces a surviving L-bracket team
                List<UUID> droppers = winnersRoundDropIds.get(winnersFeedIndex);
                winnersFeedIndex++;

                List<GameRef> roundRefs = new ArrayList<>();
                for (int i = 0; i < droppers.size(); i++) {
                    UUID gameId = UUID.randomUUID();
                    roundRefs.add(new GameRef(gameId, "W of"));
                    String posA = i < prevLosers.size()
                        ? prevLosers.get(i).label(slots)
                        : "TBD";
                    slots.add(new BracketSlot(gameId, "Losers R" + losersRound,
                        BracketSide.LOSERS,
                        posA,
                        "L of " + slotLabel(slots, droppers.get(i)),
                        false, false));
                }
                prevLosers = roundRefs;
                losersRound++;
            }

            if (prevLosers.size() > 1) {
                // Elimination round: pair surviving L-bracket teams against each other
                List<GameRef> roundRefs = new ArrayList<>();
                for (int i = 0; i < prevLosers.size(); i += 2) {
                    UUID gameId = UUID.randomUUID();
                    roundRefs.add(new GameRef(gameId, "W of"));
                    slots.add(new BracketSlot(gameId, "Losers R" + losersRound,
                        BracketSide.LOSERS,
                        prevLosers.get(i).label(slots),
                        prevLosers.get(i + 1).label(slots),
                        false, false));
                }
                prevLosers = roundRefs;
                losersRound++;
            }
        }

        // prevLosers.get(0) is the sole L-bracket finalist. Its prefix determines the
        // reference used in the Championship slot (normally "W of <L-Final>", but "L of
        // <W-R1 game>" for degenerate cases like N=2 or N=3 where no L-bracket game exists).
        String losersFinalRef = prevLosers.get(0).label(slots);
        UUID losersFinalId = prevLosers.get(0).gameId();

        // --- Championship ---
        UUID champId = UUID.randomUUID();
        slots.add(new BracketSlot(champId, "Championship", BracketSide.CHAMPIONSHIP,
            "W of " + slotLabel(slots, winnersFinalId),
            losersFinalRef,
            false, false));

        UUID rematchId = UUID.randomUUID();
        slots.add(new BracketSlot(rematchId, "Championship", BracketSide.CHAMPIONSHIP,
            losersFinalRef,
            "W of " + slotLabel(slots, champId),
            true, false));

        return slots;
    }

    private static boolean isById(List<BracketSlot> slots, UUID id) {
        return slots.stream().filter(s -> s.gameId().equals(id)).findFirst()
            .map(BracketSlot::isBye).orElse(false);
    }

    private static String slotLabel(List<BracketSlot> slots, UUID id) {
        return slots.stream().filter(s -> s.gameId().equals(id)).findFirst()
            .map(s -> "G" + (slots.indexOf(s) + 1))
            .orElse("G?");
    }

    public static PlayoffGame toPlayoffGame(BracketSlot slot) {
        return new PlayoffGame(
            slot.gameId(), slot.round(), slot.bracketSide(),
            slot.positionA(), slot.positionB(),
            null, null, null,
            slot.isConditional(), slot.isBye()
        );
    }

    public static int nextPowerOfTwo(int n) {
        if (n <= 1) return 1;
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }
}
