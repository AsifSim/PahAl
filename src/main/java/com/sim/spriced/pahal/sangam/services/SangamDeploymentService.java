package com.sim.spriced.pahal.sangam.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class SangamDeploymentService {

    private static final Logger log = LoggerFactory.getLogger(SangamDeploymentService.class);

    private final RestTemplate restTemplate = new RestTemplate();

    // -------- ENV CONFIG --------
// -------- ENV CONFIG --------
    @Value("${JAVA_API_URL}")
    private String javaApiUrl;

    @Value("${JAVA_STATUS_URL}")
    private String javaStatusUrl;

    @Value("${JAVA_HISTORY_URL}")
    private String javaHistoryUrl;

    @Value("${GITHUB_TOKEN}")
    private String githubToken;

    // ---------------- CHECK PREVIOUS DEPLOYMENT ----------------
    public Map<String, Object> checkPreviousDeployment(String repoName, String branchName) {

        log.info("Checking previous deployment for repo={} branch={}", repoName, branchName);

        try {
            String url = javaHistoryUrl + "?repoName=" + repoName + "&branchName=" + branchName;

            ResponseEntity<Map> response =
                    restTemplate.getForEntity(url, Map.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                log.info("Previous deployment found");
                return response.getBody();
            }

        } catch (Exception e) {
            log.warn("Error checking history: {}", e.getMessage(), e);
        }

        return null;
    }

    // ---------------- EXECUTE DEPLOYMENT ----------------
    public List<String> executeDeployment(Map<String, Object> state) {

        log.info("Starting deployment execution");

        List<String> logs = new ArrayList<>();

        String repoUrl = (String) state.get("repo_url");
        String branchName = (String) state.get("branch_name");
        String serverName = (String) state.get("server_name");
        String repoName = (String) state.get("repo_name");
        String requestedBy = (String) state.getOrDefault("username", "Anonymous");

        String existingBuildId = (String) state.get("redeploy_build_id");
        String commitId = (String) state.get("commit_id");
        Boolean isTag = (Boolean) state.getOrDefault("is_tag", false);

        logs.add("Initializing deployment for " + requestedBy + "...");

        if (Boolean.TRUE.equals(isTag)) {
            logs.add("Mode: Release Tag | Version: " + branchName);
        } else if (commitId != null) {
            logs.add("Mode: Commit | Branch: " + branchName + " | SHA: " + shortSha(commitId));
        } else {
            logs.add("Mode: Branch Head | Branch: " + branchName);
        }

        // -------- PRE BUILDS --------
        List<Map<String, Object>> preBuilds = new ArrayList<>();

        Object depsObj = state.get("pre_builds_list");

        if (depsObj instanceof List<?>) {
            List<Map<String, Object>> deps = (List<Map<String, Object>>) depsObj;

            log.info("Found {} dependencies", deps.size());

            for (Map<String, Object> dep : deps) {
                Map<String, Object> depMap = new HashMap<>();
                depMap.put("repoName", dep.get("repoName"));
                depMap.put("repositoryUrl", dep.get("repositoryUrl"));
                depMap.put("branch", state.getOrDefault("dependency_branch", "main"));
                preBuilds.add(depMap);
            }
        }

        // -------- PAYLOAD --------
        Map<String, Object> payload = new HashMap<>();

        payload.put("repositoryUrl", repoUrl);
        payload.put("repoName", repoName);
        payload.put("githubToken", githubToken);
        payload.put("branch", branchName);
        payload.put("commitId", commitId);
        payload.put("serverName", serverName);
        payload.put("targetUrl", state.get("targetUrl"));
        payload.put("keycloakRealm", state.get("keycloakRealm"));
        payload.put("keycloakClientId", state.get("keycloakClientId"));
        payload.put("existingBuildId", existingBuildId);
        payload.put("requestedBy", requestedBy);
        payload.put("preBuilds", preBuilds);

        try {

            log.info("Calling Java build API");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request =
                    new HttpEntity<>(payload, headers);

            ResponseEntity<Map> response =
                    restTemplate.postForEntity(javaApiUrl, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                Map body = response.getBody();
                logs.add("Build " + body.get("buildId") + " initiated successfully.");
            } else {
                logs.add("Failed to initiate build. Status: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("Deployment failed: {}", e.getMessage(), e);
            logs.add("Error: Could not connect to Java Agent (" + e.getMessage() + ")");
        }

        return logs;
    }

    // ---------------- STATUS ----------------
    public Map<String, Object> getDeploymentStatus(String buildId) {

        log.info("Fetching deployment status for {}", buildId);

        Map<String, Object> result = new HashMap<>();

        try {

            String url = javaStatusUrl + "/" + buildId;

            ResponseEntity<Map> response =
                    restTemplate.getForEntity(url, Map.class);

            Map body = response.getBody();

            String status = String.valueOf(body.getOrDefault("status", "")).toUpperCase();

            result.put("build_id", buildId);
            result.put("status_message", "Status: " + status);
            result.put("is_complete", status.equals("SUCCESS") || status.equals("FAILED"));

        } catch (Exception e) {
            log.warn("Status check failed: {}", e.getMessage());
            result.put("build_id", buildId);
            result.put("status_message", "Offline");
            result.put("is_complete", false);
        }

        return result;
    }

    // ---------------- HELPER ----------------
    private String shortSha(String sha) {
        return (sha != null && sha.length() > 7) ? sha.substring(0, 7) : sha;
    }
}