package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.dto.ActivityResponse;
import com.sim.spriced.pahal.services.ActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Configure appropriately for production
public class ActivityController {

    private final ActivityService activityService;

    /**
     * GET /api/activity
     * Get recent activities
     * Optional query params: workspaceId, limit
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getRecentActivities(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(defaultValue = "10") int limit) {
        List<ActivityResponse> activities = activityService.getRecentActivities(workspaceId, limit);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", activities,
                "count", activities.size()
        ));
    }

    /**
     * GET /api/activity/workspace/{workspaceId}
     * Get activities for specific workspace
     */
    @GetMapping("/workspace/{workspaceId}")
    public ResponseEntity<Map<String, Object>> getWorkspaceActivities(
            @PathVariable String workspaceId) {
        List<ActivityResponse> activities = activityService.getWorkspaceActivities(workspaceId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", activities,
                "count", activities.size()
        ));
    }
}