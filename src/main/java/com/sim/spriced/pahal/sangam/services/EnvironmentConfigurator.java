package com.sim.spriced.pahal.sangam.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sim.spriced.pahal.sangam.models.BuildResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@Service
public class EnvironmentConfigurator {

    private static final String CLASSNAME = "EnvironmentConfigurator";
    private static final Logger logger = LoggerFactory.getLogger(EnvironmentConfigurator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${config.path.env-properties}")
    private String envConfigPath;

    public void configureEnvironment(Path projectRoot, String serverName, BuildResponse response) {
        logger.info("{} : Entering method configureEnvironment", CLASSNAME);
        try {
            Map<String, String> targetProperties = fetchTargetProperties(serverName, response);
            if (targetProperties.isEmpty()) return;

            Path propertyFilePath = locatePropertiesFile(projectRoot, response);
            if (propertyFilePath == null) return;

            Properties props = loadExistingProperties(propertyFilePath);
            applyOverridesAndSave(props, targetProperties, propertyFilePath, serverName);

            addLog(response, "Environment configuration applied successfully.");
        } catch (Exception e) {
            logger.error("{} : Failed to configure environment: {}", CLASSNAME, e.getMessage());
            addLog(response, "ERROR: Environment configuration failed: " + e.getMessage());
        }
        logger.info("{} : Exiting method configureEnvironment", CLASSNAME);
    }

    private Map<String, String> fetchTargetProperties(String serverName, BuildResponse response) throws IOException {
        logger.info("{} : Entering method fetchTargetProperties", CLASSNAME);
        Map<String, Map<String, String>> allConfigs = loadEnvConfigs();
        
        if (!allConfigs.containsKey(serverName)) {
            logger.warn("{} : Server '{}' not found in configurations.", CLASSNAME, serverName);
            addLog(response, "WARNING: No config found for server: " + serverName + ". Using defaults.");
            return Collections.emptyMap();
        }
        
        logger.info("{} : Exiting method fetchTargetProperties", CLASSNAME);
        return allConfigs.get(serverName);
    }

    private Path locatePropertiesFile(Path projectRoot, BuildResponse response) {
        logger.info("{} : Entering method locatePropertiesFile", CLASSNAME);
        Optional<Path> propertyFileOpt = findApplicationProperties(projectRoot);

        if (propertyFileOpt.isEmpty()) {
            logger.warn("{} : application.properties could not be located.", CLASSNAME);
            addLog(response, "WARNING: application.properties not found. Skipping update.");
            return null;
        }
        
        logger.info("{} : Exiting method locatePropertiesFile", CLASSNAME);
        return propertyFileOpt.get();
    }

    private Properties loadExistingProperties(Path path) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            props.load(fis);
        }
        return props;
    }

    private void applyOverridesAndSave(Properties props, Map<String, String> targetMap, Path path, String server) throws IOException {
        logger.info("{} : Entering method applyOverridesAndSave", CLASSNAME);
        
        targetMap.forEach((key, value) -> {
            String logValue = key.toUpperCase().contains("PASSWORD") ? "*****" : value;
            logger.info("{} : Updating property -> Key: {}, Value: {}", CLASSNAME, key, logValue);
            props.setProperty(key, value);
        });

        try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
            props.store(fos, "Updated by Agent Chakra for Environment: " + server);
        }
        logger.info("{} : Exiting method applyOverridesAndSave", CLASSNAME);
    }

    private Map<String, Map<String, String>> loadEnvConfigs() throws IOException {
        logger.info("{} : Entering method loadEnvConfigs", CLASSNAME);
        Path path = resolvePath(envConfigPath);
        
        if (Files.exists(path)) {
            logger.info("{} : Config file exists. Parsing JSON...", CLASSNAME);
            return OBJECT_MAPPER.readValue(path.toFile(), new TypeReference<>() {});
        }
        
        logger.warn("{} : Config file does not exist at path: {}", CLASSNAME, path);
        return new HashMap<>();
    }

    private Path resolvePath(String relativePath) {
        Path path = Paths.get(System.getProperty("user.dir")).resolve(relativePath);
        if (!Files.exists(path.getParent())) {
            path = Paths.get(System.getProperty("user.dir")).getParent().getParent().resolve(relativePath);
        }
        return path;
    }

    private Optional<Path> findApplicationProperties(Path projectRoot) {
        logger.info("{} : Entering method findApplicationProperties", CLASSNAME);
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            return stream
                    .filter(p -> p.getFileName().toString().equals("application.properties"))
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> !p.toString().contains("test"))
                    .findFirst();
        } catch (IOException e) {
            logger.error("{} : Error while walking directory: {}", CLASSNAME, e.getMessage());
            return Optional.empty();
        }
    }

    private void addLog(BuildResponse res, String msg) {
        logger.info("[ENV-CONFIG] {}", msg);
        Optional.ofNullable(res)
                .map(BuildResponse::getBuildLogs)
                .ifPresent(logs -> logs.add(msg));
    }
}