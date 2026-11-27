package bot;

import chariot.ClientAuth;
import chariot.chess.Board;
import chariot.model.Anonymous;
import chariot.model.Enums;
import chariot.model.GameInfo;

import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GameState {
    private static final Logger LOGGER = Logger.getLogger("bot");
    private static final int MOVE_TIME_MS = Integer.parseInt(System.getProperty("movetime", "800"));

    private final ClientAuth client;
    private final String gameId;
    private final boolean white;
    private final GameInfo.Opponent opponent;
    private final List<String> trustedHistory = new ArrayList<>();
    private final List<String> sfHistory = new ArrayList<>();
    private Process stockfishCli;
    private BufferedReader stockfishReader;
    private BufferedWriter stockfishWriter;
    private int openingMoves;
    private boolean prematurelyFinishedOpening;
    private int lastScore;

    public GameState(ClientAuth client, GameInfo game) {
        this.client = client;
        this.gameId = game.gameId();
        this.white = game.color() == Enums.Color.white;
        this.opponent = game.opponent();
        lastScore = 25 * (white ? 1 : -1);
    }

    public String makeMove(Board board) {
        try {
            if (!checkStockfish()) return null;

            String beaverMove;
            if ((beaverMove = performBeaverDamOpening(board)) != null) return beaverMove;

            String positionCmd = "position startpos";
            if (!trustedHistory.isEmpty()) {
                positionCmd += " moves " + String.join(" ", trustedHistory);
            }
            sfSendCommand(positionCmd);

            Collection<String> validMoves = board.validMoves();
            if (validMoves.isEmpty()) return null;

            sfSendCommand("go movetime " + MOVE_TIME_MS);
            String bestMove = sfWaitUntil("bestmove", false);
            if (bestMove == null) return null;

            bestMove = bestMove.substring(9);
            int finish = bestMove.indexOf(' ');
            if (finish != -1) {
                bestMove = bestMove.substring(0, finish);
            }

            return bestMove;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "I/O issue during interaction with stockfish", e);

            return null;
        }
    }

    private String performBeaverDamOpening(Board board) throws IOException {
        if (openingMoves >= 8 || prematurelyFinishedOpening) return null;

        char cellChar = (char) ('a' + openingMoves);
        int cellDigit = (white ? 3 : 6);
        int prevDigit = (white ? 2 : 7);
        String move = cellChar + "" + prevDigit + cellChar + cellDigit;

        String compliment = "Hmm... that's a good move. Guess I have to leave my beaver dam unfinished.";
        if (!board.validMoves().contains(move)) {
            chat(compliment);

            return null;
        }

        sfSendCommand("go movetime " + MOVE_TIME_MS + " searchmoves " + move);
        sfWaitUntil("bestmove", true);

        Integer evalRate = null;
        for (int i = sfHistory.size() - 1; i >= 0; i--) {
            String line = sfHistory.get(i);
            if (line.contains("lowerbound") || line.contains("upperbound")) continue;

            String[] parts;
            // example: info depth 19 seldepth 27 multipv 1 score cp 0 ...
            evalRate = Integer.parseInt((parts = line.split(" "))[9]);
            if (parts[8].equals("mate")) {
                // oh, that'd be a terrible move
                evalRate = Integer.MIN_VALUE;
            }

            break;
        }
        if (evalRate != null && evalRate < lastScore) {
            int difference = Math.abs(evalRate - lastScore);
            if (difference >= 450) {
                prematurelyFinishedOpening = true;
                chat(compliment);

                return null;
            }

            lastScore = evalRate;
        }
        openingMoves++;

        return move;
    }

    private void chat(String message) {
        client.bot().chat(gameId, message);
    }

    public void addTrustedMove(String move) {
        trustedHistory.add(move);
    }

    public void dispose() {
        if (stockfishCli != null) {
            stockfishCli.destroyForcibly();
            try {
                stockfishCli.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (stockfishReader != null) {
            try {
                stockfishReader.close();
            } catch (IOException ignored) {
            }
        }
        if (stockfishWriter != null) {
            try {
                stockfishWriter.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean checkStockfish() throws IOException {
        if (stockfishCli != null) return true;

        stockfishCli = (new ProcessBuilder()).command("/usr/games/stockfish").start();
        stockfishReader = new BufferedReader(new InputStreamReader(stockfishCli.getInputStream()));
        stockfishWriter = new BufferedWriter(new OutputStreamWriter(stockfishCli.getOutputStream()));

        sfSendCommand("uci");
        if (sfWaitUntil("uciok", false) == null) return false;

        int elo;
        if (opponent instanceof GameInfo.Account account) {
            elo = Math.clamp(account.rating(), 1320, 3190);

            chat("I will be playing at " + elo + " Elo.");
        } else if (opponent instanceof Anonymous) {
            chat("I will be playing at 1500 Elo since I don't know your true rating.");

            elo = 1500;
        } else {
            chat("Since you are a bot, I will be playing at my maximum strength.");

            return true;
        }
        sfSendCommand("setoption name UCI_LimitStrength value true");
        sfSendCommand("setoption name UCI_Elo value " + elo);

        return true;
    }

    private String sfWaitUntil(String beginning, boolean collectHistory) throws IOException {
        String line;
        while ((line = stockfishReader.readLine()) != null) {
            if (line.startsWith(beginning)) return line;
            if (collectHistory) sfHistory.add(line);
        }

        return null;
    }

    private void sfSendCommand(String command) throws IOException {
        if (command.contains("\r") || command.contains("\n")) throw new IOException("Illegal new line");

        stockfishWriter.write(command);
        stockfishWriter.newLine();
        stockfishWriter.flush();
    }
}
