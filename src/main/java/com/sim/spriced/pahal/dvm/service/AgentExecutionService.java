package com.sim.spriced.pahal.dvm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sim.spriced.pahal.dvm.config.PythonAgentConfig;
import com.sim.spriced.pahal.dvm.dto.AgentRequest;
import com.sim.spriced.pahal.dvm.dto.AgentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionService {

    private final PythonAgentConfig config;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public AgentResponse executeAgent(AgentRequest request) {
        Path requestFile = null;
        Path responseFile = null;

        try {
            // Create temp files for request/response
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "agent-executions");
            Files.createDirectories(tempDir);

            requestFile = Files.createTempFile(tempDir, "agent_request_", ".json");
            responseFile = Files.createTempFile(tempDir, "agent_response_", ".json");

            // Write request to temp file
            objectMapper.writeValue(requestFile.toFile(), request);
            log.info("Request written to: {}", requestFile);

            // RESOLVE ABSOLUTE PATHS
            Path basePath = Paths.get(config.getBasePath()).toAbsolutePath();
            Path venvPythonPath = basePath
                    .resolve(config.getVenvPath())
                    .resolve("Scripts")
                    .resolve("python.exe")
                    .toAbsolutePath();
            Path scriptPath = basePath
                    .resolve(config.getScript())
                    .toAbsolutePath();
            Path generatedFilePath = Paths.get(config.getGeneratedFilePath()).toAbsolutePath();
            Files.createDirectories(generatedFilePath);

            // VERIFY FILES EXIST
            if (!Files.exists(venvPythonPath)) {
                throw new RuntimeException(
                        "Python virtual environment not found at: " + venvPythonPath
                );
            }
            if (!Files.exists(scriptPath)) {
                throw new RuntimeException("main.py not found at: " + scriptPath);
            }

            // BUILD COMMAND
            ProcessBuilder pb = new ProcessBuilder(
                    venvPythonPath.toString(),
                    "-u",  // UNBUFFERED OUTPUT - shows logs in real-time
                    scriptPath.toString(),
                    requestFile.toAbsolutePath().toString(),
                    responseFile.toAbsolutePath().toString()
            );

            pb.directory(basePath.toFile());

            // DON'T merge streams - keep stdout and stderr separate for better logging
            pb.redirectErrorStream(false);

            // Set environment variables
            Map<String, String> env = pb.environment();
            env.put("GENERATED_FILE_PATH", generatedFilePath.toString());
            env.put("PYTHONUNBUFFERED", "1");  // Force Python to flush output
            env.put("PYTHONIOENCODING", "utf-8");

            // Set PYTHONPATH
            String srcPath = basePath.resolve("src").toAbsolutePath().toString();
            String currentPythonPath = env.getOrDefault("PYTHONPATH", "");
            if (!currentPythonPath.isEmpty()) {
                env.put("PYTHONPATH", srcPath + File.pathSeparator + currentPythonPath);
            } else {
                env.put("PYTHONPATH", srcPath);
            }

            log.info("Executing Python agent...");
            log.info("Working directory: {}", basePath);
            log.info("PYTHONPATH: {}", env.get("PYTHONPATH"));

            Process process = pb.start();

            // Read stdout and stderr in separate threads
            Future<String> stdoutFuture = executorService.submit(() ->
                    readStream(process.getInputStream(), "PYTHON-OUT")
            );
            Future<String> stderrFuture = executorService.submit(() ->
                    readStream(process.getErrorStream(), "PYTHON-ERR")
            );

            // Wait for completion with timeout
            boolean completed = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                throw new RuntimeException("Agent execution timed out after " +
                        config.getTimeoutSeconds() + " seconds");
            }

            // Get outputs
            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);

            int exitCode = process.exitValue();

            // Log all Python output
            if (!stdout.isEmpty()) {
                log.info("=== Python STDOUT ===\n{}", stdout);
            }
            if (!stderr.isEmpty()) {
                log.info("=== Python STDERR ===\n{}", stderr);
            }

            log.info("Python process exited with code: {}", exitCode);

            // Parse response
            if (exitCode == 0 && Files.exists(responseFile) && Files.size(responseFile) > 0) {
                String responseContent = Files.readString(responseFile);
                log.debug("Response file content: {}", responseContent);

                AgentResponse response = objectMapper.readValue(
                        responseContent, AgentResponse.class
                );

//                if (response.getError() != null && !response.getError().isEmpty()) {
//                    log.error("Agent reported error: {}", response.getError());
//                    throw new RuntimeException("Agent execution failed: " + response.getError());
//                }

                log.info("Agent execution successful");
                return response;
            } else {
                throw new RuntimeException(
                        String.format("Agent failed. Exit code: %d\nSTDOUT: %s\nSTDERR: %s",
                                exitCode, stdout, stderr)
                );
            }

        } catch (Exception e) {
            log.error("Agent execution failed", e);
            throw new RuntimeException("Failed to execute Python agent: " + e.getMessage(), e);
        } finally {
            cleanupTempFile(requestFile);
            cleanupTempFile(responseFile);
        }
    }

    /**
     * Read stream and log in real-time
     */
    private String readStream(InputStream inputStream, String streamName) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                // Log each line in real-time based on stream type
                if (streamName.contains("ERR")) {
                    log.warn("{} | {}", streamName, line);
                } else {
                    log.info("{} | {}", streamName, line);
                }
            }
        } catch (IOException e) {
            log.error("Error reading {} stream", streamName, e);
        }
        return output.toString();
    }

    private String readProcessOutput(Process process) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        } catch (IOException e) {
            log.error("Error reading process output", e);
        }
        return output.toString();
    }

    private void cleanupTempFile(Path filePath) {
        if (filePath != null) {
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                log.warn("Failed to delete temp file: {}", filePath);
            }
        }
    }
}