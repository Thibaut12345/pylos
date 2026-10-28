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
        private final String key;
        private final int symmetry;

        private Position(String key, int symmetry) {
            this.key = key;
            this.symmetry = symmetry;
        }

        public String getKey() { return key; }

        public int indexOf(PylosLocation location) {
            if (location == null) {
                return -1;
            }

            return COORDINATE_INDEX[symmetry]
                    [location.X][location.Y][location.Z];
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
