package com.sim.spriced.pahal.sangam.models;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BuildRequest {
    @NotBlank(message = "GitHub repository URL is required")
    private String repositoryUrl;
    
    @NotBlank(message = "GitHub token is required")
    private String githubToken;
    
    @NotBlank(message = "Branch name is required")
    private String branch;
    
    @NotBlank(message = "Server name is required")
    private String serverName;
    
    private String projectType;
    private String existingBuildId;
    private String repoName;
    private String requestedBy;
    private String commitId;
    private List<PreBuildRequest> preBuilds;

    // --- ADD THESE THREE FIELDS ---
    private String targetUrl;
    private String keycloakRealm;
    private String keycloakClientId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreBuildRequest {
        private String repoName;
        private String repositoryUrl;
        private String branch;
    }
}