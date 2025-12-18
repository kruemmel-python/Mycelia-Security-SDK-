package com.mycelia.mc.driver;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class MyceliaDriverService {

    private final List<String> command;
    private final Duration requestTimeout;
    private final Logger logger;
    private ExecutorService executor;

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;

    public MyceliaDriverService(List<String> command, Duration requestTimeout, Logger logger) {
        this.command = command;
        this.requestTimeout = requestTimeout;
        this.logger = logger;
    }

    public synchronized void start() {
        if (process != null && process.isAlive()) {
            return;
        }

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mycelia-persistent-driver");
            t.setDaemon(true);
            return t;
        });

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        File workDir = inferWorkDir();
        if (workDir != null) {
            builder.directory(workDir);
        }

        try {
            process = builder.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warning("Persistenter Treiber konnte nicht gestartet werden: " + e.getMessage());
            stop();
        }
    }

    public synchronized void stop() {
        if (process != null) {
            process.destroy();
        }
        process = null;
        stdin = null;
        stdout = null;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public synchronized boolean ping() {
        Optional<String> response = send(Map.of("cmd", "health"));
        return response.map(s -> s.toLowerCase().contains("ok") || s.toLowerCase().contains("warm"))
                .orElse(false);
    }

    public synchronized Optional<MyceliaWorldData> requestWorld(Optional<Long> seed, MyceliaDriver driver) {
        return send(Map.of("cmd", "world", "seed", seed.orElse(null)))
                .flatMap(driver::parsePayload);
    }

    public synchronized Optional<String> requestNoise(int x, int z) {
        return send(Map.of("cmd", "noise", "x", x, "z", z));
    }

    public synchronized Optional<float[]> requestSymbolicAbstraction(int signalCount, float[] narrativeEmbeds, float[] weights) {
        return send(Map.of(
                        "cmd", "symbolic_abstract",
                        "signalCount", signalCount,
                        "narrativeEmbeds", narrativeEmbeds,
                        "weights", weights))
                .map(JsonUtil::parseFloatArray);
    }

    public synchronized Optional<float[]> requestDreamState(int size) {
        return send(Map.of("cmd", "dream_state", "size", size))
                .map(JsonUtil::parseFloatArray);
    }

    public synchronized Optional<Double> requestOTOC() {
        return send(Map.of("cmd", "otoc_chaos"))
                .map(resp -> {
                    try {
                        return Double.parseDouble(resp.trim());
                    } catch (NumberFormatException e) {
                        float[] arr = JsonUtil.parseFloatArray(resp);
                        return arr.length > 0 ? (double) arr[0] : null;
                    }
                });
    }

    private Optional<String> send(Map<String, Object> payload) {
        if (stdin == null || stdout == null) {
            start();
        }
        if (stdin == null || stdout == null) return Optional.empty();

        String json = JsonUtil.toJson(payload);
        try {
            stdin.write(json);
            stdin.write("\n");
            stdin.flush();
        } catch (IOException e) {
            logger.warning("Persistenter Treiber: Schreiben fehlgeschlagen: " + e.getMessage());
            stop();
            return Optional.empty();
        }

        try {
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "mycelia-persistent-driver");
                    t.setDaemon(true);
                    return t;
                });
            }
            Future<String> future = executor.submit(() -> stdout.readLine());
            String line = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return Optional.ofNullable(line);
        } catch (Exception e) {
            logger.warning("Persistenter Treiber: Antwort-Timeout/Fehler: " + e.getMessage());
            stop();
            return Optional.empty();
        }
    }

    private File inferWorkDir() {
        if (command.size() < 2) return null;
        File script = new File(command.get(1));
        File parent = script.getParentFile();
        return parent != null && parent.isDirectory() ? parent : null;
    }
}
