package bot;

import chariot.chess.Board;

import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GameState {
    private static final Logger LOGGER = Logger.getLogger("bot");

    private final String gameId;
    private final List<String> trustedHistory = new ArrayList<>();
    private Process stockfishCli;
    private BufferedReader stockfishReader;
    private BufferedWriter stockfishWriter;

    public GameState(String gameId) {
        this.gameId = gameId;
    }

    public String makeMove(Board board) {
        try {
            if (!checkStockfish()) return null;

            String positionCmd = "position startpos";
            if (!trustedHistory.isEmpty()) {
                positionCmd += " moves " + String.join(" ", trustedHistory);
            }
            sfSendCommand(positionCmd);

            Collection<String> validMoves = board.validMoves();
            if (validMoves.isEmpty()) return null;

            sfSendCommand("go movetime 800 searchmoves " + String.join(" ", validMoves));
            String bestMove = sfWaitUntil("bestmove");
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

        return sfWaitUntil("uciok") != null;
    }

    private String sfWaitUntil(String beginning) throws IOException {
        String line;
        while ((line = stockfishReader.readLine()) != null) {
            if (line.startsWith(beginning)) return line;
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
