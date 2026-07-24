package com.sim.spriced.pahal.sangam.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.*;
//import com.maf.agents.build_agent.model.BuildRequest;
//import com.maf.agents.build_agent.model.BuildRequest.PreBuildRequest;
//import com.maf.agents.build_agent.model.BuildResponse;
//import com.maf.agents.build_agent.model.DeploymentConfig;
import com.sim.spriced.pahal.sangam.models.BuildRequest;
import com.sim.spriced.pahal.sangam.models.BuildRequest.PreBuildRequest;
import com.sim.spriced.pahal.sangam.models.BuildResponse;
import com.sim.spriced.pahal.sangam.models.DeploymentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

@Service
public class BuildService {

    private static final Logger logger = LoggerFactory.getLogger(BuildService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String DOCKER_MAP_PATH = "configs/global/docker_deploy_map.json";

    @Autowired
    private AngularBuildService angularBuildService;
    @Autowired
    private LogbackStandardizer logbackStanderdizer;
    @Autowired
    private ProjectDetector projectDetector;
    @Autowired
    private DeploymentAuditService auditService;
    @Autowired
    private EnvironmentConfigurator envConfigurator;
    @Autowired
    private DependencyAnalyzer dependencyAnalyzer;
    @Autowired
    private VersioningService versioningService;

    @Value("${build.workspace:tmp/build-new}")
    private String workspaceConfig;

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, BuildResponse> buildStatuses = new ConcurrentHashMap<>();

    // --- JSCH INTERNAL DEBUGGER ---
    static {
        JSch.setLogger(new com.jcraft.jsch.Logger() {
            public boolean isEnabled(int level) { return true; }
            public void log(int level, String message) {
                System.err.println("[JSCH-INTERNAL-DEBUG] " + message);
            }
        });
    }

    public BuildResponse initiateBuild(BuildRequest request) {
        String buildId = (request.getExistingBuildId() != null && !request.getExistingBuildId().isEmpty())
                ? request.getExistingBuildId() : UUID.randomUUID().toString();

        logger.info("=========================================================");
        logger.info("[BUILD-START] Build ID: {}", buildId);
        logger.info("[BUILD-START] Repository: {}", request.getRepositoryUrl());
        logger.info("[BUILD-START] Target Server: {}", request.getServerName());
        logger.info("=========================================================");

        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path fullWorkspacePath = projectRoot.resolve(workspaceConfig).resolve(buildId).toAbsolutePath();
        String workspacePath = fullWorkspacePath.toString();

        BuildResponse response = buildStatuses.getOrDefault(buildId, new BuildResponse());
        response.setBuildId(buildId);
        response.setRepositoryUrl(request.getRepositoryUrl());
        response.setBranch(request.getBranch());
        response.setStatus("PENDING");
        
        buildStatuses.put(buildId, response);
        CompletableFuture.runAsync(() -> executeBuildAndDeploy(buildId, request, workspacePath, response), executorService);
        return response;
    }

private void executeBuildAndDeploy(String buildId, BuildRequest request, String workspacePathStr, BuildResponse response) {
        String finalBuildStatus = "PENDING";
        String finalDeploymentStatus = "PENDING";
        String repoNameKey = (request.getRepoName() != null) ? request.getRepoName() : "unknown";

        try {
            logger.info("[STEP 1] Starting Workspace Cleanup for: {}", workspacePathStr);
            cleanupWorkspace(workspacePathStr, response);
            response.setStatus("IN_PROGRESS");
            response.setStartTime(LocalDateTime.now());

            addLog(response, "Clearing local Maven failure cache (.lastUpdated files) to force dependency resolution...");
            clearMavenFailureCache(response);

            addLog(response, "Checking for required pre-build dependencies...");
            if (request.getPreBuilds() != null && !request.getPreBuilds().isEmpty()) {
                response.setCurrentStage("Processing Grouped Dependencies...");
                addLog(response, "PHASE: Grouping " + request.getPreBuilds().size() + " dependencies by Repository URL...");

                Map<String, List<PreBuildRequest>> groupedByRepo = new HashMap<>();
                for (PreBuildRequest preReq : request.getPreBuilds()) {
                    groupedByRepo.computeIfAbsent(preReq.getRepositoryUrl(), k -> new ArrayList<>()).add(preReq);
                }

                for (Map.Entry<String, List<PreBuildRequest>> entry : groupedByRepo.entrySet()) {
                    String repoUrl = entry.getKey();
                    List<PreBuildRequest> modules = entry.getValue();
                    String branch = modules.get(0).getBranch();
                    
                    String tempFolder = "dep_repo_" + UUID.randomUUID().toString().substring(0, 8);
                    Path repoWorkDir = Paths.get(workspacePathStr).resolve("deps").resolve(tempFolder);
                    Files.createDirectories(repoWorkDir);

                    addLog(response, "--------------------------------------------------");
                    addLog(response, "[DEP-GROUP] Processing Monorepo: " + repoUrl);
                    String authUrl = repoUrl.replace("https://", "https://" + request.getGithubToken() + "@");
                    executeCommand(new String[]{"git", "clone", "--branch", branch, authUrl, "."}, repoWorkDir.toString(), response);

                    addLog(response, "[DEP-PATCH] Running recursive Windows OS patch on dependency folder: " + repoWorkDir.toString());
                    patchAllPomsRecursively(repoWorkDir, response);

                    Set<String> buildPaths = new LinkedHashSet<>();
                    if (modules.stream().anyMatch(m -> m.getRepoName().equals("."))) buildPaths.add(".");
                    for (PreBuildRequest m : modules) {
                        if (!m.getRepoName().equals(".")) buildPaths.add(m.getRepoName());
                    }

                    for (String subFolder : buildPaths) {
                        Path buildDir = repoWorkDir.resolve(subFolder).normalize();
                        addLog(response, "[DEP-BUILD] Building Module Path: " + subFolder);

                        if (Files.exists(buildDir.resolve("pom.xml"))) {
                            buildJavaProject(buildDir.toString(), response);
                            addLog(response, "[DEP-SUCCESS] Built: " + subFolder);
                        } else {
                            addLog(response, "[DEP-SKIP] No pom.xml in " + subFolder);
                        }
                    }
                    addLog(response, "--------------------------------------------------");
                }
            } else {
                addLog(response, "INFO: No pre-build dependencies found.");
            }

            Path repoDir = Paths.get(workspacePathStr).resolve("repo");
            Files.createDirectories(repoDir);
            cloneRepository(request.getRepositoryUrl(), request.getGithubToken(), request.getBranch(), request.getCommitId(), repoDir.toString(), response);
            response.setCloneStatus("SUCCESS");
            
            addLog(response, "Scanning Main Repository for pom.xml files to apply Windows OS patches...");
            patchAllPomsRecursively(repoDir, response);

            addLog(response, "Performing Dynamic Dependency Analysis for: " + repoNameKey);
            dependencyAnalyzer.scanAndSaveDependencies(repoDir, repoNameKey);

            ProjectDetector.ProjectDetectionResult detectionResult = projectDetector.detectProjectType(repoDir.toString());
            Path buildDirectory = detectionResult.getProjectRoot();
            ProjectDetector.ProjectType type = detectionResult.getProjectType();
            response.setProjectType(type.getDisplayName());

            // --- BRANCHING LOGIC BY TYPE ---
            if (type == ProjectDetector.ProjectType.JAVA) {
                JsonNode dockerConfig = getDockerConfig(repoNameKey, request.getServerName());
                if (dockerConfig != null && dockerConfig.has("pomPath")) {
                    addLog(response, "Config has pomPath. Overriding Detector Root to Repository Root.");
                    buildDirectory = repoDir; 
                }

                if (Files.exists(repoDir.resolve("pom.xml"))) {
                    buildDirectory = repoDir;
                }

                boolean isMultiJar = dockerConfig != null && dockerConfig.has("isMultiJar") && dockerConfig.get("isMultiJar").asBoolean();
                Path artifact;

                if (isMultiJar && dockerConfig.has("artifacts")) {
                    addLog(response, "Multi-Jar detected. Building individual modules from config...");
                    for (JsonNode artEntry : dockerConfig.get("artifacts")) {
                        String subModule = artEntry.get("module").asText();
                        Path subModulePath = repoDir.resolve(subModule);
                        if (Files.exists(subModulePath)) {
                            addLog(response, "Building configured module: " + subModule);
                            logbackStanderdizer.enforceStandardLogback(subModulePath, response);
                            envConfigurator.configureEnvironment(subModulePath, request.getServerName(), response);
                            buildJavaProject(subModulePath.toString(), response);
                        }
                    }
                    artifact = repoDir; 
                } else {
                    logbackStanderdizer.enforceStandardLogback(buildDirectory, response);
                    envConfigurator.configureEnvironment(buildDirectory, request.getServerName(), response);

                    String mvn = System.getProperty("os.name").toLowerCase().startsWith("windows") ? "mvn.cmd" : "mvn";
                    if (dockerConfig != null && dockerConfig.has("pomPath")) {
                        String customPom = dockerConfig.get("pomPath").asText();
                        addLog(response, "Starting Build using Entry Point: " + customPom);
                        executeCommand(new String[]{mvn, "-f", customPom, "clean", "install", "-DskipTests", "-U"}, repoDir.toString(), response);
                    } else {
                        addLog(response, "Starting Main Reactor Build...");
                        buildJavaProject(buildDirectory.toString(), response);
                    }

                    String moduleName = (dockerConfig != null && dockerConfig.has("moduleName")) 
                            ? dockerConfig.get("moduleName").asText() : null;
                    
                    artifact = findJavaArtifactInModules(buildDirectory, repoNameKey, moduleName)
                            .orElseThrow(() -> new RuntimeException("JAR artifact missing in module: " + (moduleName != null ? moduleName : "root")));                
                    
                    String nextVersion = versioningService.getNextVersion(repoNameKey);
                    String newFileName = String.format("%s-%s.jar", repoNameKey, nextVersion);
                    Path renamedArtifact = artifact.getParent().resolve(newFileName);
                    Files.write(renamedArtifact, Files.readAllBytes(artifact)); // Preservation of logic
                    artifact = renamedArtifact;
                }

                Path excelModulePath = repoDir.resolve("excel_plugin");
                if (Files.exists(excelModulePath)) {
                    addLog(response, "Detected standalone 'excel_plugin' folder. Building separately...");
                    buildJavaProject(excelModulePath.toString(), response);
                }

                finalBuildStatus = "SUCCESS";
                performDockerizedDeployment(artifact, workspacePathStr, request, response);
                finalDeploymentStatus = "SUCCESS";
                response.setCurrentStage("Deployment Complete");

            } else if (type == ProjectDetector.ProjectType.ANGULAR) {
                addLog(response, "Angular Project Detected. Starting Patching Flow...");
                angularBuildService.buildAngularProject(buildDirectory, request.getServerName(), request, response);

                finalBuildStatus = "SUCCESS";
                finalDeploymentStatus = "SUCCESS (PATCHED)";
                response.setCurrentStage("Patching Complete");
                addLog(response, "Angular patching finished. Skipping Docker deployment as requested.");
            } else {
                throw new RuntimeException("Unsupported project type for this flow.");
            }

            response.setStatus("SUCCESS");

        } catch (Exception e) {
            logger.error("[CRITICAL FAILURE] Build/Deploy Failed for ID: " + buildId, e);
            response.setStatus("FAILED");
            response.setErrorMessage(e.getMessage());
            addLog(response, "CRITICAL ERROR: " + e.getMessage());
            finalBuildStatus = "FAILED";
            finalDeploymentStatus = "FAILED";
        } finally {
            response.setEndTime(LocalDateTime.now());
            auditService.logDeployment(buildId, repoNameKey, request.getBranch(), request.getServerName(), request.getRequestedBy(),response.getCloneStatus(), finalBuildStatus, finalDeploymentStatus,response.getStartupStatus());
        }
    }

    /**
     * Deletes Maven failure cache files (.lastUpdated) to force resolution of new internal JARs.
     */
    private void clearMavenFailureCache(BuildResponse response) {
        try {
            String m2Repo = System.getProperty("user.home") + File.separator + ".m2" + File.separator + "repository";
            Path comSimPath = Paths.get(m2Repo).resolve("com").resolve("sim");
            if (Files.exists(comSimPath)) {
                try (Stream<Path> walk = Files.walk(comSimPath)) {
                    walk.filter(p -> p.toString().endsWith(".lastUpdated"))
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                }
            }
        } catch (Exception e) { logger.warn("Failed to clear m2 cache: " + e.getMessage()); }
    }

    private void patchAllPomsRecursively(Path rootDir, BuildResponse response) {
        addLog(response, "[PATCH-DEBUG] Initiating recursive search for pom.xml in: " + rootDir.toString());
        try (Stream<Path> walk = Files.walk(rootDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals("pom.xml"))
                .forEach(pomPath -> {
                    try {
                        String content = new String(Files.readAllBytes(pomPath));
                        if (content.toLowerCase().contains("linux")) {
                            addLog(response, "[PATCH-ACTION] Modifying: " + rootDir.relativize(pomPath) + " (found 'linux' references)");
                            
                            // Aggressive replacement for protoc and other OS-specific plugins
                            String updatedContent = content
                                .replace("linux-x86_64", "windows-x86_64")
                                .replace("linux-aarch_64", "windows-x86_64")
                                .replace("<classifier>linux</classifier>", "<classifier>windows</classifier>")
                                .replace("linux", "windows"); 

                            Files.write(pomPath, updatedContent.getBytes());
                            addLog(response, "[PATCH-SUCCESS] Applied Windows fixes to: " + pomPath.getFileName());
                        }
                    } catch (IOException e) {
                        addLog(response, "[PATCH-WARNING] Failed to process pom at " + pomPath + " : " + e.getMessage());
                    }
                });
        } catch (IOException e) {
            addLog(response, "[PATCH-ERROR] Could not traverse directory for patching: " + e.getMessage());
            logger.error("Recursive patch failed", e);
        }
    }

    private void patchPomForWindows(Path repoDir, BuildResponse response) {
        addLog(response, "Explicit call to patch pom for Windows detected. Running recursive scan...");
        patchAllPomsRecursively(repoDir, response);
    }

    private void performDockerizedDeployment(Path sourcePath, String workspacePath, BuildRequest request, BuildResponse response) throws Exception {
        String repoName = request.getRepoName();
        String serverName = request.getServerName();

        JsonNode dockerConfig = getDockerConfig(repoName, serverName);
        if (dockerConfig == null) throw new RuntimeException("Mapping missing in docker_deploy_map.json for repo: " + repoName);

        String remoteTargetDir = getSafeJsonValue(dockerConfig, "remoteJarPath");
        String dockerContext = getSafeJsonValue(dockerConfig, "dockerContext");
        String composeFile = getSafeJsonValue(dockerConfig, "composeFile");
        String envFile = getSafeJsonValue(dockerConfig, "envFile");
        String jump2BaseStaging = getSafeJsonValue(dockerConfig, "jump2StagingPath");
        String remoteWorkingDir = getSafeJsonValue(dockerConfig, "projectWorkDir");
        String removalCheckString = getSafeJsonValue(dockerConfig, "removalCheckString");
        
        boolean isMultiJar = dockerConfig.has("isMultiJar") && dockerConfig.get("isMultiJar").asBoolean();
        String jump2StagingDir = jump2BaseStaging.endsWith("/") ? jump2BaseStaging + repoName + "/" : jump2BaseStaging + "/" + repoName + "/";

        DeploymentConfig config = loadServerConfig(serverName);
        List<Session> sessions = new ArrayList<>();
        JSch jsch = new JSch();

        try {
            // Identify keys to add
            if (config.getJump1() != null) validateKeyAndAdd(jsch, config.getJump1().getKeyPath(), "Jump1");
            if (config.getJump2() != null) validateKeyAndAdd(jsch, config.getJump2().getKeyPath(), "Jump2");
            validateKeyAndAdd(jsch, config.getTarget().getKeyPath(), "Target");

            Session bridgeSession = null;

            // Generic Connection Logic
            if (config.getJump1() != null) {
                addLog(response, "Connecting to Jump 1: " + config.getJump1().getHost());
                Session j1 = createSession(jsch, config.getJump1(), config.getJump1().getHost(), 22);
                j1.connect(30000);
                sessions.add(j1);
                bridgeSession = j1; // Default bridge is Jump 1

                if (config.getJump2() != null) {
                    addLog(response, "Tunnelling through Jump 1 to Jump 2: " + config.getJump2().getHost());
                    int tunnelPort = j1.setPortForwardingL(0, config.getJump2().getHost(), 22);
                    Session j2 = createSession(jsch, config.getJump2(), "127.0.0.1", tunnelPort);
                    j2.connect(45000);
                    sessions.add(j2);
                    bridgeSession = j2; // Final bridge is Jump 2
                }
            } else {
                // Direct connection if no jumps defined (Target must be reachable)
                addLog(response, "No jumps defined. Connecting directly to Target context...");
                // Note: In your current architecture, the commands use 'ssh serverName', 
                // so we still need a session to execute those commands from.
                // If no jumps, you'd likely connect to a local shell or a target session.
                throw new RuntimeException("No Jump servers configured for " + serverName + ". Deployment requires at least Jump1.");
            }

            if (isMultiJar) {
                JsonNode artifactsNode = dockerConfig.get("artifacts");
                addLog(response, "PHASE 1 & 2: Processing artifacts for Multi-Jar deployment...");
                
                for (JsonNode artifactEntry : artifactsNode) {
                    String module = artifactEntry.get("module").asText();
                    List<String> targetNames = new ArrayList<>();
                    JsonNode nameNode = artifactEntry.get("standardName");
                    if (nameNode.isArray()) {
                        nameNode.forEach(n -> targetNames.add(n.asText()));
                    } else {
                        targetNames.add(nameNode.asText());
                    }

                    for (String stdName : targetNames) {
                        Path jarPath = findJavaArtifactInModules(sourcePath, "", module, stdName)
                                .orElseThrow(() -> new RuntimeException("JAR missing for: " + stdName));
                        
                        logger.info("[MULTI-DEPLOY] Staging: {} -> {}", stdName, jarPath.getFileName());
                        transferArtifactToJump2(bridgeSession, jarPath.toAbsolutePath().toString(), jump2StagingDir, jarPath.getFileName().toString(), response);
                        
                        String jump2SourceFile = jump2StagingDir + jarPath.getFileName().toString();
                        String pushCommand = String.format("ssh %s 'mkdir -p %s' && scp %s %s:%s%s", 
                                serverName, remoteTargetDir, jump2SourceFile, serverName, remoteTargetDir, stdName);
                        
                        logger.info("[MULTI-DEPLOY] Pushing to target via bridge: {}", stdName);
                        executeRemoteCommand(bridgeSession, pushCommand, response, false, null);
                    }
                }
            } else {
                String standardJarName = getSafeJsonValue(dockerConfig, "standardJarName");
                String jump2SourceFile = jump2StagingDir + sourcePath.getFileName().toString();
                
                addLog(response, "PHASE 1: Transferring artifact to Bridge staging...");
                transferArtifactToJump2(bridgeSession, localPath(sourcePath), jump2StagingDir, sourcePath.getFileName().toString(), response);

                addLog(response, "PHASE 2: Pushing from Bridge to Target (" + serverName + ")");
                String pushCommand = String.format("ssh %s 'mkdir -p %s' && scp %s %s:%s%s", 
                        serverName, remoteTargetDir, jump2SourceFile, serverName, remoteTargetDir, standardJarName);
                executeRemoteCommand(bridgeSession, pushCommand, response, false, null);
            }

            addLog(response, "PHASE 3: Docker Down (Clearing existing containers)...");
            String dockerDownCmd = String.format("cd %s && docker --context %s compose -f %s --env-file %s down", remoteWorkingDir, dockerContext, composeFile, envFile);
            executeRemoteCommand(bridgeSession, dockerDownCmd, response, false, removalCheckString);

            addLog(response, "PHASE 4: Docker Up (Launching new deployment)...");
            String dockerUpCmd = String.format("cd %s && docker --context %s compose -f %s --env-file %s up -d", remoteWorkingDir, dockerContext, composeFile, envFile);
            executeRemoteCommand(bridgeSession, dockerUpCmd, response, false, null);

            logger.info("[DEPLOY-SUCCESS] Multi-phase pipeline finished.");

        } finally {
            for (int i = sessions.size() - 1; i >= 0; i--) {
                if (sessions.get(i).isConnected()) sessions.get(i).disconnect();
            }
        }
    }

    private String localPath(Path path) {
        return path.toAbsolutePath().toString();
    }

    private void executeRemoteCommand(Session session, String command, BuildResponse response, boolean fireAndForget, String requiredSuccessMessage) throws JSchException, IOException {
        logger.info("[REMOTE-CMD] Executing: {}", command);
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        InputStream in = channel.getInputStream();
        InputStream err = channel.getErrStream();
        channel.connect();
        
        StringBuilder fullOutput = new StringBuilder();
        byte[] tmp = new byte[1024];
        
        while (true) {
            while (in.available() > 0) {
                int i = in.read(tmp, 0, 1024);
                if (i < 0) break;
                String line = new String(tmp, 0, i);
                fullOutput.append(line);
                addLog(response, "REMOTE: " + line.trim());
            }
            while (err.available() > 0) {
                int i = err.read(tmp, 0, 1024);
                if (i < 0) break;
                String line = new String(tmp, 0, i);
                fullOutput.append(line);
                addLog(response, "REMOTE-ERR: " + line.trim());
            }
            if (channel.isClosed()) {
                if (in.available() > 0 || err.available() > 0) continue; 
                int exitStatus = channel.getExitStatus();
                if (exitStatus > 0) throw new IOException("Remote command failed with exit code " + exitStatus);
                if (requiredSuccessMessage != null) {
                    String cleanOutput = fullOutput.toString().replaceAll("\\x1B\\[[0-9;]*[mGJK]", "");
                    if (!cleanOutput.toLowerCase().contains(requiredSuccessMessage.toLowerCase().trim())) {
                        throw new IOException("Verification string '" + requiredSuccessMessage + "' missing from output.");
                    }
                }
                break;
            }
            try { Thread.sleep(200); } catch (Exception e) {}
        }
        channel.disconnect();
    }

    // --- OVERLOADED SEARCH TO SUPPORT LIST SEARCHING ---
    private Optional<Path> findJavaArtifactInModules(Path projectRoot, String prefix, String moduleName, String searchPattern) throws IOException {
        Path searchDir = (moduleName != null && !moduleName.isEmpty() && !moduleName.equals(".")) 
                ? projectRoot.resolve(moduleName).resolve("target") : projectRoot.resolve("target");
        
        if (!Files.exists(searchDir)) {
             logger.info("[ARTIFACT-SEARCH] Targeted path missing: {}. Performing recursive scan in repo root...", searchDir);
             try (Stream<Path> walk = Files.walk(projectRoot)) {
                return walk.filter(p -> p.toString().endsWith(".jar"))
                           .filter(p -> p.getParent().getFileName().toString().equals("target"))
                           .filter(p -> !p.toString().contains("sources") && !p.toString().contains("javadoc"))
                           .filter(p -> !p.getFileName().toString().endsWith(".original"))
                           .filter(p -> p.getFileName().toString().toLowerCase().contains(searchPattern.split("-0")[0].toLowerCase()))
                           .sorted((p1, p2) -> {
                               try { return Long.compare(Files.size(p2), Files.size(p1)); } catch (IOException e) { return 0; }
                           })
                           .findFirst();
            }
        }
        return findJavaArtifactInModules(projectRoot, prefix, moduleName);
    }

    private Optional<Path> findJavaArtifactInModules(Path projectRoot, String prefix, String moduleName) throws IOException {
        String folderName = moduleName;
        if ("payload-to-excel-service".equals(moduleName)) {
            folderName = "excel_plugin";
        }

        Path searchDir = (folderName != null && !folderName.isEmpty()) 
                         ? projectRoot.resolve(folderName).resolve("target") 
                         : projectRoot.resolve("target");

        if (!Files.exists(searchDir)) {
             logger.info("[ARTIFACT-SEARCH] Targeted path missing: {}. Performing recursive scan in repo root...", searchDir);
             try (Stream<Path> walk = Files.walk(projectRoot)) {
                return walk.filter(p -> p.toString().endsWith(".jar"))
                           .filter(p -> p.getParent().getFileName().toString().equals("target"))
                           .filter(p -> !p.toString().contains("sources") && !p.toString().contains("javadoc"))
                           .filter(p -> !p.getFileName().toString().endsWith(".original"))
                           .filter(p -> p.getFileName().toString().toLowerCase().contains(moduleName != null ? moduleName.toLowerCase() : ""))
                           .sorted((p1, p2) -> {
                               try { return Long.compare(Files.size(p2), Files.size(p1)); } catch (IOException e) { return 0; }
                           })
                           .findFirst();
            }
        }

        logger.info("[ARTIFACT-SEARCH] Checking directory: {}", searchDir);
        try (Stream<Path> stream = Files.walk(searchDir, 1)) {
            return stream
                    .filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.toString().contains("sources") && !p.toString().contains("javadoc"))
                    .filter(p -> !p.getFileName().toString().endsWith(".original"))
                    .sorted((p1, p2) -> {
                        try { return Long.compare(Files.size(p2), Files.size(p1)); } catch (IOException e) { return 0; }
                    })
                    .findFirst();
        }
    }

    private void transferArtifactToJump2(Session session, String localPath, String remotePath, String fileName, BuildResponse response) throws JSchException, SftpException {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect();
        try {
            String[] folders = remotePath.split("/");
            StringBuilder pathBuilder = new StringBuilder();
            for (String folder : folders) {
                if (folder.isEmpty()) continue;
                pathBuilder.append("/").append(folder);
                String currentPath = pathBuilder.toString();
                try {
                    try { sftp.stat(currentPath); } catch (SftpException e) {
                        sftp.mkdir(currentPath);
                    }
                } catch (Exception e) { }
            }
            String finalDest = remotePath.endsWith("/") ? remotePath + fileName : remotePath + "/" + fileName;
            sftp.put(localPath, finalDest);
        } finally {
            sftp.disconnect();
        }
    }

    private void validateKeyAndAdd(JSch jsch, String path, String label) throws JSchException {
        if (path == null || !Files.exists(Paths.get(path))) throw new JSchException("Identity Key missing: " + path);
        jsch.addIdentity(path);
    }

    private Session createSession(JSch jsch, DeploymentConfig.ServerDetails details, String host, int port) throws JSchException {
        Session session = jsch.getSession(details.getUser(), host, port);
        session.setUserInfo(new MyUserInfo());
        if (details.getPassword() != null && !details.getPassword().isEmpty()) session.setPassword(details.getPassword());
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setServerAliveInterval(15000); 
        session.setServerAliveCountMax(10);
        session.setConfig(config);
        return session;
    }

    private void cleanupWorkspace(String projectPath, BuildResponse response) throws IOException {
        Path buildDir = Paths.get(projectPath);
        if (Files.exists(buildDir)) {
            try (Stream<Path> walk = Files.walk(buildDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException e) {} });
            }
        }
        Files.createDirectories(buildDir);
    }

    private void cloneRepository(String url, String token, String branch, String commit, String path, BuildResponse res) throws IOException, InterruptedException {
        String authUrl = url.replace("https://", "https://" + token + "@");
        executeCommand(new String[]{"git", "clone", "--branch", branch, authUrl, "."}, path, res);
        if (commit != null && !commit.isEmpty()) executeCommand(new String[]{"git", "checkout", commit}, path, res);
    }

    private void buildJavaProject(String path, BuildResponse res) throws IOException, InterruptedException {
        String mvn = System.getProperty("os.name").toLowerCase().startsWith("windows") ? "mvn.cmd" : "mvn";
        executeCommand(new String[]{mvn, "clean", "install", "-DskipTests", "-U"}, path, res);
    }

    private void executeCommand(String[] command, String workingDir, BuildResponse response) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(workingDir));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) { addLog(response, line); }
        }
        if (p.waitFor() != 0) throw new RuntimeException("Local command failed: " + Arrays.toString(command));
    }

    private DeploymentConfig loadServerConfig(String serverName) throws IOException {
        Path configPath = resolveConfigPath("configs/environments/server_config.json");
        Map<String, DeploymentConfig> configMap = objectMapper.readValue(configPath.toFile(), new TypeReference<Map<String, DeploymentConfig>>() {});
        if (!configMap.containsKey(serverName)) throw new RuntimeException("Server config missing: " + serverName);
        return configMap.get(serverName);
    }

    private String getSafeJsonValue(JsonNode node, String key) {
        if (node.get(key) == null) throw new RuntimeException("Missing config key: " + key);
        return node.get(key).asText();
    }

    private JsonNode getDockerConfig(String repoName, String serverName) {
        try {
            Path path = resolveConfigPath(DOCKER_MAP_PATH);
            JsonNode root = objectMapper.readTree(path.toFile());
            return root.path("projects").path(repoName).path(serverName);
        } catch (Exception e) { return null; }
    }

    private Path resolveConfigPath(String rel) {
        Path p = Paths.get(System.getProperty("user.dir")).resolve(rel);
        if (!Files.exists(p)) p = Paths.get(System.getProperty("user.dir")).getParent().getParent().resolve(rel);
        return p;
    }

    public void addLog(BuildResponse response, String message) {
        logger.info("[BuildID: {}] {}", response.getBuildId(), message);
        if (response.getBuildLogs() != null) response.getBuildLogs().add(message);
    }

    public BuildResponse getBuildStatus(String buildId) {
        return buildStatuses.getOrDefault(buildId, new BuildResponse());
    }

    public static class MyUserInfo implements UserInfo {
        public String getPassphrase() { return null; }
        public String getPassword() { return null; }
        public boolean promptPassword(String message) { return true; }
        public boolean promptPassphrase(String message) { return true; }
        public boolean promptYesNo(String message) { return true; }
        public void showMessage(String message) { }
    }
}