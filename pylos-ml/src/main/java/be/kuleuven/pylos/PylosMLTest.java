package be.kuleuven.pylos;

import be.kuleuven.pylos.battle.BattleMT;
import be.kuleuven.pylos.player.PylosPlayer;
import be.kuleuven.pylos.player.PylosPlayerType;
import be.kuleuven.pylos.player.codes.PylosPlayerMiniMax;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.proto.MetaGraphDef;
import org.tensorflow.proto.SignatureDef;
import org.tensorflow.proto.TensorInfo;

import java.util.Map;


public class PylosMLTest {

    public final static String MODEL_PATH = "pylos-ml/src/main/training/resources/models/latest";

    public static void main(String[] args) throws Exception {

        try (SavedModelBundle model = SavedModelBundle.load(MODEL_PATH, "serve")) {
            System.out.println("Model loaded");
            printModelSignature(model);

            PylosPlayerType trainedPlayer = new PylosPlayerType("ML") {
                @Override
                public PylosPlayer create() {
                    return new PylosPlayerML(model);
                }
            };

            PylosPlayerType opp = new PylosPlayerType("MM2") {
                @Override
                public PylosPlayer create() {
                    return new PylosPlayerMiniMax(2);
                }
            };

            BattleMT.play(trainedPlayer, opp, 1000, 8);
        }
    }

    private static void printModelSignature(SavedModelBundle model) {
        MetaGraphDef m = model.metaGraphDef();
        SignatureDef sig = m.getSignatureDefOrThrow("serving_default");
        int numInputs = sig.getInputsCount();
        int i = 1;
        System.out.println("MODEL SIGNATURE");
        System.out.println("Inputs:");
        for (Map.Entry<String, TensorInfo> entry : sig.getInputsMap().entrySet()) {
            TensorInfo t = entry.getValue();
            System.out.printf(
                    "%d of %d: %-20s (Node name in graph: %-20s, type: %s)\n",
                    i++, numInputs, entry.getKey(), t.getName(), t.getDtype());
        }
        int numOutputs = sig.getOutputsCount();
        i = 1;
        System.out.println("Outputs:");
        for (Map.Entry<String, TensorInfo> entry : sig.getOutputsMap().entrySet()) {
            TensorInfo t = entry.getValue();
            System.out.printf(
                    "%d of %d: %-20s (Node name in graph: %-20s, type: %s)\n",
                    i++, numOutputs, entry.getKey(), t.getName(), t.getDtype());
        }
        System.out.println("-----------------------------------------------");
    }
}
