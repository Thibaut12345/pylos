package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.PylosBoard;
import be.kuleuven.pylos.game.PylosGameState;
import be.kuleuven.pylos.game.PylosLocation;
import be.kuleuven.pylos.game.PylosPlayerColor;
import be.kuleuven.pylos.game.PylosSphere;

/**
 * D4 symmetry helpers for the 4x4x4 Pylos pyramid.
 *
 * The eight location sequences deliberately follow the ordering used by
 * PylosBoard's private symmetry table. This class does not modify the engine
 * and treats spheres of the same colour as interchangeable for state keys.
 */
public final class PylosSymmetry {
    private static final int LOCATION_COUNT = 30;
    private static final int SYMMETRY_COUNT = 8;
    private static final int[][][] COORDINATES = createCoordinateSequences();

    private PylosSymmetry() { }

    /** Canonical state and the symmetry that maps its current board to that key. */
    public static final class Position {
        private final String key;
        private final int symmetry;

        private Position(String key, int symmetry) {
            this.key = key;
            this.symmetry = symmetry;
        }

        public String getKey() { return key; }

        /** Returns the canonical sequence index for a location in this board orientation. */
        public int indexOf(PylosLocation location) {
            if (location == null) return -1;
            for (int i = 0; i < LOCATION_COUNT; i++) {
                int[] c = COORDINATES[symmetry][i];
                if (c[0] == location.X && c[1] == location.Y && c[2] == location.Z) return i;
            }
            throw new IllegalArgumentException("Location is not part of the standard Pylos board: " + location);
        }

        /** Resolves a canonical sequence index to a location on the supplied board. */
        public PylosLocation locationAt(PylosBoard board, int canonicalIndex) {
            if (board == null) throw new IllegalArgumentException("Board is required");
            if (canonicalIndex < 0 || canonicalIndex >= LOCATION_COUNT)
                throw new IllegalArgumentException("Canonical location index out of range: " + canonicalIndex);
            int[] c = COORDINATES[symmetry][canonicalIndex];
            return board.getBoardLocation(c[0], c[1], c[2]);
        }
    }

    public static Position canonicalize(PylosBoard board, PylosGameState state, PylosPlayerColor playerToMove) {
        if (board == null || state == null || playerToMove == null)
            throw new IllegalArgumentException("Board, state and player-to-move are required");

        int bestSymmetry = 0;
        String bestOccupancy = occupancy(board, 0);
        for (int symmetry = 1; symmetry < SYMMETRY_COUNT; symmetry++) {
            String candidate = occupancy(board, symmetry);
            if (candidate.compareTo(bestOccupancy) < 0) {
                bestOccupancy = candidate;
                bestSymmetry = symmetry;
            }
        }
        return new Position(state.name() + "|" + playerToMove.name() + "|" + bestOccupancy, bestSymmetry);
    }

    public static MCTSTree.Action toCanonicalAction(MCTSTree.Action action, Position position) {
        if (action == null || position == null) throw new IllegalArgumentException("Action and position are required");
        int from = -1;
        int to = -1;
        switch (action.type) {
            case PLACEMENT:
                to = position.indexOf(action.location);
                break;
            case MOVE:
                from = position.indexOf(action.sphere.getLocation());
                to = position.indexOf(action.location);
                break;
            case REMOVE_FIRST:
            case REMOVE_SECOND:
                from = position.indexOf(action.sphere.getLocation());
                break;
            case PASS:
                break;
            default:
                throw new IllegalStateException("Unsupported action type: " + action.type);
        }
        return MCTSTree.Action.canonical(action.type, from, to);
    }

    /** Converts a canonical tree action to the corresponding legal action on this board. */
    public static MCTSTree.Action toActualAction(
            MCTSTree.Action canonicalAction,
            Iterable<MCTSTree.Action> actualLegalActions,
            Position position
    ) {
        if (canonicalAction == null || actualLegalActions == null || position == null)
            throw new IllegalArgumentException("Canonical action, legal actions and position are required");
        for (MCTSTree.Action actual : actualLegalActions) {
            if (canonicalAction.sameAs(toCanonicalAction(actual, position))) return actual;
        }
        throw new IllegalStateException("Canonical action has no legal equivalent in the current orientation: " + canonicalAction);
    }

    private static String occupancy(PylosBoard board, int symmetry) {
        StringBuilder result = new StringBuilder(LOCATION_COUNT);
        for (int i = 0; i < LOCATION_COUNT; i++) {
            int[] c = COORDINATES[symmetry][i];
            PylosSphere sphere = board.getBoardLocation(c[0], c[1], c[2]).getSphere();
            if (sphere == null) result.append('0');
            else if (sphere.PLAYER_COLOR == PylosPlayerColor.LIGHT) result.append('1');
            else result.append('2');
        }
        return result.toString();
    }

    private static int[][][] createCoordinateSequences() {
        int[][][] sequences = new int[SYMMETRY_COUNT][LOCATION_COUNT][3];
        int index;

        index = 0;
        for (int z = 0; z < 4; z++)
            for (int x = 0; x < 4 - z; x++)
                for (int y = 0; y < 4 - z; y++) sequences[0][index++] = new int[]{x, y, z};

        index = 0;
        for (int z = 0; z < 4; z++)
            for (int y = 0; y < 4 - z; y++)
                for (int x = 0; x < 4 - z; x++) sequences[1][index++] = new int[]{x, y, z};

        index = 0;
        for (int z = 0; z < 4; z++)
            for (int x = 3 - z; x >= 0; x--)
                for (int y = 0; y < 4 - z; y++) sequences[2][index++] = new int[]{x, y, z};

        index = 0;
        for (int z = 0; z < 4; z++)
            for (int y = 0; y < 4 - z; y++)
                for (int x = 3 - z; x >= 0; x--) sequences[3][index++] = new int[]{x, y, z};

        index = 0;
        for (int z = 0; z < 4; z++)
            for (int x = 3 - z; x >= 0; x--)
                for (int y = 3 - z; y >= 0; y--) sequences[4][index++] = new int[]{x, y, z};

        index = 0;
        for (int z = 0; z < 4; z++)
            for (int y = 3 - z; y >= 0; y--)
                for (int x = 3 - z; x >= 0; x--) sequences[5][index++] = new int[]{x, y, z};

        index = 0;
        for (int z = 0; z < 4; z++)
            for (int x = 0; x < 4 - z; x++)
                for (int y = 3 - z; y >= 0; y--) sequences[6][index++] = new int[]{x, y, z};

        index = 0;
        for (int z = 0; z < 4; z++)
            for (int y = 3 - z; y >= 0; y--)
                for (int x = 0; x < 4 - z; x++) sequences[7][index++] = new int[]{x, y, z};

        return sequences;
    }
}
