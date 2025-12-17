package com.mycelia.mc.driver;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Beschafft Seeds über den Mycelia-Treiber (via Subprozess) und liefert
 * bei Problemen einen kryptografisch sicheren Fallback.
 */
public class MyceliaDriver {

    private final Logger logger;
    private final String driverCommand;
    private final Duration timeout;
    private final SecureSeedFallback fallback;

    public MyceliaDriver(Plugin plugin, FileConfiguration config) {
        this.logger = plugin.getLogger();
        this.driverCommand = config.getString("driver.command", "");
        int timeoutSeconds = config.getInt("driver.timeoutSeconds", 5);
        this.timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        this.fallback = new SecureSeedFallback();
    }

    /**
     * Liefert einen Seed asynchron. Wenn eine Zahl mitgegeben wird, hat diese Vorrang.
     */
    public CompletableFuture<Long> resolveSeedAsync(Optional<Long> explicitSeed) {
        if (explicitSeed.isPresent()) {
            return CompletableFuture.completedFuture(explicitSeed.get());
        }
        return CompletableFuture.supplyAsync(() -> requestSeedFromDriver()
                .orElseGet(() -> {
                    long seed = fallback.nextSeed();
                    logger.warning("Fallback auf sicheren Seed, Treiber nicht erreichbar.");
                    return seed;
                }));
    }

    public long nextSecureSeed() {
        return fallback.nextSeed();
    }

    private Optional<Long> requestSeedFromDriver() {
        if (driverCommand == null || driverCommand.isBlank()) {
            return Optional.empty();
        }

        List<String> command = tokenize(driverCommand);
        if (command.isEmpty()) {
            return Optional.empty();
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                logger.warning("Mycelia-Treiber überschritt Timeout von " + timeout.toSeconds() + "s.");
                return Optional.empty();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line == null || line.isBlank()) {
                    logger.warning("Treiber lieferte keinen Seed.");
                    return Optional.empty();
                }
                try {
                    long seed = Long.parseLong(line.trim());
                    logger.info("Seed aus Mycelia-Treiber empfangen: " + seed);
                    return Optional.of(seed);
                } catch (NumberFormatException nfe) {
                    logger.warning("Ungültiger Seed aus Treiber: '" + line + "'.");
                    return Optional.empty();
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warning("Treiber-Aufruf unterbrochen: " + summarizeException(ex));
            return Optional.empty();
        } catch (IOException ex) {
            logger.warning("Treiber-Aufruf fehlgeschlagen: " + summarizeException(ex));
            return Optional.empty();
        }
    }

    private List<String> tokenize(String commandLine) {
        List<String> tokens = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private String summarizeException(Exception ex) {
        StringJoiner joiner = new StringJoiner(" -> ");
        Throwable current = ex;
        while (current != null) {
            joiner.add(current.getClass().getSimpleName() + ": " + current.getMessage());
            current = current.getCause();
        }
        return joiner.toString();
    }
}
