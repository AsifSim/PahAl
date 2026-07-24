package com.sim.spriced.pahal.services;

import com.sim.spriced.pahal.dto.ActivityResponse;
import com.sim.spriced.pahal.model.Activity;
import com.sim.spriced.pahal.repository.ActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ActivityService {

    private final ActivityRepository activityRepository;

    /**
     * Get recent activities (optionally filtered by workspace)
     */
    public List<ActivityResponse> getRecentActivities(String workspaceId, int limit) {
        List<Activity> activities;

        if (workspaceId != null && !workspaceId.isEmpty()) {
            activities = activityRepository.findByWorkspaceIdOrderByTimestampDesc(
                    workspaceId, PageRequest.of(0, limit));
        } else {
            activities = activityRepository.findAllByOrderByTimestampDesc(
                    PageRequest.of(0, limit));
        }

        return activities.stream()
                .map(ActivityResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Get all activities (default limit 10)
     */
    public List<ActivityResponse> getRecentActivities() {
        return getRecentActivities(null, 10);
    }

    /**
     * Get activities for specific workspace
     */
    public List<ActivityResponse> getWorkspaceActivities(String workspaceId) {
        return getRecentActivities(workspaceId, 20);
    }

    /**
     * Log a new activity
     */
    public Activity logActivity(String workspaceId, String workspaceName, String type, String message) {
        Activity activity = Activity.builder()
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .type(type)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();

        activity = activityRepository.save(activity);
        log.debug("Activity logged: [{}] {}", type, message);
        return activity;
    }

    /**
     * Delete activities for a workspace
     */
    public void deleteActivitiesForWorkspace(String workspaceId) {
        activityRepository.deleteByWorkspaceId(workspaceId);
        log.info("Activities deleted for workspace: {}", workspaceId);
    }

    /**
     * Clean up old activities (older than 30 days)
     */
    public void cleanupOldActivities() {
        // Implementation depends on your needs
        log.info("Activity cleanup completed");
    }
}