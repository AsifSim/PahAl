package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.dto.ProgressUpdateRequest;
import com.sim.spriced.pahal.dto.WorkspaceRequest;
import com.sim.spriced.pahal.dto.WorkspaceResponse;
import com.sim.spriced.pahal.model.WorkspaceFile;
import com.sim.spriced.pahal.services.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Configure appropriately for production
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    /**
     * GET /api/workspaces
     * Get all workspaces
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllWorkspaces() {
        List<WorkspaceResponse> workspaces = workspaceService.getAllWorkspaces();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", workspaces,
                "count", workspaces.size()
        ));
    }

    /**
     * GET /api/workspaces/{id}
     * Get workspace by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getWorkspace(@PathVariable String id) {
        WorkspaceResponse workspace = workspaceService.getWorkspaceResponse(id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", workspace
        ));
    }

    /**
     * POST /api/workspaces
     * Create a new workspace
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createWorkspace(
            @Valid @RequestBody WorkspaceRequest request) {
        WorkspaceResponse workspace = workspaceService.createWorkspace(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "success", true,
                "message", "Workspace created successfully",
                "data", workspace
        ));
    }

    /**
     * PATCH /api/workspaces/{id}/progress
     * Update workspace progress
     */
    @PatchMapping("/{id}/progress")
    public ResponseEntity<Map<String, Object>> updateProgress(
            @PathVariable String id,
            @Valid @RequestBody ProgressUpdateRequest request) {
        WorkspaceResponse workspace = workspaceService.updateProgress(id, request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Progress updated successfully",
                "data", workspace
        ));
    }

    /**
     * DELETE /api/workspaces/{id}
     * Delete workspace
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteWorkspace(@PathVariable String id) {
        workspaceService.deleteWorkspace(id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Workspace deleted successfully"
        ));
    }

    /**
     * POST /api/workspaces/{id}/upload
     * Upload TBRD file to workspace
     */
    @PostMapping(value = "/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(
            @PathVariable String id,
            @RequestParam("tbrd") MultipartFile file) throws IOException {
        WorkspaceResponse workspace = workspaceService.uploadFile(id, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "success", true,
                "message", "File uploaded successfully",
                "data", workspace
        ));
    }

    /**
     * GET /api/workspaces/{id}/files
     * Get workspace files
     */
    @GetMapping("/{id}/files")
    public ResponseEntity<Map<String, Object>> getWorkspaceFiles(@PathVariable String id) {
        List<WorkspaceFile> files = workspaceService.getWorkspaceFiles(id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", files,
                "count", files.size()
        ));
    }

    /**
     * GET /api/workspaces/stats/count
     * Get total workspace count
     */
    @GetMapping("/stats/count")
    public ResponseEntity<Map<String, Object>> getWorkspaceCount() {
        long count = workspaceService.getWorkspaceCount();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "count", count
        ));
    }
}