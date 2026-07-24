package com.sim.spriced.pahal.sangam.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SangamChatService {

    private static final Logger log = LoggerFactory.getLogger(SangamChatService.class);

    private final Map<String, Map<String, Object>> chatSessions = new ConcurrentHashMap<>();

    // ---------------- ENUM ----------------
    private enum Step {
        AWAITING_REPO,
        AWAITING_METHOD,
        FLOW_BRANCH,
        FLOW_TAG,
        FLOW_COMMIT,
        AWAITING_DEP_BRANCH,
        AWAITING_SERVER,
        AWAITING_CONFIRMATION,
        DONE
    }

    public boolean sessionExists(String sessionId) {
        log.info("Checking session existence for {}", sessionId);
        return chatSessions.containsKey(sessionId);
    }

    // ---------------- RESPONSE BUILDER ----------------
    private Map<String, Object> createResponse(String message, List<Map<String, Object>> options, boolean forceDropdown) {
        log.debug("Creating response: {}", message);
        Map<String, Object> res = new HashMap<>();
        String uiType = (forceDropdown || options.size() > 6) ? "dropdown" : "buttons";
        res.put("message", message);
        res.put("options", options);
        res.put("ui_type", uiType);
        return res;
    }

    // ---------------- MAIN ----------------
    public Map<String, Object> processChatMessage(String sessionId, String userMessage, String username) {

        log.info("Processing message for session {}", sessionId);

        Map<String, Object> state = new HashMap<>(chatSessions.getOrDefault(sessionId, new HashMap<>()));

        if (username != null) {
            state.put("username", username);
            log.debug("Username set: {}", username);
        }

        if (!state.containsKey("step")) {
            return initSession(state, sessionId);
        }

        Step step = Step.valueOf((String) state.get("step"));
        String input = Optional.ofNullable(userMessage).orElse("").trim();

        switch (step) {

            case AWAITING_REPO:
                return handleRepoSelection(state, sessionId, input);

            case AWAITING_METHOD:
                return handleMethodSelection(state, sessionId, input);

            case FLOW_BRANCH:
                return handleBranchFlow(state, sessionId, input);

            case FLOW_TAG:
                return handleTagFlow(state, sessionId, input);

            case FLOW_COMMIT:
                return handleCommitFlow(state, sessionId, input);

            case AWAITING_DEP_BRANCH:
                return handleDependencyBranch(state, sessionId, input);

            case AWAITING_SERVER:
                return handleServerSelection(state, sessionId, input);

            case AWAITING_CONFIRMATION:
                return handleConfirmation(state, sessionId, input);

            default:
                log.warn("Unknown state, resetting session");
                chatSessions.remove(sessionId);
                return createResponse("Restarting...", List.of(), false);
        }
    }

    // ---------------- STEP HANDLERS ----------------

    private Map<String, Object> initSession(Map<String, Object> state, String sessionId) {
        log.info("Initializing new session {}", sessionId);

        state.put("step", Step.AWAITING_REPO.name());

        List<Map<String, Object>> repos = fetchRepos();
        state.put("all_repos_data", repos);

        chatSessions.put(sessionId, state);

        return createResponse("Select Repository:", repos, false);
    }

    private Map<String, Object> handleRepoSelection(Map<String, Object> state, String sessionId, String input) {

        log.info("Handling repo selection");

        Map<String, Object> repo = findRepo(state, input);

        if (repo == null) {
            log.warn("Invalid repo selected");
            return createResponse("Invalid repo", List.of(), false);
        }

        state.put("repo_name", repo.get("name"));
        state.put("repo_url", repo.get("url"));
        state.put("step", Step.AWAITING_METHOD.name());

        chatSessions.put(sessionId, state);

        return createResponse("Select method:",
                List.of(option("Deploy Branch"), option("Deploy Tag"), option("Deploy Commit")),
                false);
    }

    private Map<String, Object> handleMethodSelection(Map<String, Object> state, String sessionId, String input) {

        log.info("Handling method selection");

        String upper = input.toUpperCase();

        if (upper.contains("BRANCH")) {
            state.put("step", Step.FLOW_BRANCH.name());
            chatSessions.put(sessionId, state);
            return createResponse("Select branch:", fetchBranches((String) state.get("repo_name")), false);
        }

        if (upper.contains("TAG")) {
            state.put("step", Step.FLOW_TAG.name());
            chatSessions.put(sessionId, state);
            return createResponse("Select tag:", fetchTags((String) state.get("repo_name")), true);
        }

        if (upper.contains("COMMIT")) {
            state.put("step", Step.FLOW_COMMIT.name());
            chatSessions.put(sessionId, state);
            return createResponse("Select commit:", fetchCommits((String) state.get("repo_name")), true);
        }

        return createResponse("Invalid selection", List.of(), false);
    }

    private Map<String, Object> handleBranchFlow(Map<String, Object> state, String sessionId, String input) {
        log.info("Handling branch flow");

        state.put("branch_name", input);
        return finalizeRef(state, sessionId, input);
    }

    private Map<String, Object> handleTagFlow(Map<String, Object> state, String sessionId, String input) {
        log.info("Handling tag flow");

        state.put("branch_name", input);
        return finalizeRef(state, sessionId, input);
    }

    private Map<String, Object> handleCommitFlow(Map<String, Object> state, String sessionId, String input) {
        log.info("Handling commit flow");

        state.put("commit_id", input);
        return finalizeRef(state, sessionId, input);
    }

    private Map<String, Object> handleDependencyBranch(Map<String, Object> state, String sessionId, String input) {

        log.info("Handling dependency branch");

        state.put("dependency_branch", input);

        return handleServerSelectionLogic(state, sessionId);
    }

    private Map<String, Object> handleServerSelection(Map<String, Object> state, String sessionId, String input) {

        log.info("Handling server selection");

        state.put("server_name", input);
        state.put("step", Step.AWAITING_CONFIRMATION.name());

        chatSessions.put(sessionId, state);

        return createResponse("Confirm deployment?", List.of(option("Yes"), option("No")), false);
    }

    private Map<String, Object> handleConfirmation(Map<String, Object> state, String sessionId, String input) {

        log.info("Handling confirmation");

        if (input.toLowerCase().contains("yes")) {

            List<String> logs = executeDeployment(state);
            chatSessions.remove(sessionId);

            return createResponse(String.join("\n", logs), List.of(), false);
        }

        chatSessions.remove(sessionId);
        return createResponse("Cancelled", List.of(), false);
    }

    // ---------------- CORE LOGIC ----------------

    private Map<String, Object> finalizeRef(Map<String, Object> state, String sessionId, String ref) {

        log.info("Finalizing ref {}", ref);

        List<Map<String, Object>> deps = detectDependencies((String) state.get("repo_name"), ref);

        if (deps != null && !deps.isEmpty()) {

            state.put("pre_builds_list", deps);
            state.put("step", Step.AWAITING_DEP_BRANCH.name());

            chatSessions.put(sessionId, state);

            return createResponse("Dependencies found. Select branch:",
                    fetchBranchesFromUrl((String) deps.get(0).get("repositoryUrl")), false);
        }

        return handleServerSelectionLogic(state, sessionId);
    }

    private Map<String, Object> handleServerSelectionLogic(Map<String, Object> state, String sessionId) {

        log.info("Checking previous deployments");

        Map<String, Object> previous = checkPreviousDeployment(
                (String) state.get("repo_name"),
                (String) state.get("branch_name")
        );

        if (previous != null) {

            state.put("prev_deploy", previous);
            state.put("step", Step.AWAITING_CONFIRMATION.name());

            chatSessions.put(sessionId, state);

            return createResponse("Duplicate build found. Redeploy?",
                    List.of(option("Yes"), option("No")), false);
        }

        state.put("step", Step.AWAITING_SERVER.name());
        chatSessions.put(sessionId, state);

        return createResponse("Select server:", loadServers(), true);
    }

    // ---------------- HELPERS ----------------

    private Map<String, Object> option(String val) {
        return Map.of("label", val, "value", val);
    }

    private Map<String, Object> findRepo(Map<String, Object> state, String input) {

        Object obj = state.get("all_repos_data");

        if (!(obj instanceof List)) {
            log.error("Invalid repo list");
            return null;
        }

        List<Map<String, Object>> repos = (List<Map<String, Object>>) obj;

        return repos.stream()
                .filter(r -> input.equals(r.get("name")))
                .findFirst()
                .orElse(null);
    }

    // ---------------- STUBS ----------------

    private List<Map<String, Object>> fetchRepos() { return new ArrayList<>(); }
    private List<Map<String, Object>> fetchBranches(String repo) { return new ArrayList<>(); }
    private List<Map<String, Object>> fetchTags(String repo) { return new ArrayList<>(); }
    private List<Map<String, Object>> fetchCommits(String repo) { return new ArrayList<>(); }
    private List<Map<String, Object>> loadServers() { return new ArrayList<>(); }
    private List<Map<String, Object>> detectDependencies(String repo, String ref) { return new ArrayList<>(); }
    private List<Map<String, Object>> fetchBranchesFromUrl(String url) { return new ArrayList<>(); }
    private Map<String, Object> checkPreviousDeployment(String repo, String branch) { return null; }
    private List<String> executeDeployment(Map<String, Object> state) { return List.of("Deployment started"); }
}