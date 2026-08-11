package com.sim.spriced.pahal.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repo")
@CrossOrigin(origins = "*")
public class RepositorySetupController {

    private static final Logger logger = LoggerFactory.getLogger(RepositorySetupController.class);
    private final RepositorySetupService repositorySetupService;

    @Autowired
    public RepositorySetupController(RepositorySetupService repositorySetupService) {
        this.repositorySetupService = repositorySetupService;
    }

    @GetMapping("/branches")
    public ResponseEntity<List<String>> fetchBranches() {
        logger.info("Entering fetchBranches");
        try {
            List<String> branches = repositorySetupService.getRemoteBranches();
            return ResponseEntity.ok(branches);
        } catch (Exception e) {
            logger.warn("Exception while fetching branches", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @PostMapping("/setup")
    public ResponseEntity<Map<String, String>> setupRepository(@RequestBody Map<String, String> request) {
        logger.info("Entering setupRepository");
        String baseBranch = request.get("baseBranch");
        String newBranchName = request.get("newBranchName");
        String serviceName = request.get("serviceName");  // optional, defaults to newBranchName if missing

        if (baseBranch == null || newBranchName == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required fields: baseBranch, newBranchName"));
        }

        if (serviceName == null || serviceName.isBlank()) {
            serviceName = newBranchName;
        }

        try {
            Path submodulePath = repositorySetupService.setupRepository(
                    baseBranch, newBranchName, serviceName,
                    (percentage, message) -> logger.info("[Setup {}%] {}", percentage, message)
            );
            return ResponseEntity.ok(Map.of(
                    "submodulePath", submodulePath.toString(),
                    "serviceName", serviceName
            ));
        } catch (Exception e) {
            logger.warn("Repository setup failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}