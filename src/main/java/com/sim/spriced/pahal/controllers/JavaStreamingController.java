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
            "private void extractInputPayload(TransactionData data) ",
            "private ValidationStatus performTechnicalValidations(TransactionData data) ",
            "private ValidationStatus performFunctionalValidations(TransactionData data) ",
            "private List<Map<String, Object>> fetchEnrichedData(PlatformContext context, TransactionData data) ",
            "private void evaluateBusinessRules(PlatformContext context, TransactionData data, Map<String, Object> enrichedData) ",
            "private Double calculations(PlatformContext context, TransactionData data, Map<String, Object> enrichedData) ",
            "private TransactionData prepareResponseOutput(PlatformContext context, TransactionData data, Double calculatedValue) "
    };

    public JavaStreamingController(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    // ===================================================================
    // STREAMING ENDPOINT
    // ===================================================================
    @PostMapping(value = "/generate-java-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamJavaGeneration(@RequestBody String tbrdYaml) {
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();
        new Thread(() -> {
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
                        case 1, 2, 3, 4 -> tbrdYaml;
                        case 5 -> extractYamlSections(yamlNode, List.of("businessRules", "stateMachine"));
                        case 6 -> extractYamlSections(yamlNode, List.of("request", "dataEnrichment", "calculations"));
                        case 7 -> extractYamlSections(yamlNode, List.of("request", "response"));
                        default -> tbrdYaml;
                    };

                    String previousCode = codeBuilder.toString();
                    if (i > 4) previousCode = stripLoggers(previousCode);

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
                        .data(e.getMessage())
                        .build());
                sink.tryEmitComplete();
            }
        }).start();
        return sink.asFlux();
    }

    // ===================================================================
    // SAVE TO FILE (NO GIT)
    // ===================================================================
    @PostMapping("/generate-and-save")
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
    // CLONE + GENERATE + SAVE (RESTORES OLD FUNCTIONALITY)
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
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        JsonNode yamlNode = yamlMapper.readTree(tbrdYaml);
        StringBuilder codeBuilder = new StringBuilder();

        for (int i = 1; i <= 7; i++) {
            String yamlForMethod = switch (i) {
                case 1, 2, 3, 4 -> tbrdYaml;
                case 5 -> extractYamlSections(yamlNode, List.of("businessRules", "stateMachine"));
                case 6 -> extractYamlSections(yamlNode, List.of("request", "dataEnrichment", "calculations"));
                case 7 -> extractYamlSections(yamlNode, List.of("request", "response"));
                default -> tbrdYaml;
            };

            String previousCode = codeBuilder.toString();
            if (i > 4) previousCode = stripLoggers(previousCode);

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
        if (code == null) return "";
        StringBuilder stripped = new StringBuilder();
        for (String line : code.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("logger.") && !trimmed.startsWith("//")) {
                stripped.append(line).append("\n");
            }
        }
        return stripped.toString().trim();
    }

    private String generateMethodBody(int methodNumber, String yaml, String previousCode) {
        String prompt = buildPrompt(methodNumber, yaml, previousCode);
        String rawResponse = ollamaService.generate(prompt);
        return cleanMethodBody(rawResponse);
    }

    private String cleanMethodBody(String raw) {
        String code = extractCodeFromMarkdown(raw).trim();
        code = removeSignatureBeforeBraces(code);
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

    private String removeSignatureBeforeBraces(String text) {
        int braceIndex = text.indexOf('{');
        if (braceIndex == -1) return text;
        String before = text.substring(0, braceIndex).trim();
        if (!before.isEmpty() &&
                (before.contains("private") || before.contains("public") || before.contains("protected")
                        || before.contains("ValidationStatus") || before.contains("void") || before.contains("List<"))) {
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
        String executeMethod = """

                @Override
                public TransactionData execute(PlatformContext context, TransactionData data) {
                    logger.info("Entering execute");

                    logger.debug("Invoking extractInputPayload()");
                    extractInputPayload(data);
                    logger.debug("Completed extractInputPayload()");

                    logger.debug("Invoking performTechnicalValidations()");
                    ValidationStatus techStatus = performTechnicalValidations(data);
                    logger.debug("Technical validation status = {}", techStatus);

                    if (techStatus != ValidationStatus.VALIDATION_SUCCESS) {
                        logger.error("Technical validation failed: {}", techStatus);
                        logger.info("Exiting execute due to technical validation failure");
                        return data;
                    }

                    logger.debug("Invoking performFunctionalValidations()");
                    ValidationStatus funcStatus = performFunctionalValidations(data);
                    logger.debug("Functional validation status = {}", funcStatus);

                    if (funcStatus != ValidationStatus.VALIDATION_SUCCESS) {
                        logger.error("Functional validation failed: {}", funcStatus);
                        logger.info("Exiting execute due to functional validation failure");
                        return data;
                    }

                    logger.debug("Invoking fetchEnrichedData()");
                    Map<String, Object> enrichedData = fetchEnrichedData(context, data);
                    logger.debug("Enriched data = {}", enrichedData);

                    logger.debug("Invoking evaluateBusinessRules()");
                    evaluateBusinessRules(context, data, enrichedData);
                    logger.debug("Completed evaluateBusinessRules()");

                    logger.debug("Invoking calculations()");
                    Double calculatedValue = calculations(context, data, enrichedData);
                    logger.debug("Calculated value = {}", calculatedValue);

                    logger.debug("Invoking prepareResponseOutput()");
                    prepareResponseOutput(context, data, calculatedValue);
                    logger.debug("Completed prepareResponseOutput()");

                    logger.info("Exiting execute");
                    return data;
                }
                """;
        return code + "\n\n" + executeMethod;
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

                import com.google.common.collect.ArrayListMultimap;
                import com.sim.spriced.platform.BusinessInterface.ApplicationInterface;
                import com.sim.spriced.platform.BusinessInterface.PlatformContext;
                import com.sim.spriced.platform.BusinessInterface.TransactionData;
                import com.sim.spriced.platform.commons_management_layer.Enums.ValidationStatus;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Service;
                import java.util.*;
                import java.util.stream.*;

                @Service("%s/1.0")
                public class %s implements ApplicationInterface {

                    private static final Logger logger = LoggerFactory.getLogger(%s.class);

                    %s

                    @Override
                    public ValidationStatus validate(TransactionData message) {
                        logger.info("Entering validate");
                        ValidationStatus status = ValidationStatus.VALIDATION_SUCCESS;
                        logger.debug("Validation status set to VALIDATION_SUCCESS");
                        logger.info("Exiting validate");
                        return status;
                    }

                    @Override
                    public TransactionData transform(TransactionData requestData) {
                        logger.info("Entering transform");
                        logger.info("Exiting transform");
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
    // PROMPTS (THE EXACT PROMPTS WE REFINED)
    // ===================================================================
    private String buildPrompt(int methodNumber, String yaml, String previousCode) {
        return switch (methodNumber) {
            case 1 -> """
                    You are a Java code generator. Output ONLY the body of a method, starting with '{' and ending with '}'.
                    Do NOT output the method signature or any other text. No markdown.

                    The method body must look exactly like this pattern, but with fields taken from the YAML's request section.

                    <EXAMPLE_BODY>
                    {
                        logger.info("Entered extractInputPayload()");
                        Map<String, Object> requestData = data.getRequestData();
                        // For each field in the YAML request.fields, extract and log like this:
                        // String fieldName = (String) requestData.get("field_name");
                        // logger.debug("fieldName = {}", fieldName);
                        logger.info("Exiting extractInputPayload()");
                    }
                    </EXAMPLE_BODY>

                    Now, read the <TARGET_YAML> below. Go to the "request" section, find the "fields" list.
                    For each field, produce one extraction line using the exact field name and type:
                    - If type is String, cast to (String)
                    - If type is Integer, cast to (Integer)
                    - If type is Decimal, cast to (Double)
                    Then log each variable with logger.debug, using the variable name in the message.

                    Insert these lines between the two logger.info calls exactly as shown in the example.
                    Output ONLY the complete method body, beginning with '{' and ending with '}'. No additional text.

                    <PREVIOUSLY_GENERATED_CODE>
                    """ + (previousCode.isEmpty() ? "(none)" : previousCode) + """
                    </PREVIOUSLY_GENERATED_CODE>

                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>""";

            case 2 -> """
                    You are a Java code generator. Output ONLY the body of a method, starting with '{' and ending with '}'.
                    Do NOT output the method signature or any other text. No markdown.

                    The method body must look exactly like this pattern, but with validations taken from the YAML's validations.technical section.

                    <EXAMPLE_BODY>
                    {
                        logger.info("Entered performTechnicalValidations()");
                        Map<String, Object> requestData = data.getRequestData();
                        // For each rule in validations.technical, generate an instanceof check:
                        // if (!(requestData.get("field_name") instanceof Integer)) {
                        //     logger.debug("field_name type check failed");
                        //     return ValidationStatus.HALT_TECHNICAL_ERR_XXX;
                        // }
                        logger.info("Exiting performTechnicalValidations()");
                        return ValidationStatus.VALIDATION_SUCCESS;
                    }
                    </EXAMPLE_BODY>

                    Now, read the <TARGET_YAML> below. Go to the "validations" section, then the "technical" list.
                    For each technical validation rule, extract the field name from the expression (the part inside TYPEOF(Request. ...)).
                    Check that the field is of the expected type using instanceof. The expected type is the one inside quotes after '==' (e.g., 'Integer').
                    On failure, return the ValidationStatus constant derived from the onFailure value: take the part after the last colon (e.g., ERR_XXX) and prefix with HALT_TECHNICAL_ (so it becomes HALT_TECHNICAL_ERR_XXX).
                    Only do this for rules whose expression starts with TYPEOF. Ignore any other rules.
                    Output ONLY the method body, starting with '{' and ending with '}'. No additional text.

                    <PREVIOUSLY_GENERATED_CODE>
                    """ + (previousCode.isEmpty() ? "(none)" : previousCode) + """
                    </PREVIOUSLY_GENERATED_CODE>

                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>""";

            case 3 -> """
                    You are a Java code generator. Output ONLY the body of a method, starting with '{' and ending with '}'.
                    Do NOT output the method signature or any other text. No markdown.

                    The method body must implement ONLY the functional validations from the YAML's validations.functional list.
                    Do NOT add any business rules or other checks that are not in that list.

                    <EXAMPLE_BODY>
                    // Assume the YAML functional validations are:
                    //   - expression: "AND(Request.field1 >= 10, Request.field1 <= 100)"
                    //     onFailure: HALT_FUNCTIONAL:ERR_RANGE
                    //   - expression: "Request.field2 >= 20000"
                    //     onFailure: HALT_FUNCTIONAL:ERR_TOO_LOW

                    {
                        logger.info("Entered performFunctionalValidations()");
                        Map<String, Object> requestData = data.getRequestData();
                        Integer field1 = (Integer) requestData.get("field1");
                        Double field2 = (Double) requestData.get("field2");

                        // First validation (numeric)
                        Map<String, Double> vars1 = new HashMap<>();
                        vars1.put("field1", (double) field1);
                        double r1 = new BusinessRuleEvaluator("AND(field1 >= 10, field1 <= 100)", vars1).evaluate();
                        if (r1 == 0.0) {
                            logger.debug("field1 out of range");
                            return ValidationStatus.HALT_FUNCTIONAL_ERR_RANGE;
                        }

                        // Second validation (numeric)
                        Map<String, Double> vars2 = new HashMap<>();
                        vars2.put("field2", field2);
                        double r2 = new BusinessRuleEvaluator("field2 >= 20000", vars2).evaluate();
                        if (r2 == 0.0) {
                            logger.debug("field2 insufficient");
                            return ValidationStatus.HALT_FUNCTIONAL_ERR_TOO_LOW;
                        }

                        logger.info("Exiting performFunctionalValidations()");
                        return ValidationStatus.VALIDATION_SUCCESS;
                    }
                    </EXAMPLE_BODY>

                    Now implement the functional validations for the <TARGET_YAML> below.
                    Strictly follow these steps for each rule in validations.functional:

                    1. **Extract the expression**. Remove any `Request.` prefix from variable names: e.g., `Request.field1` becomes `field1`. That is the variable name used in the evaluator map.
                    2. **Build a `Map<String, Double>`** containing the needed variables from requestData (field names exactly as in request section, cast to double). Use `new HashMap<>()`.
                    3. **Call `new BusinessRuleEvaluator(expression_without_Request_prefix, map).evaluate()`**.
                    4. **If result == 0.0**, construct the ValidationStatus constant:
                       - Take the `onFailure` string (e.g., "HALT_FUNCTIONAL:ERR_RANGE").
                       - Remove the "HALT_FUNCTIONAL:" prefix → `ERR_RANGE`.
                       - Prefix with `HALT_FUNCTIONAL_` → `HALT_FUNCTIONAL_ERR_RANGE`.
                       - Return that constant.
                    5. **If the expression contains string literals** (single quotes), use `.equals()` and return the corresponding constant similarly.
                    6. Do NOT add any validations that are not present in the YAML list.

                    Output ONLY the method body, starting with '{' and ending with '}'.

                    <PREVIOUSLY_GENERATED_CODE>
                    """ + (previousCode.isEmpty() ? "(none)" : previousCode) + """
                    </PREVIOUSLY_GENERATED_CODE>

                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>""";

            case 4 -> """
                    You are a Java code generator. Output ONLY the body of a method, starting with '{' and ending with '}'.
                    Do NOT output the method signature or any other text. No markdown.

                    The method body must implement data enrichment exactly as specified in the YAML's dataEnrichment section.
                    Use ONLY the allowed classes: TransactionData, PlatformContext, ArrayListMultimap, ArrayList, List, Map, HashMap, String, Object.
                    No other classes, no reference to the YAML itself.

                    **Determine the exactly_match behavior** directly from the YAML:
                    - If the enrichment has `exactly_match: true`, you MUST include a check that throws `new IllegalStateException("Expected exactly one record")` if the result size is not 1.
                    - If it is `false` or absent, you MUST NOT include that check.

                    <EXAMPLE_BODY_EXACTLY_MATCH_FALSE>
                    // exactly_match is false in this YAML
                    {
                        logger.info("Entered fetchEnrichedData()");
                        Map<String, Object> requestData = data.getRequestData();
                        String idValue = (String) requestData.get("id");
                        ArrayListMultimap<String, Object> filterParams = ArrayListMultimap.create();
                        filterParams.put("id", idValue);
                        List<Map<String, Object>> results = context.fetchDataFromDB("primary_deam", "enrichment_filter", filterParams);
                        logger.debug("results.size() = {}", results != null ? results.size() : 0);
                        if (results == null || results.isEmpty()) {
                            return new ArrayList<>();
                        }
                        logger.info("Exiting fetchEnrichedData()");
                        return results;
                    }
                    </EXAMPLE_BODY_EXACTLY_MATCH_FALSE>

                    <EXAMPLE_BODY_EXACTLY_MATCH_TRUE>
                    // exactly_match is true in this YAML
                    {
                        logger.info("Entered fetchEnrichedData()");
                        Map<String, Object> requestData = data.getRequestData();
                        String idValue = (String) requestData.get("id");
                        ArrayListMultimap<String, Object> filterParams = ArrayListMultimap.create();
                        filterParams.put("id", idValue);
                        List<Map<String, Object>> results = context.fetchDataFromDB("primary_deam", "enrichment_filter", filterParams);
                        logger.debug("results.size() = {}", results != null ? results.size() : 0);
                        if (results == null || results.size() != 1) {
                            throw new IllegalStateException("Expected exactly one record");
                        }
                        logger.info("Exiting fetchEnrichedData()");
                        return results;
                    }
                    </EXAMPLE_BODY_EXACTLY_MATCH_TRUE>

                    Now, read the <TARGET_YAML> below. Implement the enrichment using the correct example template based on the exactly_match value you find.
                    - The filter field name is the value of `where.field`.
                    - The request data key is the `where.valueSource` with the `Request.` prefix removed.
                    - Always call `context.fetchDataFromDB` with `"primary_deam"` and `"enrichment_filter"`.
                    - Return the list directly, do not extract specific produced fields.

                    Output ONLY the method body, starting with '{' and ending with '}'. No additional text.

                    <PREVIOUSLY_GENERATED_CODE>
                    """ + (previousCode.isEmpty() ? "(none)" : previousCode) + """
                    </PREVIOUSLY_GENERATED_CODE>

                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>""";

            case 5 -> """
                    Output ONLY the body of a method, starting with '{' and ending with '}'. No other text.

                    You are a Java code generator. Implement the blocking business rules and state transitions described in the YAML that follows.

                    **STRICT RULES – VIOLATING ANY OF THESE MAKES THE OUTPUT INVALID**
                    1. Use ONLY these classes: HashMap, ArrayList, BusinessRuleEvaluator, BusinessRuleRejectionException.
                       `transitionService` is already available.
                    2. Never call fetchEnrichedData. Use the `enrichedData` parameter directly.
                    3. Never call transitionService.getContext() or any other method on transitionService except
                       `transitionService.processCommand(id, command, context)`.
                    4. Always pass the `context` parameter (the one the method receives) as the third argument to processCommand.
                    5. Skip ANY rule whose condition is empty or whose actions contain only PUBLISH_EVENT or are empty.
                    6. Do NOT generate code for rules that are entry/exit actions of the state machine (they are handled elsewhere).
                    7. Hardcode EVERY field name, expression, error code, entity name, etc. directly from the YAML.
                       Do NOT write loops over the YAML – each rule becomes an explicit if‑block.
                    8. **ABSOLUTELY NO PLACEHOLDER STRINGS** – The output must NOT contain any placeholder text like "EnrichmentName", "fieldName", "dbField", "entityName", "guardCommand", "initialCommand", "firstFieldName", etc. Every string must be an actual value taken from the YAML. If you see such a placeholder in the template below, replace it with the correct value.

                    **CRITICAL: The variable used as the `id` in processCommand MUST be the first field you extracted from requestData.**
                       Always refer to it by the name you gave it (e.g., if you did `String someId = ...`, use `someId`). Never use a bare, undefined `id`.

                    **CRITICAL VALUE MAPPINGS – READ THESE EXACTLY FROM THE YAML**
                    - Enrichment key: the value of the `name` field inside the `dataEnrichment` entry. Do NOT use "primary_deam".
                    - Entity name in setResponse: the `entity` field from the DB_UPDATE action's params, not the stateMachine entity.
                    - Field names and valueSources: exactly as they appear in the YAML (strip only surrounding single quotes if present).

                    **MANDATORY STRUCTURE – fill in the placeholders with values from the YAML (the template below uses generic names like "EnrichmentName", "fieldName" etc. – YOU MUST REPLACE THEM WITH ACTUAL VALUES FROM THE YAML)**

                    {
                        logger.info("Entered evaluateBusinessRules()");
                        Map<String, Object> requestData = data.getRequestData();
                        // Extract exactly these request fields (names and types from PREVIOUSLY_GENERATED_CODE):
                        Type firstField = (Type) requestData.get("firstFieldName");   // this is the ID field – replace "firstFieldName" with the actual first field name
                        Type field2 = (Type) requestData.get("field2");
                        ...

                        // Rule1: numeric condition → RETURN_ERROR
                        Map<String,Double> v1 = new HashMap<>();
                        v1.put("var1", (double) fieldX);   // var names MUST match the expression
                        v1.put("var2", fieldY);
                        if (new BusinessRuleEvaluator("numeric_expression", v1).evaluate() != 0.0) {
                            throw new BusinessRuleRejectionException("ERROR_CODE");
                        }

                        // Rule2: string condition + DB_UPDATE + RETURN_ERROR (guard)
                        List<Map<String,Object>> enrichList = (List<Map<String,Object>>) enrichedData.get("EnrichmentName");
                        if (enrichList != null && !enrichList.isEmpty()) {
                            String val = (String) enrichList.get(0).get("fieldName");
                            if ("EXPECTED".equals(val)) {
                                Map<String,Object> dm = new HashMap<>();
                                dm.put("dbField", "value");
                                Map<String,Object> rm = new HashMap<>();
                                rm.put("operation", "update");
                                rm.put("data", dm);
                                List<Map<String,Object>> rl = new ArrayList<>();
                                rl.add(rm);
                                data.setResponse("entityName", rl);
                                // Guard command from state machine (use transition command or alternateTransition)
                                transitionService.processCommand(firstField, "guardCommand", context);
                                throw new BusinessRuleRejectionException("ERROR_CODE");
                            }
                        }

                        // After ALL blocking rules pass, trigger the initial state transition.
                        // Use the command of the transition where "from" equals initialState (the first such transition).
                        transitionService.processCommand(firstField, "initialCommand", context);

                        logger.info("Exiting evaluateBusinessRules()");
                    }

                    **REMEMBER: Every placeholder in the template above (like EnrichmentName, fieldName, dbField, value, entityName, guardCommand, initialCommand, firstFieldName, field2, var1, var2, numeric_expression, ERROR_CODE, EXPECTED, etc.) must be replaced with actual values from the YAML. Do not leave any placeholder text in the output.**

                    **ADDITIONAL CLARIFICATIONS**
                    - Do not include any logger.debug lines – only the two logger.info calls shown.
                    - The PREVIOUSLY_GENERATED_CODE below shows the exact field names and types – use them exactly.

                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>

                    <PREVIOUSLY_GENERATED_CODE>
                    """ + (previousCode.isEmpty() ? "(none)" : previousCode) + """
                    </PREVIOUSLY_GENERATED_CODE>
                    """;

            case 6 -> """
                    Output ONLY the body of a method, starting with '{' and ending with '}'. No other text.

                    You are a Java code generator. Implement ALL calculations from the YAML's calculations section.
                    Use ONLY the allowed classes: TransactionData, PlatformContext, Map, HashMap, ArrayList, List,
                    BusinessRuleEvaluator, Double, Integer, String.

                    **CRITICAL RULES – ANY VIOLATION MAKES THE OUTPUT INVALID**
                    1. NEVER call fetchEnrichedData. Use the `enrichedData` parameter directly.
                    2. **All mathematical operations, functions (ROUND, ABS, POWER, etc.), and logical conditions (AND, OR, >, <, etc.) MUST be evaluated exclusively through `new BusinessRuleEvaluator(expression, map).evaluate()`**.
                       Do NOT write raw Java arithmetic or logic (like `if (a >= b)`, `ROUND(...)`, `Math.round(...)`, `Math.abs(...)`, `Math.pow(...)`, etc.). Everything goes through the evaluator.
                    3. Store each computed result in requestData using the exact variable name given in the YAML (the `variable` field under `compute`).
                    4. All local variables must be declared before use. The variable returned at the end must be in scope at the return statement.
                    5. In DB_UPDATE side effects, ONLY include the fields listed in the YAML's `sideEffects`. Do NOT add extra fields like `application_id` unless specified.
                    6. Implement EVERY calculation rule in order. If there are multiple calculations, generate code for all.
                    7. The returned value must be the result of the LAST calculation.

                    **MANDATORY STRUCTURE – fill in the placeholders with values from the YAML**

                    {
                        logger.info("Entered calculations()");
                        Map<String, Object> requestData = data.getRequestData();
                        // Extract request fields (names and types from PREVIOUSLY_GENERATED_CODE)
                        Type field1 = (Type) requestData.get("field1");
                        Type field2 = (Type) requestData.get("field2");

                        // Retrieve enrichment list if needed (use the enrichment name from the YAML)
                        List<Map<String, Object>> enrichList = (List<Map<String, Object>>) enrichedData.get("EnrichmentName");

                        // Aggregate any needed fields from the enrichment list (e.g., sum of a field)
                        int aggregatedValue = 0;
                        if (enrichList != null) {
                            aggregatedValue = enrichList.stream().mapToInt(row -> (Integer) row.get("fieldN")).sum();
                        }

                        // --- First calculation ---
                        Map<String, Double> vars1 = new HashMap<>();
                        vars1.put("var1", (double) field1);
                        vars1.put("var2", (double) aggregatedValue);
                        double result1 = 0.0;
                        // Evaluate condition using BusinessRuleEvaluator
                        double cond1 = new BusinessRuleEvaluator("condition_expression", vars1).evaluate();
                        if (cond1 != 0.0) {
                            result1 = new BusinessRuleEvaluator("compute_expression", vars1).evaluate();
                        } else {
                            // Second sub‑rule condition and computation (if present in YAML)
                            // double cond2 = new BusinessRuleEvaluator("alternative_condition", vars1).evaluate();
                            // if (cond2 != 0.0) {
                            //     result1 = new BusinessRuleEvaluator("alternative_expression", vars1).evaluate();
                            // }
                        }
                        requestData.put("variable1", result1);   // exact variable name from YAML

                        // Side effect DB_UPDATE for first calculation (if any) – only include the field(s) specified
                        // (use flat map pattern: dataMap.put("dbField", result1); ...)

                        // --- Second calculation (if present) ---
                        double prevValue = (Double) requestData.get("variable1");
                        Map<String, Double> vars2 = new HashMap<>();
                        vars2.put("var3", (double) field1);
                        vars2.put("var4", prevValue);
                        // The expression itself may contain the condition (e.g., if the YAML has a "TRUE" condition, just compute)
                        double result2 = new BusinessRuleEvaluator("compute_expression2", vars2).evaluate();
                        requestData.put("variable2", result2);

                        logger.info("Exiting calculations()");
                        return result2;   // last computed value, must be in scope
                    }

                    **FLAT MAP PATTERN FOR DB_UPDATE**
                    {
                        Map<String,Object> dataMap = new HashMap<>();
                        dataMap.put("dbField", computedValue);   // only the field(s) from YAML
                        Map<String,Object> respMap = new HashMap<>();
                        respMap.put("operation","update");
                        respMap.put("data",dataMap);
                        List<Map<String,Object>> rl = new ArrayList<>();
                        rl.add(respMap);
                        data.setResponse("entityName", rl);
                    }

                    **IMPORTANT**
                    - The PREVIOUSLY_GENERATED_CODE shows the exact request field names and types. Use them.
                    - The enrichment key is the `name` field of the dataEnrichment entry.
                    - Ensure all calculations from the YAML are implemented. If there are two separate calculations (e.g., compute_A and compute_B), you MUST generate code for both.
                    - The returned value must be the result of the last calculation, and it must be in scope.

                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>

                    <PREVIOUSLY_GENERATED_CODE>
                    """ + (previousCode.isEmpty() ? "(none)" : previousCode) + """
                    </PREVIOUSLY_GENERATED_CODE>
                    """;

            case 7 -> """
                    Output ONLY the body of a method, starting with '{' and ending with '}'. No other text.

                    You are a Java code generator. Implement the final database mutation(s) from the YAML's response.success.dbMutations section.

                    **STRICT RULES – VIOLATING ANY OF THESE MAKES THE OUTPUT INVALID**
                    1. Use ONLY these classes: TransactionData, PlatformContext, Map, HashMap, ArrayList, List, String, Double.
                       `data` and `calculatedValue` are already available.
                    2. NEVER use any YAML parsing classes (YAMLUtil, targetYaml, etc.) or loops that iterate over the YAML.
                       Hardcode EVERYTHING directly from the YAML.
                    3. The DB mutation(s) must be written exactly as shown in the pattern, with the entity name and field/value
                       taken directly from the YAML (strip single quotes from literal values, keep variable references as-is but extract them from requestData if needed).
                    4. After the mutations, always set:
                       requestData.put("execution_status", "SUCCESS");
                       requestData.put("processed_value", calculatedValue);
                    5. Return data.

                    **MANDATORY PATTERN – fill in the placeholders with values from the YAML**

                    {
                        logger.info("Entered prepareResponseOutput()");
                        Map<String, Object> requestData = data.getRequestData();

                        // Mutation 1 (if multiple, repeat the block)
                        Map<String, Object> dataMap = new HashMap<>();
                        dataMap.put("field1", "value1");   // field and value from YAML (strip quotes from string literals)
                        // dataMap.put("field2", ...);     // if there are multiple fields in the set list
                        Map<String, Object> respMap = new HashMap<>();
                        respMap.put("operation", "update");
                        respMap.put("data", dataMap);
                        List<Map<String, Object>> list = new ArrayList<>();
                        list.add(respMap);
                        data.setResponse("entity_name", list);

                        requestData.put("execution_status", "SUCCESS");
                        requestData.put("processed_value", calculatedValue);

                        logger.info("Exiting prepareResponseOutput()");
                        return data;
                    }

                    **HOW TO REPLACE PLACEHOLDERS FROM THE YAML**
                    - "entity_name" : the value of the `entity` field in the dbMutation entry.
                    - "field1" / "value1" : from the `set` list inside the dbMutation. If valueSource is a literal string in quotes (e.g., "'COMPLETED'"), use the string without quotes (e.g., "COMPLETED"). If it references a request field (e.g., "Request.loan_amount"), extract that field from requestData and use its value (no quotes).
                    - Do NOT include any extra fields that are not in the YAML.

                    The <TARGET_YAML> contains only the necessary sections (request and response). The PREVIOUSLY_GENERATED_CODE shows the request field names and types.

                    <TARGET_YAML>
                    """ + yaml + """
                    </TARGET_YAML>

                    <PREVIOUSLY_GENERATED_CODE>
                    """ + (previousCode.isEmpty() ? "(none)" : previousCode) + """
                    </PREVIOUSLY_GENERATED_CODE>
                    """;

            default -> "";
        };
    }
}
