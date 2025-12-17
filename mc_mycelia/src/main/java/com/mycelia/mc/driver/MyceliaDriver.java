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
 * Beschafft Welt-Daten über den Mycelia-Treiber (via Subprozess) und liefert
 * bei Problemen kryptografisch sichere Fallbacks.
 */
public class MyceliaDriver {

    private final Logger logger;
    private final String driverCommand;
    private final Duration timeout;
    private final SecureSeedFallback fallback;
    private final String defaultBaseBlock;
    private final String defaultSurfaceBlock;
    private final String defaultOreBlock;
    private final double defaultScale;

    public MyceliaDriver(Plugin plugin, FileConfiguration config) {
        this.logger = plugin.getLogger();
        this.driverCommand = config.getString("driver.command", "");
        int timeoutSeconds = config.getInt("driver.timeoutSeconds", 5);
        this.timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        this.fallback = new SecureSeedFallback();
        this.defaultBaseBlock = config.getString("world.baseBlock", "STONE");
        this.defaultSurfaceBlock = config.getString("world.surfaceBlock", "MYCELIUM");
        this.defaultOreBlock = config.getString("world.oreBlock", "AMETHYST_BLOCK");
        this.defaultScale = config.getDouble("world.scale", 0.025D);
    }

    public CompletableFuture<MyceliaWorldData> resolveWorldDataAsync(Optional<Long> explicitSeed) {
        return CompletableFuture.supplyAsync(() -> {
            if (explicitSeed.isPresent()) {
                return createFallbackData(explicitSeed.get());
            }
            return requestWorldDataFromDriver().orElseGet(() -> {
                logger.warning("Fallback auf sichere Welt-Daten, Treiber nicht erreichbar.");
                return createFallbackData(fallback.nextSeed());
            });
        });
    }

    private Optional<MyceliaWorldData> requestWorldDataFromDriver() {
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
                List<String> lines = new ArrayList<>();
                for (String line; (line = reader.readLine()) != null; ) {
                    if (!line.isBlank()) {
                        lines.add(line.trim());
                    }
                }

                if (lines.isEmpty()) {
                    return Optional.empty();
                }

                String payload = lines.get(lines.size() - 1);
                if (payload.contains("{")) {
                    return Optional.of(parseJson(payload));
                }
                return Optional.empty();
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

    private MyceliaWorldData parseJson(String json) {
        long seed = Long.parseLong(extract(json, "seed", String.valueOf(fallback.nextSeed())));
        String base = extract(json, "baseBlock", defaultBaseBlock);
        String surface = extract(json, "surfaceBlock", defaultSurfaceBlock);
        String ore = extract(json, "oreBlock", defaultOreBlock);
        double scale = Double.parseDouble(extract(json, "scale", String.valueOf(defaultScale)));
        return new MyceliaWorldData(seed, base, surface, ore, scale);
    }

    private String extract(String json, String key, String def) {
        int keyIndex = json.indexOf(key + "\"");
        if (keyIndex < 0) {
            keyIndex = json.indexOf("\"" + key + "\"");
        }
        if (keyIndex < 0) {
            return def;
        }
        String[] parts = json.substring(keyIndex).split(":", 2);
        if (parts.length < 2) {
            return def;
        }
        String valueAndRest = parts[1];
        int end = valueAndRest.indexOf(',');
        if (end < 0) {
            end = valueAndRest.indexOf('}');
        }
        if (end < 0) {
            end = valueAndRest.length();
        }
        String cleaned = valueAndRest.substring(0, end).replace("\"", "").trim();
        if (cleaned.isEmpty()) {
            return def;
        }
        return cleaned;
    }

    public MyceliaWorldData createFallbackData(long seed) {
        return new MyceliaWorldData(seed, defaultBaseBlock, defaultSurfaceBlock, defaultOreBlock, defaultScale);
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
