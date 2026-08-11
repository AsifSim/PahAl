package com.sim.spriced.pahal.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

@RestController
@RequestMapping("/api/deam-reads")
@CrossOrigin(origins = "*")
public class DeamReadController {

    private static final Logger logger = LoggerFactory.getLogger(DeamReadController.class);
    private static final String DEAM_READ_URL = "http://localhost:8087/spriced/platform"; // Update this

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DeamReadController(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        logger.debug("DeamReadController initialized with RestTemplate and ObjectMapper.");
    }

    @PostMapping("/process")
    public ResponseEntity<?> processDeamReads(
            @RequestHeader(value = "X-TENANT-ID", defaultValue = "default") String tenantId,
            @RequestBody String requestPayloadStr) {

        logger.debug("Received request to /api/deam-reads/process with X-TENANT-ID: {}", tenantId);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-TENANT-ID", tenantId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        List<String> successLogs = new ArrayList<>();

        try {
            // Safely parse the raw string payload into a JSON tree
            JsonNode requestPayload = objectMapper.readTree(requestPayloadStr);

            // 1. Find all dataEnrichment nodes dynamically
            List<JsonNode> enrichmentRules = new ArrayList<>();
            findEnrichmentNodes(requestPayload, enrichmentRules);

            if (enrichmentRules.isEmpty()) {
                logger.warn("No dataEnrichment blocks found in the TBRD payload. Skipping.");
                return ResponseEntity.ok(Map.of("status", "SKIPPED", "message", "No dataEnrichment blocks found in the TBRD payload."));
            }

            // 2. Iterate through each discovered enrichment block
            for (JsonNode enrichmentNode : enrichmentRules) {

                // Extract entity (fallback to 'entityName' if 'entity' is missing)
                String entityName = enrichmentNode.path("entity").asText(null);
                if (entityName == null || entityName.isBlank()) {
                    entityName = enrichmentNode.path("entityName").asText("UnknownEntity");
                }

                String filterName = enrichmentNode.path("name").asText("defaultFilter");
                String entitySlug = entityName.toLowerCase().replace(" ", "_");

                logger.debug("Processing data enrichment for entity: {}", entityName);

                // 3. Fetch existing data (Replicating fetch_entity_data)
                JsonNode fetchedData = fetchEntityData(entityName, headers);

                if (fetchedData == null || fetchedData.isEmpty() || !fetchedData.has(0)) {
                    String errorMsg = String.format("Deam create is not present for %s", entityName);
                    logger.error(errorMsg);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("error", "RECORD_NOT_FOUND", "message", errorMsg));
                }

                JsonNode existingRecord = fetchedData.get(0);
                String uuid = existingRecord.path("uuid").asText(null);

                if (uuid == null || uuid.isBlank()) {
                    String errorMsg = String.format("Deam create is not present for %s (Missing UUID)", entityName);
                    logger.error(errorMsg);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("error", "RECORD_NOT_FOUND", "message", errorMsg));
                }

                // 4. Process the UPDATE
                logger.info("UUID present ({}). Operation set to UPDATE", uuid);
                String operation = "update";

                JsonNode existingPayload = existingRecord.get("payload");
                ArrayNode existingDeamReads = (ArrayNode) existingPayload.get("deamReads");
                Set<String> existingFilterNames = new HashSet<>();

                if (existingDeamReads != null) {
                    for (JsonNode read : existingDeamReads) {
                        existingFilterNames.add(read.path("filterName").asText());
                    }
                } else {
                    existingDeamReads = objectMapper.createArrayNode();
                }

                // Check for duplicates
                if (existingFilterNames.contains(filterName)) {
                    String errorMsg = String.format("Duplicate filterName '%s' found for entity '%s'.", filterName, entityName);
                    logger.warn(errorMsg);
                    successLogs.add("SKIPPED: " + errorMsg);
                    continue;
                }

                // Adapt the YAML structure to the required deamRead format
                ObjectNode newRead = enrichmentNode.deepCopy();
                newRead.put("filterName", filterName);
                existingDeamReads.add(newRead);

                ((ObjectNode) existingPayload).set("deamReads", existingDeamReads);

                // 5. Build models and persist to the DB
                ObjectNode requestData = buildRequestData(entitySlug, operation, uuid, existingPayload);
                ObjectNode messageModel = buildMessageModel(requestData);

                ResponseEntity<String> response = persistData(messageModel, headers);

                if (response.getStatusCode().is2xxSuccessful()) {
                    successLogs.add("DEAM Read mapped successfully for entity: " + entitySlug + " (Filter: " + filterName + ")");
                } else {
                    return ResponseEntity.status(response.getStatusCode())
                            .body(Map.of("error", "PERSIST_FAILED", "message", response.getBody()));
                }
            }

            return ResponseEntity.ok(Map.of("status", "SUCCESS", "details", successLogs));

        } catch (Exception e) {
            logger.error("Error processing DEAM reads", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "INTERNAL_ERROR", "message", e.getMessage()));
        }
    }

    /**
     * Recursively traverses the JSON tree to find all instances of 'dataEnrichment' arrays,
     * including those buried inside arrays of strings.
     */
    private void findEnrichmentNodes(JsonNode root, List<JsonNode> result) {
        if (root.isObject()) {
            if (root.has("dataEnrichment") && root.get("dataEnrichment").isArray()) {
                root.get("dataEnrichment").forEach(result::add);
            }
            root.fields().forEachRemaining(entry -> {
                JsonNode val = entry.getValue();
                if (val.isTextual() && val.asText().contains("dataEnrichment:")) {
                    extractFromYamlString(val.asText(), result);
                } else {
                    findEnrichmentNodes(val, result);
                }
            });
        } else if (root.isArray()) {
            // Check if this is an array of strings (like 'raw_content' in TBRD parser)
            if (root.size() > 0 && root.get(0).isTextual()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode child : root) {
                    sb.append(child.asText()).append("\n");
                }
                String combinedText = sb.toString();
                if (combinedText.contains("dataEnrichment:")) {
                    extractFromYamlString(combinedText, result);
                }
            } else {
                for (JsonNode child : root) {
                    findEnrichmentNodes(child, result);
                }
            }
        } else if (root.isTextual() && root.asText().contains("dataEnrichment:")) {
            extractFromYamlString(root.asText(), result);
        }
    }

    /**
     * Extracts and parses a YAML block from within a generic text string.
     */
    private void extractFromYamlString(String text, List<JsonNode> result) {
        try {
            int deIndex = text.indexOf("dataEnrichment:");
            if (deIndex == -1) return;

            int yamlStart = text.indexOf("service:");
            if (yamlStart == -1 || yamlStart > deIndex) {
                yamlStart = text.lastIndexOf('\n', deIndex);
                if (yamlStart == -1) yamlStart = 0;
            }

            String yamlContent = text.substring(yamlStart);
            yamlContent = yamlContent.replace("```yaml", "").replace("```", "");

            Yaml yaml = new Yaml();
            Iterable<Object> parsedObjects = yaml.loadAll(yamlContent);

            for (Object obj : parsedObjects) {
                if (obj instanceof Map) {
                    JsonNode yamlNode = objectMapper.valueToTree(obj);
                    if (yamlNode.has("dataEnrichment") && yamlNode.get("dataEnrichment").isArray()) {
                        yamlNode.get("dataEnrichment").forEach(result::add);
                    }
                }
            }
        } catch (Throwable t) {
            logger.debug("SnakeYaml failed (likely due to missing indentation). Using manual fallback.");
            manualFallbackExtraction(text, result);
        }
    }

    /**
     * Robust parser to extract `name` and `entity` from a flattened text string where
     * YAML indentation may have been lost during the TBRD document parsing phase.
     */
    private void manualFallbackExtraction(String text, List<JsonNode> result) {
        try {
            String[] lines = text.split("\\r?\\n");
            boolean inDataEnrichment = false;
            ObjectNode currentEnrichment = null;

            for (String line : lines) {
                String trimmed = line.trim();

                if (trimmed.startsWith("dataEnrichment:")) {
                    inDataEnrichment = true;
                    continue;
                }

                if (inDataEnrichment) {
                    // If we hit another major root-level YAML key, we've left dataEnrichment
                    if (trimmed.equals("businessRules:") || trimmed.equals("calculations:") ||
                            trimmed.equals("response:") || trimmed.equals("errorCodes:") ||
                            trimmed.equals("validations:") || trimmed.startsWith("service:")) {
                        break;
                    }

                    if (trimmed.startsWith("- name:") || trimmed.startsWith("-name:")) {
                        if (currentEnrichment != null && currentEnrichment.has("name")) {
                            result.add(currentEnrichment);
                        }
                        currentEnrichment = objectMapper.createObjectNode();
                        String nameVal = trimmed.substring(trimmed.indexOf("name:") + 5).trim().replaceAll("['\"]", "");
                        currentEnrichment.put("name", nameVal);
                    } else if (trimmed.startsWith("entity:") && currentEnrichment != null) {
                        String entityVal = trimmed.substring(7).trim().replaceAll("['\"]", "");
                        currentEnrichment.put("entity", entityVal);
                    }
                }
            }
            if (currentEnrichment != null && currentEnrichment.has("name")) {
                result.add(currentEnrichment);
            }
        } catch (Exception ex) {
            logger.error("Manual YAML extraction fallback failed.", ex);
        }
    }

    private JsonNode fetchEntityData(String entityName, HttpHeaders headers) throws Exception {
        // A Multimap expects a JSON object structure mapping strings to arrays.
        // E.g., {"name": ["risk_profile"]}
        ObjectNode requestBody = objectMapper.createObjectNode();
        ArrayNode valueArray = objectMapper.createArrayNode();

        valueArray.add(entityName);

        // Assuming your backend filter "fetchPayloadByName" is looking for the "name" key.
        requestBody.set("name", valueArray);

        // Serialize the ObjectNode back into a JSON String
        String jsonPayload = objectMapper.writeValueAsString(requestBody);

        HttpEntity<String> request = new HttpEntity<>(jsonPayload, headers);
        String url = DEAM_READ_URL + "/entity_store/fetchPayloadByName";

        try {
            // Fetch as String to bypass Jackson JsonNode mapping issues, then parse to JsonNode
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            if (response.getBody() != null && !response.getBody().isBlank()) {
                return objectMapper.readTree(response.getBody());
            }
            return null;
        } catch (Exception e) {
            logger.warn("Fetch returned no data or failed for {}: {}", entityName, e.getMessage());
            return null;
        }
    }

    private ObjectNode buildRequestData(String entitySlug, String operation, String uuid, JsonNode payload) {
        ObjectNode requestData = objectMapper.createObjectNode();
        requestData.put("is_enabled", true);
        requestData.put("name", entitySlug);
        requestData.put("operation", operation);
        requestData.set("payload", payload);
        requestData.put("uuid", uuid);
        return requestData;
    }

    private ObjectNode buildMessageModel(ObjectNode requestData) {
        ObjectNode messageModel = objectMapper.createObjectNode();
        messageModel.put("store", "entity_store");
        messageModel.set("requestData", requestData);
        messageModel.put("environment", "test");
        messageModel.put("transactionId", "abc1234");
        messageModel.set("errors", objectMapper.createArrayNode());
        messageModel.put("version", "1.0");
        return messageModel;
    }

    private ResponseEntity<String> persistData(ObjectNode messageModel, HttpHeaders headers) throws Exception {
        String messageModelString = objectMapper.writeValueAsString(messageModel);
        HttpEntity<String> request = new HttpEntity<>(messageModelString, headers);
        String url = DEAM_READ_URL + "/persist";
        return restTemplate.exchange(url, HttpMethod.POST, request, String.class);
    }
}