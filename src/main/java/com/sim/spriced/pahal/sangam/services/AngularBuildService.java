package com.sim.spriced.pahal.sangam.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.*;
import com.sim.spriced.pahal.sangam.models.BuildRequest;
import com.sim.spriced.pahal.sangam.models.BuildResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class AngularBuildService {

    private static final Logger logger = LoggerFactory.getLogger(AngularBuildService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SERVER_CONFIG = "configs/environments/server_config.json";
    private static final String ANGULAR_METADATA = "configs/global/angular_metadata.json";
    private static final String SPRICED_UI_METADATA = "configs/global/angular_metadata.json";

    public Path buildAngularProject(Path projectRoot, String serverName, BuildRequest request, BuildResponse response) throws IOException {
        logger.info(">>> STARTING BUILD PROCESS <<< Repository: {} | Server: {}", request.getRepoName(), serverName);
        addLog(response, "Initializing build for repository: " + request.getRepoName());

        // Workspace discovery
        logger.info("Searching for workspace root starting from: {}", projectRoot.toAbsolutePath());
        addLog(response, "Searching for workspace configuration files...");
        Path workingDir = findWorkspaceRoot(projectRoot);
        if (workingDir == null) {
            logger.warn("No nx.json or angular.json found. Searching for package.json...");
            workingDir = findPackageJsonDir(projectRoot, response);
        }
        
        if (workingDir == null) {
            logger.error("Build failed: Could not locate workspace root or package.json in {}", projectRoot);
            addLog(response, "ERROR: Could not locate package.json or workspace configuration.");
            throw new RuntimeException("Workspace root / package.json missing in cloned project");
        }
        
        addLog(response, "Detected Workspace Root: " + workingDir.toAbsolutePath());
        logger.info("Working directory set to: {}", workingDir.toAbsolutePath());

        boolean isSpricedUi = "spriced-ui".equalsIgnoreCase(request.getRepoName());
        String appName = isSpricedUi ? "spriced-container" : "my-app";
        logger.info("Project detected as: {}. Target application name: {}", isSpricedUi ? "Spriced-UI (Nx)" : "Standard DVM", appName);
        addLog(response, "Target App: " + appName);

        try {
            if (isSpricedUi) {
                patchSpricedUiEnvFiles(workingDir, request, response);
            } else {
                updateEnvironmentFile(workingDir, request, response);
            }
        } catch (Exception e) {
            logger.error("Configuration patching failed for repo {}: {}", request.getRepoName(), e.getMessage());
            addLog(response, "ERROR: Configuration update failed: " + e.getMessage());
            throw new RuntimeException("Environment config failed", e);
        }

        // Step 2: npm install (with conditional --force)
        runNpmInstall(workingDir, request, response);

        // Step 3: Run correct build command
        runCorrectBuildCommand(workingDir, appName, isSpricedUi, response);

        // Dist path resolution - UPDATED for spriced-ui to include all apps
        logger.info("Resolving distribution artifacts path for {}", appName);
        addLog(response, "Verifying build output in 'dist' folder...");
        
        Path distPath;
        if (isSpricedUi) {
            // For spriced-ui, we want the whole 'apps' folder containing all micro-frontends
            distPath = workingDir.resolve("dist").resolve("apps");
        } else {
            distPath = workingDir.resolve("dist").resolve("apps").resolve(appName);
            if (!Files.exists(distPath)) {
                distPath = workingDir.resolve("dist").resolve(appName); 
            }
        }

        if (!Files.exists(distPath)) {
            distPath = workingDir.resolve("dist"); 
        }
        
        if (!Files.exists(distPath)) {
            logger.error("Build directory not found at any expected location relative to: {}", workingDir);
            addLog(response, "ERROR: Build artifacts folder not found in dist.");
            throw new IOException("Build succeeded but dist folder is missing.");
        }

        addLog(response, "BUILD PHASE SUCCESS: Artifacts found at " + distPath.getFileName());
        logger.info("Artifacts confirmed at: {}", distPath.toAbsolutePath());

        try {
            deployToLastJump(distPath, workingDir, serverName, request, response, appName, isSpricedUi);
            addLog(response, "DEPLOYMENT PHASE SUCCESS: Application updated on target server.");
        } catch (Exception e) {
            logger.error("Deployment failed during SSH/SFTP phase: ", e);
            addLog(response, "DEPLOYMENT ERROR: " + e.getMessage());
            throw new RuntimeException("Deployment failed", e);
        }

        logger.info(">>> BUILD AND DEPLOYMENT FINISHED SUCCESSFULLY <<<");
        return distPath;
    }

    private void patchSpricedUiEnvFiles(Path workingDir, BuildRequest request, BuildResponse response) throws IOException {
            addLog(response, "Step 1.5: Patching spriced-ui .env files...");
            
            String targetUrl = request.getTargetUrl();
            // Extract full host (e.g., "dev.simadvisory.com")
            String targetHost = targetUrl.replaceFirst("^https?://", "").split("/")[0];
            // Extract prefix (e.g., "dev")
            String newPrefix = targetHost.split("\\.")[0];

            JsonNode meta = loadJsonConfig(SPRICED_UI_METADATA);
            JsonNode identity = findIdentityByClientId(meta, request.getKeycloakClientId());
            
            String realm = identity.path("realm").asText();
            String kcUrl = identity.path("keycloakUrl").asText();
            String clientId = identity.path("clientId").asText();

            String[] apps = {"spriced-container", "spriced-data", "spriced-data-definition", "spriced-reports", "spriced-user-management"};

            for (String appFolder : apps) {
                Path envPath = workingDir.resolve("apps").resolve(appFolder).resolve(".env");
                if (Files.exists(envPath)) {
                    logger.info("Patching .env file for app: {}", appFolder);
                    List<String> lines = Files.readAllLines(envPath);
                    List<String> updatedLines = new ArrayList<>();

                    for (String line : lines) {
                        String updatedLine = line;

                        // 1. Maintain existing logic for Keycloak/Superset/Host replacements
                        if (line.startsWith("NX_KEY_CLOAK_URL=")) {
                            updatedLine = "NX_KEY_CLOAK_URL=" + kcUrl;
                        } else if (line.startsWith("NX_KEY_CLOAK_REALM=")) {
                            updatedLine = "NX_KEY_CLOAK_REALM=" + realm;
                        } else if (line.startsWith("NX_KEY_CLOAK_CLIENT_ID=")) {
                            updatedLine = "NX_KEY_CLOAK_CLIENT_ID=" + clientId;
                        }
                        else if (line.startsWith("NX_SUPERSET_DOMAIN=")) {
                            if (newPrefix.equalsIgnoreCase("localhost")) {
                                updatedLine = "NX_SUPERSET_DOMAIN=https://localhost:8088"; 
                            } else {
                                updatedLine = "NX_SUPERSET_DOMAIN=https://" + newPrefix + ".reports.simadvisory.com";
                            }
                        }
                        else if (line.contains("://")) {
                            updatedLine = line.replaceAll("(?<=://)[^/]+", targetHost);
                        }

                        // 2. SPECIFIC FIX: Replace hyphen with underscore in USER-ACCESS
                        // This fixes NX_API_USER-ACCESS_URL to become NX_API_USER_ACCESS_URL
                        if (updatedLine.contains("USER-ACCESS")) {
                            updatedLine = updatedLine.replace("USER-ACCESS", "USER_ACCESS");
                        }
                        // Handling the typo version "USER-ACESS" mentioned previously just in case
                        if (updatedLine.contains("USER-ACESS")) {
                            updatedLine = updatedLine.replace("USER-ACESS", "USER_ACESS");
                        }

                        // 3. FORCE HTTPS: Ensure all URLs use https protocol
                        if (updatedLine.contains("http://")) {
                            updatedLine = updatedLine.replace("http://", "https://");
                        }

                        updatedLines.add(updatedLine);
                    }
                    Files.write(envPath, updatedLines, StandardCharsets.UTF_8);
                }
            }
            addLog(response, "Environment patching for Spriced-UI completed (Fixed USER_ACCESS and forced HTTPS).");
        }
    private void deployToLastJump(Path distPath, Path workingDir, String serverName, BuildRequest request, BuildResponse response, String appName, boolean isSpricedUi) throws Exception {
        addLog(response, "Step 4: Preparing deployment archive...");
        
        String targetDeployPath = resolveTargetDeployPath(request);
        logger.info("Target deployment directory on remote: {}", targetDeployPath);
        
        String zipName = "frontend_build.zip";
        Path zipFilePath = workingDir.resolve(zipName);

        logger.info("Zipping dist artifacts from {} into {}", distPath, zipFilePath);
        addLog(response, "Compressing artifacts for transport...");
        zipDirectoryWithFolder(distPath, distPath.getParent(), zipFilePath);

        JsonNode serverConfigRoot = loadJsonConfig(SERVER_CONFIG);
        JsonNode serverConfig = serverConfigRoot.get(serverName);
        if (serverConfig == null) throw new RuntimeException("Server configuration not found for: " + serverName);

        JSch jsch = new JSch();
        List<Session> sessions = new ArrayList<>();
        
        try {
            JsonNode j1 = serverConfig.get("jump1");
            JsonNode j2 = serverConfig.get("jump2");

            addLog(response, "Establishing SSH Tunnel through jump hosts...");
            logger.info("Connecting to Jump 1: {}", j1.get("host").asText());
            jsch.addIdentity(j1.get("keyPath").asText());
            if (j2 != null) {
                logger.info("Adding identity for Jump 2: {}", j2.get("keyPath").asText());
                jsch.addIdentity(j2.get("keyPath").asText());
            }

            Session s1 = createSession(jsch, j1, j1.get("host").asText(), 22);
            s1.connect(30000);
            sessions.add(s1);
            
            Session finalJumpSession = s1;
            if (j2 != null) {
                logger.info("Setting up port forwarding through Jump 1 to Jump 2...");
                int lport = s1.setPortForwardingL(0, j2.get("host").asText(), 22);
                Session s2 = createSession(jsch, j2, "127.0.0.1", lport);
                s2.connect(30000);
                sessions.add(s2);
                finalJumpSession = s2;
            }

            uploadAndExtract(finalJumpSession, zipFilePath.toString(), targetDeployPath, zipName, response, appName, isSpricedUi);

        } finally {
            logger.info("Cleaning up temporary zip file and closing SSH sessions...");
            Files.deleteIfExists(zipFilePath);
            for (int i = sessions.size() - 1; i >= 0; i--) {
                if (sessions.get(i).isConnected()) sessions.get(i).disconnect();
            }
        }
    }

    private void uploadAndExtract(Session session, String localZip, String remoteDir, String zipName, BuildResponse res, String appName, boolean isSpricedUi) throws JSchException, SftpException, IOException {
        logger.info("Opening SFTP channel to {}", session.getHost());
        addLog(res, "Uploading artifacts via SFTP...");
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect();
        try {
            logger.info("Ensuring remote directory structure exists: {}", remoteDir);
            String[] folders = remoteDir.split("/");
            StringBuilder path = new StringBuilder();
            for (String folder : folders) {
                if (folder.isEmpty()) continue;
                path.append("/").append(folder);
                try { sftp.mkdir(path.toString()); } catch (Exception ignored) {}
            }
            sftp.cd(remoteDir);
            logger.info("Uploading {} to {}", localZip, remoteDir);
            sftp.put(localZip, zipName);
            addLog(res, "Upload complete.");
        } finally {
            sftp.disconnect();
        }

        String automatedCmd;
        if (isSpricedUi) {
            addLog(res, "Replacement Phase: Deploying all folders from 'apps' to final destination...");
            automatedCmd = String.format(
                "cd %1$s && " +
                "unzip -o -qq %2$s && rm %2$s && " +
                "if [ -d \"apps\" ]; then " +
                "  cd apps && " +
                "  for d in *; do " +
                "    if [ -d \"$d\" ]; then " +
                "       ssh -o StrictHostKeyChecking=no ai-spriced \"rm -rf %1$s/\"$d; " +
                "       sftp -o StrictHostKeyChecking=no ai-spriced <<EOF\n" +
                "cd %1$s\n" +
                "put -r \"$d\"\n" +
                "bye\n" +
                "EOF\n" +
                "    fi; " +
                "  done && " +
                "  cd .. && rm -rf apps; " +
                "fi",
                remoteDir, zipName
            );
        } else {
            addLog(res, "Replacement Phase: Deploying folder '" + appName + "' to final destination...");
            automatedCmd = String.format(
                "cd %1$s && " +
                "unzip -o -qq %2$s && rm %2$s && " +
                "ssh -o StrictHostKeyChecking=no ai-spriced \"rm -rf %1$s/%3$s\" && " +
                "sftp -o StrictHostKeyChecking=no ai-spriced <<EOF\n" +
                "cd %1$s\n" +
                "put -r %3$s\n" +
                "bye\n" +
                "EOF",
                remoteDir, zipName, appName
            );
        }

        logger.info("Executing remote deployment script...");
        executeRemoteCommand(session, automatedCmd, res);
        addLog(res, "Success: Application replaced on target host.");
    }

    private void runCorrectBuildCommand(Path workingDir, String app, boolean isSpricedUi, BuildResponse response) {
        String cmd = System.getProperty("os.name").toLowerCase().startsWith("windows") ? "npx.cmd" : "npx";
        
        if (isSpricedUi) {
            addLog(response, "Step 3: Executing Spriced-UI Build (nx run-many)...");
            logger.info("Running: npx nx run-many --targets=build --all --skip-nx-cache");
            executeCommand(new String[]{cmd, "nx", "run-many", "--targets=build", "--all", "--skip-nx-cache"}, workingDir, "[NX-BUILD]", response);
        } else {
            addLog(response, "Step 3: Executing DVM Build (ng build)...");
            logger.info("Running:  build {} --configuration=development", app);
            executeCommand(new String[]{cmd, "ng", "build", app, "--configuration=development"}, workingDir, "[DVM-BUILD]", response);
        }
        addLog(response, "Build command completed successfully.");
    }

    private Path findWorkspaceRoot(Path root) throws IOException {
        logger.debug("Walking file tree to find nx.json or angular.json in {}", root);
        try (Stream<Path> stream = Files.walk(root, 3)) {
            return stream.filter(p -> p.getFileName().toString().equals("nx.json") || 
                                     p.getFileName().toString().equals("angular.json"))
                         .map(Path::getParent)
                         .findFirst()
                         .orElse(null);
        }
    }

    private void updateEnvironmentFile(Path workingDir, BuildRequest request, BuildResponse response) throws IOException {
        addLog(response, "Step 1.5: Patching environment.dev.ts...");
        logger.info("Updating standard environment file for Client ID: {}", request.getKeycloakClientId());

        JsonNode meta = loadJsonConfig(ANGULAR_METADATA);
        JsonNode identities = meta.get("identities");
        
        String realm = "", clientId = "", keycloakUrl = "";
        boolean found = false;

        Iterator<Map.Entry<String, JsonNode>> fields = identities.fields();
        while (fields.hasNext()) {
            JsonNode data = fields.next().getValue();
            if (data.path("clientId").asText().equalsIgnoreCase(request.getKeycloakClientId())) {
                realm = data.path("realm").asText();
                clientId = data.path("clientId").asText();
                keycloakUrl = data.path("keycloakUrl").asText(); 
                found = true;
                break;
            }
        }

        if (!found) {
            logger.error("Client ID {} not found in {}", request.getKeycloakClientId(), ANGULAR_METADATA);
            throw new RuntimeException("No configuration found in angular_metadata.json");
        }

        String envContent = String.format(
            "export const environment = {\n  URL: '%s',\n  KEYCLOAK_URL: '%s',\n  KEYCLOAK_REALM: '%s',\n  KEYCLOAK_CLIENT_ID: '%s',\n  const_url_report: '%s',\n  const_url_process: 'https://cdbu-dev.alpha.simadvisory.com/',\n};",
            request.getTargetUrl(), keycloakUrl, realm, clientId, request.getTargetUrl() 
        );

        Path envPath = workingDir.resolve("projects").resolve("my-app").resolve("src").resolve("environments").resolve("environment.dev.ts");
        logger.info("Writing environment content to: {}", envPath.toAbsolutePath());
        if (!Files.exists(envPath.getParent())) Files.createDirectories(envPath.getParent());
        Files.write(envPath, envContent.getBytes(StandardCharsets.UTF_8));
    }

    private String resolveTargetDeployPath(BuildRequest request) throws IOException {
        String requestClientId = request.getKeycloakClientId();
        logger.debug("Resolving deploy path for Client ID: {}", requestClientId);
        
        String[] configFiles = {SPRICED_UI_METADATA, ANGULAR_METADATA};
        for (String cfg : configFiles) {
            try {
                JsonNode meta = loadJsonConfig(cfg);
                JsonNode identities = meta.get("identities");
                Iterator<Map.Entry<String, JsonNode>> fields = identities.fields();
                while (fields.hasNext()) {
                    JsonNode data = fields.next().getValue();
                    if (data.path("clientId").asText().equalsIgnoreCase(requestClientId)) {
                        if ("spriced-ui".equals(request.getRepoName()) && data.has("spricedDeployPath")) {
                            return data.get("spricedDeployPath").asText();
                        }
                        if (data.has("deployPath")) return data.get("deployPath").asText();
                    }
                }
            } catch (Exception ignored) {}
        }
        logger.error("Could not find a valid deploy path for Client ID: {}", requestClientId);
        throw new RuntimeException("No deployPath found for Client ID: " + requestClientId);
    }

    private JsonNode loadJsonConfig(String relPath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"));
        logger.debug("Attempting to load config: {} starting from {}", relPath, current);
        Path foundPath = null;
        for (int i = 0; i < 3; i++) {
            Path check = current.resolve(relPath);
            if (Files.exists(check)) { 
                foundPath = check; 
                break; 
            }
            current = current.getParent();
            if (current == null) break;
        }
        if (foundPath == null) {
            logger.error("Configuration file not found: {}", relPath);
            throw new FileNotFoundException("Config missing: " + relPath);
        }
        logger.debug("Config found at: {}", foundPath.toAbsolutePath());
        return objectMapper.readTree(foundPath.toFile());
    }

    private void zipDirectoryWithFolder(Path source, Path base, Path zipFile) throws IOException {
        logger.debug("Zipping directory {} relative to {}", source, base);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            Files.walk(source).filter(p -> !Files.isDirectory(p)).forEach(p -> {
                try {
                    String name = base.relativize(p).toString().replace("\\", "/");
                    zos.putNextEntry(new ZipEntry(name));
                    Files.copy(p, zos);
                    zos.closeEntry();
                } catch (IOException e) { 
                    logger.error("Error zipping file {}: {}", p, e.getMessage());
                    throw new RuntimeException(e); 
                }
            });
        }
    }

    private void executeRemoteCommand(Session s, String cmd, BuildResponse res) throws JSchException, IOException {
        logger.info("Executing remote command...");
        ChannelExec ce = (ChannelExec) s.openChannel("exec");
        ce.setCommand(cmd);
        InputStream in = ce.getInputStream();
        InputStream err = ce.getErrStream();
        ce.connect();
        
        byte[] tmp = new byte[1024];
        while (true) {
            while (in.available() > 0) {
                int i = in.read(tmp, 0, 1024);
                if (i < 0) break;
                addLog(res, "REMOTE-OUTPUT: " + new String(tmp, 0, i).trim());
            }
            while (err.available() > 0) {
                int i = err.read(tmp, 0, 1024);
                if (i < 0) break;
                String errMsg = new String(tmp, 0, i).trim();
                logger.error("REMOTE-ERROR: {}", errMsg);
                addLog(res, "REMOTE-ERROR: " + errMsg);
            }
            if (ce.isClosed()) {
                int exitStatus = ce.getExitStatus();
                logger.info("Remote command finished with exit status: {}", exitStatus);
                if (exitStatus != 0) throw new IOException("Remote command failed with status: " + exitStatus);
                break;
            }
            try { Thread.sleep(200); } catch (Exception ignored) {}
        }
        ce.disconnect();
    }
    private void runNpmInstall(Path workingDir, BuildRequest request, BuildResponse response) {
        String repoName = request.getRepoName().toLowerCase();

        // Use --force if it's a DVM or Visualization repository
        // This covers dvm_visualization_map, dvm_visualization_tool, etc.
        boolean useForce = repoName.contains("dvm") || repoName.contains("visualization");
        
        String logMsg = useForce ? "Step 2: Installing dependencies (npm install --force)..." : "Step 2: Installing dependencies (npm install)...";
        addLog(response, logMsg);
        
        logger.info("Running npm install in directory: {}. Use Force: {}", workingDir, useForce);
        String cmd = System.getProperty("os.name").toLowerCase().startsWith("windows") ? "npm.cmd" : "npm";
        
        // Construct command based on the force flag
        String[] commandArgs = useForce ? new String[]{cmd, "install", "--force"} : new String[]{cmd, "install"};
        
        executeCommand(commandArgs, workingDir, "[NPM]", response);
        addLog(response, "Dependency installation successful.");
    }

    private void executeCommand(String[] cmd, Path dir, String prefix, BuildResponse res) {
        logger.debug("Executing local command: {} in {}", Arrays.toString(cmd), dir);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir.toFile()); 
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l; while ((l = r.readLine()) != null) addLog(res, prefix + " " + l);
            }
            int code = p.waitFor();
            logger.info("Command {} exited with code {}", prefix, code);
            if (code != 0) throw new RuntimeException(prefix + " failed with exit code " + code);
        } catch (Exception e) { 
            logger.error("Process execution error: ", e);
            throw new RuntimeException(e); 
        }
    }

    private void addLog(BuildResponse res, String msg) {
        logger.info("[LOGGER] {}", msg);
        if (res != null && res.getBuildLogs() != null) res.getBuildLogs().add(msg);
    }

    public static class MyUserInfo implements UserInfo {
        public String getPassphrase() { return null; }
        public String getPassword() { return null; }
        public boolean promptPassword(String m) { return true; }
        public boolean promptPassphrase(String m) { return true; }
        public boolean promptYesNo(String m) { return true; }
        public void showMessage(String m) { logger.info("SSH Message: {}", m); }
    }
    
    private Session createSession(JSch jsch, JsonNode node, String host, int port) throws JSchException {
        Session s = jsch.getSession(node.get("user").asText(), host, port);
        Properties c = new Properties();
        c.put("StrictHostKeyChecking", "no");
        s.setConfig(c);
        s.setUserInfo(new MyUserInfo());
        return s;
    }

    private Path findPackageJsonDir(Path projectRoot, BuildResponse response) throws IOException {
        logger.debug("Searching for package.json in {}", projectRoot);
        try (Stream<Path> stream = Files.walk(projectRoot, 3)) { 
            return stream.filter(p -> p.getFileName().toString().equals("package.json")).map(Path::getParent).findFirst().orElse(null);
        }
    }
    
    private JsonNode findIdentityByClientId(JsonNode meta, String clientId) {
        JsonNode identities = meta.get("identities");
        Iterator<Map.Entry<String, JsonNode>> fields = identities.fields();
        while (fields.hasNext()) {
            JsonNode data = fields.next().getValue();
            if (data.path("clientId").asText().equalsIgnoreCase(clientId)) return data;
        }
        logger.error("Client ID {} not found in identity configuration", clientId);
        throw new RuntimeException("Client ID " + clientId + " not found in config.");
    }
}