package com.sim.spriced.pahal.dto;

import com.sim.spriced.pahal.model.Activity;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class ActivityResponse {

    private String id;
    private String workspaceId;
    private String workspaceName;
    private String type;
    private String message;
    private LocalDateTime timestamp;

    public static ActivityResponse fromEntity(Activity activity) {
        return ActivityResponse.builder()
                .id(activity.getId())
                .workspaceId(activity.getWorkspaceId())
                .workspaceName(activity.getWorkspaceName())
                .type(activity.getType())
                .message(activity.getMessage())
                .timestamp(activity.getTimestamp())
                .build();
    }
}