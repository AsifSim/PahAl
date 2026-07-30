package com.sim.spriced.pahal.dvm.service;


import com.sim.spriced.pahal.dvm.config.PythonAgentConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PythonEnvironmentService {

    private final PythonAgentConfig config;

    @PostConstruct
    public void initialize() {
        validateConfiguration();
        detectPythonCommand();
        setupVirtualEnv();
        installDependencies();
    }

    private void validateConfiguration() {
        // Check if base path exists
        Path basePath = Paths.get(config.getBasePath()).toAbsolutePath();
        if (!Files.exists(basePath)) {
            log.error("Python agent base path does not exist: {}", basePath);
            throw new RuntimeException(
                    "Python agent directory not found: " + basePath +
                            ". Please ensure the 'dvm/adk_final-initial_commit' directory exists in your project root."
            );
        }

        // Check if requirements.txt exists
        Path requirementsPath = basePath.resolve("requirements.txt");
        if (!Files.exists(requirementsPath)) {
            log.error("requirements.txt not found at: {}", requirementsPath);
            throw new RuntimeException(
                    "requirements.txt not found: " + requirementsPath
            );
        }

        log.info("Configuration validated. Base path: {}", basePath);
    }

    private void detectPythonCommand() {
        // Try to find working Python command
        List<String> possibleCommands = new ArrayList<>();

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            possibleCommands.addAll(Arrays.asList("python", "python3", "py", "py -3"));
        } else {
            possibleCommands.addAll(Arrays.asList("python3", "python"));
        }

        String workingCommand = null;
        for (String cmd : possibleCommands) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd.split(" ")[0], "--version");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                String version = readStream(process.getInputStream());
                int exitCode = process.waitFor(5, TimeUnit.SECONDS) ? process.exitValue() : -1;

                if (exitCode == 0) {
                    workingCommand = cmd.split(" ")[0];
                    log.info("Found working Python command: {} (version: {})",
                            workingCommand, version.trim());
                    break;
                }
            } catch (Exception e) {
                log.debug("Command '{}' not available: {}", cmd, e.getMessage());
            }
        }

        if (workingCommand != null) {
            config.setPythonCommand(workingCommand);
        } else {
            log.error("No Python installation found. Checked commands: {}", possibleCommands);
            throw new RuntimeException(
                    "Python is not installed or not in PATH. " +
                            "Please install Python 3.8+ and ensure it's available in your system PATH."
            );
        }
    }

    private void setupVirtualEnv() {
        Path basePath = Paths.get(config.getBasePath()).toAbsolutePath();
        Path venvPath = basePath.resolve(config.getVenvPath());

        if (!Files.exists(venvPath)) {
            log.info("Creating virtual environment at: {}", venvPath);
            try {
                List<String> command = new ArrayList<>();
                command.add(config.getPythonCommand());
                command.add("-m");
                command.add("venv");
                command.add(config.getVenvPath());

                log.debug("Running command: {} in directory: {}",
                        String.join(" ", command), basePath);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(basePath.toFile());
                pb.redirectErrorStream(true);

                Process process = pb.start();
                String output = readStream(process.getInputStream());

                boolean completed = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);

                if (!completed) {
                    process.destroyForcibly();
                    log.error("Virtual environment creation timed out");
                    throw new RuntimeException(
                            "Virtual environment creation timed out after " +
                                    config.getTimeoutSeconds() + " seconds"
                    );
                }

                if (process.exitValue() != 0) {
                    log.error("Failed to create venv. Exit code: {}, Output: {}",
                            process.exitValue(), output);
                    throw new RuntimeException(
                            "Failed to create virtual environment. Error: " + output
                    );
                }

                log.info("Virtual environment created successfully at: {}", venvPath);

                // Verify venv was created properly
                Path venvPython = getVenvPythonPath();
                if (!Files.exists(venvPython)) {
                    throw new RuntimeException(
                            "Virtual environment created but Python executable not found at: " +
                                    venvPython
                    );
                }
                log.debug("Virtual environment Python executable: {}", venvPython);

            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("Failed to create virtual environment", e);
                throw new RuntimeException(
                        "Python environment setup failed: " + e.getMessage(), e
                );
            }
        } else {
            log.info("Virtual environment already exists at: {}", venvPath);

            // Verify the venv Python exists
            Path venvPython = getVenvPythonPath();
            if (!Files.exists(venvPython)) {
                log.warn("Virtual environment directory exists but Python executable not found. Recreating...");
                try {
                    deleteDirectory(venvPath);
                } catch (IOException e) {
                    log.error("Failed to delete corrupted venv", e);
                }
                setupVirtualEnv(); // Recursive call to recreate
                return;
            }
        }
    }

    private void installDependencies() {
        log.info("Installing Python dependencies...");
        try {
            Path basePath = Paths.get(config.getBasePath()).toAbsolutePath();

            // Upgrade pip first
            List<String> upgradePip = new ArrayList<>();
            upgradePip.add(getVenvPythonPath().toString());
            upgradePip.add("-m");
            upgradePip.add("pip");
            upgradePip.add("install");
            upgradePip.add("--upgrade");
            upgradePip.add("pip");

            executeCommand(basePath, upgradePip);

            // Install packages one at a time with --no-deps flag first
            String[] packages = {
                    "google-adk",
                    "google-cloud-aiplatform",
                    "google-generativeai"
            };

            for (String pkg : packages) {
                log.info("Installing {}", pkg);
                try {
                    // First try with --no-deps to get the package installed
                    List<String> installNoDeps = new ArrayList<>();
                    installNoDeps.add(getVenvPythonPath().toString());
                    installNoDeps.add("-m");
                    installNoDeps.add("pip");
                    installNoDeps.add("install");
                    installNoDeps.add(pkg);
                    installNoDeps.add("--no-deps");
                    installNoDeps.add("--quiet");

                    executeCommand(basePath, installNoDeps);

                    // Then install dependencies
                    List<String> installWithDeps = new ArrayList<>();
                    installWithDeps.add(getVenvPythonPath().toString());
                    installWithDeps.add("-m");
                    installWithDeps.add("pip");
                    installWithDeps.add("install");
                    installWithDeps.add(pkg);
                    installWithDeps.add("--quiet");

                    executeCommand(basePath, installWithDeps);

                } catch (Exception e) {
                    log.warn("Failed to install {}: {}", pkg, e.getMessage());
                }
            }

            log.info("Dependencies installation completed");

        } catch (Exception e) {
            log.warn("Failed to install dependencies: {}", e.getMessage());
        }
    }

    private void executeCommand(Path workingDir, List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        log.debug("Executing: {} in {}", String.join(" ", command), workingDir);

        Process process = pb.start();
        String output = readStream(process.getInputStream());

        boolean completed = process.waitFor(120, TimeUnit.SECONDS);

        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("Command timed out: " + String.join(" ", command));
        }

        if (process.exitValue() != 0) {
            log.error("Command failed with exit code {}: {}", process.exitValue(), output);
            throw new RuntimeException("Command failed: " + output);
        }

        log.debug("Command output: {}", output);
    }

    private Path getVenvPythonPath() {
        Path basePath = Paths.get(config.getBasePath()).toAbsolutePath();
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            return basePath.resolve(config.getVenvPath())
                    .resolve("Scripts")
                    .resolve("python.exe");
        } else {
            return basePath.resolve(config.getVenvPath())
                    .resolve("bin")
                    .resolve("python");
        }
    }

    private String readStream(InputStream inputStream) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        return output.toString();
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", p);
                        }
                    });
        }
    }
}