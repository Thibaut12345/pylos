package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.*;
import be.kuleuven.pylos.player.PylosPlayer;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

public class StudentPlayer extends PylosPlayer {

    private static final long THINK_TIME_MS = 100;
    private static final int MAX_DEPTH = 20;

    private static final int WIN = 1_000_000;
    private static final int INF = 2_000_000;

    private static final byte EXACT = 0;
    private static final byte LOWER = 1;
    private static final byte UPPER = 2;

    private static final int TT_SIZE = 1 << 18;
    private static final int TT_MASK = TT_SIZE - 1;

    private final long[] ttKeys = new long[TT_SIZE];
    private final int[] ttScores = new int[TT_SIZE];
    private final int[] ttMoves = new int[TT_SIZE];
    private final byte[] ttDepths = new byte[TT_SIZE];
    private final byte[] ttFlags = new byte[TT_SIZE];

    private final IdentityHashMap<PylosLocation, Integer> locationIds =
            new IdentityHashMap<>();

    private PylosBoard board;
    private PylosGameSimulator simulator;

    private long deadline;
    private int nodes;

    private static final Timeout TIMEOUT = new Timeout();

    // =========================================================
    // CALLBACKS
    // =========================================================

    @Override
    public void doMove(PylosGameIF game, PylosBoard board) {
        Action a = findBestAction(game.getState(), board);
        game.moveSphere(a.sphere, a.location);
    }

    @Override
    public void doRemove(PylosGameIF game, PylosBoard board) {
        Action a = findBestAction(game.getState(), board);
        game.removeSphere(a.sphere);
    }

    @Override
    public void doRemoveOrPass(PylosGameIF game, PylosBoard board) {
        Action a = findBestAction(game.getState(), board);

        if (a.type == Type.PASS) {
            game.pass();
        } else {
            game.removeSphere(a.sphere);
        }
    }

    // =========================================================
    // ROOT SEARCH
    // =========================================================

    private Action findBestAction(PylosGameState state, PylosBoard board) {

        this.board = board;
        this.simulator = new PylosGameSimulator(state, PLAYER_COLOR, board);

        deadline = System.nanoTime() + THINK_TIME_MS * 1_000_000L;
        nodes = 0;

        locationIds.clear();

        int id = 0;
        for (PylosLocation location : board.getLocations()) {
            locationIds.put(location, id++);
        }

        List<Action> actions = getActions();

        if (actions.isEmpty()) {
            throw new IllegalStateException("Geen legale actie.");
        }

        if (actions.size() == 1) {
            return actions.get(0);
        }

        orderActions(actions, -1);

        Action best = actions.get(0);
        int bestCode = best.code;

        for (int depth = 1; depth <= MAX_DEPTH; depth++) {

            try {

                checkTime();

                actions = getActions();
                orderActions(actions, bestCode);

                Action iterationBest = actions.get(0);
                int iterationScore = -INF;
                int alpha = -INF;

                for (Action action : actions) {

                    checkTime();

                    PylosGameState oldState = simulator.getState();
                    PylosPlayerColor oldColor = simulator.getColor();

                    PylosLocation oldLocation =
                            action.sphere == null
                                    ? null
                                    : action.sphere.getLocation();

                    boolean wasReserve =
                            action.sphere != null
                                    && action.sphere.isReserve();

                    apply(action);

                    int nextDepth = depth;

                    if (simulator.getColor() != oldColor) {
                        nextDepth--;
                    }

                    int score;

                    try {
                        score = search(nextDepth, alpha, INF);
                    } finally {
                        undo(
                                action,
                                oldState,
                                oldColor,
                                oldLocation,
                                wasReserve
                        );
                    }

                    if (score > iterationScore) {
                        iterationScore = score;
                        iterationBest = action;
                    }

                    alpha = Math.max(alpha, score);
                }

                best = iterationBest;
                bestCode = best.code;

                if (Math.abs(iterationScore) >= WIN) {
                    break;
                }

            } catch (Timeout ignored) {
                break;
            }
        }

        return best;
    }

    // =========================================================
    // ALPHA BETA
    // =========================================================

    private int search(int depth, int alpha, int beta) {

        nodes++;

        if ((nodes & 1023) == 0) {
            checkTime();
        }

        PylosGameState state = simulator.getState();

        if (state == PylosGameState.COMPLETED) {
            return simulator.getWinner() == PLAYER_COLOR
                    ? WIN + depth
                    : -WIN - depth;
        }

        if (state == PylosGameState.DRAW) {
            return 0;
        }

        long key = getKey();
        int index = hash(key);

        int oldAlpha = alpha;
        int oldBeta = beta;

        int preferredMove = -1;

        if (ttDepths[index] != 0 && ttKeys[index] == key) {

            int storedDepth = (ttDepths[index] & 0xFF) - 1;
            int cachedScore = ttScores[index];
            preferredMove = ttMoves[index];

            if (storedDepth >= depth) {

                if (ttFlags[index] == EXACT) {
                    return cachedScore;
                }

                if (ttFlags[index] == LOWER) {
                    alpha = Math.max(alpha, cachedScore);
                } else {
                    beta = Math.min(beta, cachedScore);
                }

                if (alpha >= beta) {
                    return cachedScore;
                }
            }
        }

        // Eerst remove-acties afwerken voordat we evalueren.
        if (depth <= 0 && state == PylosGameState.MOVE) {
            return evaluate();
        }

        List<Action> actions = getActions();

        if (actions.isEmpty()) {
            return evaluate();
        }

        orderActions(actions, preferredMove);

        boolean maximizing =
                simulator.getColor() == PLAYER_COLOR;

        int bestScore = maximizing ? -INF : INF;
        int bestMove = actions.get(0).code;

        for (Action action : actions) {

            PylosGameState oldState = simulator.getState();
            PylosPlayerColor oldColor = simulator.getColor();

            PylosLocation oldLocation =
                    action.sphere == null
                            ? null
                            : action.sphere.getLocation();

            boolean wasReserve =
                    action.sphere != null
                            && action.sphere.isReserve();

            apply(action);

            int nextDepth = depth;

            if (simulator.getColor() != oldColor) {
                nextDepth--;
            }

            int score;

            try {
                score = search(nextDepth, alpha, beta);
            } finally {
                undo(
                        action,
                        oldState,
                        oldColor,
                        oldLocation,
                        wasReserve
                );
            }

            if (maximizing) {

                if (score > bestScore) {
                    bestScore = score;
                    bestMove = action.code;
                }

                alpha = Math.max(alpha, bestScore);

            } else {

                if (score < bestScore) {
                    bestScore = score;
                    bestMove = action.code;
                }

                beta = Math.min(beta, bestScore);
            }

            if (alpha >= beta) {
                break;
            }
        }

        byte flag;

        if (bestScore <= oldAlpha) {
            flag = UPPER;
        } else if (bestScore >= oldBeta) {
            flag = LOWER;
        } else {
            flag = EXACT;
        }

        saveTT(key, depth, bestScore, bestMove, flag);

        return bestScore;
    }

    // =========================================================
    // ACTIONS
    // =========================================================

    private List<Action> getActions() {

        List<Action> actions = new ArrayList<>(40);

        PylosPlayerColor color = simulator.getColor();
        PylosGameState state = simulator.getState();

        if (state == PylosGameState.MOVE) {

            for (PylosSphere sphere : board.getSpheres(color)) {

                if (sphere.isReserve() || !sphere.canMove()) {
                    continue;
                }

                for (PylosLocation location : board.getLocations()) {

                    if (sphere.canMoveTo(location)) {
                        actions.add(
                                new Action(
                                        Type.MOVE,
                                        sphere,
                                        location,
                                        actionCode(
                                                sphere,
                                                location,
                                                Type.MOVE
                                        )
                                )
                        );
                    }
                }
            }

            if (board.getReservesSize(color) > 0) {

                PylosSphere reserve =
                        board.getReserve(color);

                for (PylosLocation location : board.getLocations()) {

                    if (location.isUsable()) {

                        actions.add(
                                new Action(
                                        Type.MOVE,
                                        reserve,
                                        location,
                                        actionCode(
                                                reserve,
                                                location,
                                                Type.MOVE
                                        )
                                )
                        );
                    }
                }
            }

        } else {

            for (PylosSphere sphere : board.getSpheres(color)) {

                if (sphere.canRemove()) {

                    actions.add(
                            new Action(
                                    Type.REMOVE,
                                    sphere,
                                    null,
                                    actionCode(
                                            sphere,
                                            null,
                                            Type.REMOVE
                                    )
                            )
                    );
                }
            }

            if (state == PylosGameState.REMOVE_SECOND) {

                actions.add(
                        new Action(
                                Type.PASS,
                                null,
                                null,
                                4096
                        )
                );
            }
        }

        return actions;
    }

    // =========================================================
    // MOVE ORDERING
    // =========================================================

    private void orderActions(
            List<Action> actions,
            int preferred
    ) {

        PylosPlayerColor color =
                simulator.getColor();

        for (Action action : actions) {

            if (action.code == preferred) {
                action.orderScore = 1_000_000;
            } else {
                action.orderScore =
                        actionScore(action, color);
            }
        }

        actions.sort(
                (a, b) ->
                        Integer.compare(
                                b.orderScore,
                                a.orderScore
                        )
        );
    }

    private int actionScore(
            Action action,
            PylosPlayerColor color
    ) {

        if (action.type == Type.PASS) {
            return -10_000;
        }

        if (action.type == Type.REMOVE) {

            int score = 5000;

            // Liefst vrije / minder structureel belangrijke bol wegnemen.
            for (PylosSquare square :
                    action.sphere.getLocation().getSquares()) {

                if (square.getInSquare(color.other()) == 0) {
                    score -= square.getInSquare(color) * 100;
                }
            }

            return score;
        }

        int score = 0;

        if (!action.sphere.isReserve()) {
            score += 5000;
        }

        if (completesSquare(action.location, color)) {
            score += 50_000;
        }

        if (completesSquare(
                action.location,
                color.other()
        )) {
            score += 30_000;
        }

        for (PylosSquare square :
                action.location.getSquares()) {

            if (square.getInSquare(color.other()) == 0) {

                int mine =
                        square.getInSquare(color);

                if (mine == 2) {
                    score += 800;
                } else if (mine == 1) {
                    score += 100;
                }
            }
        }

        score += action.location.Z * 50;

        return score;
    }

    // =========================================================
    // EVALUATION
    // =========================================================

    private int evaluate() {

        PylosPlayerColor me = PLAYER_COLOR;
        PylosPlayerColor enemy = me.other();

        int score =
                1000
                        * (
                        board.getReservesSize(me)
                                - board.getReservesSize(enemy)
                );

        for (PylosSquare square :
                board.getAllSquares()) {

            int mine =
                    square.getInSquare(me);

            int theirs =
                    square.getInSquare(enemy);

            if (theirs == 0) {

                if (mine == 3) {
                    score += 100;
                } else if (mine == 2) {
                    score += 25;
                }
            }

            if (mine == 0) {

                if (theirs == 3) {
                    score -= 100;
                } else if (theirs == 2) {
                    score -= 25;
                }
            }
        }

        boolean myTurn =
                simulator.getColor() == me;

        for (PylosLocation location :
                board.getLocations()) {

            if (!location.isUsable()) {
                continue;
            }

            if (completesSquare(location, me)) {
                score += myTurn ? 300 : 100;
            }

            if (completesSquare(location, enemy)) {
                score -= myTurn ? 100 : 300;
            }
        }

        return score;
    }

    private boolean completesSquare(
            PylosLocation location,
            PylosPlayerColor color
    ) {

        for (PylosSquare square :
                location.getSquares()) {

            if (
                    square.getInSquare() == 3
                            &&
                            square.getInSquare(color) == 3
            ) {

                return true;
            }
        }

        return false;
    }

    // =========================================================
    // APPLY / UNDO
    // =========================================================

    private void apply(Action action) {

        if (action.type == Type.MOVE) {

            simulator.moveSphere(
                    action.sphere,
                    action.location
            );

        } else if (action.type == Type.REMOVE) {

            simulator.removeSphere(
                    action.sphere
            );

        } else {

            simulator.pass();
        }
    }

    private void undo(
            Action action,
            PylosGameState oldState,
            PylosPlayerColor oldColor,
            PylosLocation oldLocation,
            boolean wasReserve
    ) {

        if (action.type == Type.MOVE) {

            if (wasReserve) {

                simulator.undoAddSphere(
                        action.sphere,
                        oldState,
                        oldColor
                );

            } else {

                simulator.undoMoveSphere(
                        action.sphere,
                        oldLocation,
                        oldState,
                        oldColor
                );
            }

        } else if (action.type == Type.REMOVE) {

            if (
                    oldState
                            == PylosGameState.REMOVE_FIRST
            ) {

                simulator.undoRemoveFirstSphere(
                        action.sphere,
                        oldLocation,
                        oldState,
                        oldColor
                );

            } else {

                simulator.undoRemoveSecondSphere(
                        action.sphere,
                        oldLocation,
                        oldState,
                        oldColor
                );
            }

        } else {

            simulator.undoPass(
                    oldState,
                    oldColor
            );
        }
    }

    // =========================================================
    // TRANSPOSITION TABLE
    // =========================================================

    private long getKey() {

        long bits = 0;

        for (PylosSphere sphere :
                board.getSpheres(PLAYER_COLOR)) {

            if (!sphere.isReserve()) {

                int id =
                        locationIds.get(
                                sphere.getLocation()
                        );

                bits |=
                        1L << (id * 2);
            }
        }

        for (PylosSphere sphere :
                board.getSpheres(
                        PLAYER_COLOR.other()
                )) {

            if (!sphere.isReserve()) {

                int id =
                        locationIds.get(
                                sphere.getLocation()
                        );

                bits |=
                        2L << (id * 2);
            }
        }

        bits |=
                (
                        (long)
                                simulator
                                        .getState()
                                        .ordinal()
                                & 7L
                )
                        << 60;

        bits |=
                (
                        (long)
                                simulator
                                        .getColor()
                                        .ordinal()
                                & 1L
                )
                        << 63;

        return bits;
    }

    private int hash(long key) {

        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;

        return ((int) key) & TT_MASK;
    }

    private void saveTT(
            long key,
            int depth,
            int score,
            int move,
            byte flag
    ) {

        int index = hash(key);

        int oldDepth =
                (ttDepths[index] & 0xFF) - 1;

        if (
                ttKeys[index] != key
                        ||
                        depth >= oldDepth
        ) {

            ttKeys[index] = key;
            ttScores[index] = score;
            ttMoves[index] = move;
            ttFlags[index] = flag;

            ttDepths[index] =
                    (byte)
                            Math.min(
                                    depth + 1,
                                    255
                            );
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private int actionCode(
            PylosSphere sphere,
            PylosLocation target,
            Type type
    ) {

        int source =
                sphere.isReserve()
                        ? -1
                        : locationIds.get(
                        sphere.getLocation()
                );

        if (type == Type.REMOVE) {
            return 2048 + source;
        }

        return
                (source + 1) * 32
                        + locationIds.get(target);
    }

    private void checkTime() {

        if (System.nanoTime() >= deadline) {
            throw TIMEOUT;
        }
    }

    private enum Type {
        MOVE,
        REMOVE,
        PASS
    }

    private static class Action {

        final Type type;
        final PylosSphere sphere;
        final PylosLocation location;
        final int code;

        int orderScore;

        Action(
                Type type,
                PylosSphere sphere,
                PylosLocation location,
                int code
        ) {

            this.type = type;
            this.sphere = sphere;
            this.location = location;
            this.code = code;
        }
    }

    private static class Timeout
            extends RuntimeException {

        Timeout() {
            super(
                    null,
                    null,
                    false,
                    false
            );
        }
    }
}