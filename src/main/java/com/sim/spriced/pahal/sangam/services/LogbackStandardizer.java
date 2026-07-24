package com.sim.spriced.pahal.sangam.services;

//import com.maf.agents.build_agent.model.BuildResponse;
import com.sim.spriced.pahal.sangam.models.BuildResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;

@Service
public class LogbackStandardizer {

    private static final String CLASSNAME = "LogbackStandardizer";
    private static final Logger logger = LoggerFactory.getLogger(LogbackStandardizer.class);
    
    @Value("${config.path.logback-master}")
    private String masterConfigPath;

    public void enforceStandardLogback(Path projectRoot, BuildResponse response) {
        logger.info("{} : Entering method enforceStandardLogback", CLASSNAME);
        
        try {
            Path masterPath = resolveConfigPath();
            
            if (!validateMasterFile(masterPath, response)) {
                return;
            }

            processStandardization(projectRoot, masterPath, response);
            addLog(response, "Standard logback.xml applied successfully.");

        } catch (IOException e) {
            logger.error("Unexpected error in ClassName : {}, method enforceStandardLogback, error: {} ", CLASSNAME, e.getMessage(), e);
            addLog(response, "ERROR: Failed to update logback.xml: " + e.getMessage());
        }
        
        logger.info("{} : Exiting method enforceStandardLogback", CLASSNAME);
    }

    private boolean validateMasterFile(Path masterPath, BuildResponse response) {
        logger.info("{} : Entering method validateMasterFile", CLASSNAME);
        if (!Files.exists(masterPath)) {
            logger.warn("{} : Master config file not found at: {}", CLASSNAME, masterPath);
            addLog(response, "WARNING: Standard logback config not found. Skipping standardization.");
            return false;
        }
        logger.info("{} : Exiting method validateMasterFile", CLASSNAME);
        return true;
    }

    private void processStandardization(Path projectRoot, Path masterPath, BuildResponse response) throws IOException {
        logger.info("{} : Entering method processStandardization", CLASSNAME);
        
        Path resourcesDir = projectRoot.resolve("src/main/resources");
        Path targetLogback = resourcesDir.resolve("logback.xml");

        ensureDirectoryExists(resourcesDir);
        logStandardizationIntent(targetLogback, response);

        Files.copy(masterPath, targetLogback, StandardCopyOption.REPLACE_EXISTING);
        logger.info("{} : File copy operation completed successfully.", CLASSNAME);
        
        logger.info("{} : Exiting method processStandardization", CLASSNAME);
    }

    private void ensureDirectoryExists(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            logger.info("{} : Creating resources directory: {}", CLASSNAME, directory);
            Files.createDirectories(directory);
        }
    }

    private void logStandardizationIntent(Path targetLogback, BuildResponse response) {
        if (Files.exists(targetLogback)) {
            logger.info("{} : Existing logback.xml detected at target.", CLASSNAME);
            addLog(response, "Existing logback.xml found. Overwriting with standard configuration...");
        } else {
            logger.info("{} : No existing logback.xml found at target.", CLASSNAME);
            addLog(response, "No logback.xml found. Injecting standard configuration...");
        }
    }

    private Path resolveConfigPath() {
        logger.info("{} : Entering method resolveConfigPath", CLASSNAME);
        Path path = Paths.get(System.getProperty("user.dir")).resolve(masterConfigPath);
        
        if (!Files.exists(path.getParent())) {
            logger.info("{} : Parent directory missing. Checking two levels up...", CLASSNAME);
            path = Paths.get(System.getProperty("user.dir")).getParent().getParent().resolve(masterConfigPath);
        }
        
        logger.info("{} : Exiting method resolveConfigPath", CLASSNAME);
        return path;
    }

    private void addLog(BuildResponse res, String msg) {
        logger.info("[LOGBACK-STD] [BuildID: {}] {}", res.getBuildId(), msg);
        Optional.ofNullable(res)
                .map(BuildResponse::getBuildLogs)
                .ifPresent(logs -> logs.add(msg));
    }
}