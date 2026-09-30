package be.kuleuven.pylos;

import be.kuleuven.pylos.game.*;
import be.kuleuven.pylos.player.PylosPlayer;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.Tensor;
import org.tensorflow.ndarray.StdArrays;
import org.tensorflow.proto.MetaGraphDef;
import org.tensorflow.proto.SignatureDef;
import org.tensorflow.types.TFloat32;

import java.util.ArrayList;
import java.util.List;

public class PylosPlayerML extends PylosPlayer {
    private final SavedModelBundle model;
    private final String inputLayerName;
    private final String outputLayerName;

    public PylosPlayerML(SavedModelBundle model) {
        this.model = model;

        MetaGraphDef m = model.metaGraphDef();
        SignatureDef sig = m.getSignatureDefOrThrow("serving_default");
        this.inputLayerName = sig.getInputsMap().entrySet().iterator().next().getValue().getName();
        this.outputLayerName = sig.getOutputsMap().entrySet().iterator().next().getValue().getName();
    }

    @Override
    public void doMove(PylosGameIF game, PylosBoard board) {
        Action bestAction = bestAction(board, this.PLAYER_COLOR, PylosGameState.MOVE);
        bestAction.execute(game);
    }

    @Override
    public void doRemove(PylosGameIF game, PylosBoard board) {
        Action bestAction = bestAction(board, this.PLAYER_COLOR, PylosGameState.REMOVE_FIRST);
        bestAction.execute(game);
    }

    @Override
    public void doRemoveOrPass(PylosGameIF game, PylosBoard board) {
        Action bestAction = bestAction(board, this.PLAYER_COLOR, PylosGameState.REMOVE_SECOND);
        bestAction.execute(game);
    }

    // First iteration of minimax function which returns an action instead of a score
    private Action bestAction(PylosBoard board, PylosPlayerColor color, PylosGameState state) {
        List<Action> actions = generateActions(board, color, state);
        PylosGameSimulator simulator = new PylosGameSimulator(state, color, board);

        Action bestAction = null;
        float bestEval = Float.NEGATIVE_INFINITY;
        for (Action action : actions) {
            action.simulate(simulator);
            float eval = evalBoard(board, color);
            action.reverseSimulate(simulator);
            if (eval > bestEval) {
                bestEval = eval;
                bestAction = action;
            }
        }

        return bestAction;
    }

    // Returns a value which we try to maximise and our opponent tries to minimize.
    private float evalBoard(PylosBoard board, PylosPlayerColor color) {
        long boardAsLong = board.toLong();

        //convert board to array of bits
        float[] boardAsArray = new float[60];
        for (int i = 0; i < 60; i++) {
            int leftShifts = 59 - i;
            boolean light = (boardAsLong & (1L << leftShifts)) == 0;
            boardAsArray[i] = light ? 0 : 1;
        }

        float output = Float.NaN;
        try (Tensor inputTensor = TFloat32.tensorOf(StdArrays.ndCopyOf(new float[][]{boardAsArray}))) {
            try (TFloat32 outputTensor = (TFloat32) model.session().runner()
                    .feed(this.inputLayerName, inputTensor)
                    .fetch(this.outputLayerName)
                    .run().get(0)) {
                output = outputTensor.getFloat();
            }
        }

        assert !Float.isNaN(output) : "output is NaN";

        return switch (color) {
            case LIGHT -> output;
            case DARK -> -output;
        };
    }

    private static List<Action> generateActions(PylosBoard board, PylosPlayerColor color, PylosGameState state) {
        List<Action> actions = new ArrayList<>();
        PylosSphere[] spheres = board.getSpheres(color);

        switch (state) {
            case MOVE -> {
                PylosLocation[] locations = board.getLocations();
                PylosSquare[] squares = board.getAllSquares();
                List<PylosLocation> availableFullSquaresTopLocations = new ArrayList<>();

                // Add actions for moving a sphere to a higher location
                for (PylosSquare square : squares)
                    if (square.getTopLocation().isUsable())
                        availableFullSquaresTopLocations.add(square.getTopLocation());

                for (PylosSphere sphere : spheres)
                    if (!sphere.isReserve())
                        for (PylosLocation location : availableFullSquaresTopLocations)
                            if (sphere.canMoveTo(location) && sphere.getLocation() != location)
                                actions.add(new Action(ActionType.MOVE, sphere, location));

                // Add actions for moving a reserve sphere to a free location
                for (PylosLocation location : locations)
                    if (location.isUsable())
                        actions.add(new Action(ActionType.ADD, board.getReserve(color), location));
            }
            case REMOVE_FIRST -> {
                for (PylosSphere sphere : spheres)
                    if (sphere.canRemove())
                        actions.add(new Action(ActionType.REMOVE_FIRST, sphere, null));
            }
            case REMOVE_SECOND -> {
                actions.add(new Action(ActionType.PASS, null, null));
                for (PylosSphere sphere : spheres)
                    if (sphere.canRemove())
                        actions.add(new Action(ActionType.REMOVE_SECOND, sphere, null));
            }
        }

        return actions;
    }


    enum ActionType {
        ADD,
        MOVE,
        REMOVE_FIRST,
        REMOVE_SECOND,
        PASS
    }

    private static class Action {
        private final ActionType type;
        private final PylosSphere sphere;
        private final PylosLocation location;

        private PylosLocation prevLocation;
        private PylosGameState prevState;
        private PylosPlayerColor prevColor;

        public Action(ActionType type, PylosSphere sphere, PylosLocation location) {
            this.type = type;
            this.sphere = sphere;
            this.location = location;
        }

        public void execute(PylosGameIF game) {
            switch (type) {
                case ADD, MOVE -> game.moveSphere(sphere, location);
                case REMOVE_FIRST, REMOVE_SECOND -> game.removeSphere(sphere);
                case PASS -> game.pass();
                default -> throw new IllegalStateException("type not found in switch");
            }
        }

        public void simulate(PylosGameSimulator simulator) {
            prevState = simulator.getState();
            prevColor = simulator.getColor();

            if (type == ActionType.MOVE || type == ActionType.REMOVE_FIRST || type == ActionType.REMOVE_SECOND) {
                // Save the previous location of the sphere
                prevLocation = sphere.getLocation();
                assert prevLocation != null : "prevLocation is null";
            }
            switch (type) {
                case ADD, MOVE -> simulator.moveSphere(sphere, location);
                case REMOVE_FIRST, REMOVE_SECOND -> simulator.removeSphere(sphere);
                case PASS -> simulator.pass();
                default -> throw new IllegalStateException("type not found in switch");
            }
        }

        public void reverseSimulate(PylosGameSimulator simulator) {
            switch (type) {
                case ADD -> simulator.undoAddSphere(sphere, prevState, prevColor);
                case MOVE -> simulator.undoMoveSphere(sphere, prevLocation, prevState, prevColor);
                case REMOVE_FIRST -> simulator.undoRemoveFirstSphere(sphere, prevLocation, prevState, prevColor);
                case REMOVE_SECOND -> simulator.undoRemoveSecondSphere(sphere, prevLocation, prevState, prevColor);
                case PASS -> simulator.undoPass(prevState, prevColor);
                default -> throw new IllegalStateException("type not found in switch");
            }
        }
    }
}

