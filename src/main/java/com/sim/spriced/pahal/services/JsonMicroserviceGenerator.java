package com.sim.spriced.pahal.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


import java.io.FileWriter;
import java.io.IOException;

@Slf4j
@Service
public class JsonMicroserviceGenerator {
    private static final com.knuddels.jtokkit.api.EncodingRegistry REGISTRY = com.knuddels.jtokkit.Encodings.newLazyEncodingRegistry();
    private static final com.knuddels.jtokkit.api.Encoding ENCODER = REGISTRY.getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE);
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public JsonMicroserviceGenerator(ChatClient.Builder chatClientBuilder) {
        log.info("[Service Init] Building ChatClient instance via Spring AI Auto-Configuration.");
        this.chatClient = chatClientBuilder.build();

        log.info("[Service Init] Creating local instance of Jackson ObjectMapper.");
        this.objectMapper = new ObjectMapper();
    }

    private static final String DOCUMENT_TO_JSON_PROMPT = """
    System: You are an advanced, deterministic JSON compiler. Your sole objective is to parse the raw text of English business rules or functional requirements and convert them into a mathematically sound, structural JSON object following a strict structural schema contract.

      I. OUTPUT AND SYNTAX CONSTRAINTS
      1. EXCLUSIVE RAW OUTPUT: Output exactly one valid JSON object. Do NOT wrap the JSON in markdown code blocks (never use ```json or ```). Do not include introductory text, summary statements, notes, or headers. Start with '{' and end with '}'.
      2. ESCAPING SAFETY RULE: Inside the values of the JSON attributes, all inner strings, pseudo-code logic, conditions, and evaluations MUST use single quotes ('example') to eliminate any chance of invalidating the outer JSON string delimiters.
      3. LOGICAL MUTUAL EXCLUSIVITY:
         - "workflow_steps": Strictly handles the ordered internal operational pipeline (DB fetches, stream handling, or local analytical checking).
         - "conditional_routing": Strictly manages downstream messaging and cross-microservice invocations. Do not mix these with workflow steps.
         - "persistence_actions": Strictly captures write/insert/delete state transformations on database entities.

      II. ATTRIBUTE EXTRACTION PROTOCOLS

      | JSON Section | Extraction Source Definition | Processing Rules |
      | `microservice_name` | Technical Header / Header Metadata | Extract exact system identifier string. |
      | `request_data_extractions` | Payload Inputs / API Parameters | Map every incoming field name, data type, and logical purpose. |
      | `eligibility_criteria` | Pre-conditions / Gatekeeper Logic | Combine into a single logical condition statement. Set on_failure to 'HALT_PROCESS'. |
      | `workflow_steps` | Procedural Step-by-Step Flow | Loop over operations. For lookups, specify parameters. For code logic, assign type 'APPLICATION_LOGIC_VALIDATION' and populate evaluation rules. |
      | `conditional_routing` | Downstream Actions ("Call service X") | Map target routing, target service name, and version parameters. |
      | `persistence_actions` | DB State Mutations ("Save/Update row") | Structure by database table, map operation types and field payload mappings. |
      | `test_specification_matrix` | Acceptance Criteria / Edge Cases | Construct explicit test beds mapping inputs/stubs (PRE), mid-run checks (MID), and asset state changes (POST). |
      | Never omit the `test_specification_matrix` from the output. |

      III. EXHAUSTIVE REFERENCE STRUCTURAL BLUEPRINT (ONE-SHOT)

      {
        "microservice_name": "order_validation_engine",
        "service_version": "v2.1",
        "is_platform_triggered": true,
        "request_data_extractions": [
          {
            "field_name": "order_uuid",
            "data_type": "String",
            "purpose": "Primary key identifier for tracking transaction processing history"
          },
          {
            "field_name": "total_amount",
            "data_type": "Double",
            "purpose": "Financial value check for threshold tier routing"
          }
        ],
        "eligibility_criteria": {
          "rule_name": "initial_state_gatekeeping",
          "expression": "(Request.order_uuid != null) AND (Request.total_amount > 0.0)",
          "on_failure": "HALT_PROCESS"
        },
        "workflow_steps": [
          {
            "step_number": 1,
            "type": "DATABASE_LOOKUP",
            "target_entity": "customer_accounts",
            "filter_name": "find_active_by_id",
            "where_clause_parameters": ["customer_id"],
            "columns_to_fetch": ["account_status", "credit_limit"],
            "joins": [
              {
                "join_type": "INNER",
                "join_entity": "customer_demographics",
                "on_criteria": "customer_accounts.id == customer_demographics.account_id"
              }
            ],
            "evaluation_rules": {
              "expression": "customer_accounts.account_status == 'ACTIVE'",
              "on_true": "CONTINUE",
              "on_false": "RAISE_ERROR:ERR_ACCT_SUSPENDED"
            },
            "purpose": "Verify client account status is functional before proceeding"
          },
          {
            "step_number": 2,
            "type": "APPLICATION_LOGIC_VALIDATION",
            "target_entity": "internal_rules_engine",
            "filter_name": "RULE_VAL_04",
            "where_clause_parameters": [],
            "columns_to_fetch": [],
            "joins": [],
            "evaluation_rules": {
              "expression": "Request.total_amount <= customer_accounts.credit_limit",
              "on_true": "CONTINUE",
              "on_false": "RAISE_ERROR:ERR_CREDIT_EXCEEDED"
            },
            "purpose": "Enforce maximum transaction limit against customer credit caps"
          }
        ],
        "conditional_routing": [
          {
            "condition": "If order amount exceeds ten thousand units, trigger secondary audit",
            "trigger_microservice": "HighValueAuditService",
            "target_version": "v1.0",
            "input_parameters": {
              "audit_target": "Request.order_uuid",
              "flagged_value": "Request.total_amount"
            }
          }
        ],
        "persistence_actions": [
          {
            "condition": "When step execution validates successfully, write record to ledger",
            "entity": "order_processing_ledger",
            "operation": "insert",
            "source_of_uuid": "Request.order_uuid",
            "fields": {
              "processing_state": "VERIFIED",
              "verified_timestamp": "SYSTEM_TIME"
            }
          }
        ],
        "test_specification_matrix": {
          "scenarios": [
            {
              "scenario_id": "TC_001_COMPLIANT_RUN",
              "description": "Happy path: standard evaluation under legal thresholds with valid active credit logs",
              "PRE_EXECUTION": {
                "given_request_payload": {
                  "order_uuid": "ORD-5521",
                  "total_amount": 250.50
                },
                "database_stubs": [
                  {
                    "target_entity": "customer_accounts",
                    "query_filters": { "id": "CUST-99" },
                    "operators": { "id": "EQUALS" },
                    "mocked_return_data": {
                      "account_status": "ACTIVE",
                      "credit_limit": 5000.00
                    }
                  }
                ]
              },
              "MID_EXECUTION": {
                "expected_runtime_variables": {
                  "is_within_limit": true
                },
                "intercept_service_calls": []
              },
              "POST_EXECUTION": {
                "database_verifications": [
                  {
                    "target_entity": "order_processing_ledger",
                    "query_filters": { "id": "ORD-5521" },
                    "expected_final_state": {
                      "processing_state": "VERIFIED"
                    }
                  }
                ],
                "final_response_assertion": {
                  "execution_status": "SUCCESS",
                  "error_thrown": null
                }
              }
            }
          ]
        }
      }

      IV. INPUT DATA MATRIX FOR TARGET COMPILATION
      Please analyze the business document provided in user prompt and generate the single valid output object mirroring the blueprint mechanics without deviations.
      In the business document, The word `Join Criteria:` maps to the where_clause_parameters for the json being generated.
    """;

    public String executePipeline(String incomingJsonString) throws Exception {
        log.info("[Pipeline Execution] Beginning new raw specification parsing sequence.");
        log.debug("[Jackson Processing] Attempting to map string content into JsonNode tree structures.");

        JsonNode rootNode = objectMapper.readTree(incomingJsonString);
        log.debug("[Jackson Processing] Root JSON string parsed successfully.");

        JsonNode sections = rootNode.path("sections");
        log.info("[Data Isolation] Navigating to target node path key: 'sections'.");

        StringBuilder rawContentText = new StringBuilder();
        if (sections.isArray() && sections.has(0)) {
            log.info("[Data Isolation] Located multi-nested 'sections' collection structure. Isolating first element framework.");
            JsonNode rawContentArray = sections.get(0).path("raw_content");
            log.info("[Data Isolation] Iterating over lines inside 'raw_content' payload text arrays.");

            for (JsonNode contentNode : rawContentArray) {
                rawContentText.append(contentNode.asText()).append("\n");
            }
            log.debug("[Data Isolation] Aggregated {} characters of requirement context mappings.", rawContentText.length());
        } else {
            log.warn("[Data Isolation] 'sections' layout missing target index structures. Empty content fallback initialized.");
        }

        String extractedText = rawContentText.toString();

        log.info("[AI Orchestration] Formatting structured compile system prompt matrix instructions.");
        String mjmPrompt = DOCUMENT_TO_JSON_PROMPT + "\n\nINPUT DATA TO COMPILE:\n" + extractedText;

        log.info("[AI Orchestration] Dispatching prompt to local Ollama inference model instance.");
//        String microserviceMjmJson = chatClient.prompt(mjmPrompt).call().content();

        org.springframework.ai.chat.model.ChatResponse response = chatClient.prompt(mjmPrompt)
                .call()
                .chatResponse();
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();

            Integer promptTokens = usage.getPromptTokens();// Input tokens
            Integer totalTokens = usage.getTotalTokens();          // Total combination

            log.info("[AI Analytics] Token Usage - Prompt: {}, Total: {}",
                    promptTokens, totalTokens);
        } else {
            log.warn("[AI Analytics] Token usage metadata was not returned by the Ollama instance.");
        }

        String microserviceMjmJson = response.getResult().getOutput().getText();
        log.debug("microserviceMjmJson = {}",microserviceMjmJson);

        log.debug("Checking the accuracy and refining the generated JSON");

        microserviceMjmJson = generateValidatedMicroserviceJson(extractedText,microserviceMjmJson);
        log.debug("The accuracy improved json = {}",microserviceMjmJson);


        log.info("[AI Orchestration] Received generated raw model response payload metadata.");


        // --- RESILIENT BOUNDARY CORRECTION LAYER ---
        log.debug("[Sanitization] Isomorphic cleansing of outer structural JSON boundaries.");
        if (microserviceMjmJson != null) {
            int firstBrace = microserviceMjmJson.indexOf("{");
            int lastBrace = microserviceMjmJson.lastIndexOf("}");

            if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                microserviceMjmJson = microserviceMjmJson.substring(firstBrace, lastBrace + 1).trim();
                log.info("[Sanitization] Boundaries truncated successfully. String spans indices {} to {}.", firstBrace, lastBrace);
            } else {
                log.warn("[Sanitization] Unable to identify clear structural root JSON boundaries. Proceeding with standard cleanups.");
                microserviceMjmJson = microserviceMjmJson.replaceAll("```json", "").replaceAll("```", "").trim();
            }
        }

        // Dynamically pull the microservice name directly from the AI's generated output structure
        String serviceName = "GeneratedService";
        try {
            log.info("[Naming Matrix] Parsing generated MJM structure to extract dynamic microservice name.");
            JsonNode mjmRoot = objectMapper.readTree(microserviceMjmJson);
            if (mjmRoot.has("microservice_name") && !mjmRoot.path("microservice_name").asText().isEmpty()) {
                serviceName = mjmRoot.path("microservice_name").asText().trim();
                log.info("[Naming Matrix] Dynamic identifier successfully extracted: {}", serviceName);
            }
        } catch (Exception parseException) {
            log.warn("[Naming Matrix] Could not parse microservice_name from model output. Using generic safety fallback framework.", parseException);
        }

        String intermediateFileName = serviceName + "_microservice.json";
        log.info("[Persistence Stage] Writing compiled microservice blueprint schema output locally to: {}", intermediateFileName);
        saveFileLocally(intermediateFileName, microserviceMjmJson);
        log.info("[Persistence Stage] Structural data saved successfully.");

        log.info("[Pipeline Complete] Microservice JSON compiled and ready for UI consumption.");
        return microserviceMjmJson;
    }

    public String fetchTokens(String incomingJsonString) throws Exception {
        log.info("[Pipeline Execution] Beginning token metric pre-calculation sequence.");

        JsonNode rootNode = objectMapper.readTree(incomingJsonString);
        JsonNode sections = rootNode.path("sections");

        StringBuilder rawContentText = new StringBuilder();
        if (sections.isArray() && sections.has(0)) {
            JsonNode rawContentArray = sections.get(0).path("raw_content");
            for (JsonNode contentNode : rawContentArray) {
                rawContentText.append(contentNode.asText()).append("\n");
            }
        }

        String extractedText = rawContentText.toString();

        // 1. Calculate individual token segments explicitly
        int systemPromptTokens = ENCODER.countTokensOrdinary(DOCUMENT_TO_JSON_PROMPT);
        int userPromptTokens = ENCODER.countTokensOrdinary("\n\nINPUT DATA TO COMPILE:\n" + extractedText);
        int totalCombinedTokens = systemPromptTokens + userPromptTokens;

        // 2. Build a raw JSON string to return so that the controller's String signature doesn't break
        com.fasterxml.jackson.databind.node.ObjectNode tokenMetricsNode = objectMapper.createObjectNode();
        tokenMetricsNode.put("total_combined_tokens", totalCombinedTokens);
        tokenMetricsNode.put("system_prompt_tokens", systemPromptTokens);
        tokenMetricsNode.put("user_prompt_tokens", userPromptTokens);

        log.info("[Telemetry Engine] Token pre-check calculated: {} total tokens.", totalCombinedTokens);
        return objectMapper.writeValueAsString(tokenMetricsNode);
    }


    private void saveFileLocally(String fileName, String content) throws IOException {
        log.debug("[I/O Action] Invoking disk write streaming handle operations for file: {}", fileName);
        try (FileWriter file = new FileWriter(fileName)) {
            file.write(content);
            log.debug("[I/O Action] File target written and stream flushed securely.");
        } catch (IOException ioException) {
            log.error("[I/O Failure] Encountered severe system disruption writing payload definitions down to disk!", ioException);
            throw ioException;
        }
    }

    /**
     * Orchestrates an iterative refinement loop:
     * 1. Generates JSON from business text.
     * 2. Audits JSON for accuracy using a secondary LLM call.
     * 3. Refines JSON based on audit feedback until 95% accuracy is reached.
     */
    public String generateValidatedMicroserviceJson(String extractedText, String MJM) {
        int currentAccuracy = 0;
        int maxIterations = 2; // Safety cap to prevent infinite loops
        int iterationCount = 0;

        String microserviceMjmJson = MJM;
        String feedback = "No issues yet.";

        // The Auditor Prompt Template
        String auditorPromptTemplate = """
        You are an expert technical auditor. Compare the Original Business Text against the Generated JSON to calculate accuracy.
        
        STRICT SCHEMA DEFINITION:
        - microservice_name: String identifier.
        - service_version: String (e.g., 'v2.1').
        - is_platform_triggered: Boolean.
        - request_data_extractions: Array of objects {field_name, data_type, purpose}.
        - eligibility_criteria: Object {rule_name, expression, on_failure}.
        - workflow_steps: Array of objects (must include step_number, type, evaluation_rules, purpose).
        - conditional_routing: Array of objects {condition, trigger_microservice, target_version, input_parameters}.
        - persistence_actions: Array of objects {condition, entity, operation, source_of_uuid, fields}.
        - test_specification_matrix: Object containing 'scenarios' (must include scenario_id, PRE_EXECUTION, MID_EXECUTION, POST_EXECUTION).

        VALIDATION RULES:
        1. Accuracy must be 100 if all logic is captured and schema is strictly followed.
        2. Deduct points heavily for missing required sections, extra unauthorized keys, or incorrect data types.
        3. Output ONLY a valid JSON object (no markdown, no backticks).
        4. Schema for output: {"accuracy": <int 0-100>, "issues": ["issue 1", "issue 2"]}
        
        ORIGINAL BUSINESS TEXT:
        %s
        
        GENERATED JSON:
        %s
        """;

        // The Refiner Prompt Template
        String refinerPrompt = """
        You are a deterministic JSON correction engine. Improve the following JSON based strictly on these specific issues: %s
        
        STRICT SCHEMA CONTRACT:
        You MUST preserve the following structure exactly. Do NOT add new keys, remove keys, or change the data types of the following fields:
        ['microservice_name', 'service_version', 'is_platform_triggered', 'request_data_extractions',
         'eligibility_criteria', 'workflow_steps', 'conditional_routing', 'persistence_actions',
         'test_specification_matrix']
        
        CURRENT JSON:
        %s
        """;

        while (currentAccuracy < 95 && iterationCount < maxIterations) {
            iterationCount++;
            log.info("[Iteration {}] Beginning processing...", iterationCount);

            if(iterationCount!=1){
                log.info("[Iteration {}] Refiner LLM processing corrections.", iterationCount);
                String refinedPrompt = String.format(refinerPrompt, feedback, microserviceMjmJson) + "\n\nCURRENT JSON:\n" + microserviceMjmJson;
                microserviceMjmJson = chatClient.prompt(refinedPrompt).call().content();
                microserviceMjmJson = extractJsonFromLlmResponse(microserviceMjmJson);
                log.debug("microserviceMjmJson = {}",microserviceMjmJson);
            }


            // 1. AUDITOR PHASE
            log.info("[Iteration {}] Auditor LLM evaluating accuracy.", iterationCount);
            String evaluationResultJson = chatClient.prompt(String.format(auditorPromptTemplate, extractedText, microserviceMjmJson)).call().content();
            evaluationResultJson = extractJsonFromLlmResponse(evaluationResultJson);
            // 3. PARSE AND DECIDE
            try {
                JsonNode evalNode = objectMapper.readTree(evaluationResultJson);
                currentAccuracy = evalNode.get("accuracy").asInt();
                feedback = evalNode.get("issues").toString(); // Store issues for the Refiner in the next loop

                log.info("Current Accuracy: {}%", currentAccuracy);
                log.info("current issues = {}",feedback);
            } catch (Exception e) {
                log.error("[Iteration {}] Failed to parse auditor response. Retrying generation.", iterationCount, e);
                currentAccuracy = 0; // Force another loop iteration
            }
        }

        if (currentAccuracy < 95) {
            log.warn("Max iterations reached. Returning best effort result with {}% accuracy.", currentAccuracy);
        } else {
            log.info("Target accuracy of 95%+ reached.");
        }

        return microserviceMjmJson;
    }

    private String extractJsonFromLlmResponse(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "";
        }

        int startIndex = rawOutput.indexOf('{');
        int endIndex = rawOutput.lastIndexOf('}');

        if (startIndex != -1 && endIndex != -1 && startIndex <= endIndex) {
            return rawOutput.substring(startIndex, endIndex + 1);
        }

        // Fallback just in case it doesn't contain braces (the parser will catch the error)
        return rawOutput.trim();
    }
}