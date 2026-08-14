package com.sim.spriced.pahal.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sim.spriced.pahal.services.OllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/stream")
public class JavaStreamingController {

    private static final Logger log = LoggerFactory.getLogger(JavaStreamingController.class);
    private final OllamaService ollamaService;

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

    @PostMapping("/save-code")
    public ResponseEntity<Map<String, String>> saveCodeToWorkspace(@RequestBody Map<String, String> request) {
        String submodulePath = request.get("submodulePath");
        String serviceName = request.get("serviceName");
        String code = request.get("code");

        if (submodulePath == null || serviceName == null || code == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required fields: submodulePath, serviceName, or code"));
        }

        try {
            // Navigate directly into the Maven package structure of the cloned repo
            Path targetDir = Paths.get(submodulePath, "src", "main", "java", "com", "sim", "spriced", "application", "service");

            // Ensure the base package directories exist
            Files.createDirectories(targetDir);

            // FIX: Strip out any branch prefixes (like "feat/" or "bugfix/") for the file name
            String safeFileName = serviceName;
            if (safeFileName.contains("/")) {
                safeFileName = safeFileName.substring(safeFileName.lastIndexOf("/") + 1);
            } else if (safeFileName.contains("\\")) {
                safeFileName = safeFileName.substring(safeFileName.lastIndexOf("\\") + 1);
            }

            // Write the final .java file using the sanitized name (e.g., test777.java)
            Path filePath = targetDir.resolve(safeFileName + ".java");

            // Failsafe to guarantee the parent directory exists before writing
            Files.createDirectories(filePath.getParent());

            Files.writeString(filePath, code);

            log.info("Successfully saved {} to {}", safeFileName + ".java", filePath);

            return ResponseEntity.ok(Map.of(
                    "message", "File saved successfully",
                    "path", filePath.toString()
            ));

        } catch (Exception e) {
            log.error("Failed to save code to workspace at {}", submodulePath, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to save file: " + e.getMessage()));
        }
    }

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
                    String body = generateMethodBody(i, yamlNode, yamlForMethod, previousCode);

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

    private String generateMethodBody(int methodNumber, JsonNode yamlNode, String yamlForMethod, String previousCode) {
        String prompt;
        if (methodNumber >= 1 && methodNumber <= 3) {
            // Original detailed prompts for methods 1-3
            prompt = buildPrompt(methodNumber, yamlForMethod, previousCode);
        } else {
            // Fill-in-the-blank approach for methods 4-7
            prompt = buildFillInPrompt(methodNumber, yamlNode, yamlForMethod, previousCode);
        }

        String rawResponse = ollamaService.generate(prompt);
        return cleanMethodBody(rawResponse);
    }

    // ------------------------------------------------------------------
    // Original detailed prompts for methods 1-3 (unchanged)
    // ------------------------------------------------------------------
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
            
            <TARGET_YAML>
            """ + yaml + """
            </TARGET_YAML>""";

            default -> "";
        };
    }

    // ------------------------------------------------------------------
    // Fill-in-the-blank prompts for methods 4-7
    // ------------------------------------------------------------------
    private String buildFillInPrompt(int methodNumber, JsonNode yamlNode, String yamlForMethod, String previousCode) {
        return switch (methodNumber) {
            case 4 -> buildMethod4FillInPrompt(yamlNode);
            case 5 -> buildMethod5FillInPrompt(yamlNode);
            case 6 -> buildMethod6FillInPrompt(yamlNode);
            case 7 -> buildMethod7FillInPrompt(yamlNode);
            default -> "";
        };
    }

    // ------------------------------------------------------------------
// Method 4: fetchEnrichedData – FIXED
// ------------------------------------------------------------------
    private String buildMethod4FillInPrompt(JsonNode yamlNode) {
        StringBuilder skeleton = new StringBuilder();
        skeleton.append("""
        Output ONLY the raw Java body inside { }. Do NOT output the method signature or markdown.

        You are writing the `fetchEnrichedData` method. Use the EXACT Java code provided below, replacing nothing except if a placeholder is clearly marked with <...>. The code is already complete; just copy it and fill in the placeholders with the actual values from the YAML `dataEnrichment` section.

        {
            logger.info("Entering fetchEnrichedData()");
            Map<String, Object> enriched = new ConcurrentHashMap<>();

        """);

        JsonNode enrichments = yamlNode.path("dataEnrichment");
        if (enrichments.isArray()) {
            for (JsonNode enrichment : enrichments) {
                String name = enrichment.path("name").asText("");
                String consumes = enrichment.path("consumes").asText("");
                String entityTable = enrichment.path("entity").asText("");
                boolean exactlyMatch = enrichment.path("exactly_match").asBoolean(true);
                JsonNode where = enrichment.path("where");
                String field = where.path("field").asText("");
                String valueSource = where.path("valueSource").asText("");

                skeleton.append("\n// Enrichment: ").append(name).append("\n");

                // Determine if consumes is scalar or array
                boolean isArray = consumes.endsWith("[]");

                if (!isArray) {
                    // Scalar consumption
                    // e.g., consumes = "Request.TemplateId"
                    String fieldName = consumes.substring("Request.".length());
                    skeleton.append("String ").append(name.toLowerCase()).append("Input = (String) requestData.get(\"")
                            .append(fieldName).append("\");\n");
                    skeleton.append("if (").append(name.toLowerCase()).append("Input != null) {\n");
                    skeleton.append("    Map<String, Object> params = new HashMap<>();\n");
                    skeleton.append("    params.put(\"").append(field.trim()).append("\", ").append(name.toLowerCase()).append("Input);\n");
                    skeleton.append("    List<Map<String, Object>> resultList = context.fetchDataFromDB(\"")
                            .append(entityTable).append("\", params);\n");
                    if (exactlyMatch) {
                        skeleton.append("    if (!resultList.isEmpty()) {\n");
                        skeleton.append("        enriched.put(\"").append(name).append("\", resultList.get(0));\n");
                        skeleton.append("    }\n");
                    } else {
                        skeleton.append("    enriched.put(\"").append(name).append("\", resultList);\n");
                    }
                    skeleton.append("}\n");
                } else {
                    // Array consumption
                    // e.g., consumes = "Request.QuoteDetails[].PartNumber"
                    // We need to extract the parent array and the nested field
                    String path = consumes.substring("Request.".length(), consumes.indexOf("[]"));
                    String nestedField = consumes.substring(consumes.indexOf("[]") + 2);
                    // Remove leading "." if present
                    if (nestedField.startsWith(".")) {
                        nestedField = nestedField.substring(1);
                    }

                    skeleton.append("List<Map<String, Object>> ").append(name.toLowerCase()).append("List = (List<Map<String, Object>>) requestData.get(\"")
                            .append(path).append("\");\n");
                    skeleton.append("Map<String, Map<String, Object>> ").append(name.toLowerCase()).append("Map = new ConcurrentHashMap<>();\n");
                    skeleton.append("if (").append(name.toLowerCase()).append("List != null) {\n");
                    skeleton.append("    ").append(name.toLowerCase()).append("List.stream().forEach(item -> {\n");
                    skeleton.append("        String itemValue = (String) item.get(\"").append(nestedField).append("\");\n");
                    skeleton.append("        if (itemValue != null) {\n");
                    skeleton.append("            Map<String, Object> params = new HashMap<>();\n");
                    skeleton.append("            params.put(\"").append(field.trim()).append("\", itemValue);\n");
                    skeleton.append("            List<Map<String, Object>> resultList = context.fetchDataFromDB(\"")
                            .append(entityTable).append("\", params);\n");
                    if (exactlyMatch) {
                        skeleton.append("            if (!resultList.isEmpty()) {\n");
                        skeleton.append("                ").append(name.toLowerCase()).append("Map.put(itemValue, resultList.get(0));\n");
                        skeleton.append("            }\n");
                    } else {
                        skeleton.append("            ").append(name.toLowerCase()).append("Map.put(itemValue, resultList);\n");
                    }
                    skeleton.append("        }\n");
                    skeleton.append("    });\n");
                    skeleton.append("    enriched.put(\"").append(name).append("\", ").append(name.toLowerCase()).append("Map);\n");
                    skeleton.append("}\n");
                }
            }
        }

        skeleton.append("""
            logger.info("Exiting fetchEnrichedData()");
            return enriched;
        }

        Important:
        - The code above is complete. Just replace any remaining placeholders with values from the YAML.
        - Do NOT add any extra logic, try-catch, or validation.
        - Output the final Java code exactly as shown (inside { }).
        """);

        return skeleton.toString();
    }

    // ------------------------------------------------------------------
// Method 5: evaluateBusinessRules – FIXED
// ------------------------------------------------------------------
    private String buildMethod5FillInPrompt(JsonNode yamlNode) {
        StringBuilder skeleton = new StringBuilder();
        skeleton.append("""
        Output ONLY the raw Java body inside { }. Do NOT output the method signature or markdown.

        You are translating the `businessRules` section from the YAML below into Java code.
        You MUST output real Java statements – NO pseudo‑code, NO undefined variables, NO helper methods.

        ==========================
        HOW TO PROCESS THE RULES
        ==========================
        1. Process each rule in the order they appear.
        2. For each rule, process its actions.
        3. Action types:
           - "COMPUTE": translate the pseudo‑code expression into Java. Store the result in `requestData` using the key given by `targetVariable`.
           - "DB_UPDATE": log `logger.warn("DB_UPDATE skipped");` and continue.

        ==========================
        TRANSLATION TABLE (pseudo‑function → Java)
        ==========================
        LOOKUP(enrichmentName, field1 = value1, ...)
          -> Retrieve data from `enrichedData`.
             If the enrichment result is a **Map** (scalar lookup), use:
               `Map<String,Object> map = (Map<String,Object>) enrichedData.get("enrichmentName");`
             If the enrichment result is a **Map<String, Map<String,Object>>** (nested/array lookup, e.g. from PartMasterLookup), use:
               `Map<String, Map<String,Object>> lookupMap = (Map<String, Map<String,Object>>) enrichedData.get("enrichmentName");`
               Then to find one item:
               `Map<String,Object> found = lookupMap.get(keyValue);`
             Always check for null before using.
        PARALLEL_MAP(array, lambda) -> ((List<Map<String,Object>>) array).parallelStream().map(item -> { ...lambda body...; return result; }).collect(Collectors.toList())
        MAP(array, lambda)          -> ((List<Map<String,Object>>) array).stream().map(item -> { ...lambda body...; return result; }).collect(Collectors.toList())
        LET(var1 = expr1, ..., body) -> Declare local variables one after another, then use them in `body`.
        IF(cond, t, f)               -> cond ? t : f
        AND(cond1, cond2, ...)       -> cond1 && cond2 && ...
        OR(cond1, cond2, ...)        -> cond1 || cond2 || ...
        NOT(cond)                    -> !cond
        CONTAINS(collection, item)   -> collection != null && ((List<?>) collection).contains(item)
        CONCAT(a, b, ...)            -> a + b + ...   (all operands must be strings or convertible)
        NOW()                        -> new Date()
        NOW().getTime()              -> System.currentTimeMillis()
        ROUND(value, decimals)       -> BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue()
        JSON.stringify(obj)          -> new ObjectMapper().writeValueAsString(obj) (wrap in try‑catch)
        MAP('key1', value1, ...)     -> Map.of("key1", value1, ...) (use HashMap if >10 keys)
        IS_NOT_NULL(x)               -> x != null
        == / =                       -> Objects.equals() for objects, == for primitives
        SUM(array, lambda)           -> array.stream().mapToDouble(item -> lambda).sum()

        ==================================
        HANDLING UNSUPPORTED FUNCTIONS
        ==================================
        If you encounter a pseudo‑function not listed above (e.g., `MIDDLEWARE_DERIVE`, `TRUNCATE`, `PAD`, `DATE_FORMAT`, `FIND_HEADER`), do NOT invent Java code.
        Instead, do the following:
          1. Log a warning: `logger.warn("Skipping unsupported expression: <expression snippet>");`
          2. Set the target variable to an empty list (for array results) or `null` (for scalar results), e.g.:
             `requestData.put(targetVariable, Collections.emptyList());`
             or
             `requestData.put(targetVariable, null);`
        You may also use common Java patterns for simple transformations (e.g., `String.format` for padding, `SimpleDateFormat` for date formatting), but if unsure, skip.

        ==========================
        EXAMPLE (partial)
        ==========================
        If a rule has:
          targetVariable: "language_ok"
          expression: |
            LET(
              template = LOOKUP(TemplateLookup, template_id = Request.TemplateId),
              supported = template.supported_languages,
              langOk = AND(supported IS NOT NULL, CONTAINS(supported, Request.DocumentData.Language)),
              IF(langOk, true, false)
            )
        Your Java translation would be:
          Map<String,Object> templateMap = (Map<String,Object>) enrichedData.get("TemplateLookup");
          if (templateMap != null) {
              List<Object> supportedList = (List<Object>) templateMap.get("supported_languages");
              String requestedLanguage = (String) ((Map<String,Object>) requestData.get("DocumentData")).get("Language");
              boolean langOk = supportedList != null && supportedList.contains(requestedLanguage);
              requestData.put("language_ok", langOk);
          } else {
              requestData.put("language_ok", false);
          }

        Now, translate the rules from the YAML below into Java code using the same approach.

        <YAML_BUSINESS_RULES>
        """);

        JsonNode businessRulesNode = yamlNode.path("businessRules");
        skeleton.append(businessRulesNode.toPrettyString()).append("\n</YAML_BUSINESS_RULES>\n");

        skeleton.append("""
        Output only the Java code inside { }. Start with:
            logger.info("Entering evaluateBusinessRules()");
        and end with:
            logger.info("Exiting evaluateBusinessRules()");
        """);
        return skeleton.toString();
    }

    // Method 6: calculations
    private String buildMethod6FillInPrompt(JsonNode yamlNode) {
        StringBuilder skeleton = new StringBuilder();
        skeleton.append("""
        Output ONLY the raw Java body inside { }. Do NOT output the method signature or markdown.

        You are translating the `calculations` section from the YAML below into Java code.
        The method must return a `Double`. If the calculation produces a complex object (Map/List), store it in `requestData` and return 0.0D.

        ==========================
        TRANSLATION RULES
        ==========================
        - Use `requestData` and `enrichedData` only; do NOT call `fetchDataFromDB`.
        - Use Java Streams for any iteration (no for/while loops).
        - Declare all variables before use.
        - Log entry/exit with `logger.info()`.

        ==========================
        TRANSLATION TABLE (same as method 5)
        ==========================
        LOOKUP, MAP, PARALLEL_MAP, IF, AND, OR, NOT, CONTAINS, CONCAT, ROUND, SUM, JSON.stringify, etc.
        (see method 5 for full mapping)

        ==========================
        EXAMPLE
        ==========================
        If YAML calculation is:
          variable: "total_amount"
          expression: |
            IF(Request.DocumentData.Items IS NOT NULL,
               SUM(Request.DocumentData.Items, item.Amount),
               0.0
            )
        Your Java code should be:
          logger.info("Entering calculations()");
          List<Map<String,Object>> items = (List<Map<String,Object>>) ((Map<String,Object>) requestData.get("DocumentData")).get("Items");
          double total = 0.0;
          if (items != null) {
              total = items.stream()
                  .mapToDouble(item -> ((Number) item.get("Amount")).doubleValue())
                  .sum();
          }
          requestData.put("total_amount", total);
          logger.info("Exiting calculations()");
          return 0.0D;

        Now, translate the calculation(s) from the YAML below.

        <YAML_CALCULATIONS>
        """);

        JsonNode calcsNode = yamlNode.path("calculations");
        skeleton.append(calcsNode.toPrettyString()).append("\n</YAML_CALCULATIONS>\n");

        skeleton.append("""
        Output only the Java code inside { }. Start with:
            logger.info("Entering calculations()");
        and end with:
            logger.info("Exiting calculations()");
            return 0.0D;
        """);
        return skeleton.toString();
    }

    // Method 7: prepareResponseOutput
    private String buildMethod7FillInPrompt(JsonNode yamlNode) {
        StringBuilder skeleton = new StringBuilder();
        skeleton.append("""
        Output ONLY the raw Java body inside { }. Do NOT output the method signature or markdown.

        You are building the final response and audit data according to the YAML sections `response.success.payload` and `response.success.dbMutations`.

        ==========================
        RULES
        ==========================
        1. You MUST return the `data` object.
        2. The only allowed method on `data` is `data.setResponse(String key, List<Map<String,Object>> payload)`.
        3. All values needed are in `requestData` or `enrichedData`.
        4. JSON conversion: `new ObjectMapper().writeValueAsString(obj)` – wrap in try‑catch for `JsonProcessingException`.
        5. Log entry/exit with `logger.info()`.

        ==========================
        STEP‑BY‑STEP
        ==========================
        1. **Build response payload** from `response.success.payload`.
           For each entry:
             - `name` is the response key.
             - `valueSource` tells you how to get the value:
               * If it starts with `MAP(` → translate the MAP expression to a Java Map.
               * If it is a literal string (e.g., `'PROCESSING'`) → use the string without quotes.
               * If it is a variable name (like `job_id`, `total_amount`, `template_version`):
                 - If the variable was stored in `requestData` by earlier methods, use `requestData.get("variableName")`.
                 - If it is part of an enrichment result, retrieve it from `enrichedData`, e.g.:
                   `Map<String,Object> templateLookup = (Map<String,Object>) enrichedData.get("TemplateLookup");`
                   then `templateLookup.get("template_version")`.
           Put all entries into `Map<String,Object> responseMap`.

        2. **Attach the response**:
           `data.setResponse("Response", Collections.singletonList(responseMap));`

        3. **Build audit records** from `response.success.dbMutations`.
           For each mutation:
             - `entity` is the table name.
             - For each field in `set`:
               - `field` is the key.
               - Translate the `valueSource`:
                 * `JSON.stringify(Request)` → `new ObjectMapper().writeValueAsString(requestData)` (in try‑catch)
                 * `NOW()` → `new Date()`
                 * A variable name → retrieve from `requestData` or `enrichedData`.
                 * A string concatenation like `'https://...' + job_id + '.pdf'` → build the string in Java.
             - Put the fields into a `Map<String,Object> auditRecord`, add to a list.
           Store the list: `data.setResponse(entity, auditList);`

        4. Return `data`.

        ==========================
        EXAMPLE
        ==========================
        If payload has:
          - name: "JobId", valueSource: "job_id"
          - name: "Status", valueSource: "'PROCESSING'"
        Your code:
          Map<String,Object> responseMap = new HashMap<>();
          responseMap.put("JobId", requestData.get("job_id"));
          responseMap.put("Status", "PROCESSING");
          data.setResponse("Response", Collections.singletonList(responseMap));

        Now, translate the YAML below.

        <YAML_RESPONSE>
        """);

        JsonNode responseNode = yamlNode.path("response").path("success");
        skeleton.append(responseNode.toPrettyString()).append("\n</YAML_RESPONSE>\n");

        skeleton.append("""
        Output only the Java code inside { }. Start with:
            logger.info("Entering prepareResponseOutput()");
        and end with:
            logger.info("Exiting prepareResponseOutput()");
            return data;
        """);
        return skeleton.toString();
    }

    // ------------------------------------------------------------------
    // Clean / helper methods (unchanged)
    // ------------------------------------------------------------------
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
}