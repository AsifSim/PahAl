package com.sim.spriced.pahal.sangam.services;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;

@Service
public class GitHubSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubSyncService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    // -------- ENV --------
    @Value("${GITHUB_TOKEN}")
    private String githubToken;

    @Value("${ORG_NAME}")
    private String orgName;

    @Value("${ARTIFACT_MAP_FILE}")
    private String artifactMapFile;

    // ---------------- HEADERS ----------------
    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "token " + githubToken);
        headers.set("Accept", "application/vnd.github.v3+json");
        return headers;
    }

    // ---------------- FETCH COMMITS ----------------
    public List<Map<String, Object>> fetchRecentCommits(String repo, String branch) {

        log.info("Fetching commits for {} branch {}", repo, branch);

        List<Map<String, Object>> result = new ArrayList<>();

        try {
            String url = String.format(
                    "https://api.github.com/repos/%s/%s/commits?sha=%s&per_page=30",
                    orgName, repo, branch
            );

            ResponseEntity<List> response =
                    restTemplate.exchange(url, HttpMethod.GET,
                            new HttpEntity<>(getHeaders()), List.class);

            if (response.getStatusCode().is2xxSuccessful()) {

                List<Map<String, Object>> commits = response.getBody();

                for (Map<String, Object> c : commits) {

                    String sha = (String) c.get("sha");

                    Map commit = (Map) c.get("commit");
                    String msg = ((String) commit.get("message")).split("\n")[0];

                    Map author = (Map) commit.get("author");
                    String name = (String) author.get("name");

                    result.add(Map.of(
                            "label", shortSha(sha) + " - " + msg + " (" + name + ")",
                            "value", sha
                    ));
                }
            }

        } catch (Exception e) {
            log.error("Error fetching commits", e);
        }

        return result;
    }

    // ---------------- FETCH TAGS ----------------
    public List<Map<String, Object>> fetchRepoTags(String repo) {

        log.info("Fetching tags for {}", repo);

        List<Map<String, Object>> result = new ArrayList<>();

        try {
            String url = String.format(
                    "https://api.github.com/repos/%s/%s/tags?per_page=100",
                    orgName, repo
            );

            ResponseEntity<List> response =
                    restTemplate.exchange(url, HttpMethod.GET,
                            new HttpEntity<>(getHeaders()), List.class);

            if (response.getStatusCode().is2xxSuccessful()) {

                List<Map<String, Object>> tags = response.getBody();

                for (Map<String, Object> t : tags) {
                    result.add(Map.of("label", t.get("name"), "value", t.get("name")));
                }
            }

        } catch (Exception e) {
            log.error("Error fetching tags", e);
        }

        return result;
    }

    // ---------------- FETCH REPOS ----------------
    public List<Map<String, Object>> fetchRepos() {

        log.info("Fetching repos");

        List<Map<String, Object>> result = new ArrayList<>();

        try {
            String url = "https://api.github.com/user/repos?per_page=100&type=all";

            ResponseEntity<List> response =
                    restTemplate.exchange(url, HttpMethod.GET,
                            new HttpEntity<>(getHeaders()), List.class);

            if (response.getStatusCode().is2xxSuccessful()) {

                for (Map<String, Object> repo : (List<Map<String, Object>>) response.getBody()) {

                    String name = (String) repo.get("name");

                    result.add(Map.of(
                            "name", name,
                            "id", name,
                            "url", "https://github.com/" + orgName + "/" + name + ".git"
                    ));
                }
            }

        } catch (Exception e) {
            log.error("Error fetching repos", e);
        }

        return result;
    }

    // ---------------- FETCH BRANCHES ----------------
    public List<Map<String, Object>> fetchBranches(String repo) {

        log.info("Fetching branches for {}", repo);

        List<String> branches = new ArrayList<>();
        int page = 1;

        try {

            while (true) {

                String url = String.format(
                        "https://api.github.com/repos/%s/%s/branches?per_page=100&page=%d",
                        orgName, repo, page
                );

                ResponseEntity<List> response =
                        restTemplate.exchange(url, HttpMethod.GET,
                                new HttpEntity<>(getHeaders()), List.class);

                if (!response.getStatusCode().is2xxSuccessful()) break;

                List<Map<String, Object>> data = response.getBody();

                if (data == null || data.isEmpty()) break;

                for (Map<String, Object> b : data) {
                    branches.add((String) b.get("name"));
                }

                if (data.size() < 100) break;

                page++;
            }

        } catch (Exception e) {
            log.error("Error fetching branches", e);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String b : branches) {
            result.add(Map.of("label", b, "value", b));
        }

        return result;
    }

    // ---------------- DEPENDENCY SCAN ----------------
    public List<Map<String, Object>> detectDependencies(String repo, String ref) {

        log.info("Scanning dependencies for {} {}", repo, ref);

        Map<String, Map<String, Object>> artifactMap = loadJson(artifactMapFile);
        if (artifactMap.isEmpty()) return null;

        try {

            String url = String.format(
                    "https://api.github.com/repos/%s/%s/contents/pom.xml?ref=%s",
                    orgName, repo, ref
            );

            ResponseEntity<Map> response =
                    restTemplate.exchange(url, HttpMethod.GET,
                            new HttpEntity<>(getHeaders()), Map.class);

            if (!response.getStatusCode().is2xxSuccessful()) return null;

            String encoded = (String) response.getBody().get("content");

            String xml = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(xml.getBytes()));

            NodeList deps = doc.getElementsByTagName("dependency");

            List<Map<String, Object>> result = new ArrayList<>();

            for (int i = 0; i < deps.getLength(); i++) {

                Element dep = (Element) deps.item(i);

                String groupId = getTag(dep, "groupId");
                String artifactId = getTag(dep, "artifactId");

                if (groupId != null && groupId.startsWith("com.sim")) {

                    if (artifactMap.containsKey(artifactId)) {

                        Map<String, Object> mapping = artifactMap.get(artifactId);

                        result.add(Map.of(
                                "repoName", mapping.get("path"),
                                "repositoryUrl", mapping.get("url"),
                                "branch", "main"
                        ));
                    }
                }
            }

            return result.isEmpty() ? null : result;

        } catch (Exception e) {
            log.error("Dependency scan error", e);
        }

        return null;
    }

    // ---------------- BRANCH FROM URL ----------------
    public List<Map<String, Object>> fetchBranchesFromUrl(String url) {

        String repo = url.substring(url.lastIndexOf("/") + 1).replace(".git", "");
        return fetchBranches(repo);
    }

    // ---------------- JSON LOAD ----------------
    private Map<String, Map<String, Object>> loadJson(String path) {

        try {
            return mapper.readValue(new File(path),
                    new TypeReference<Map<String, Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Error loading JSON {}", path);
            return new HashMap<>();
        }
    }

    // ---------------- HELPER ----------------
    private String getTag(Element e, String tag) {
        NodeList list = e.getElementsByTagName(tag);
        return list.getLength() > 0 ? list.item(0).getTextContent() : null;
    }

    private String shortSha(String sha) {
        return sha != null && sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}