package com.sim.spriced.pahal.sangam.controllers;

import com.sim.spriced.pahal.sangam.services.ConfigLoaderService;
import com.sim.spriced.pahal.sangam.services.GitHubSyncService;
import com.sim.spriced.pahal.sangam.services.SangamChatService;
import com.sim.spriced.pahal.sangam.services.SangamDeploymentService;
import com.sim.spriced.pahal.sangam.utility.TokenUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/")
public class SangamChatController {

    private static final Logger log = LoggerFactory.getLogger(SangamChatController.class);

    @Autowired
    private SangamChatService chatService;

    @Autowired
    private SangamDeploymentService deploymentService;

    @Autowired
    private TokenUtils tokenUtils;

    @Autowired
    private GitHubSyncService githubSyncService;

    @Autowired
    private ConfigLoaderService configLoaderService;

    // ------------------ /chat ------------------
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chatHandler(
            @RequestBody Map<String, Object> data,
            HttpServletRequest request) {

        log.info("Entering chatHandler with data: {}", data);

        String sessionId = (String) data.get("session_id");
        log.debug("Extracted sessionId: {}", sessionId);

        String message = (String) data.get("message");
        log.debug("Extracted message: {}", message);

        String username = tokenUtils.extractUsername(request);
        log.info("Detected User: {}", username);

        Map<String, Object> response;
        log.debug("Initialized response map");

        if (sessionId == null || !chatService.sessionExists(sessionId)) {
            log.info("New session detected or session not found");

            sessionId = UUID.randomUUID().toString();
            log.debug("Generated new sessionId: {}", sessionId);

            log.info("Calling processChatMessage for new session");
            response = chatService.processChatMessage(sessionId, null, username);
            log.debug("Received response from chatService: {}", response);

        } else {
            log.info("Existing session detected");

            log.info("Calling processChatMessage for existing session");
            response = chatService.processChatMessage(sessionId, message, username);
            log.debug("Received response from chatService: {}", response);
        }

        response.put("session_id", sessionId);
        log.debug("Added session_id to response");

        log.info("Exiting chatHandler");
        return ResponseEntity.ok(response);
    }

    // ------------------ /chat/status ------------------
    @GetMapping("/chat/status/{buildId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String buildId) {

        log.info("Entering getStatus with buildId: {}", buildId);

        log.info("Calling deploymentService.getDeploymentStatus");
        Map<String, Object> status = deploymentService.getDeploymentStatus(buildId);

        log.debug("Received status: {}", status);

        log.info("Exiting getStatus");
        return ResponseEntity.ok(status);
    }

    // ------------------ /api/v1/direct-deploy ------------------
    @PostMapping("/api/v1/direct-deploy")
    public ResponseEntity<Map<String, Object>> triggerDeployment(
            @RequestBody Map<String, Object> data,
            HttpServletRequest request) {

        log.info("Entering triggerDeployment with payload: {}", data);

        String repoName = (String) data.get("repo_name");
        log.debug("repoName: {}", repoName);

        String branchName = (String) data.get("branch_name");
        log.debug("branchName: {}", branchName);

        String serverName = (String) data.get("server_name");
        log.debug("serverName: {}", serverName);

        String depBranch = (String) data.getOrDefault("dependency_branch", "main");
        log.debug("dependency branch: {}", depBranch);

        String username = tokenUtils.extractUsername(request);
        log.info("Extracted username: {}", username);

        // -------- VALIDATION --------
        if (repoName == null || branchName == null || serverName == null) {
            log.warn("Missing required fields");
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required fields"));
        }

        // -------- FETCH REPOS --------
        log.info("Fetching repositories from GitHub");
        List<Map<String, Object>> repos = githubSyncService.fetchRepos();
        log.debug("Fetched repos: {}", repos);

        if (repos == null || repos.isEmpty()) {
            log.warn("GitHub repos empty, falling back to config");
            repos = configLoaderService.loadRepos();
            log.debug("Loaded repos from config: {}", repos);
        }

        // -------- FIND REPO --------
        log.info("Finding selected repository");
        Map<String, Object> selectedRepo = repos.stream()
                .filter(r -> repoName.equals(r.get("name")))
                .findFirst()
                .orElse(null);

        log.debug("Selected repo: {}", selectedRepo);

        if (selectedRepo == null) {
            log.error("Repository not found: {}", repoName);
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Repository '" + repoName + "' not found."));
        }

        // -------- DEPENDENCY SCAN --------
        log.info("Scanning dependencies for repo: {}", repoName);

        List<Map<String, Object>> deps =
                githubSyncService.detectDependencies(repoName, branchName);

        log.debug("Detected dependencies: {}", deps);

        if (deps != null && !deps.isEmpty()) {
            log.info("Found {} dependencies", deps.size());
        } else {
            log.info("No dependencies found, initializing empty list");
            deps = new ArrayList<>();
        }

        // -------- BUILD CONTEXT --------
        log.info("Building deployment context");

        Map<String, Object> context = new HashMap<>();

        context.put("repo_name", repoName);
        log.debug("Added repo_name");

        context.put("repo_id", selectedRepo.get("id"));
        log.debug("Added repo_id");

        context.put("repo_url", selectedRepo.get("url"));
        log.debug("Added repo_url");

        context.put("branch_name", branchName);
        log.debug("Added branch_name");

        context.put("server_name", serverName);
        log.debug("Added server_name");

        context.put("username", username);
        log.debug("Added username");

        context.put("pre_builds_list", deps);
        log.debug("Added dependencies");

        context.put("dependency_branch", depBranch);
        log.debug("Added dependency_branch");

        try {
            log.info("Calling deploymentService.executeDeployment");

            List<String> logs = deploymentService.executeDeployment(context);
            log.debug("Deployment logs: {}", logs);

            log.info("Deployment successful");

            return ResponseEntity.ok(
                    Map.of(
                            "status", "success",
                            "logs", logs
                    )
            );

        } catch (Exception e) {
            log.error("Deployment failed", e);

            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "status", "failed",
                            "error", e.getMessage()
                    ));
        }
    }
}