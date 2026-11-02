package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.PylosBoard;
import be.kuleuven.pylos.game.PylosGameState;
import be.kuleuven.pylos.game.PylosLocation;
import be.kuleuven.pylos.game.PylosPlayerColor;
import be.kuleuven.pylos.game.PylosSphere;


public final class PylosSymmetry {
    private static final int LOCATION_COUNT = 30;
    private static final int SYMMETRY_COUNT = 8;
    private static final int[][][] COORDINATES = createCoordinateSequences();

    private PylosSymmetry() { }

    public static final class Position {
        private final long key;
        private final int symmetry;

        private Position(long key, int symmetry) {
            this.key = key;
            this.symmetry = symmetry;
        }

        public long getKey() { return key; }

        public int indexOf(PylosLocation location) {
            if (location == null) {
                return -1;
            }

            return COORDINATE_INDEX[symmetry]
                    [location.X][location.Y][location.Z];
        }

        private PylosLocation locationAt(PylosBoard board, int index) {
            if (board == null || index < 0 || index >= LOCATION_COUNT)
                throw new IllegalArgumentException("Board and valid canonical location index are required");
            int[] coordinates = COORDINATES[symmetry][index];
            return board.getBoardLocation(coordinates[0], coordinates[1], coordinates[2]);
        }

        private static final int[][][][] COORDINATE_INDEX = createCoordinateIndex();

        private static int[][][][] createCoordinateIndex() {
            int[][][][] index = new int[SYMMETRY_COUNT][4][4][4];

            for (int symmetry = 0; symmetry < SYMMETRY_COUNT; symmetry++) {
                for (int i = 0; i < LOCATION_COUNT; i++) {
                    int x = COORDINATES[symmetry][i][0];
                    int y = COORDINATES[symmetry][i][1];
                    int z = COORDINATES[symmetry][i][2];

                    index[symmetry][x][y][z] = i;
                }
            }

            return index;
        }
    }

    public static Position canonicalize(PylosBoard board, PylosGameState state, PylosPlayerColor playerToMove) {
        if (board == null || state == null || playerToMove == null) throw new IllegalArgumentException("Board, state and player-to-move are required");

        // PylosBoard maintains this packed 2-bit-per-location representation
        // incrementally, so read it once instead of dereferencing 30 spheres for
        // each of the eight board symmetries.
        long boardBits = board.toLong();
        int bestSymmetry = 0;
        long bestOccupancy = occupancy(boardBits, 0);
        for (int symmetry = 1; symmetry < SYMMETRY_COUNT; symmetry++) {
            long candidate = occupancy(boardBits, symmetry);
            if (Long.compareUnsigned(candidate, bestOccupancy) < 0) {
                bestOccupancy = candidate;
                bestSymmetry = symmetry;
            }
        }
        long key = bestOccupancy | ((long) state.ordinal() << 60);
        if (playerToMove == PylosPlayerColor.DARK) key |= Long.MIN_VALUE;
        return new Position(key, bestSymmetry);
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

    public static MCTSTree.Action toActualAction(
            MCTSTree.Action canonicalAction,
            PylosBoard board,
            PylosPlayerColor playerColor,
            Position position
    ) {
        if (canonicalAction == null || board == null || playerColor == null || position == null)
            throw new IllegalArgumentException("Canonical action, board, player color and position are required");

        PylosLocation from = canonicalAction.fromIndex < 0
                ? null : position.locationAt(board, canonicalAction.fromIndex);
        PylosLocation to = canonicalAction.toIndex < 0
                ? null : position.locationAt(board, canonicalAction.toIndex);

        switch (canonicalAction.type) {
            case PLACEMENT:
                PylosSphere reserve = board.getReserve(playerColor);
                if (reserve == null || to == null)
                    throw new IllegalStateException("Canonical placement cannot be mapped to the current board");
                return MCTSTree.Action.placement(reserve, to);
            case MOVE:
                PylosSphere movingSphere = from == null ? null : from.getSphere();
                if (movingSphere == null || to == null)
                    throw new IllegalStateException("Canonical move cannot be mapped to the current board");
                return MCTSTree.Action.move(movingSphere, to);
            case REMOVE_FIRST:
                PylosSphere firstRemoval = from == null ? null : from.getSphere();
                if (firstRemoval == null)
                    throw new IllegalStateException("Canonical first removal cannot be mapped to the current board");
                return MCTSTree.Action.removeFirst(firstRemoval);
            case REMOVE_SECOND:
                PylosSphere secondRemoval = from == null ? null : from.getSphere();
                if (secondRemoval == null)
                    throw new IllegalStateException("Canonical second removal cannot be mapped to the current board");
                return MCTSTree.Action.removeSecond(secondRemoval);
            case PASS:
                return MCTSTree.Action.pass();
            default:
                throw new IllegalStateException("Unsupported canonical action type: " + canonicalAction.type);
        }
    }

    private static long occupancy(long boardBits, int symmetry) {
        long bits = 0L;
        int[] sourceOffsets = SOURCE_BIT_OFFSETS[symmetry];
        for (int i = 0; i < LOCATION_COUNT; i++) {
            long value = (boardBits >>> sourceOffsets[i]) & 3L;
            bits = (bits << 2) | value;
        }
        return bits;
    }

    // PylosBoard packs locations in z, x, y order, with two bits per location.
    // Precompute the source bit offset for each location in each canonical ordering.
    private static final int[][] SOURCE_BIT_OFFSETS = createSourceBitOffsets();

    private static int[][] createSourceBitOffsets() {
        int[][] offsets = new int[SYMMETRY_COUNT][LOCATION_COUNT];
        for (int symmetry = 0; symmetry < SYMMETRY_COUNT; symmetry++) {
            for (int i = 0; i < LOCATION_COUNT; i++) {
                int[] c = COORDINATES[symmetry][i];
                int z = c[2];
                int precedingLocations = 0;
                for (int level = 0; level < z; level++)
                    precedingLocations += (4 - level) * (4 - level);
                int boardIndex = precedingLocations + c[0] * (4 - z) + c[1];
                offsets[symmetry][i] = boardIndex * 2;
            }
        }
        return offsets;
    }

    private static int[][][] createCoordinateSequences() {
        return new int[][][] {
                // Symmetry 0
                {
                        {0,0,0}, {0,1,0}, {0,2,0}, {0,3,0},
                        {1,0,0}, {1,1,0}, {1,2,0}, {1,3,0},
                        {2,0,0}, {2,1,0}, {2,2,0}, {2,3,0},
                        {3,0,0}, {3,1,0}, {3,2,0}, {3,3,0},

                        {0,0,1}, {0,1,1}, {0,2,1},
                        {1,0,1}, {1,1,1}, {1,2,1},
                        {2,0,1}, {2,1,1}, {2,2,1},

                        {0,0,2}, {0,1,2},
                        {1,0,2}, {1,1,2},

                        {0,0,3}
                },

                // Symmetry 1
                {
                        {0,0,0}, {1,0,0}, {2,0,0}, {3,0,0},
                        {0,1,0}, {1,1,0}, {2,1,0}, {3,1,0},
                        {0,2,0}, {1,2,0}, {2,2,0}, {3,2,0},
                        {0,3,0}, {1,3,0}, {2,3,0}, {3,3,0},

                        {0,0,1}, {1,0,1}, {2,0,1},
                        {0,1,1}, {1,1,1}, {2,1,1},
                        {0,2,1}, {1,2,1}, {2,2,1},

                        {0,0,2}, {1,0,2},
                        {0,1,2}, {1,1,2},

                        {0,0,3}
                },

                // Symmetry 2
                {
                        {3,0,0}, {3,1,0}, {3,2,0}, {3,3,0},
                        {2,0,0}, {2,1,0}, {2,2,0}, {2,3,0},
                        {1,0,0}, {1,1,0}, {1,2,0}, {1,3,0},
                        {0,0,0}, {0,1,0}, {0,2,0}, {0,3,0},

                        {2,0,1}, {2,1,1}, {2,2,1},
                        {1,0,1}, {1,1,1}, {1,2,1},
                        {0,0,1}, {0,1,1}, {0,2,1},

                        {1,0,2}, {1,1,2},
                        {0,0,2}, {0,1,2},

                        {0,0,3}
                },

                // Symmetry 3
                {
                        {3,0,0}, {2,0,0}, {1,0,0}, {0,0,0},
                        {3,1,0}, {2,1,0}, {1,1,0}, {0,1,0},
                        {3,2,0}, {2,2,0}, {1,2,0}, {0,2,0},
                        {3,3,0}, {2,3,0}, {1,3,0}, {0,3,0},

                        {2,0,1}, {1,0,1}, {0,0,1},
                        {2,1,1}, {1,1,1}, {0,1,1},
                        {2,2,1}, {1,2,1}, {0,2,1},

                        {1,0,2}, {0,0,2},
                        {1,1,2}, {0,1,2},

                        {0,0,3}
                },

                // Symmetry 4
                {
                        {3,3,0}, {3,2,0}, {3,1,0}, {3,0,0},
                        {2,3,0}, {2,2,0}, {2,1,0}, {2,0,0},
                        {1,3,0}, {1,2,0}, {1,1,0}, {1,0,0},
                        {0,3,0}, {0,2,0}, {0,1,0}, {0,0,0},

                        {2,2,1}, {2,1,1}, {2,0,1},
                        {1,2,1}, {1,1,1}, {1,0,1},
                        {0,2,1}, {0,1,1}, {0,0,1},

                        {1,1,2}, {1,0,2},
                        {0,1,2}, {0,0,2},

                        {0,0,3}
                },

                // Symmetry 5
                {
                        {3,3,0}, {2,3,0}, {1,3,0}, {0,3,0},
                        {3,2,0}, {2,2,0}, {1,2,0}, {0,2,0},
                        {3,1,0}, {2,1,0}, {1,1,0}, {0,1,0},
                        {3,0,0}, {2,0,0}, {1,0,0}, {0,0,0},

                        {2,2,1}, {1,2,1}, {0,2,1},
                        {2,1,1}, {1,1,1}, {0,1,1},
                        {2,0,1}, {1,0,1}, {0,0,1},

                        {1,1,2}, {0,1,2},
                        {1,0,2}, {0,0,2},

                        {0,0,3}
                },

                // Symmetry 6
                {
                        {0,3,0}, {0,2,0}, {0,1,0}, {0,0,0},
                        {1,3,0}, {1,2,0}, {1,1,0}, {1,0,0},
                        {2,3,0}, {2,2,0}, {2,1,0}, {2,0,0},
                        {3,3,0}, {3,2,0}, {3,1,0}, {3,0,0},

                        {0,2,1}, {0,1,1}, {0,0,1},
                        {1,2,1}, {1,1,1}, {1,0,1},
                        {2,2,1}, {2,1,1}, {2,0,1},

                        {0,1,2}, {0,0,2},
                        {1,1,2}, {1,0,2},

                        {0,0,3}
                },

                // Symmetry 7
                {
                        {0,3,0}, {1,3,0}, {2,3,0}, {3,3,0},
                        {0,2,0}, {1,2,0}, {2,2,0}, {3,2,0},
                        {0,1,0}, {1,1,0}, {2,1,0}, {3,1,0},
                        {0,0,0}, {1,0,0}, {2,0,0}, {3,0,0},

                        {0,2,1}, {1,2,1}, {2,2,1},
                        {0,1,1}, {1,1,1}, {2,1,1},
                        {0,0,1}, {1,0,1}, {2,0,1},

                        {0,1,2}, {1,1,2},
                        {0,0,2}, {1,0,2},

                        {0,0,3}
                }
        };
    }
}
