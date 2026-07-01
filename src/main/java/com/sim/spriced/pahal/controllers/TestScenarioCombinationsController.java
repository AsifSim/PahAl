package com.sim.spriced.pahal.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@CrossOrigin(origins = "*")
public class TestScenarioCombinationsController {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    // Point to your local Ollama API instance
    private final String LLM_API_URL = "http://localhost:11501/api/generate";
    private final String OLLAMA_MODEL_NAME = "llama3.1:8b"; // Set to your preferred local model (e.g., mistral, qwen)
    private static final Logger logger = LoggerFactory.getLogger(TestScenarioCombinationsController.class);
    // Helper class to store parameter configurations
    private static class ParameterConfig {
        String name;
        String source;
        List<Map<String, Object>> variants = new ArrayList<>();

        public ParameterConfig(String name, String source, String validValue) {
            this.name = name;
            this.source = source;

            // Seed VALID variant
            Map<String, Object> validVariant = new LinkedHashMap<>();
            validVariant.put("id", "VALID");
            validVariant.put("value", validValue);
            this.variants.add(validVariant);

            // Seed NULL variant
            Map<String, Object> nullVariant = new LinkedHashMap<>();
            nullVariant.put("id", "NULL");
            nullVariant.put("value", null);
            this.variants.add(nullVariant);
        }
    }

    @PostMapping(value = "/api/code/test-combinations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> generateParameterPairs(@RequestBody String microserviceJsonString) {
        ObjectNode finalRootNode = mapper.createObjectNode();

        try {
            JsonNode rootNode = mapper.readTree(microserviceJsonString);
            Map<String, ParameterConfig> parameterConfigs = new LinkedHashMap<>();

            // 1. Extract from request data extractions (Source: REQUEST)
            JsonNode requestExtractions = rootNode.path("request_data_extractions");
            JsonNode initialPayload = rootNode.path("test_specification_matrix")
                    .path("scenarios").path(0).path("PRE_EXECUTION").path("given_request_payload");

            if (requestExtractions.isArray()) {
                for (JsonNode extraction : requestExtractions) {
                    String fieldName = extraction.path("field_name").asText();
                    if (!fieldName.isEmpty()) {
                        String mockValue = initialPayload.has(fieldName) ? initialPayload.path(fieldName).asText() : "test_" + fieldName;
                        parameterConfigs.put(fieldName, new ParameterConfig(fieldName, "REQUEST", mockValue));
                    }
                }
            }

            // 2. Extract from scenario stubs / others if they exist (Source: STUB/DATABASE)
            JsonNode databaseStubs = rootNode.path("test_specification_matrix")
                    .path("scenarios").path(0).path("PRE_EXECUTION").path("database_stubs");

            if (databaseStubs.isArray()) {
                for (JsonNode stub : databaseStubs) {
                    JsonNode mockReturn = stub.path("mocked_return_data");
                    if (mockReturn.isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> fields = mockReturn.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> field = fields.next();
                            if (!parameterConfigs.containsKey(field.getKey())) {
                                parameterConfigs.put(field.getKey(), new ParameterConfig(field.getKey(), "DATABASE", field.getValue().asText()));
                            }
                        }
                    }
                }
            }

            // 3. Build the "parameters" JSON layout block
            ArrayNode parametersArrayNode = mapper.createArrayNode();
            List<String> paramNames = new ArrayList<>();
            List<List<String>> variantIdsMatrix = new ArrayList<>();

            for (ParameterConfig config : parameterConfigs.values()) {
                paramNames.add(config.name);

                ObjectNode paramNode = mapper.createObjectNode();
                paramNode.put("name", config.name);
                paramNode.put("source", config.source);

                ArrayNode valuesArray = mapper.createArrayNode();
                List<String> currentParamIds = new ArrayList<>();
                for (Map<String, Object> variant : config.variants) {
                    ObjectNode vNode = mapper.createObjectNode();
                    vNode.put("id", (String) variant.get("id"));
                    if (variant.get("value") == null) {
                        vNode.putNull("value");
                    } else {
                        vNode.put("value", variant.get("value").toString());
                    }
                    valuesArray.add(vNode);
                    currentParamIds.add((String) variant.get("id"));
                }
                paramNode.set("values", valuesArray);
                parametersArrayNode.add(paramNode);

                variantIdsMatrix.add(currentParamIds);
            }

            // 4. Compute all ID-based combinations
            ArrayNode combinationsArrayNode = mapper.createArrayNode();
            if (!paramNames.isEmpty()) {
                List<Map<String, String>> combinationResults = new ArrayList<>();
                generateCombinationsRecursive(paramNames, variantIdsMatrix, 0, new HashMap<>(), combinationResults);

                int scenarioCounter = 1;
                for (Map<String, String> combinationMap : combinationResults) {
                    ObjectNode scenarioWrapper = mapper.createObjectNode();
                    scenarioWrapper.put("scenario_id", String.format("AP_%03d", scenarioCounter++));

                    ObjectNode parameterValuesNode = mapper.createObjectNode();
                    combinationMap.forEach(parameterValuesNode::put);

                    scenarioWrapper.set("parameter_values", parameterValuesNode);
                    combinationsArrayNode.add(scenarioWrapper);
                }
            }

            // 5. Wrap inside "all_pair_matrix" object
            ObjectNode allPairMatrixNode = mapper.createObjectNode();
            allPairMatrixNode.set("parameters", parametersArrayNode);
            allPairMatrixNode.set("combinations", combinationsArrayNode);

            finalRootNode.set("all_pair_matrix", allPairMatrixNode);

            return ResponseEntity.ok(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(finalRootNode));

        } catch (Exception e) {
            try {
                ObjectNode errorNode = mapper.createObjectNode();
                errorNode.put("error", "Combinatorial breakdown failure: " + e.getMessage());
                return ResponseEntity.badRequest().body(mapper.writeValueAsString(errorNode));
            } catch (Exception ignored) {
                return ResponseEntity.badRequest().body("{\"error\":\"Critical serialization fault encountered.\"}");
            }
        }
    }

    /**
     * Endpoint 2: Predict Outcomes via local Ollama instance.
     */
    @PostMapping(value = "/api/code/predict-outcomes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> predictOutcomes(@RequestBody String incomingWrapperJson) {
        logger.info("========================================================");
        logger.info("[PREDICT-OUTCOMES] Inbound Request Processing Triggered");
        logger.info("========================================================");

        try {
            logger.info("[STEP 1] Parsing incoming payload string...");
            logger.info("incomingWrapperJson = {}",incomingWrapperJson);
            JsonNode payloadNode = mapper.readTree(incomingWrapperJson);

            JsonNode originalJson = payloadNode.path("microservice_json");
            JsonNode allPairMatrix = payloadNode.path("all_pair_matrix");

            // 1. Extract metadata from incoming JSON
            String microserviceName = originalJson.path("microservice_name").asText("UnknownService");
            String serviceVersion = originalJson.path("service_version").asText("v1.0");

            // Dynamically locate combinations regardless of whether it's double-wrapped or standard
            JsonNode combinations = allPairMatrix.path("combinations");
            if (combinations.isMissingNode() && allPairMatrix.has("all_pair_matrix")) {
                logger.warn("[WARN] Detected double root wrapper structure. Falling back to nested 'all_pair_matrix' element...");
                allPairMatrix = allPairMatrix.get("all_pair_matrix");
                combinations = allPairMatrix.path("combinations");
            }

            if (combinations.isMissingNode() || !combinations.isArray()) {
                logger.error("[CRITICAL ERROR] Aborting. Structural validation failed because 'combinations' array node is missing.");
                return ResponseEntity.badRequest().body("{\"error\":\"Invalid payload structure: 'all_pair_matrix.combinations' missing.\"}");
            }

            logger.info("[STEP 2] Structuring prompt templates...");
            String originalJsonStr = mapper.writeValueAsString(originalJson);
            String combinationsStr = mapper.writeValueAsString(combinations);

            String promptTemplate = String.format(
                    "You are an expert backend QA automation engine.\n" +
                            "Analyze the provided microservice configuration properties and evaluate every combination item in the test scenarios list sequentially.\n\n" +
                            "MICROSERVICE CONFIGURATION RULES:\n%s\n\n" +
                            "COMBINATIONS LIST TO EVALUATE:\n%s\n\n" +
                            "CRITICAL RESPONSE LAYOUT REQUIREMENT:\n" +
                            "You must return a single JSON object containing a root key named \"evaluations\".\n" +
                            "The \"evaluations\" key must point directly to a JSON array containing EXACTLY %d items matching the index order of the incoming test items.\n\n" +
                            "Follow this exact JSON structure example:\n" +
                            "{\n" +
                            "  \"evaluations\": [\n" +
                            "    { \"status\": \"SUCCESS\", \"description\": \"Parameters are valid\" },\n" +
                            "    { \"status\": \"HALT_PROCESS\", \"description\": \"UUID missing error condition matched\" }\n" +
                            "  ]\n" +
                            "}",
                    originalJsonStr, combinationsStr, combinations.size()
            );

            logger.info("[STEP 3] Assembling Ollama JSON execution configuration payload...");
            ObjectNode apiRequestBody = mapper.createObjectNode();
            apiRequestBody.put("model", OLLAMA_MODEL_NAME);
            apiRequestBody.put("prompt", promptTemplate);
            apiRequestBody.put("stream", false);
            apiRequestBody.put("format", "json");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(apiRequestBody), headers);

            logger.info("[STEP 4] Dispatching request downstream to Ollama instance...");
            ResponseEntity<String> responseEntity = restTemplate.postForEntity(LLM_API_URL, entity, String.class);

            JsonNode responseJson = mapper.readTree(responseEntity.getBody());
            String llmContentText = responseJson.path("response").asText().trim();
            logger.info("[DEBUG] Raw LLM content received: {}", llmContentText);

            logger.info("[STEP 5] Parsing text content responses back down into array nodes...");
            JsonNode evaluationNode = mapper.readTree(llmContentText);
            JsonNode evaluationArray = null;

            // UNWRAPPING LAYER: If the LLM returned an object containing an array field, extract the internal array.
            if (evaluationNode.isArray()) {
                evaluationArray = evaluationNode;
            } else if (evaluationNode.isObject()) {
                logger.info("[DEBUG] LLM returned a root object instead of a direct array. Attempting auto-unwrapping...");
                java.util.Iterator<String> fields = evaluationNode.fieldNames();
                while (fields.hasNext()) {
                    JsonNode nested = evaluationNode.get(fields.next());
                    if (nested.isArray()) {
                        evaluationArray = nested;
                        logger.info("[SUCCESS] Extracted array from nested object key structure.");
                        break;
                    }
                }
            }

            ArrayNode evaluatedCombinationsNode = mapper.createArrayNode();
            int index = 0;
            for (JsonNode scenario : combinations) {
                ObjectNode updatedScenario = scenario.deepCopy();

                if (evaluationArray != null && evaluationArray.isArray() && index < evaluationArray.size()) {
                    updatedScenario.set("expected_output", evaluationArray.get(index));
                } else {
                    logger.warn("[WARN] Index {} out of bounds or evaluation mapping failed. Applying safe fallback.", index);
                    ObjectNode safeFallback = mapper.createObjectNode();
                    safeFallback.put("status", "UNKNOWN");
                    safeFallback.put("description", "Evaluation missing from LLM response schema.");
                    updatedScenario.set("expected_output", safeFallback);
                }
                evaluatedCombinationsNode.add(updatedScenario);
                index++;
            }

            ObjectNode updatedMatrixNode = allPairMatrix.deepCopy();
            updatedMatrixNode.set("combinations", evaluatedCombinationsNode);

            ObjectNode responseWrapper = mapper.createObjectNode();
            responseWrapper.put("microservice_name", microserviceName);
            responseWrapper.put("service_version", serviceVersion);
            responseWrapper.set("all_pair_matrix", updatedMatrixNode);

            logger.info("[SUCCESS] Pipeline evaluation matrix assembled cleanly.");
            logger.info("responseWrapper = {}",responseWrapper);
            String jsonOutput = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(responseWrapper);
            try {
                Path directory = Paths.get("ExpectedResult");
                if (!Files.exists(directory)) {
                    Files.createDirectories(directory);
                }

                // File naming: ServiceName_Version_Expected.json
                String fileName = String.format("%s_%s_Expected_Result.json", microserviceName, serviceVersion);
                Files.writeString(directory.resolve(fileName), jsonOutput);
                logger.info("[SUCCESS] ExpectedResult saved to: {}", directory.resolve(fileName));
            } catch (Exception fileEx) {
                logger.error("[ERROR] Failed to save expected result file:", fileEx);
                return ResponseEntity.badRequest().body("{\"error\":\"Error while saving the expected result file: " + fileEx.getMessage() + "\"}");
            }

            return ResponseEntity.ok(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(responseWrapper));

        } catch (Exception e) {
            logger.error("[EXCEPTION CRASH] Execution failed:", e);
            return ResponseEntity.badRequest().body("{\"error\":\"Local Ollama processing engine exception: " + e.getMessage() + "\"}");
        }
    }

    private void generateCombinationsRecursive(List<String> names, List<List<String>> values, int depth,
                                               Map<String, String> current, List<Map<String, String>> results) {
        if (depth == names.size()) {
            results.add(new HashMap<>(current));
            return;
        }

        String currentParamName = names.get(depth);
        List<String> currentParamValues = values.get(depth);

        for (String val : currentParamValues) {
            current.put(currentParamName, val);
            generateCombinationsRecursive(names, values, depth + 1, current, results);
            current.remove(currentParamName);
        }
    }
}