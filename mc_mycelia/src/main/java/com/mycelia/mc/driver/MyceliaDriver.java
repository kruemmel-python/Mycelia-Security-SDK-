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
    private final int defaultSeaLevel;

    public MyceliaDriver(Plugin plugin, FileConfiguration config) {
        this.logger = plugin.getLogger();
        this.driverCommand = normalize(config.getString("driver.command", ""));
        int timeoutSeconds = config.getInt("driver.timeoutSeconds", 5);
        this.timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        this.fallback = new SecureSeedFallback();
        this.defaultBaseBlock = config.getString("world.baseBlock", "STONE");
        this.defaultSurfaceBlock = config.getString("world.surfaceBlock", "MYCELIUM");
        this.defaultOreBlock = config.getString("world.oreBlock", "AMETHYST_BLOCK");
        this.defaultScale = config.getDouble("world.scale", 0.025D);
        this.defaultSeaLevel = config.getInt("world.seaLevel", 40);
    }

    public CompletableFuture<MyceliaWorldData> resolveWorldDataAsync(Optional<Long> explicitSeed) {
        return CompletableFuture.supplyAsync(() -> {
            if (explicitSeed.isPresent()) {
                return createFallbackData(explicitSeed.get());
            }
            return requestWorldDataFromDriver().orElseGet(() -> {
                logger.warning("Fallback auf sichere Welt-Daten, Treiber nicht erreichbar oder Antwort unbrauchbar.");
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
        builder.redirectErrorStream(false);
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

                String stderr = readAll(process.getErrorStream());
                if (!stderr.isBlank()) {
                    logger.warning("Treiber-STDERR: " + stderr.trim());
                }

                if (lines.isEmpty()) {
                    return Optional.empty();
                }

                String payload = lines.get(lines.size() - 1);
                return parsePayload(payload);
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

    private String readAll(java.io.InputStream stream) throws IOException {
        try (BufferedReader err = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            for (String line; (line = err.readLine()) != null; ) {
                if (!line.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append(' ');
                    }
                    sb.append(line.trim());
                }
            }
            return sb.toString();
        }
    }

    private MyceliaWorldData parseJson(String json) {
        long seed = Long.parseLong(extract(json, "seed", String.valueOf(fallback.nextSeed())));
        String base = extract(json, "baseBlock", defaultBaseBlock);
        String surface = extract(json, "surfaceBlock", defaultSurfaceBlock);
        String ore = extract(json, "oreBlock", defaultOreBlock);
        double scale = Double.parseDouble(extract(json, "scale", String.valueOf(defaultScale)));
        int seaLevel = Integer.parseInt(extract(json, "seaLevel", String.valueOf(defaultSeaLevel)));
        return new MyceliaWorldData(seed, base, surface, ore, scale, seaLevel);
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
        return new MyceliaWorldData(seed, defaultBaseBlock, defaultSurfaceBlock, defaultOreBlock, defaultScale, defaultSeaLevel);
    }

    private Optional<MyceliaWorldData> parsePayload(String payload) {
        String trimmed = payload.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        try {
            if (trimmed.contains("{")) {
                return Optional.of(parseJson(trimmed));
            }

            if (trimmed.matches("-?\\d+")) {
                long seed = Long.parseLong(trimmed);
                return Optional.of(createFallbackData(seed));
            }

            Optional<MyceliaWorldData> kv = parseKeyValuePayload(trimmed);
            if (kv.isPresent()) {
                return kv;
            }

            logger.warning("Treiber antwortete, aber das Format war nicht erkennbar: " + trimmed);
            return Optional.empty();
        } catch (Exception ex) {
            logger.warning("Treiber-Antwort konnte nicht gelesen werden: " + summarizeException(ex));
            return Optional.empty();
        }
    }

    private Optional<MyceliaWorldData> parseKeyValuePayload(String payload) {
        String[] parts = payload.split("[,\\s]+");
        long seed = fallback.nextSeed();
        String base = defaultBaseBlock;
        String surface = defaultSurfaceBlock;
        String ore = defaultOreBlock;
        double scale = defaultScale;
        int seaLevel = defaultSeaLevel;
        boolean found = false;

        for (String part : parts) {
            if (!part.contains("=") && !part.contains(":")) {
                continue;
            }
            String[] kv = part.split("[:=]", 2);
            if (kv.length != 2) {
                continue;
            }
            String key = kv[0].trim();
            String value = kv[1].trim();
            switch (key) {
                case "seed" -> {
                    seed = Long.parseLong(value);
                    found = true;
                }
                case "baseBlock" -> {
                    base = value;
                    found = true;
                }
                case "surfaceBlock" -> {
                    surface = value;
                    found = true;
                }
                case "oreBlock" -> {
                    ore = value;
                    found = true;
                }
                case "scale" -> {
                    scale = Double.parseDouble(value);
                    found = true;
                }
                case "seaLevel" -> {
                    seaLevel = Integer.parseInt(value);
                    found = true;
                }
                default -> {
                    // ignore
                }
            }
        }

        if (found) {
            return Optional.of(new MyceliaWorldData(seed, base, surface, ore, scale, seaLevel));
        }
        return Optional.empty();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.indexOf('"', 1) == trimmed.length() - 1) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private List<String> tokenize(String commandLine) {
        List<String> tokens = new ArrayList<>();
        boolean inQuotes = false;
        char quoteChar = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (c == '"' || c == '\'') {
                if (inQuotes && quoteChar == c) {
                    inQuotes = false;
                    continue;
                }
                if (!inQuotes) {
                    inQuotes = true;
                    quoteChar = c;
                    continue;
                }
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
