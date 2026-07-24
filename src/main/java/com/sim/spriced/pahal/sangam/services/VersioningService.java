package com.sim.spriced.pahal.sangam.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Service
public class VersioningService {

    private static final String CLASSNAME = "VersioningService";
    private static final Logger logger = LoggerFactory.getLogger(VersioningService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    @Value("${config.path.repo-versions}")
    private String versionFilePath;

    /**
     * Generates and persists the next version number for a given repository.
     */
    public synchronized String getNextVersion(String repoName) {
        logger.info("{} : Entering method getNextVersion", CLASSNAME);
        logger.info("Generating next version for repo: {}", repoName);
        
        File versionFile = resolveVersionFilePath().toFile();
        Map<String, String> versionMap = loadVersionMap(versionFile);

        String currentVersion = versionMap.getOrDefault(repoName, "0.0");
        String nextVersion = incrementVersion(currentVersion);

        versionMap.put(repoName, nextVersion);
        saveVersionMap(versionFile, versionMap, repoName, nextVersion);

        logger.info("{} : Exiting method getNextVersion", CLASSNAME);
        return nextVersion;
    }

    private Map<String, String> loadVersionMap(File versionFile) {
        logger.info("{} : Entering method loadVersionMap", CLASSNAME);
        if (versionFile.exists()) {
            try {
                return OBJECT_MAPPER.readValue(versionFile, new TypeReference<Map<String, String>>() {});
            } catch (IOException e) {
                logger.error("{} : Failed to read version file, starting fresh: {}", CLASSNAME, e.getMessage());
            }
        }
        return new HashMap<>();
    }

    private void saveVersionMap(File versionFile, Map<String, String> versionMap, String repoName, String nextVersion) {
        logger.info("{} : Entering method saveVersionMap", CLASSNAME);
        try {
            ensureDirectoryExists(versionFile.getParentFile());
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(versionFile, versionMap);
            logger.info("Updated version for {} to {}", repoName, nextVersion);
        } catch (IOException e) {
            logger.error("{} : Failed to save version file: {}", CLASSNAME, e.getMessage());
        }
    }

    private void ensureDirectoryExists(File directory) {
        if (directory != null && !directory.exists()) {
            directory.mkdirs();
        }
    }

    private String incrementVersion(String version) {
        logger.info("{} : Entering method incrementVersion", CLASSNAME);
        try {
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);

            minor++; // Logic: 0.1 -> 0.2 ... 0.9 -> 0.10
            
            return major + "." + minor;
        } catch (Exception e) {
            logger.warn("{} : Could not parse version '{}'. Resetting to 0.1", CLASSNAME, version);
            return "0.1";
        }
    }

    private Path resolveVersionFilePath() {
        logger.info("{} : Entering method resolveVersionFilePath", CLASSNAME);
        Path path = Paths.get(System.getProperty("user.dir")).resolve(versionFilePath);
        
        if (!Files.exists(path.getParent())) {
            path = Paths.get(System.getProperty("user.dir")).getParent().getParent().resolve(versionFilePath);
        }
        
        logger.info("{} : Exiting method resolveVersionFilePath", CLASSNAME);
        return path;
    }
}