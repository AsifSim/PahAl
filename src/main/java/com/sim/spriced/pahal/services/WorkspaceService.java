package com.sim.spriced.pahal.services;

import com.sim.spriced.pahal.dto.ProgressUpdateRequest;
import com.sim.spriced.pahal.dto.WorkspaceRequest;
import com.sim.spriced.pahal.dto.WorkspaceResponse;
import com.sim.spriced.pahal.exception.DuplicateWorkspaceException;
import com.sim.spriced.pahal.exception.WorkspaceNotFoundException;
import com.sim.spriced.pahal.model.Workspace;
import com.sim.spriced.pahal.model.WorkspaceFile;
import com.sim.spriced.pahal.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final ActivityService activityService;

    @Value("${app.upload.dir:${user.home}/pahal-uploads}")
    private String uploadDir;

    /**
     * Get all workspaces
     */
    public List<WorkspaceResponse> getAllWorkspaces() {
        return workspaceRepository.findAllOrderByUpdatedAt()
                .stream()
                .map(WorkspaceResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Get workspace by ID
     */
    public Workspace getWorkspaceById(String id) {
        return workspaceRepository.findByIdWithFiles(id)
                .orElseThrow(() -> new WorkspaceNotFoundException(id));
    }

    /**
     * Get workspace response by ID
     */
    public WorkspaceResponse getWorkspaceResponse(String id) {
        return WorkspaceResponse.fromEntity(getWorkspaceById(id));
    }

    /**
     * Create a new workspace
     */
    public WorkspaceResponse createWorkspace(WorkspaceRequest request) {
        // Check for duplicate name
        if (workspaceRepository.existsByNameIgnoreCase(request.getName().trim())) {
            throw new DuplicateWorkspaceException(request.getName().trim());
        }

        Workspace workspace = Workspace.builder()
                .name(request.getName().trim())
                .progress(0)
                .phase("upload")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        workspace = workspaceRepository.save(workspace);

        // Log activity
        activityService.logActivity(
                workspace.getId(),
                workspace.getName(),
                "workspace_created",
                String.format("Workspace \"%s\" created", workspace.getName())
        );

        log.info("Workspace created: {} ({})", workspace.getName(), workspace.getId());
        return WorkspaceResponse.fromEntity(workspace);
    }

    /**
     * Update workspace progress
     */
    public WorkspaceResponse updateProgress(String id, ProgressUpdateRequest request) {
        Workspace workspace = workspaceRepository.findById(id)
                .orElseThrow(() -> new WorkspaceNotFoundException(id));

        if (request.getProgress() != null) {
            workspace.setProgress(request.getProgress());
        }

        if (request.getPhase() != null && isValidPhase(request.getPhase())) {
            workspace.setPhase(request.getPhase().toLowerCase());
        }

        workspace.setUpdatedAt(LocalDateTime.now());
        workspace = workspaceRepository.save(workspace);

        // Log activity if phase changed
        if (request.getPhase() != null) {
            activityService.logActivity(
                    workspace.getId(),
                    workspace.getName(),
                    "phase_changed",
                    String.format("Phase changed to \"%s\" - Progress: %d%%",
                            workspace.getPhase(), workspace.getProgress())
            );
        }

        log.info("Workspace progress updated: {} -> {}% (Phase: {})",
                workspace.getName(), workspace.getProgress(), workspace.getPhase());
        return WorkspaceResponse.fromEntity(workspace);
    }

    /**
     * Delete workspace
     */
    public void deleteWorkspace(String id) {
        Workspace workspace = workspaceRepository.findById(id)
                .orElseThrow(() -> new WorkspaceNotFoundException(id));

        // Check if it's the last workspace
        if (workspaceRepository.count() <= 1) {
            throw new IllegalStateException("Cannot delete the last workspace");
        }

        String workspaceName = workspace.getName();
        workspaceRepository.delete(workspace);

        // Log activity for deletion (use a generic workspace ID since it's deleted)
        activityService.logActivity(
                "deleted",
                workspaceName,
                "workspace_deleted",
                String.format("Workspace \"%s\" deleted", workspaceName)
        );

        log.info("Workspace deleted: {} ({})", workspaceName, id);
    }

    /**
     * Upload file to workspace
     */
    public WorkspaceResponse uploadFile(String workspaceId, MultipartFile file) throws IOException {
        Workspace workspace = workspaceRepository.findByIdWithFiles(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));

        // Validate file type
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !isValidFileType(originalFilename)) {
            throw new IllegalArgumentException(
                    "Invalid file type. Allowed: PDF, DOCX, MD, TXT");
        }

        // Validate file size (50MB max)
        if (file.getSize() > 50 * 1024 * 1024) {
            throw new IllegalArgumentException("File too large. Maximum size is 50MB.");
        }

        // Create upload directory
        Path uploadPath = Paths.get(uploadDir, workspaceId);
        Files.createDirectories(uploadPath);

        // Generate unique filename
        String storedName = UUID.randomUUID().toString() + "_" + originalFilename;
        Path filePath = uploadPath.resolve(storedName);

        // Save file
        file.transferTo(filePath.toFile());

        // Create file record
        WorkspaceFile workspaceFile = WorkspaceFile.builder()
                .originalName(originalFilename)
                .storedName(storedName)
                .filePath(filePath.toString())
                .size(file.getSize())
                .mimeType(file.getContentType())
                .uploadedAt(LocalDateTime.now())
                .workspace(workspace)
                .build();

        workspace.getFiles().add(workspaceFile);

        // Update progress
        if (workspace.getProgress() < 10) {
            workspace.setProgress(Math.min(workspace.getProgress() + 5, 10));
        }
        workspace.setUpdatedAt(LocalDateTime.now());
        workspaceRepository.save(workspace);

        // Log activity
        activityService.logActivity(
                workspace.getId(),
                workspace.getName(),
                "file_upload",
                String.format("TBRD document \"%s\" uploaded (%.1f KB)",
                        originalFilename, file.getSize() / 1024.0)
        );

        log.info("File uploaded to workspace {}: {}", workspace.getName(), originalFilename);
        return WorkspaceResponse.fromEntity(workspace);
    }

    /**
     * Get workspace files
     */
    public List<WorkspaceFile> getWorkspaceFiles(String workspaceId) {
        Workspace workspace = workspaceRepository.findByIdWithFiles(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
        return workspace.getFiles();
    }

    /**
     * Get total workspace count
     */
    public long getWorkspaceCount() {
        return workspaceRepository.count();
    }

    /**
     * Validate file type
     */
    private boolean isValidFileType(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.')).toLowerCase();
        return ext.equals(".pdf") || ext.equals(".docx") || ext.equals(".md") || ext.equals(".txt");
    }

    /**
     * Validate phase name
     */
    private boolean isValidPhase(String phase) {
        return List.of("upload", "maps", "code", "deploy", "test").contains(phase.toLowerCase());
    }
}