package com.sim.spriced.pahal.dto;

import com.sim.spriced.pahal.model.Workspace;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class WorkspaceResponse {

    private String id;
    private String name;
    private Integer progress;
    private String phase;
    private Integer fileCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WorkspaceResponse fromEntity(Workspace workspace) {
        return WorkspaceResponse.builder()
                .id(workspace.getId())
                .name(workspace.getName())
                .progress(workspace.getProgress())
                .phase(workspace.getPhase())
                .fileCount(workspace.getFiles() != null ? workspace.getFiles().size() : 0)
                .createdAt(workspace.getCreatedAt())
                .updatedAt(workspace.getUpdatedAt())
                .build();
    }
}