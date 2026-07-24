package com.sim.spriced.pahal.sangam.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

@Service
public class ArtifactRegistryService {

    private static final String CLASSNAME = "ArtifactRegistryService";
    private static final Logger logger = LoggerFactory.getLogger(ArtifactRegistryService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${config.path.artifact-map}")
    private String mapPath;

    @Data
    public static class RepoDetails {
        private String repoUrl;
        private String description;
    }

    public RepoDetails getSourceRepo(String artifactId) {
        logger.info("{} : Entering method getSourceRepo", CLASSNAME);
        RepoDetails details = null;
        
        try {
            Path path = resolvePath(mapPath);
            if (validateMapFile(path)) {
                details = lookupArtifactInMap(path, artifactId);
            }
        } catch (Exception e) {
            logger.error("{} : Failed to parse artifact map: {}", CLASSNAME, e.getMessage());
        }

        logger.info("{} : Exiting method getSourceRepo", CLASSNAME);
        return details;
    }

    private boolean validateMapFile(Path path) {
        if (!Files.exists(path)) {
            logger.error("{} : Artifact map file not found at: {}", CLASSNAME, path);
            return false;
        }
        return true;
    }

    private RepoDetails lookupArtifactInMap(Path path, String artifactId) throws Exception {
        logger.info("{} : Entering method lookupArtifactInMap", CLASSNAME);
        Map<String, RepoDetails> map = OBJECT_MAPPER.readValue(path.toFile(), new TypeReference<>() {});
        
        return Optional.ofNullable(map.get(artifactId))
                .map(details -> {
                    logger.info("Match found! Artifact '{}' belongs to Repo: {}", artifactId, details.getRepoUrl());
                    return details;
                })
                .orElseGet(() -> {
                    logger.warn("No mapping found for artifact: {}", artifactId);
                    return null;
                });
    }

    private Path resolvePath(String relativePath) {
        logger.info("{} : Entering method resolvePath", CLASSNAME);
        Path path = Paths.get(System.getProperty("user.dir")).resolve(relativePath);
        
        if (!Files.exists(path.getParent())) {
            path = Paths.get(System.getProperty("user.dir")).getParent().getParent().resolve(relativePath);
        }

        logger.info("{} : Exiting method resolvePath", CLASSNAME);
        return path;
    }
}