package com.sim.spriced.pahal.sangam.models;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "deployment_transactions")
@Data
public class DeploymentTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String buildId;
    private String repoName;
    private String branchName;
    private String serverName;
    private String buildStatus;
    private String requestedBy;
    private String deploymentStatus;
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }
}