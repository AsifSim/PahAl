package com.sim.spriced.pahal.controllers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@CrossOrigin(origins = "*") // Allows your HTML frontend to access these safely
public class HardwareTelemetryController {

    @Value("${LOG_FILE_PATH}")
    String logFilePath;

    // A cached thread pool handles log-streaming asynchronously without blocking your primary REST APIs
    private final ExecutorService logExecutor = Executors.newCachedThreadPool();

    @GetMapping("/api/telemetry")
    public ResponseEntity<Map<String, Object>> getHardwareTelemetry() {
        Map<String, Object> response = new HashMap<>();
        try {
            String cmd = "nvidia-smi --query-gpu=utilization.gpu,memory.used,memory.total --format=csv,noheader,nounits";

            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            Process process = isWindows
                    ? Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", cmd})
                    : Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", cmd});

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = reader.readLine();
            process.waitFor();

            if (output != null && !output.trim().isEmpty()) {
                String[] metrics = output.split(", ");
                response.put("gpu_load", Integer.parseInt(metrics[0].trim()));
                response.put("vram_used", Integer.parseInt(metrics[1].trim()));
                response.put("vram_total", Integer.parseInt(metrics[2].trim()));
                return ResponseEntity.ok(response);
            } else {
                throw new Exception("NVIDIA-SMI target execution returned an empty stream.");
            }
        } catch (Exception e) {
            // Fallback safe payload context if an NVIDIA card is missing/busy
            response.put("gpu_load", 0);
            response.put("vram_used", 1156);
            response.put("vram_total", 8192);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping(value = "/api/logs/core/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCoreLogs() {
        // 0L means the emitter will not time out automatically
        SseEmitter emitter = new SseEmitter(0L);

        logExecutor.execute(() -> {
            Process process = null;
            try {
                // 2. Fix path concatenation bugs using robust Path manipulation
                System.out.println("logFilePath = "+logFilePath);
                File logFile = Paths.get(logFilePath, "pahal.log").toFile();
                System.out.println("logFile = "+logFile);

                // 3. Keep waiting in a soft loop if logback hasn't flushed the first file entry yet
                while (!logFile.exists()) {
                    emitter.send(SseEmitter.event().data("[Core Logs] Awaiting log file generation at: " + logFile.getAbsolutePath()));
                    Thread.sleep(3000);
                }

                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                if (isWindows) {
                    // Using powershell to tail the absolute file path correctly wrapped in quotes
                    process = Runtime.getRuntime().exec(new String[]{
                            "powershell.exe", "-Command", "Get-Content \"" + logFile.getAbsolutePath() + "\" -Tail 30 -Wait"
                    });
                } else {
                    process = Runtime.getRuntime().exec(new String[]{
                            "tail", "-f", "-n", "30", logFile.getAbsolutePath()
                    });
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;

                // Read lines from terminal process output stream and pipe out over SSE link
                while ((line = reader.readLine()) != null) {
                    emitter.send(SseEmitter.event().data(line));
                }

            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().data("[Core Log Bridge Error] " + e.getMessage()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        });

        return emitter;
    }

    @GetMapping(value = "/api/logs/ollama/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOllamaLogs() {
        // Continuous server-sent events stream container
        SseEmitter emitter = new SseEmitter(0L);

        logExecutor.execute(() -> {
            Process process = null;
            try {
                // Target name or ID of your running Ollama container
                String containerName = "ollama";

                // Command to stream ('-f') and tail the last 20 lines from docker runtime environment
                String[] cmd = {"docker", "logs", "-f", "--tail", "20", containerName};

                process = Runtime.getRuntime().exec(cmd);

                // Capture both normal log output streams and potential stderr lines
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

                // Read runtime engine streams asynchronously
                String line;
                while ((line = reader.readLine()) != null) {
                    emitter.send(SseEmitter.event().data(line));
                }

                // Fallback checking to stream errors if the stdout channel breaks early
                while ((line = errorReader.readLine()) != null) {
                    emitter.send(SseEmitter.event().data("[Docker Stderr] " + line));
                }

            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().data("[Ollama Bridge Error] Failed to stream container logs: " + e.getMessage()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            } finally {
                if (process != null) {
                    process.destroy(); // Safely clean up the native process if client disconnects
                }
            }
        });

        return emitter;
    }
}