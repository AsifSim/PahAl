package com.sim.spriced.pahal.controllers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Service
public class RepositorySetupService {

    private static final Logger log = LoggerFactory.getLogger(RepositorySetupService.class);
    private static final String REPO_URL = "https://github.com/AsifSim/application-interface";

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int percentage, String stageMessage);
    }

    /**
     * Fetches all remote branches from the configured repository.
     */
    public List<String> getRemoteBranches() throws GitAPIException {
        log.info("Fetching remote branches");
        Collection<Ref> refs = Git.lsRemoteRepository()
                .setRemote(REPO_URL)
                .setHeads(true)
                .call();

        List<String> branches = new ArrayList<>();
        for (Ref ref : refs) {
            branches.add(ref.getName().replace("refs/heads/", ""));
        }
        log.debug("Branches fetched: {}", branches);
        return branches;
    }

    /**
     * Clones the repository, creates a new branch, scaffolds the Maven module
     * under data-entity-services, and writes pom.xml and application.properties.
     *
     * @param baseBranch    the source branch to clone
     * @param newBranchName the name for the new working branch
     * @param serviceName   the module/service name (used as folder and artifactId)
     * @param progress      optional progress callback
     * @return Path pointing to the generated submodule directory
     */
    public Path setupRepository(String baseBranch,
                                String newBranchName,
                                String serviceName,
                                ProgressCallback progress) throws Exception {
        log.info("Entering setupRepository for service: {}", serviceName);

        if (progress != null) progress.onProgress(5, "Initializing workspace...");

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String targetFolderName = newBranchName + "_" + timestamp;

        Path workspacePath = Path.of(System.getProperty("user.dir"), "cloned_repositories", targetFolderName);
        File localRepoDir = workspacePath.toFile();

        if (progress != null) progress.onProgress(15, "Cloning repository branch: " + baseBranch);

        try (Git git = Git.cloneRepository()
                .setURI(REPO_URL)
                .setDirectory(localRepoDir)
                .setCloneAllBranches(true)
                .setBranch(baseBranch)
                .call()) {

            if (progress != null) progress.onProgress(30, "Creating and checking out branch: " + newBranchName);
            git.branchCreate().setName(newBranchName).call();
            git.checkout().setName(newBranchName).call();

            // Create Maven submodule directory tree
            if (progress != null) progress.onProgress(45, "Building module directory structure...");
            Path submodulePath = workspacePath.resolve(Path.of("data-entity-services", serviceName));
            Path packageDirPath = submodulePath.resolve(Path.of("src", "main", "java", "com", "sim", "spriced", "application", "service"));
            Path resourceDirPath = submodulePath.resolve(Path.of("src", "main", "resources"));

            Files.createDirectories(packageDirPath);
            Files.createDirectories(resourceDirPath);

            // Write pom.xml
            String pomContent = getPomTemplate(serviceName);
            Files.writeString(submodulePath.resolve("pom.xml"), pomContent);
            log.debug("pom.xml written for {}", serviceName);

            // Write application.properties
            String propertiesContent = "spring.application.name=" + serviceName + "\n";
            Files.writeString(resourceDirPath.resolve("application.properties"), propertiesContent);
            log.debug("application.properties written for {}", serviceName);

            if (progress != null) progress.onProgress(100, "Repository setup complete.");

            log.info("Repository setup finished at {}", submodulePath);
            return submodulePath;
        }
    }

    private String getPomTemplate(String artifactName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n\n" +
                "    <modelVersion>4.0.0</modelVersion>\n\n" +
                "    <parent>\n" +
                "        <groupId>com.sim.spriced.application</groupId>\n" +
                "        <artifactId>data-entity-services</artifactId>\n" +
                "        <version>2.0.101-SNAPSHOT</version>\n" +
                "        <relativePath>../pom.xml</relativePath>\n" +
                "    </parent>\n\n" +
                "    <groupId>com.sim.spriced.application</groupId>\n" +
                "    <artifactId>" + artifactName + "</artifactId>\n" +
                "    <version>3.0.0-SNAPSHOT</version>\n\n" +
                "    <name>" + artifactName + "</name>\n" +
                "    <description>" + artifactName + "</description>\n\n" +
                "    <properties>\n" +
                "        <java.version>21</java.version>\n" +
                "    </properties>\n\n" +
                "    <dependencies>\n" +
                "        <dependency>\n" +
                "            <groupId>org.springframework.boot</groupId>\n" +
                "            <artifactId>spring-boot-starter-test</artifactId>\n" +
                "            <scope>test</scope>\n" +
                "        </dependency>\n" +
                "    </dependencies>\n\n" +
                "    <build>\n" +
                "        <plugins>\n" +
                "            <plugin>\n" +
                "                <groupId>org.springframework.boot</groupId>\n" +
                "                <artifactId>spring-boot-maven-plugin</artifactId>\n" +
                "                <executions>\n" +
                "                    <execution>\n" +
                "                        <id>repackage</id>\n" +
                "                        <phase>none</phase>\n" +
                "                    </execution>\n" +
                "                </executions>\n" +
                "            </plugin>\n" +
                "        </plugins>\n" +
                "    </build>\n" +
                "</project>";
    }
}
