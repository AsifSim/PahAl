package com.sim.spriced.pahal.sangam.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.IntStream;

@Service
public class DependencyAnalyzer {

    private static final String CLASSNAME = "DependencyAnalyzer";
    private static final Logger logger = LoggerFactory.getLogger(DependencyAnalyzer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    @Value("${config.path.project-dependencies}")
    private String configPath;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DependencyDetail {
        private String groupId;
        private String artifactId;
        private String version;
    }

    public void scanAndSaveDependencies(Path projectRoot, String repoName) {
        logger.info("{} : Entering method scanAndSaveDependencies", CLASSNAME);
        File pomFile = projectRoot.resolve("pom.xml").toFile();
        
        if (!pomFile.exists()) {
            logger.warn("{} : No pom.xml found in {}. Skipping scan.", CLASSNAME, projectRoot);
            return;
        }

        analyzeAndSync(pomFile, repoName);
        logger.info("{} : Exiting method scanAndSaveDependencies", CLASSNAME);
    }

    private void analyzeAndSync(File pomFile, String repoName) {
        logger.info("{} : Entering method analyzeAndSync", CLASSNAME);
        try {
            List<DependencyDetail> requiredDependencies = parsePomForSimDependencies(pomFile);
            logger.info("{} : Found {} internal dependencies for {}", CLASSNAME, requiredDependencies.size(), repoName);
            updateConfigFile(repoName, requiredDependencies);
        } catch (Exception e) {
            logger.error("{} : CRITICAL: Failed to analyze dependencies for {}: {}", CLASSNAME, repoName, e.getMessage());
        }
        logger.info("{} : Exiting method analyzeAndSync", CLASSNAME);
    }

    private List<DependencyDetail> parsePomForSimDependencies(File pomFile) throws Exception {
        logger.info("{} : Entering method parsePomForSimDependencies", CLASSNAME);
        Document doc = parseXmlDocument(pomFile);
        Map<String, String> properties = extractProperties(doc);

        List<DependencyDetail> dependencies = new ArrayList<>();
        NodeList nList = doc.getElementsByTagName("dependency");
        
        IntStream.range(0, nList.getLength())
                .mapToObj(nList::item)
                .filter(node -> node.getNodeType() == Node.ELEMENT_NODE)
                .map(node -> (Element) node)
                .forEach(element -> processDependencyElement(element, properties, dependencies));

        logger.info("{} : Exiting method parsePomForSimDependencies", CLASSNAME);
        return dependencies;
    }

    private Document parseXmlDocument(File pomFile) throws Exception {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(pomFile);
        doc.getDocumentElement().normalize();
        return doc;
    }

    private void processDependencyElement(Element element, Map<String, String> properties, List<DependencyDetail> list) {
        String groupId = getTagValue("groupId", element);
        if (groupId != null && groupId.startsWith("com.sim")) {
            String artifactId = getTagValue("artifactId", element);
            String rawVersion = getTagValue("version", element);
            String resolvedVersion = resolveVersion(rawVersion, properties);
            
            logger.debug("{} : Detected internal dependency: {}:{}:{}", CLASSNAME, groupId, artifactId, resolvedVersion);
            list.add(new DependencyDetail(groupId, artifactId, resolvedVersion));
        }
    }

    private Map<String, String> extractProperties(Document doc) {
        logger.info("{} : Entering method extractProperties", CLASSNAME);
        Map<String, String> props = new HashMap<>();
        NodeList propList = doc.getElementsByTagName("properties");
        
        if (propList.getLength() > 0) {
            NodeList children = propList.item(0).getChildNodes();
            IntStream.range(0, children.getLength())
                    .mapToObj(children::item)
                    .filter(child -> child.getNodeType() == Node.ELEMENT_NODE)
                    .forEach(child -> props.put(child.getNodeName(), child.getTextContent()));
        }
        logger.info("{} : Exiting method extractProperties", CLASSNAME);
        return props;
    }

    private String resolveVersion(String rawVersion, Map<String, String> properties) {
        if (rawVersion == null) return "LATEST";
        
        if (rawVersion.startsWith("${") && rawVersion.endsWith("}")) {
            String key = rawVersion.substring(2, rawVersion.length() - 1);
            return properties.getOrDefault(key, rawVersion);
        }
        return rawVersion;
    }

    private String getTagValue(String tag, Element element) {
        NodeList nodeList = element.getElementsByTagName(tag);
        return (nodeList.getLength() > 0) ? nodeList.item(0).getTextContent() : null;
    }

    private synchronized void updateConfigFile(String repoName, List<DependencyDetail> newDependencies) {
        logger.info("{} : Entering method updateConfigFile", CLASSNAME);
        try {
            Path configFilePath = resolveConfigPath(configPath);
            ensureDirectoryExists(configFilePath.getParent());

            Map<String, List<DependencyDetail>> dependencyMap = loadExistingConfig(configFilePath);
            dependencyMap.put(repoName, newDependencies);

            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(configFilePath.toFile(), dependencyMap);
            logger.info("{} : Successfully synced dependencies to: {}", CLASSNAME, configFilePath);
        } catch (Exception e) {
            logger.error("{} : Failed to save config file: {}", CLASSNAME, e.getMessage());
        }
        logger.info("{} : Exiting method updateConfigFile", CLASSNAME);
    }

    private Map<String, List<DependencyDetail>> loadExistingConfig(Path path) {
        if (Files.exists(path)) {
            try {
                return OBJECT_MAPPER.readValue(path.toFile(), new TypeReference<>() {});
            } catch (Exception e) {
                logger.warn("{} : Config file corrupt, starting fresh.", CLASSNAME);
            }
        }
        return new HashMap<>();
    }

    private void ensureDirectoryExists(Path parent) throws Exception {
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    private Path resolveConfigPath(String rel) {
        Path p = Paths.get(System.getProperty("user.dir")).resolve(rel);
        if (!Files.exists(p.getParent())) {
            p = Paths.get(System.getProperty("user.dir")).getParent().getParent().resolve(rel);
        }
        return p;
    }
}