package be.kuleuven.pylos.tournament;

import be.kuleuven.pylos.player.PylosPlayerFactory;
import be.kuleuven.pylos.player.PylosPlayerType;

import java.util.List;

public record TournamentConfig(PylosPlayerFactory playerFactory, int pairRounds, int threads, int randomOpeningMoves,
                               boolean verbose, String anchorPlayerName, Double anchorRating) {
    public TournamentConfig {
        if (playerFactory == null) {
            throw new IllegalArgumentException("Tournament requires a non-null PylosPlayerFactory");
        }
        if (playerFactory.getTypes() == null || playerFactory.getTypes().size() < 2) {
            throw new IllegalArgumentException("Tournament requires at least 2 players in playerFactory");
        }
        long distinctCount = playerFactory.getTypes().stream().map(Object::toString).distinct().count();
        if (distinctCount != playerFactory.getTypes().size()) {
            throw new IllegalArgumentException("All players in PylosPlayerFactory must have unique names");
        }
        if (pairRounds <= 0) {
            throw new IllegalArgumentException("pairRounds must be > 0");
        }
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be > 0");
        }
        if (randomOpeningMoves < 0) {
            throw new IllegalArgumentException("randomOpeningMoves cannot be negative");
        }
    }

    public List<PylosPlayerType> getPlayers() {
        return playerFactory.getTypes();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(PylosPlayerFactory playerFactory) {
        return new Builder().playerFactory(playerFactory);
    }

    public static class Builder {
        private PylosPlayerFactory playerFactory;
        private int pairRounds = 10;
        private int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        private int randomOpeningMoves = 6;
        private boolean verbose = true;

        private String anchorPlayerName = null;
        private Double anchorRating = null;

        public Builder playerFactory(PylosPlayerFactory playerFactory) {
            this.playerFactory = playerFactory;
            return this;
        }

        public Builder pairRounds(int pairRounds) {
            this.pairRounds = pairRounds;
            return this;
        }

        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        public Builder randomOpeningMoves(int randomOpeningMoves) {
            this.randomOpeningMoves = randomOpeningMoves;
            return this;
        }

        public Builder verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }

        /**
         * Anchors a player by name to a fixed rating (e.g., anchorPlayer("MM2", 1000.0)).
         */
        public Builder anchorPlayer(String playerName, double targetRating) {
            this.anchorPlayerName = playerName;
            this.anchorRating = targetRating;
            return this;
        }

        public TournamentConfig build() {
            return new TournamentConfig(playerFactory, pairRounds, threads, randomOpeningMoves, verbose,
                    anchorPlayerName, anchorRating);
        }
    }
}
