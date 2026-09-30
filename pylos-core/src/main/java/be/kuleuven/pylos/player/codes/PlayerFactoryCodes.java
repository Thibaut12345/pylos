package be.kuleuven.pylos.player.codes;

import be.kuleuven.pylos.player.PylosPlayer;
import be.kuleuven.pylos.player.PylosPlayerFactory;
import be.kuleuven.pylos.player.PylosPlayerType;

import java.lang.reflect.Constructor;

/**
 * Created by Jan on 20/02/2015.
 */
public class PlayerFactoryCodes extends PylosPlayerFactory {

    public PlayerFactoryCodes() {
        super("CODeS");
    }

    @Override
    protected void createTypes() {
        tryToAddType("CODeS - Random Fit", "PylosPlayerRandomFit");
        tryToAddType("CODeS - Best Fit", "PylosPlayerBestFit");
        tryToAddType("CODeS - Level 1", "PylosPlayerMiniMax", 1);
        tryToAddType("CODeS - Level 2", "PylosPlayerMiniMax", 2);
        tryToAddType("CODeS - Level 3", "PylosPlayerMiniMax", 3);
        tryToAddType("CODeS - Level 4", "PylosPlayerMiniMax", 4);
        tryToAddType("CODeS - Level 5", "PylosPlayerMiniMax", 5);
        tryToAddType("CODeS - Level 6", "PylosPlayerMiniMax", 6);
        tryToAddType("CODeS - Level 7", "PylosPlayerMiniMax", 7);
        tryToAddType("CODeS - Level 8", "PylosPlayerMiniMax", 8);
    }

    private void tryToAddType(String name, String className) {
        tryToAddType(name, className, null);
    }

    private void tryToAddType(String name, String className, Integer param) {
        try {
            Class<?> playerClass = Class.forName("be.kuleuven.pylos.player.codes." + className);
            Constructor<?> constructor = param == null ? playerClass.getConstructor() : playerClass.getConstructor(Integer.class);
            add(new PylosPlayerType(name) {
                @Override
                public PylosPlayer create() {
                    return PlayerFactoryCodes.this.create(constructor, param);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private PylosPlayer create(Constructor<?> constructor, Integer param) {
        try {
            Object player = param == null ? constructor.newInstance() : constructor.newInstance(param);
            return (PylosPlayer) player;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}
