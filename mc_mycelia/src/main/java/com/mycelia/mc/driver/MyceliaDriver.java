package com.mycelia.mc.driver;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MyceliaDriver {

    private final Logger logger;
    private final String driverCommand;

    private final Duration timeoutRuntime;
    private final Duration timeoutWarmup;

    private final SecureSeedFallback fallback;
    private final String defaultBaseBlock;
    private final String defaultSurfaceBlock;
    private final String defaultOreBlock;
    private final double defaultScale;
    private final int defaultSeaLevel;

    public MyceliaDriver(Plugin plugin, FileConfiguration config) {
        this.logger = plugin.getLogger();
        this.driverCommand = normalize(config.getString("driver.command", ""));

        int runtimeSeconds = config.getInt("driver.timeoutSeconds", 35);
        int warmupSeconds = config.getInt("driver.warmupTimeoutSeconds", 300);

        this.timeoutRuntime = Duration.ofSeconds(Math.max(runtimeSeconds, 1));
        this.timeoutWarmup = Duration.ofSeconds(Math.max(warmupSeconds, 1));

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
            return requestWorldDataFromDriver(timeoutRuntime).orElseGet(() -> {
                logger.warning("Fallback auf sichere Welt-Daten, Treiber nicht erreichbar oder Antwort unbrauchbar.");
                return createFallbackData(fallback.nextSeed());
            });
        });
    }

    public CompletableFuture<Void> warmupAsync() {
        return CompletableFuture.runAsync(() -> {
            if (driverCommand == null || driverCommand.isBlank()) {
                return;
            }
            requestWorldDataFromDriver(timeoutWarmup);
        });
    }

    private Optional<MyceliaWorldData> requestWorldDataFromDriver(Duration timeout) {
        if (driverCommand == null || driverCommand.isBlank()) {
            return Optional.empty();
        }

        List<String> command = tokenize(driverCommand);
        if (command.isEmpty()) {
            return Optional.empty();
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);

        // Workdir auf Skriptverzeichnis setzen (wie manueller Aufruf)
        File workDir = inferWorkDirFromCommand(command);
        if (workDir != null) {
            builder.directory(workDir);
        }

        // Timeout an Python übergeben
        builder.environment().put("MYCELIA_DRIVER_TIMEOUT", String.valueOf(timeout.toSeconds()));

        // Streams parallel konsumieren, sonst kann der Child bei viel Output blockieren
        List<String> stdoutLines = Collections.synchronizedList(new ArrayList<>());
        StringBuilder stderrAll = new StringBuilder(4096);

        try {
            Process process = builder.start();

            Thread stdoutGobbler = new Thread(
                    () -> readLines(process.getInputStream(), stdoutLines, null),
                    "mycelia-stdout-gobbler"
            );
            Thread stderrGobbler = new Thread(
                    () -> readLines(process.getErrorStream(), null, stderrAll),
                    "mycelia-stderr-gobbler"
            );

            stdoutGobbler.setDaemon(true);
            stderrGobbler.setDaemon(true);
            stdoutGobbler.start();
            stderrGobbler.start();

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warning("Mycelia-Treiber überschritt Timeout von " + timeout.toSeconds() + "s.");

                // best effort: Streams noch kurz leerziehen
                joinQuietly(stdoutGobbler, 200);
                joinQuietly(stderrGobbler, 200);

                // STDERR ggf. noch loggen (meist nützlich bei Timeout)
                logStderrSmart(stderrAll.toString());

                return Optional.empty();
            }

            // Prozess ist fertig: Streams zu Ende lesen (best effort)
            joinQuietly(stdoutGobbler, 500);
            joinQuietly(stderrGobbler, 500);

            // STDERR smart loggen (INFO vs WARN)
            logStderrSmart(stderrAll.toString());

            String payload = lastNonBlank(stdoutLines);
            if (payload == null) {
                return Optional.empty();
            }

            return parsePayload(payload);

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warning("Treiber-Aufruf unterbrochen: " + summarizeException(ex));
            return Optional.empty();
        } catch (Exception ex) {
            logger.warning("Treiber-Aufruf fehlgeschlagen: " + summarizeException(ex));
            return Optional.empty();
        }
    }

    /**
     * Loggt STDERR abhängig vom Inhalt:
     * - WARN: Timeout/Fehler/Exception/Fatal/Traceback/AccessViolation/etc.
     * - INFO: normale Statusmeldungen wie "Welt-Typ generiert ..."
     *
     * Zusätzlich: Kürzt sehr langen Text.
     */
    private void logStderrSmart(String stderrRaw) {
        if (stderrRaw == null) {
            return;
        }
        String stderr = stderrRaw.trim();
        if (stderr.isBlank()) {
            return;
        }

        String normalized = stderr.toLowerCase();

        boolean looksBad =
                normalized.contains("timeout")
                        || normalized.contains("fehler")
                        || normalized.contains("error")
                        || normalized.contains("exception")
                        || normalized.contains("traceback")
                        || normalized.contains("fatal")
                        || normalized.contains("access violation")
                        || normalized.contains("segmentation fault")
                        || normalized.contains("dll nicht gefunden")
                        || normalized.contains("context fail")
                        || normalized.contains("init fail")
                        || normalized.contains("process_buffer fail");

        // Log-Kürzung (falls z.B. C-Code viel ausgibt)
        final int maxLen = 1200;
        String toLog = stderr.length() > maxLen
                ? stderr.substring(0, maxLen) + " ...[truncated]"
                : stderr;

        if (looksBad) {
            logger.warning("Treiber-STDERR: " + toLog);
        } else {
            logger.info("Treiber-STDERR: " + toLog);
        }
    }

    private void readLines(InputStream stream, List<String> outLines, StringBuilder outText) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (outLines != null) {
                    if (!line.isBlank()) outLines.add(line.trim());
                }
                if (outText != null) {
                    if (!line.isBlank()) {
                        if (!outText.isEmpty()) outText.append(' ');
                        outText.append(line.trim());
                    }
                }
            }
        } catch (Exception ignored) {
            // best effort: hier nicht crashen
        }
    }

    private void joinQuietly(Thread t, long millis) {
        try {
            t.join(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String lastNonBlank(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String s = lines.get(i);
            if (s != null && !s.isBlank()) return s.trim();
        }
        return null;
    }

    private File inferWorkDirFromCommand(List<String> command) {
        if (command.size() < 2) return null;
        File script = new File(command.get(1));
        File parent = script.getParentFile();
        return (parent != null && parent.isDirectory()) ? parent : null;
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
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) return def;

        String[] parts = json.substring(keyIndex).split(":", 2);
        if (parts.length < 2) return def;

        String valueAndRest = parts[1];
        int end = valueAndRest.indexOf(',');
        if (end < 0) end = valueAndRest.indexOf('}');
        if (end < 0) end = valueAndRest.length();

        String cleaned = valueAndRest.substring(0, end).replace("\"", "").trim();
        return cleaned.isEmpty() ? def : cleaned;
    }

    public MyceliaWorldData createFallbackData(long seed) {
        return new MyceliaWorldData(seed, defaultBaseBlock, defaultSurfaceBlock, defaultOreBlock, defaultScale, defaultSeaLevel);
    }

    private Optional<MyceliaWorldData> parsePayload(String payload) {
        String trimmed = payload.trim();
        if (trimmed.isEmpty()) return Optional.empty();

        try {
            if (trimmed.contains("{")) {
                return Optional.of(parseJson(trimmed));
            }

            if (trimmed.matches("-?\\d+")) {
                long seed = Long.parseLong(trimmed);
                return Optional.of(createFallbackData(seed));
            }

            Optional<MyceliaWorldData> kv = parseKeyValuePayload(trimmed);
            if (kv.isPresent()) return kv;

            logger.warning("Treiber antwortete, aber Format nicht erkennbar: " + trimmed);
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
            if (!part.contains("=") && !part.contains(":")) continue;
            String[] kv = part.split("[:=]", 2);
            if (kv.length != 2) continue;

            String key = kv[0].trim();
            String value = kv[1].trim();

            switch (key) {
                case "seed" -> { seed = Long.parseLong(value); found = true; }
                case "baseBlock" -> { base = value; found = true; }
                case "surfaceBlock" -> { surface = value; found = true; }
                case "oreBlock" -> { ore = value; found = true; }
                case "scale" -> { scale = Double.parseDouble(value); found = true; }
                case "seaLevel" -> { seaLevel = Integer.parseInt(value); found = true; }
                default -> { }
            }
        }
        return found ? Optional.of(new MyceliaWorldData(seed, base, surface, ore, scale, seaLevel)) : Optional.empty();
    }

    private String normalize(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                && trimmed.indexOf('"', 1) == trimmed.length() - 1) {
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

        if (current.length() > 0) tokens.add(current.toString());
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
