package com.sim.spriced.pahal.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sim.spriced.pahal.services.OllamaService;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/stream")
public class JavaStreamingController {

    private static final Logger log = LoggerFactory.getLogger(JavaStreamingController.class);
    private final OllamaService ollamaService;
    private static final String REPO_URL = "https://github.com/AsifSim/application-interface";

    private static final String[] METHOD_SIGNATURES = {
            "",
            "private Map<String, Object> extractInputPayload(TransactionData data) ",
            "private ValidationStatus performTechnicalValidations(TransactionData data, Map<String, Object> requestData) ",
            "private ValidationStatus performFunctionalValidations(TransactionData data, Map<String, Object> requestData) ",
            "@SuppressWarnings(\"unchecked\")\nprivate Map<String, Object> fetchEnrichedData(PlatformContext context, TransactionData data, Map<String, Object> requestData) ",
            "private void evaluateBusinessRules(PlatformContext context, TransactionData data, Map<String, Object> requestData, Map<String, Object> enrichedData) ",
            "private Double calculations(PlatformContext context, TransactionData data, Map<String, Object> requestData, Map<String, Object> enrichedData) ",
            "@SuppressWarnings(\"unchecked\")\nprivate TransactionData prepareResponseOutput(PlatformContext context, TransactionData data, Map<String, Object> requestData, Double calculatedValue) "
    };

    public JavaStreamingController(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    // ===================================================================
    // STREAMING ENDPOINT - ACCEPTS RAW YAML
    // ===================================================================
    @PostMapping(value = "/generate-java-stream", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamJavaGeneration(@RequestBody String tbrdYaml) {
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();

        log.debug("Received raw YAML payload, length = {}", tbrdYaml != null ? tbrdYaml.length() : 0);

        Thread executionThread = new Thread(() -> {
            try {
                ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                JsonNode yamlNode = yamlMapper.readTree(tbrdYaml);
                StringBuilder codeBuilder = new StringBuilder();

                for (int i = 1; i <= 7; i++) {
                    sink.tryEmitNext(ServerSentEvent.<String>builder()
                            .event("progress")
                            .data(String.valueOf(i * 14))
                            .build());

                    String yamlForMethod = switch (i) {
                        case 1 -> extractYamlSections(yamlNode, List.of("request"));
                        case 2, 3 -> extractYamlSections(yamlNode, List.of("validations"));
                        case 4 -> extractYamlSections(yamlNode, List.of("dataEnrichment", "entities", "businessRules"));
                        case 5 -> extractYamlSections(yamlNode, List.of("businessRules", "stateMachine"));
                        case 6 -> extractYamlSections(yamlNode, List.of("calculations"));
                        case 7 -> extractYamlSections(yamlNode, List.of("request", "response"));
                        default -> tbrdYaml;
                    };

                    // Strip loggers to save context tokens, but KEEP previous code
                    String previousCode = stripLoggers(codeBuilder.toString());

                    String body = generateMethodBody(i, yamlForMethod, previousCode);
                    String methodBlock = "// START BODY " + i + "\n" + METHOD_SIGNATURES[i] + "\n" + body + "\n";
                    codeBuilder.append(methodBlock).append("\n");

                    sink.tryEmitNext(ServerSentEvent.<String>builder()
                            .event("method")
                            .data(methodBlock.replace("\n", "\\n"))
                            .build());
                }

                String generatedCode = codeBuilder.toString().trim();
                String fullCode = appendExecuteMethod(generatedCode);
                String serviceName = determineServiceName(yamlNode, "GeneratedService");
                String completeClass = wrapInServiceTemplate(fullCode, serviceName);

                sink.tryEmitNext(ServerSentEvent.<String>builder()
                        .event("complete")
                        .data(completeClass.replace("\n", "\\n"))
                        .build());
                sink.tryEmitComplete();

            } catch (Exception e) {
                log.error("Streaming generation failed", e);
                sink.tryEmitNext(ServerSentEvent.<String>builder()
                        .event("error")
                        .data(e.getMessage() != null ? e.getMessage() : "Internal Error")
                        .build());
                sink.tryEmitComplete();
            }
        });

        executionThread.setDaemon(true);
        executionThread.start();

        return sink.asFlux();
    }

    // ===================================================================
    // SAVE TO FILE (NO GIT) - DIRECT YAML PAYLOAD
    // ===================================================================
    @PostMapping(value = "/generate-and-save", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> generateAndSaveToFile(@RequestBody String tbrdYaml) {
        try {
            String completeClass = generateFullServiceClassFromYaml(tbrdYaml);
            JsonNode yamlNode = new ObjectMapper(new YAMLFactory()).readTree(tbrdYaml);
            String serviceName = determineServiceName(yamlNode, "GeneratedService");

            Path outputDir = Path.of(System.getProperty("user.dir"), "generated_services");
            Files.createDirectories(outputDir);
            Path filePath = outputDir.resolve(serviceName + ".java");
            Files.writeString(filePath, completeClass);

            return ResponseEntity.ok("Service class saved to: " + filePath.toAbsolutePath());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Generation failed: " + e.getMessage());
        }
    }

    // ===================================================================
    // CLONE + GENERATE + SAVE - EXPECTS tbrdYaml IN MAP
    // ===================================================================
    @PostMapping("/clone-and-generate")
    public ResponseEntity<String> cloneAndGenerate(@RequestBody Map<String, String> request) {
        String baseBranch = request.get("baseBranch");
        String newBranchName = request.get("newBranchName");
        String tbrdYaml = request.get("tbrdYaml");

        if (baseBranch == null || newBranchName == null || tbrdYaml == null) {
            return ResponseEntity.badRequest().body("Missing required fields: baseBranch, newBranchName, tbrdYaml");
        }

        try {
            String fullClassContent = generateFullServiceClassFromYaml(tbrdYaml);
            JsonNode yamlNode = new ObjectMapper(new YAMLFactory()).readTree(tbrdYaml);
            String serviceName = determineServiceName(yamlNode, newBranchName);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path workspacePath = Path.of(System.getProperty("user.dir"), "cloned_repositories", newBranchName + "_" + timestamp);
            File localRepoDir = workspacePath.toFile();

            try (Git git = Git.cloneRepository()
                    .setURI(REPO_URL)
                    .setDirectory(localRepoDir)
                    .setCloneAllBranches(true)
                    .setBranch(baseBranch)
                    .call()) {

                git.branchCreate().setName(newBranchName).call();
                git.checkout().setName(newBranchName).call();

                Path submodulePath = workspacePath.resolve(Path.of("data-entity-services", serviceName));
                Path packageDirPath = submodulePath.resolve(Path.of("src", "main", "java", "com", "sim", "spriced", "application", "service"));
                Path resourceDirPath = submodulePath.resolve(Path.of("src", "main", "resources"));
                Files.createDirectories(packageDirPath);
                Files.createDirectories(resourceDirPath);

                Files.writeString(packageDirPath.resolve(serviceName + ".java"), fullClassContent);
                Files.writeString(resourceDirPath.resolve("application.properties"), "spring.application.name=" + serviceName + "\n");
                Files.writeString(submodulePath.resolve("pom.xml"), getPomTemplate(serviceName));

                return ResponseEntity.ok("Service class generated and saved to: " + packageDirPath.resolve(serviceName + ".java").toAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Clone-and-generate failed", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    // ===================================================================
    // SHARED GENERATION LOGIC
    // ===================================================================
    private String generateFullServiceClassFromYaml(String tbrdYaml) throws Exception {
        log.debug("Method generateFullServiceClassFromYaml invoked");
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        JsonNode yamlNode = yamlMapper.readTree(tbrdYaml);
        StringBuilder codeBuilder = new StringBuilder();

        for (int i = 1; i <= 7; i++) {
            String yamlForMethod = switch (i) {
                case 1 -> extractYamlSections(yamlNode, List.of("request"));
                case 2, 3 -> extractYamlSections(yamlNode, List.of("validations"));
                case 4 -> extractYamlSections(yamlNode, List.of("dataEnrichment", "entities", "businessRules"));
                case 5 -> extractYamlSections(yamlNode, List.of("businessRules", "stateMachine"));
                case 6 -> extractYamlSections(yamlNode, List.of("calculations"));
                case 7 -> extractYamlSections(yamlNode, List.of("request", "response"));
                default -> tbrdYaml;
            };

            String previousCode = stripLoggers(codeBuilder.toString());

            String body = generateMethodBody(i, yamlForMethod, previousCode);
            String methodBlock = "// START BODY " + i + "\n" + METHOD_SIGNATURES[i] + "\n" + body + "\n";
            codeBuilder.append(methodBlock).append("\n");
        }

        String generatedCode = codeBuilder.toString().trim();
        String fullCode = appendExecuteMethod(generatedCode);
        return wrapInServiceTemplate(fullCode, determineServiceName(yamlNode, "GeneratedService"));
    }

    // ===================================================================
    // HELPER METHODS
    // ===================================================================
    private String extractYamlSections(JsonNode yaml, List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            JsonNode node = yaml.path(key);
            if (!node.isMissingNode()) {
                sb.append(key).append(":\n");
                try {
                    ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                    StringWriter sw = new StringWriter();
                    yamlMapper.writeValue(sw, node);
                    sb.append(sw.toString().indent(2));
                } catch (Exception e) {
                    sb.append(node.toString()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String stripLoggers(String code) {
        if (code == null || code.isEmpty()) return "";
        // Aggressively remove all logger.xxx(...) calls and empty lines to save tokens
        String stripped = code.replaceAll("logger\\.(info|debug|warn|error|trace)\\([^;]+\\);", "");
        stripped = stripped.replaceAll("(?m)^\\s*$\\n", "");
        return stripped.trim();
    }

    private String generateMethodBody(int methodNumber, String yaml, String previousCode) {
        String prompt = buildPrompt(methodNumber, yaml, previousCode);
        String rawResponse = ollamaService.generate(prompt);
        return cleanMethodBody(rawResponse);
    }

    private String cleanMethodBody(String raw) {
        String code = extractCodeFromMarkdown(raw).trim();
        code = removeSignatureBeforeBraces(code);
        code = sanitizeMethodBody(code);

        int start = code.indexOf('{');
        if (start == -1) return "{\n" + code + "\n}";
        int count = 0, end = -1;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') count++;
            else if (c == '}') {
                count--;
                if (count == 0) { end = i; break; }
            }
        }
        code = (end >= 0) ? code.substring(start, end + 1) : "{\n" + code.substring(start) + "\n}";
        if (!code.startsWith("{")) code = "{" + code;
        if (!code.endsWith("}")) code = code + "\n}";
        return code;
    }

    private String sanitizeMethodBody(String code) {
        if (code == null) return "{\n}";
        if (code.contains("\"response\":") || code.contains("\"success\":") || code.contains("\"payload\":")) {
            log.warn("Detected illegal JSON structure in LLM output. Sanitizing and resetting body.");
            return """
                   {
                       logger.info("Executing generated block");
                       return ValidationStatus.VALIDATION_SUCCESS;
                   }
                   """;
        }
        return code;
    }

    private String removeSignatureBeforeBraces(String text) {
        int braceIndex = text.indexOf('{');
        if (braceIndex == -1) return text;
        String before = text.substring(0, braceIndex).trim();
        if (!before.isEmpty() &&
                (before.contains("private") || before.contains("public") || before.contains("protected")
                        || before.contains("ValidationStatus") || before.contains("void") || before.contains("List<") || before.contains("Map<"))) {
            return text.substring(braceIndex);
        }
        return text;
    }

    private String extractCodeFromMarkdown(String text) {
        Pattern pattern = Pattern.compile("```(?:java)?\\s*([\\s\\S]*?)\\s*```", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : text;
    }

    private String appendExecuteMethod(String code) {
        return code + """
                
                @Override
                public TransactionData execute(PlatformContext context, TransactionData data) {
                    logger.info("Entering execute()");
                    
                    Map<String, Object> requestData = extractInputPayload(data);
                    logger.debug("requestData assigned = {}", requestData);
                    
                    ValidationStatus technicalValidationStatus = performTechnicalValidations(data, requestData);
                    logger.debug("technicalValidationStatus assigned = {}", technicalValidationStatus);
                    
                    if (technicalValidationStatus == ValidationStatus.VALIDATION_FAILED) {
                        logger.info("Exiting execute() - Technical validation failed");
                        return data;
                    }
                    
                    ValidationStatus functionalValidationStatus = performFunctionalValidations(data, requestData);
                    logger.debug("functionalValidationStatus assigned = {}", functionalValidationStatus);
                    
                    if (functionalValidationStatus == ValidationStatus.VALIDATION_FAILED) {
                        logger.info("Exiting execute() - Functional validation failed");
                        return data;
                    }
                    
                    Map<String, Object> enrichedData = fetchEnrichedData(context, data, requestData);
                    logger.debug("enrichedData processing complete");
                    
                    evaluateBusinessRules(context, data, requestData, enrichedData);
                    logger.debug("Business rules evaluation completed");
                    
                    Double calcVal = calculations(context, data, requestData, enrichedData);
                    logger.debug("calcVal assigned = {}", calcVal);
                    
                    TransactionData response = prepareResponseOutput(context, data, requestData, calcVal);
                    logger.debug("response generated");
                    
                    logger.info("Exiting execute()");
                    return response;
                }
                """;
    }

    private String determineServiceName(JsonNode yaml, String defaultName) {
        JsonNode serviceNode = yaml.path("service");
        if (!serviceNode.isMissingNode()) {
            String name = serviceNode.path("name").asText();
            if (!name.isEmpty()) return name;
        }
        return defaultName;
    }

    private String wrapInServiceTemplate(String generatedCode, String serviceName) {
        return """
                package com.sim.spriced.application.service;

                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.google.common.collect.ArrayListMultimap;
                import com.sim.spriced.platform.BusinessInterface.ApplicationInterface;
                import com.sim.spriced.platform.BusinessInterface.PlatformContext;
                import com.sim.spriced.platform.BusinessInterface.TransactionData;
                import com.sim.spriced.platform.commons_management_layer.Enums.ValidationStatus;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Service;
                
                import java.sql.Timestamp;
                import java.util.*;
                import java.util.concurrent.*;
                import java.util.stream.*;
                import java.math.BigDecimal;
                import java.math.RoundingMode;

                @Service("%s/1.0")
                public class %s implements ApplicationInterface {

                    private static final Logger logger = LoggerFactory.getLogger(%s.class);

                    %s

                    @Override
                    public ValidationStatus validate(TransactionData message) {
                        return ValidationStatus.VALIDATION_SUCCESS;
                    }

                    @Override
                    public TransactionData transform(TransactionData requestData) {
                        return requestData;
                    }
                }
                """.formatted(serviceName, serviceName, serviceName, generatedCode);
    }

    private String getPomTemplate(String artifactName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.sim.spriced.application</groupId>
                        <artifactId>data-entity-services</artifactId>
                        <version>2.0.101-SNAPSHOT</version>
                        <relativePath>../pom.xml</relativePath>
                    </parent>
                    <groupId>com.sim.spriced.application</groupId>
                    <artifactId>%s</artifactId>
                    <version>3.0.0-SNAPSHOT</version>
                    <name>%s</name>
                    <description>%s</description>
                    <properties>
                        <java.version>21</java.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-test</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <executions>
                                    <execution>
                                        <id>repackage</id>
                                        <phase>none</phase>
                                    </execution>
                                </executions>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(artifactName, artifactName, artifactName);
    }

    // ===================================================================
    // STRICT CONCISE PROMPTS FOR GPT-OSS:20B (WITH CONTEXT)
    // ===================================================================
    private String buildPrompt(int methodNumber, String yaml, String previousCode) {
        String baseRules = """
                You are a strict Java backend generator. Output ONLY the raw Java code inside the { }. DO NOT output markdown. NO explanations.

                STRICT RULES:
                1. NO POJOS: Do NOT invent classes like `Header`, `DataAreaItem`, or `Part`. Cast strictly to `Map<String, Object>` or `List<Map<String, Object>>`.
                2. NO EXTERNAL LIBS: Do NOT use `Gson` or `Jackson`. Use standard Java Collections.
                3. NO VARIABLE SHADOWING: The variable `Map<String, Object> requestData` is already defined in the method signature. DO NOT redeclare it inside the method body. Use `reqData` or similar if you need a new Map.
                4. NO LOOPS: `for`, `while`, and `do-while` loops are STRICTLY FORBIDDEN. You MUST use Java 8+ Streams (`.stream().map().filter().forEach()`).
                5. LOGGING: Log method entry/exit with `logger.info()`. Log EVERY variable assignment immediately with `logger.debug()`. Log catch blocks with `logger.warn()`.
                6. DB RULE: If <TARGET_YAML> states `exactly_match: true`, use `context.fetchDataFromDB(...)`. Otherwise, use `context.streamDataFromDB(...)`.
                7. DO NOT REWRITE <PREVIOUS_CODE>. Read it for context, but ONLY output the new method logic.
                """;

        return switch (methodNumber) {
            case 1 -> baseRules + """
                    TASK: Extract the fields mapped in <TARGET_YAML>. DO NOT perform validation. DO NOT throw exceptions.
                    
                    TEMPLATE TO COMPLETE:
                    {
                        logger.info("Entering extractInputPayload()");
                        Map<String, Object> reqData = data.getRequestData() != null ? data.getRequestData() : new HashMap<>();
                        logger.debug("reqData assigned = {}", reqData);
                        
                        // WRITE LOGIC HERE: Extract Header and DataArea using reqData.get()
                        
                        logger.info("Exiting extractInputPayload()");
                        return reqData;
                    }

                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>""";

            case 2 -> baseRules + """
                    TASK: Implement technical validations based strictly on <TARGET_YAML>.
                    
                    TEMPLATE TO COMPLETE:
                    {
                        logger.info("Entering performTechnicalValidations()");
                        // WRITE LOGIC HERE: Validate requestData using Streams. 
                        // If validation fails, return ValidationStatus.VALIDATION_FAILED;
                        
                        logger.info("Exiting performTechnicalValidations()");
                        return ValidationStatus.VALIDATION_SUCCESS;
                    }

                    <PREVIOUS_CODE>
                    """ + (previousCode.isEmpty() ? "none" : previousCode) + """
                    </PREVIOUS_CODE>

                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>""";

            case 3 -> baseRules + """
                    TASK: Implement functional validations based strictly on <TARGET_YAML>.
                    
                    TEMPLATE TO COMPLETE:
                    {
                        logger.info("Entering performFunctionalValidations()");
                        // WRITE LOGIC HERE: Validate requestData using Streams.
                        // If validation fails, return ValidationStatus.VALIDATION_FAILED;
                        
                        logger.info("Exiting performFunctionalValidations()");
                        return ValidationStatus.VALIDATION_SUCCESS;
                    }

                    <PREVIOUS_CODE>
                    """ + (previousCode.isEmpty() ? "none" : previousCode) + """
                    </PREVIOUS_CODE>

                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>""";

            case 4 -> baseRules + """
                    TASK: Execute `dataEnrichment` DB lookups. You MUST use `CompletableFuture.runAsync` with Java Streams to perform lookups in parallel.
                    
                    TEMPLATE TO COMPLETE:
                    {
                        logger.info("Entering fetchEnrichedData()");
                        Map<String, Object> enrichedData = new ConcurrentHashMap<>();
                        logger.debug("enrichedData assigned = {}", enrichedData);
                        
                        // WRITE LOGIC HERE: Parallel DB fetches based on YAML. Follow DB LOOKUP RULE for fetch/stream methods.
                        
                        logger.info("Exiting fetchEnrichedData()");
                        return enrichedData; 
                    }
                    
                    <PREVIOUS_CODE>
                    """ + (previousCode.isEmpty() ? "none" : previousCode) + """
                    </PREVIOUS_CODE>
                    
                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>""";

            case 5 -> baseRules + """
                    TASK: Execute the `businessRules` block. 
                    RESTRICTION: This method MUST return `void`. Mutate `requestData` or `enrichedData` in place.
                    
                    TEMPLATE TO COMPLETE:
                    {
                        logger.info("Entering evaluateBusinessRules()");
                        
                        // WRITE LOGIC HERE: Process parallel streams and mutate maps in place. Do NOT return anything.
                        
                        logger.info("Exiting evaluateBusinessRules()");
                    }
                    
                    <PREVIOUS_CODE>
                    """ + (previousCode.isEmpty() ? "none" : previousCode) + """
                    </PREVIOUS_CODE>
                    
                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>""";

            case 6 -> baseRules + """
                    TASK: Execute the `calculations` block. 
                    RESTRICTION: You MUST return a `Double`. Store any complex Map/List results inside `requestData` via `.put()`.
                    
                    TEMPLATE TO COMPLETE:
                    {
                        logger.info("Entering calculations()");
                        Double result = 0.0D;
                        logger.debug("result assigned = {}", result);
                        
                        // WRITE LOGIC HERE: Execute calculations using Streams.
                        
                        logger.info("Exiting calculations()");
                        return result;
                    }
                    
                    <PREVIOUS_CODE>
                    """ + (previousCode.isEmpty() ? "none" : previousCode) + """
                    </PREVIOUS_CODE>
                    
                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>""";

            case 7 -> baseRules + """
                    TASK: Build the final Response Map and Audit logging based on the `response` section of <TARGET_YAML>.
                    RESTRICTION: You MUST return `data`. Do NOT return a Map. Use `data.setResponse()` to attach your maps.
                    
                    TEMPLATE TO COMPLETE:
                    {
                        logger.info("Entering prepareResponseOutput()");
                        
                        // WRITE LOGIC HERE: 
                        // 1. Build responseMap using Map/List
                        // 2. data.setResponse("Response", Collections.singletonList(responseMap));
                        // 3. Build auditList using Map/List
                        // 4. data.setResponse("YOUR_AUDIT_TABLE_NAME", auditList);
                        
                        logger.info("Exiting prepareResponseOutput()");
                        return data;
                    }
                    
                    <PREVIOUS_CODE>
                    """ + (previousCode.isEmpty() ? "none" : previousCode) + """
                    </PREVIOUS_CODE>
                    
                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>""";

            default -> "";
        };
    }
}