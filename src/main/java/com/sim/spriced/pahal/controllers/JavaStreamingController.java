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
            "@SuppressWarnings(\"unchecked\")\nprivate void evaluateBusinessRules(PlatformContext context, TransactionData data, Map<String, Object> requestData, Map<String, Object> enrichedData) ",
            "@SuppressWarnings(\"unchecked\")\nprivate Double calculations(PlatformContext context, TransactionData data, Map<String, Object> requestData, Map<String, Object> enrichedData) ",
            "@SuppressWarnings(\"unchecked\")\nprivate TransactionData prepareResponseOutput(PlatformContext context, TransactionData data, Map<String, Object> requestData, Double calculatedValue) "
    };

    public JavaStreamingController(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    // ===================================================================
    // STREAMING ENDPOINT
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
                        case 4 -> extractYamlSections(yamlNode, List.of("dataEnrichment"));
                        case 5 -> extractYamlSections(yamlNode, List.of("businessRules"));
                        case 6 -> extractYamlSections(yamlNode, List.of("calculations"));
                        case 7 -> extractYamlSections(yamlNode, List.of("request", "response"));
                        default -> tbrdYaml;
                    };

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
    // SHARED GENERATION LOGIC
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
                if (count == 0) {
                    end = i;
                    break;
                }
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
                    logger.debug("enrichedData assigned = {}", enrichedData);
                
                    evaluateBusinessRules(context, data, requestData, enrichedData);
                    logger.debug("Business rules evaluation completed");
                
                    Double calcVal = calculations(context, data, requestData, enrichedData);
                    logger.debug("calcVal assigned = {}", calcVal);
                
                    TransactionData response = prepareResponseOutput(context, data, requestData, calcVal);
                    logger.debug("response assigned = {}", response);
                
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

    // ===================================================================
    // HIGH-PRECISION PROMPTS OPTIMIZED FOR GPT-OSS:20B
    // ===================================================================
// ===================================================================
    // HIGH-PRECISION PROMPTS OPTIMIZED FOR GPT-OSS:20B
    // ===================================================================
//    private String buildPrompt(int methodNumber, String yaml, String previousCode) {
//        return switch (methodNumber) {
//
//            case 1 -> """
//            Output ONLY the raw Java body with curly brackets (no method signature). No markdown, no backticks.
//
//            First three lines exactly:
//            {
//            logger.info("Entering extractInputPayload()");
//            Map<String, Object> requestData = data.getRequestData() != null ? data.getRequestData() : new HashMap<>();
//            logger.debug("requestData = {}", requestData);
//
//            After that, read the YAML under `request.fields`. Each field has a `name` and a `type`.
//            For EACH field, in the exact order they appear, add the following block (replace <name> with the actual YAML field name and use the appropriate type block below):
//
//            If type is "Object":
//            Map<String, Object> <name> = (Map<String, Object>) requestData.get("<name>");
//            if (<name> == null) {
//                <name> = new HashMap<>();
//                requestData.put("<name>", <name>);
//            }
//            logger.debug("<name> = {}", <name>);
//
//            If type is "Array":
//            List<Map<String, Object>> <name> = (List<Map<String, Object>>) requestData.get("<name>");
//            if (<name> == null) {
//                <name> = new ArrayList<>();
//                requestData.put("<name>", <name>);
//            }
//            logger.debug("<name> = {}", <name>);
//
//            If type is "String":
//            String <name> = (String) requestData.get("<name>");
//            logger.debug("<name> = {}", <name>); // null is allowed
//
//            After processing ALL fields, end with exactly:
//            logger.info("Exiting extractInputPayload()");
//            return requestData;
//
//            IMPORTANT:
//            - Write each field extraction individually. NO loops, NO for/while.
//            - Use the exact field names from the YAML. Do NOT use generic variables like "name" or "value".
//
//            <TARGET_YAML>
//            """ + yaml + """
//            </TARGET_YAML>""";
//
//
//            case 2 -> """
//            Output ONLY the raw Java body wrapped in { }. DO NOT output the method signature. DO NOT output markdown.
//
//            METHOD CONTEXT: `private ValidationStatus performTechnicalValidations(TransactionData data, Map<String, Object> requestData)`
//
//            CRITICAL RULES (READ CAREFULLY):
//            1. SECTION ISOLATION: Read ONLY from `validations.technical` in the <TARGET_YAML>. Do NOT read or process `validations.functional`.
//            2. NPE SAFETY (OPERATOR PRECEDENCE): You MUST wrap OR/AND conditions in parentheses.
//               BAD: `var != null && var.equals("A") || var.equals("B")`
//               GOOD: `(var != null && (var.equals("A") || var.equals("B")))`
//            3. NO UNCAST CHAINING: You MUST cast maps before getting nested keys.
//               BAD: `requestData.get("DocumentData").get("Language")`
//               GOOD: `((Map<String, Object>) requestData.get("DocumentData")).get("Language")`
//            4. NO LOOPS: Use Java Streams. NO shadowing `requestData`.
//
//            TRANSLATION DICTIONARY FOR YAML EXPRESSIONS:
//            - TYPEOF(X) == 'Object'  -> `X instanceof Map`
//            - TYPEOF(X) == 'Array'   -> `X instanceof List`
//            - TYPEOF(X) == 'String'  -> `X instanceof String`
//            - LEN(X) > 0             -> `(X != null && X instanceof String && !((String) X).isEmpty())`
//            - AND(a, b)              -> `(a && b)`
//            - OR(a, b)               -> `(a || b)`
//
//            TEMPLATE TO FOLLOW FOR EACH RULE:
//            Object <field_var> = <extract_field_safely>;
//            boolean <rule_name>_valid = <translated_condition_with_safe_parentheses>;
//            if (!<rule_name>_valid) {
//                logger.warn("Validation failed: <rule_name> - <onFailure_string_from_yaml>");
//                return ValidationStatus.VALIDATION_FAILED;
//            }
//
//            TASK:
//            Start exactly with `{ logger.info("Entering performTechnicalValidations()"); `.
//            Translate every technical rule.
//            End exactly with `logger.info("Exiting performTechnicalValidations()"); return ValidationStatus.VALIDATION_SUCCESS; }`.
//
//            <TARGET_YAML>
//            """ + yaml + """
//            </TARGET_YAML>""";
//
//
//            case 3 -> """
//            Output ONLY the raw Java body wrapped in { }. DO NOT output the method signature. DO NOT output markdown.
//
//            METHOD CONTEXT: `private ValidationStatus performFunctionalValidations(TransactionData data, Map<String, Object> requestData)`
//
//            CRITICAL RULES (READ CAREFULLY):
//            1. SECTION ISOLATION: Read ONLY from `validations.functional` in the <TARGET_YAML>. Do NOT read or process `validations.technical`.
//            2. NPE SAFETY (OPERATOR PRECEDENCE): You MUST wrap OR/AND conditions in parentheses to prevent NullPointerExceptions.
//            3. NO UNCAST CHAINING: You MUST cast maps before getting nested keys.
//               GOOD: `((Map<String, Object>) requestData.get("DocumentData")).get("Language")`
//            4. NO LOOPS: Use Java Streams (`.stream().allMatch(...)`).
//
//            TRANSLATION DICTIONARY FOR YAML EXPRESSIONS:
//            - MATCHES(X, regex) -> `(X != null && ((String) X).matches(regex))`
//            - TYPEOF(X) == 'String' -> `X instanceof String`
//            - OR(a, b) -> `(a || b)`
//
//            TEMPLATE TO FOLLOW FOR EACH RULE:
//            Object <field_var> = <extract_field_safely>;
//            boolean <rule_name>_valid = <translated_condition>;
//            if (!<rule_name>_valid) {
//                logger.warn("Validation failed: <rule_name> - <onFailure_string_from_yaml>");
//                return ValidationStatus.VALIDATION_FAILED;
//            }
//
//            TASK:
//            Start exactly with `{ logger.info("Entering performFunctionalValidations()"); `.
//            Translate every functional rule.
//            End exactly with `logger.info("Exiting performFunctionalValidations()"); return ValidationStatus.VALIDATION_SUCCESS; }`.
//
//            <TARGET_YAML>
//            """ + yaml + """
//            </TARGET_YAML>""";
//
//
//            case 4 -> """
//            Output ONLY the raw Java body wrapped in { }. DO NOT output the method signature. DO NOT output markdown.
//
//            METHOD CONTEXT: `private Map<String, Object> fetchEnrichedData(PlatformContext context, TransactionData data, Map<String, Object> requestData)`
//
//            CRITICAL META-RULES (READ CAREFULLY):
//            1. STRIP PREFIXES (CRITICAL): If the YAML says `Request.FieldName`, DO NOT write `requestData.get("Request.FieldName")`. You MUST strip the `Request.` prefix and write `requestData.get("FieldName")`.
//            2. ASYNC ENFORCEMENT: You MUST execute every DB lookup asynchronously. Wrap every DB call inside `CompletableFuture.runAsync(() -> { ... })` and add it to a list of futures.
//            3. EXACT API ARGUMENTS: `context.fetchDataFromDB(tableName, params)` takes EXACTLY TWO arguments. DO NOT pass a third boolean argument.
//            4. DB RETURN TYPE: `context.fetchDataFromDB` returns `List<Map<String, Object>>`. You MUST check `!list.isEmpty()` before calling `list.get(0)`.
//
//            TEMPLATE TO FOLLOW:
//            {
//                logger.info("Entering fetchEnrichedData()");
//                Map<String, Object> enriched = new ConcurrentHashMap<>();
//                List<CompletableFuture<Void>> futures = new ArrayList<>();
//
//                // For each dataEnrichment in YAML:
//                futures.add(CompletableFuture.runAsync(() -> {
//                    // Extract params safely (No dot-notation chaining allowed)
//                    // Call context.fetchDataFromDB
//                    // Put results into `enriched` map
//                }));
//
//                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
//                logger.info("Exiting fetchEnrichedData()");
//                return enriched;
//            }
//
//            <TARGET_YAML>
//            """ + yaml + """
//            </TARGET_YAML>""";
//
//
//            case 5 -> """
//            Output ONLY the raw Java body wrapped in { }. DO NOT output the method signature. DO NOT output markdown.
//
//            METHOD CONTEXT: `private void evaluateBusinessRules(PlatformContext context, TransactionData data, Map<String, Object> requestData, Map<String, Object> enrichedData)`
//
//            CRITICAL META-RULES (READ CAREFULLY):
//            1. NO DOT-NOTATION FOR MAPS (CRITICAL): Java Maps DO NOT support `.get("A.B")`. If the YAML references a nested field like `ObjectA.FieldB`, you MUST translate it to nested, casted gets: `((Map<String, Object>) map.get("ObjectA")).get("FieldB")`.
//            2. ENRICHED DATA TYPE SAFETY: Data pulled from `enrichedData` is typically a `Map<String, Object>` or `List<Map<String, Object>>` (from Method 4). DO NOT blindly cast it to a `String`.
//            3. NO DYNAMIC PARSERS: Do NOT use `YAMLUtil` or write literal `LOOKUP()` methods in Java. Translate them to raw Java using the dictionary below.
//
//            TRANSLATION DICTIONARY FOR YAML:
//            - LOOKUP(key)       -> `enrichedData.get("key")` (cast to Map or List as needed)
//            - CONCAT(a, b, c)   -> `a + b + c`
//            - NOW().getTime()   -> `System.currentTimeMillis()`
//            - AND(a, b)         -> `(a && b)`
//            - DB_UPDATE         -> `logger.warn("DB_UPDATE skipped in evaluateBusinessRules");`
//
//            TASK: Read `businessRules` from <TARGET_YAML> and translate the exact logic step-by-step into hardcoded Java. Mutate `requestData` using `.put()`. Return nothing.
//
//            <TARGET_YAML>
//            """ + yaml + """
//            </TARGET_YAML>""";
//
//
//            case 6 -> """
//            Output ONLY the raw Java body wrapped in { }. DO NOT output the method signature. DO NOT output markdown.
//
//            METHOD CONTEXT: `private Double calculations(PlatformContext context, TransactionData data, Map<String, Object> requestData, Map<String, Object> enrichedData)`
//
//            CRITICAL META-RULES (READ CAREFULLY):
//            1. SYNTAX COMPLETENESS (CRITICAL): You must write full, valid Java statements ending with semicolons `;`. Do NOT output dangling expressions or ternary operators without assignments.
//            2. NO DOT-NOTATION FOR MAPS: Translate YAML dot notation into nested, casted `.get()` calls.
//               BAD: `requestData.get("DocumentData.Items")`
//               GOOD: `((Map<String, Object>) requestData.get("DocumentData")).get("Items")`
//            3. EXACT RETURN TYPE: You MUST assign your final calculated double to a variable and explicitly `return` it at the end of the method.
//
//            TEMPLATE TO FOLLOW:
//            {
//                logger.info("Entering calculations()");
//                Double finalCalculatedValue = 0.0D;
//
//                // Read YAML `calculations`
//                // Perform math logic (Use Java Streams if iterating over a List)
//                // requestData.put("variable_name_from_yaml", computed_value);
//                // finalCalculatedValue = computed_value;
//
//                logger.info("Exiting calculations()");
//                return finalCalculatedValue;
//            }
//
//            <TARGET_YAML>
//            """ + yaml + """
//            </TARGET_YAML>""";
//
//
//            case 7 -> """
//            Output ONLY the raw Java body wrapped in { }. DO NOT output the method signature. DO NOT output markdown.
//
//            METHOD CONTEXT: `private TransactionData prepareResponseOutput(PlatformContext context, TransactionData data, Map<String, Object> requestData, Double calculatedValue)`
//
//            CRITICAL META-RULES (READ CAREFULLY):
//            1. NO POJO HALLUCINATIONS (CRITICAL): `TransactionData` is NOT a custom POJO. It does NOT have methods like `.setJobId()` or `.setStatus()`. You are ONLY allowed to use `data.setResponse(String key, Object payload)`.
//            2. NO API HALLUCINATIONS (CRITICAL): `PlatformContext` does NOT have a `.addAuditLog()` method. Do NOT invent fake methods.
//            3. JSON CHECKED EXCEPTIONS: `new ObjectMapper().writeValueAsString(obj)` MUST be wrapped in a `try-catch` block.
//
//            TASK:
//            1. Read `response.success.payload` from <TARGET_YAML>. Build a `Map<String, Object>` and attach it via `data.setResponse("Response", Collections.singletonList(responseMap));`.
//            2. Read `response.success.dbMutations` from <TARGET_YAML>. Build a `List<Map<String, Object>>` representing the audit payload and attach it via `data.setResponse(entityNameFromYaml, auditList);`.
//            3. Return the `data` object.
//
//            <TARGET_YAML>
//            """ + yaml + """
//            </TARGET_YAML>""";
//
//            default -> "";
//        };
//    }

    // ===================================================================
    // HIGH-PRECISION PROMPTS OPTIMIZED FOR GPT-OSS:20B (>87% ACCURACY)
    // ===================================================================
// ===================================================================
    // HIGH-PRECISION PROMPTS OPTIMIZED FOR GPT-OSS:20B (>87% ACCURACY)
    // ===================================================================
    private String buildPrompt(int methodNumber, String yaml, String previousCode) {
        return switch (methodNumber) {

            case 1 -> """
            Output ONLY the raw Java body. You MUST wrap your entire response in outer curly braces { }.
            DO NOT output the method signature. DO NOT output markdown or backticks.
            
            First lines exactly:
            {
                logger.info("Entering extractInputPayload()");
                Map<String, Object> requestData = data.getRequestData() != null ? data.getRequestData() : new HashMap<>();
                logger.debug("requestData = {}", requestData);
            
            After that, read the YAML under `request.fields`. For EACH top-level field, add this block (replace <name> with the field name and use the appropriate type block):
            
            If type is "Object":
            Map<String, Object> <name> = (Map<String, Object>) requestData.get("<name>");
            if (<name> == null) {
                <name> = new HashMap<>(); 
                requestData.put("<name>", <name>);
            }
            logger.debug("<name> = {}", <name>);
            
            If type is "Array":
            List<Map<String, Object>> <name> = (List<Map<String, Object>>) requestData.get("<name>");
            if (<name> == null) { 
                <name> = new ArrayList<>(); 
                requestData.put("<name>", <name>);
            }
            logger.debug("<name> = {}", <name>);
            
            If type is "String":
            String <name> = (String) requestData.get("<name>");
            logger.debug("<name> = {}", <name>);
            
            End with exactly:
                logger.info("Exiting extractInputPayload()");
                return requestData;
            }
            
            IMPORTANT: Write each extraction individually. NO loops. Output plain Java inside { }.
            
            <TARGET_YAML>
            """ + yaml + """
            </TARGET_YAML>""";

            case 2 -> """
            Output ONLY the raw Java body wrapped in { }. DO NOT output the method signature. DO NOT output markdown.
            
            METHOD CONTEXT: `private ValidationStatus performTechnicalValidations(TransactionData data, Map<String, Object> requestData)`
            
            CRITICAL ANTI-HALLUCINATION RULES:
            1. "REQUEST" KEYWORD BAN: The YAML prefix `Request.` implies the variable `requestData`. THERE IS NO KEY NAMED "Request". 
               BAD: `requestData.get("Request").get("TemplateId")`
               GOOD: `requestData.get("TemplateId")`
            2. UNCAST CHAINING BAN: You MUST cast every level of a nested map before calling `.get()`.
               BAD: `((Map) requestData.get("A")).get("B").get("C")`
               GOOD: `((Map<String, Object>) ((Map<String, Object>) requestData.get("A")).get("B")).get("C")`
            3. NPE SAFETY (OPERATOR PRECEDENCE): Wrap OR/AND conditions in parentheses.
               BAD: `var != null && var.equals("A") || var.equals("B")`
               GOOD: `(var != null && (var.equals("A") || var.equals("B")))`
            4. SECTION ISOLATION: Translate ONLY the rules inside `validations.technical`.
            
            TRANSLATION DICTIONARY:
            - TYPEOF(X) == 'Object'  -> `X instanceof Map`
            - TYPEOF(X) == 'Array'   -> `X instanceof List`
            - TYPEOF(X) == 'String'  -> `X instanceof String`
            - LEN(X) > 0             -> `(X != null && X instanceof String && !((String) X).isEmpty())`
            
            TEMPLATE FOR EACH RULE:
            Object <field_var> = <extract_safely_with_casts>;
            boolean <rule_name>_valid = <translated_condition>;
            if (!<rule_name>_valid) {
                logger.warn("Validation failed: <rule_name> - <onFailure_string_from_yaml>");
                return ValidationStatus.VALIDATION_FAILED;
            }
            
            TASK: Start with `{ logger.info("Entering performTechnicalValidations()"); `. Translate rules. End with `logger.info("Exiting performTechnicalValidations()"); return ValidationStatus.VALIDATION_SUCCESS; }`.
            
            <PREVIOUS_CODE>
            """ + (previousCode.isEmpty() ? "none" : previousCode) + """
            </PREVIOUS_CODE>
            
            <TARGET_YAML>
            """ + yaml + """
            </TARGET_YAML>""";

            case 3 -> """
            Output ONLY the raw Java body wrapped in { }. DO NOT output the method signature. DO NOT output markdown.
            
            METHOD CONTEXT: `private ValidationStatus performFunctionalValidations(TransactionData data, Map<String, Object> requestData)`
            
            CRITICAL ANTI-HALLUCINATION RULES:
            1. "REQUEST" KEYWORD BAN: Strip `Request.` from paths. NEVER use `requestData.get("Request")`.
            2. UNCAST CHAINING BAN: You MUST cast every level of a nested map before calling `.get()`.
               GOOD: `((Map<String, Object>) ((Map<String, Object>) requestData.get("A")).get("B")).get("C")`
            3. SECTION ISOLATION: Translate ONLY the rules inside `validations.functional`.
            
            TRANSLATION DICTIONARY:
            - MATCHES(X, regex) -> `(X != null && X instanceof String && ((String) X).matches(regex))`
            - OR(a, b) -> `(a || b)`
            - AND(a, b) -> `(a && b)`
            
            TEMPLATE FOR EACH RULE:
            Object <field_var> = <extract_safely_with_casts>;
            boolean <rule_name>_valid = <translated_condition>;
            if (!<rule_name>_valid) {
                logger.warn("Validation failed: <rule_name> - <onFailure_string_from_yaml>");
                return ValidationStatus.VALIDATION_FAILED;
            }
            
            TASK: Start with `{ logger.info("Entering performFunctionalValidations()"); `. Translate rules. End with `logger.info("Exiting performFunctionalValidations()"); return ValidationStatus.VALIDATION_SUCCESS; }`.
            
            <PREVIOUS_CODE>
            """ + (previousCode.isEmpty() ? "none" : previousCode) + """
            </PREVIOUS_CODE>
            
            <TARGET_YAML>
            """ + yaml + """
            </TARGET_YAML>""";

            case 4 -> """
    Output ONLY the raw Java body inside { }. Do NOT output the method signature or markdown.

    METHOD CONTEXT: private Map<String, Object> fetchEnrichedData(PlatformContext context, TransactionData data, Map<String, Object> requestData)

    ABSOLUTE RULES:
    1. NEVER redeclare `requestData` or `data` – they are already in scope.
    2. Use `new ConcurrentHashMap<>()` as the enriched map.
    3. DB access ONLY via `context.fetchDataFromDB(String tableName, Map<String,Object> params)`, which returns `List<Map<String,Object>>`.
    4. For `exactly_match: true`, take the first list element if the list is not empty, else `null`.
    5. Use `CompletableFuture.runAsync()` for each **independent** DB call.  
       If a later enrichment depends on the output of a previous one, wait for the previous future to complete (e.g., `previousFuture.join()`), then run the dependent one.
    6. After launching all futures, wait for them to finish with `CompletableFuture.allOf(…).join()`.
    7. Log entry/exit with `logger.info()` and every enrichment result with `logger.debug()`.

    STEP‑BY‑STEP TRANSLATION OF YAML `dataEnrichment`:

    For each enrichment in the YAML list (process them in order):

    a) Identify the consumed data:
       - If `consumes` is "Request.SomeArray[]":
           `List<Map<String,Object>> items = (List<Map<String,Object>>) requestData.get("SomeArray");`
       - If `consumes` is "Request.SomeArray[].Nested[]":
           first get `SomeArray`, then for each item get the nested list.
       - If `consumes` does NOT end with `[]` (e.g., "Request.TemplateId"), it is a scalar value taken directly from `requestData`.

    b) Build DB query parameters using the `where` block:
       - Split `where.field` by commas to get the DB column names (e.g., "template_id").
       - Translate `where.valueSource`:
         * "Request.X" → `requestData.get("X")`
         * "item.Field" → `item.get("Field")`
         * "AND(item.A, item.B)" → map the column names to item fields in the same order.
       - Create a `Map<String,Object>` with column names as keys and the corresponding values.

    c) Call `context.fetchDataFromDB(entity.table, params)` and store the result.
       - For `exactly_match: true`: store the first map or `null`.
       - For a top‑level enrichment, store a `List<Map<String,Object>>` containing one entry per array item (parallel to the consumed array).
       - For a nested enrichment (e.g., Parts), store a `Map<String, Map<String,Object>>` keyed by the nested identifier (like part number).

    d) Store the enrichment’s result in the concurrent map using the enrichment `name` (e.g., `"TemplateLookup"`) as the key.

    e) If the enrichment is the first one, launch it in a `runAsync` and keep a reference to the future.  
       If a later enrichment needs data from an earlier one, call `.join()` on that earlier future first, then build the params and run the new future.

    Finally, after the loop over enrichments, wait for any remaining futures with `CompletableFuture.allOf(…).join()`.
    Return the enriched map.

    <PREVIOUS_CODE>
    """ + (previousCode.isEmpty() ? "none" : previousCode) + """
    </PREVIOUS_CODE>
    <TARGET_YAML>
    """ + yaml + """
    </TARGET_YAML>""";



            case 5 -> """
    Output ONLY the raw Java body inside { }. Do NOT output the method signature or markdown.

    METHOD CONTEXT: private void evaluateBusinessRules(PlatformContext context, TransactionData data, Map<String, Object> requestData, Map<String, Object> enrichedData)

    ABSOLUTE RULES:
    1. This method returns nothing. It mutates `requestData` by calling `requestData.put(key, value)`.
    2. NEVER write undefined variables like `rule` without declaring them. You must loop over the YAML rules yourself.
    3. NO pseudo‑code – you MUST translate the expressions into real Java using the TRANSLATION TABLE below.
    4. NO helper methods – all logic inline.
    5. Use Java Streams (`.stream().map().filter().collect()`) – no for/while loops.
    6. Log entry/exit with `logger.info()`.

    HOW TO PROCESS businessRules:
    - Read the list from the YAML: you already have the YAML in the prompt. Translate the YAML into Java by directly writing the code for each rule, not by parsing a Java object.
    - The YAML contains a list of rules. For each rule, there is a list of `actions`.
    - For each action:
        * If action type is "COMPUTE":
            1. Declare a local variable with the name given in `action.params.targetVariable`.
            2. Translate the expression in `action.params.expression` using the TRANSLATION TABLE.
            3. After computing, do: `requestData.put(targetVariable, computedValue);`
        * If action type is "DB_UPDATE":
            Just log: `logger.warn("DB_UPDATE skipped");`

    TRANSLATION TABLE (pseudo‑function → Java):
    - `PARALLEL_MAP(array, lambda)` → `((List<Map<String,Object>>) array).parallelStream().map(item -> { lambda body; return result; }).collect(Collectors.toList())`
    - `MAP(array, lambda)` → `((List<Map<String,Object>>) array).stream().map(item -> { lambda body; return result; }).collect(Collectors.toList())`
    - `LET(var = expr; body)` → declare the local variable, then write the body.
    - `LOOKUP(enrichName, field1 = value1, ...)`:
        * Get the enrichment data: `Object lookupData = enrichedData.get(enrichName);`
        * If it's a `List<Map>`: filter: `list.stream().filter(m -> Objects.equals(m.get("field1"), value1) && ...).findFirst().orElse(null)`
        * If it's a `Map<String,Map>`: `((Map<String,Map<String,Object>>)lookupData).get(keyValue)`
        * Return `null` if not found.
    - `IF(cond, trueVal, falseVal)` → `cond ? trueVal : falseVal`
    - `AND(cond1, cond2, ...)` → `cond1 && cond2 && ...`
    - `OR(cond1, cond2, ...)` → `cond1 || cond2 || ...`
    - `NOT(cond)` → `!cond`
    - `CONTAINS(collection, item)` → `collection != null && ((List<?>)collection).contains(item)`
    - `CONCAT(a, b, ...)` → `a + b + ...`
    - `NOW()` → `new Date()`
    - `NOW().getTime()` → `System.currentTimeMillis()`
    - `ROUND(value, decimals)` → `BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue()`
    - `JSON.stringify(obj)` → `new ObjectMapper().writeValueAsString(obj)` (wrap in try‑catch)
    - `MAP('key1', value1, ...)` → `Map.of("key1", value1, ...)` (if >10 keys, use `HashMap`)
    - `IS_NOT_NULL(x)` → `x != null`
    - `==` / `=` → `Objects.equals()` for objects, `==` for primitives
    - `SUM(array, lambda)` → `array.stream().mapToDouble(item -> lambda).sum()`

    Important: When you see `Request.X` in an expression, you must translate it to the appropriate path in the `requestData` map, e.g., `requestData.get("X")` or `((Map<String,Object>) requestData.get("X")).get("Y")`.

    Start with `logger.info("Entering evaluateBusinessRules()");` and end with `logger.info("Exiting evaluateBusinessRules()");`.

    <PREVIOUS_CODE>
    """ + (previousCode.isEmpty() ? "none" : previousCode) + """
    </PREVIOUS_CODE>
    <TARGET_YAML>
    """ + yaml + """
    </TARGET_YAML>""";


            case 6 -> """
    Output ONLY the raw Java body inside { }. Do NOT output the method signature or markdown.

    METHOD CONTEXT: private Double calculations(PlatformContext context, TransactionData data, Map<String, Object> requestData, Map<String, Object> enrichedData)

    ABSOLUTE RULES:
    1. You MUST return a `Double`. If the YAML expression produces a complex object (Map/List), store it in `requestData` and return `0.0D`.
    2. Use `enrichedData` for any LOOKUPs; do NOT call `fetchDataFromDB`.
    3. Use the same TRANSLATION TABLE as in method 5.
    4. Log entry/exit with `logger.info()`.

    TASK:
    - Read the calculation entry from YAML `calculations` (usually the first one).
    - Translate its `compute.expression` into Java.
    - Store the result in a local variable (named as per `compute.variable`), then `requestData.put(variableName, result);`.
    - Return `0.0D`.

    <PREVIOUS_CODE>
    """ + (previousCode.isEmpty() ? "none" : previousCode) + """
    </PREVIOUS_CODE>
    <TARGET_YAML>
    """ + yaml + """
    </TARGET_YAML>""";


            case 7 -> """
    Output ONLY the raw Java body inside { }. Do NOT output the method signature or markdown.

    METHOD CONTEXT: private TransactionData prepareResponseOutput(PlatformContext context, TransactionData data, Map<String, Object> requestData, Double calculatedValue)

    ABSOLUTE RULES:
    1. Return `data` at the end.
    2. Access `TransactionData` ONLY via `data.setResponse(String key, List<Map<String,Object>> payload)`.
    3. All variables produced by previous methods are stored in `requestData` (or in `enrichedData` if it's an enrichment result). Retrieve them by their exact YAML names.
    4. JSON conversion: `new ObjectMapper().writeValueAsString(obj)` – wrap in try‑catch for `JsonProcessingException`.

    STEPS:

    1. **Build response payload** from YAML `response.success.payload`.
       For each entry:
         - `name` is the response key.
         - `valueSource`: if it is a MAP(…) expression, translate it as before.
           Otherwise, it is a variable name (like `"job_id"`, `"total_amount"`, `"template_version"`).
           Retrieve the value:
             * If the variable is stored in `requestData` (e.g., `job_id`, `total_amount`), use `requestData.get("variableName")`.
             * If it is stored in `enrichedData` under the enrichment name (e.g., `template_version` was produced by `TemplateLookup`), retrieve it from there. For instance: `Map<String,Object> templateLookup = (Map<String,Object>) enrichedData.get("TemplateLookup");` then `templateLookup.get("template_version")`.
       Put all pairs into a `Map<String,Object> responseMap`.

    2. **Set the response**:
       `data.setResponse("Response", Collections.singletonList(responseMap));`

    3. **Build audit mutations** from YAML `response.success.dbMutations`.
       For each mutation:
         - The entity name is `mutation.entity`.
         - For each field in `mutation.set`:
           - Translate the `valueSource`:
             * `JSON.stringify(Request)` → `new ObjectMapper().writeValueAsString(requestData)` (catch exception)
             * `NOW()` → `new Date()`
             * Other identifiers (like `job_id`, `total_amount`) → retrieve from `requestData` or `enrichedData` as appropriate.
             * If it contains a string concatenation (e.g., `"'https://...' + job_id + '.pdf'"`), evaluate it as a Java string expression.
         - Build a `Map<String,Object>` for that audit record and add it to a list.
       Store with: `data.setResponse(entityName, auditList);`

    4. Return `data`.

    IMPORTANT: Always use the exact variable names and paths from the YAML. Do NOT invent keys like "Header" unless the YAML contains them.

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