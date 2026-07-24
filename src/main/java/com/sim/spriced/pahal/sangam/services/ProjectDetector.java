package com.sim.spriced.pahal.sangam.services;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Stream;

@Component
public class ProjectDetector {

    private static final String CLASSNAME = "ProjectDetector";
    private static final Logger logger = LoggerFactory.getLogger(ProjectDetector.class);
    
    @Value("${config.project-detector.max-depth}")
    private int maxSearchDepth;

    public enum ProjectType {
        JAVA("Java/Maven"),
        ANGULAR("Angular"),
        PYTHON("Python"), 
        UNKNOWN("Unknown");

        @Getter
        private final String displayName;

        ProjectType(String displayName) {
            this.displayName = displayName;
        }
    }

    @Getter
    public static class ProjectDetectionResult {
        private final ProjectType projectType;
        private final Path projectRoot; 

        public ProjectDetectionResult(ProjectType projectType, Path projectRoot) {
            this.projectType = projectType;
            this.projectRoot = projectRoot;
        }
    }

    public ProjectDetectionResult detectProjectType(String projectPath) {
        logger.info("{} : Entering method detectProjectType", CLASSNAME);
        Path basePath = Paths.get(projectPath);
        
        // --- NEW LOGIC: Check Root specifically first for package.json ---
        if (Files.exists(basePath.resolve("package.json"))) {
            logger.info("{} : Detected package.json at Root. Marking as ANGULAR/Web project.", CLASSNAME);
            return new ProjectDetectionResult(ProjectType.ANGULAR, basePath);
        }

        // --- Fallback to deep search if not found at root ---
        Optional<Path> javaPath = checkProject(basePath, "pom.xml", ProjectType.JAVA);
        if (javaPath.isPresent()) return new ProjectDetectionResult(ProjectType.JAVA, javaPath.get());

        // Changed from angular.json to package.json
        Optional<Path> angularPath = checkProject(basePath, "package.json", ProjectType.ANGULAR);
        if (angularPath.isPresent()) return new ProjectDetectionResult(ProjectType.ANGULAR, angularPath.get());

        Optional<Path> pythonPath = checkProject(basePath, "requirements.txt", ProjectType.PYTHON);
        if (pythonPath.isPresent()) return new ProjectDetectionResult(ProjectType.PYTHON, pythonPath.get());

        logger.warn("{} : No project structure detected. Returning UNKNOWN.", CLASSNAME);
        return new ProjectDetectionResult(ProjectType.UNKNOWN, basePath);
    }

    private Optional<Path> checkProject(Path basePath, String markerFile, ProjectType type) {
        logger.info("{} : Checking for {} project (looking for {})...", CLASSNAME, type.getDisplayName(), markerFile);
        Optional<Path> foundPath = findBuildFile(basePath, markerFile);
        
        if (foundPath.isPresent()) {
            logger.info("{} : {} project detected! Root: {}", CLASSNAME, type.getDisplayName(), foundPath.get());
        } else {
            logger.debug("{} : {} not found.", CLASSNAME, markerFile);
        }
        return foundPath;
    }

    private Optional<Path> findBuildFile(Path basePath, String fileName) {
        logger.info("{} : Entering method findBuildFile", CLASSNAME);
        try (Stream<Path> stream = Files.walk(basePath, maxSearchDepth)) {
            return stream
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .filter(this::isNotExcludedPath)
                    .peek(path -> logger.debug("{} : Found name match candidate: {}", CLASSNAME, path))
                    .findFirst()
                    .map(Path::getParent);
        } catch (IOException e) {
            logger.error("Unexpected error in ClassName : {}, method findBuildFile, error: {} ", CLASSNAME, e.getMessage(), e);
            return Optional.empty();
        } finally {
            logger.info("{} : Exiting method findBuildFile", CLASSNAME);
        }
    }

    private boolean isNotExcludedPath(Path path) {
        String p = path.toString();
        boolean excluded = p.contains("node_modules") || p.contains(".git") || p.contains("venv");
        if (excluded) {
            logger.debug("{} : Skipping excluded path: {}", CLASSNAME, path);
        }
        return !excluded;
    }
}