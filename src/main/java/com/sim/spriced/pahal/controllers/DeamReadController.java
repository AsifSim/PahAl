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

import java.util.*;

@RestController
@RequestMapping("/api/deam-reads")
public class DeamReadController {

    private static final Logger logger = LoggerFactory.getLogger(DeamReadController.class);
    private static final String DEAM_READ_URL = "http://localhost:8880/spriced/platform"; // Update this

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DeamReadController(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        logger.debug("DeamReadController initialized with RestTemplate and ObjectMapper.");
    }

    @PostMapping("/process")
    public ResponseEntity<?> processDeamReads(
            @RequestHeader("X-TENANT-ID") String tenantId,
            @RequestBody JsonNode requestPayload) {

        logger.debug("Received request to /api/deam-reads/process with X-TENANT-ID: {}", tenantId);

        HttpHeaders headers = new HttpHeaders();
        logger.debug("Initialized HttpHeaders.");

        headers.set("X-TENANT-ID", tenantId);
        logger.debug("Set X-TENANT-ID header.");

        headers.setContentType(MediaType.APPLICATION_JSON);
        logger.debug("Set Content-Type header to APPLICATION_JSON.");

        List<String> successLogs = new ArrayList<>();
        logger.debug("Initialized successLogs array list.");

        try {
            logger.debug("Entering main try-catch block.");

            Iterator<Map.Entry<String, JsonNode>> fields = requestPayload.fields();
            logger.debug("Extracted fields iterator from requestPayload.");

            while (fields.hasNext()) {
                logger.debug("Starting next iteration in requestPayload fields loop.");

                Map.Entry<String, JsonNode> field = fields.next();
                logger.debug("Extracted next field from payload iterator.");

                String entitySlug = field.getKey();
                logger.debug("Extracted entitySlug: {}", entitySlug);

                JsonNode newEntityData = field.getValue();
                logger.debug("Extracted newEntityData for slug: {}", entitySlug);

                String entityName = newEntityData.get("entityName").asText();
                logger.debug("Extracted entityName: {}", entityName);

                ArrayNode newDeamReads = (ArrayNode) newEntityData.get("deamReads");
                logger.debug("Extracted newDeamReads ArrayNode. Size: {}", newDeamReads != null ? newDeamReads.size() : "null");

                logger.debug("Processing entity: {}", entityName);

                // 1. Fetch existing data (Replicating fetch_entity_data)
                logger.debug("Calling fetchEntityData for entityName: {}", entityName);
                JsonNode fetchedData = fetchEntityData(entityName, headers);
                logger.debug("Returned from fetchEntityData. fetchedData is null? {}", fetchedData == null);

                // 2. Check if record exists; if not, return the required error
                logger.debug("Evaluating if fetchedData is valid and not empty.");
                if (fetchedData == null || fetchedData.isEmpty() || !fetchedData.has(0)) {
                    logger.debug("fetchedData evaluated as missing or empty.");
                    String errorMsg = String.format("Deam create is not present for %s", entityName);
                    logger.error(errorMsg);
                    logger.debug("Returning 404 NOT_FOUND response.");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("error", "RECORD_NOT_FOUND", "message", errorMsg));
                }
                logger.debug("fetchedData is valid. Extracting existingRecord at index 0.");

                JsonNode existingRecord = fetchedData.get(0);
                logger.debug("Extracted existingRecord.");

                String uuid = existingRecord.has("uuid") ? existingRecord.get("uuid").asText() : null;
                logger.debug("Extracted uuid from existingRecord: {}", uuid);

                logger.debug("Evaluating if uuid is valid.");
                if (uuid == null || uuid.isBlank()) {
                    logger.debug("uuid evaluated as null or blank.");
                    String errorMsg = String.format("Deam create is not present for %s (Missing UUID)", entityName);
                    logger.error(errorMsg);
                    logger.debug("Returning 404 NOT_FOUND response due to missing UUID.");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("error", "RECORD_NOT_FOUND", "message", errorMsg));
                }
                logger.debug("uuid is valid.");

                // 3. Process the UPDATE
                logger.info("UUID present ({}). Operation set to UPDATE", uuid);

                String operation = "update";
                logger.debug("Assigned operation variable: {}", operation);

                JsonNode existingPayload = existingRecord.get("payload");
                logger.debug("Extracted existingPayload from existingRecord.");

                ArrayNode existingDeamReads = (ArrayNode) existingPayload.get("deamReads");
                logger.debug("Extracted existingDeamReads from existingPayload.");

                // Extract existing filter names to check for duplicates
                Set<String> existingFilterNames = new HashSet<>();
                logger.debug("Initialized existingFilterNames HashSet.");

                if (existingDeamReads != null) {
                    logger.debug("existingDeamReads is not null. Iterating to extract filter names.");
                    for (JsonNode read : existingDeamReads) {
                        String existingFilter = read.get("filterName").asText();
                        existingFilterNames.add(existingFilter);
                        logger.debug("Added existing filterName to Set: {}", existingFilter);
                    }
                    logger.debug("Finished extracting existing filter names.");
                } else {
                    logger.debug("existingDeamReads is null. Creating new ArrayNode.");
                    existingDeamReads = objectMapper.createArrayNode();
                    logger.debug("Created new existingDeamReads ArrayNode.");
                }

                // Check for duplicates and append new reads
                logger.debug("Beginning iteration over newDeamReads to check for duplicates.");
                for (JsonNode newRead : newDeamReads) {
                    String newFilterName = newRead.get("filterName").asText();
                    logger.debug("Extracted newFilterName: {}", newFilterName);

                    logger.debug("Checking if existingFilterNames contains '{}'", newFilterName);
                    if (existingFilterNames.contains(newFilterName)) {
                        logger.debug("Duplicate found for filterName: {}", newFilterName);
                        String errorMsg = String.format(
                                "Duplicate filterName '%s' found for entity '%s'.",
                                newFilterName, entityName
                        );
                        logger.error(errorMsg);
                        logger.debug("Returning 409 CONFLICT response.");
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("error", "DUPLICATE_FILTER", "message", errorMsg));
                    }
                    logger.debug("No duplicate found for {}. Appending to existingDeamReads.", newFilterName);
                    existingDeamReads.add(newRead);
                    logger.debug("Appended newRead to existingDeamReads.");
                }
                logger.debug("Finished iterating over newDeamReads.");

                // Set the updated deamReads back to the existing payload
                ((ObjectNode) existingPayload).set("deamReads", existingDeamReads);
                logger.debug("Set updated existingDeamReads back into existingPayload.");

                // 4. Build the request_data and message_model
                logger.debug("Calling buildRequestData.");
                ObjectNode requestData = buildRequestData(entitySlug, operation, uuid, existingPayload);
                logger.debug("Returned from buildRequestData.");

                logger.debug("Calling buildMessageModel.");
                ObjectNode messageModel = buildMessageModel(requestData);
                logger.debug("Returned from buildMessageModel.");

                // 5. Persist data to the DB
                logger.debug("Calling persistData.");
                ResponseEntity<String> response = persistData(messageModel, headers);
                logger.debug("Returned from persistData. Status code: {}", response.getStatusCode());

                logger.debug("Evaluating response status code.");
                if (response.getStatusCode().is2xxSuccessful()) {
                    logger.debug("Response was successful.");
                    successLogs.add("Entity processed successfully for " + entitySlug);
                    logger.debug("Added success message to successLogs.");
                } else {
                    logger.debug("Response was not successful. Preparing error response.");
                    return ResponseEntity.status(response.getStatusCode())
                            .body(Map.of("error", "PERSIST_FAILED", "message", response.getBody()));
                }
                logger.debug("Finished processing current entity in the loop.");
            }
            logger.debug("Exited fields iterator loop.");

            logger.debug("Returning final SUCCESS response.");
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "details", successLogs));

        } catch (Exception e) {
            logger.error("Error processing DEAM reads", e);
            logger.debug("Returning 500 INTERNAL_SERVER_ERROR response due to exception.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "INTERNAL_ERROR", "message", e.getMessage()));
        }
    }

    private JsonNode fetchEntityData(String entityName, HttpHeaders headers) throws Exception {
        logger.debug("Entering fetchEntityData method.");

        ObjectNode requestBody = objectMapper.createObjectNode();
        logger.debug("Created requestBody ObjectNode.");

        ArrayNode payloadArray = objectMapper.createArrayNode();
        logger.debug("Created payloadArray ArrayNode.");

        ObjectNode entityObj = objectMapper.createObjectNode();
        logger.debug("Created entityObj ObjectNode.");

        entityObj.put("entity", entityName);
        logger.debug("Put 'entity' = '{}' into entityObj.", entityName);

        payloadArray.add(entityObj);
        logger.debug("Added entityObj to payloadArray.");

        requestBody.set("payload", payloadArray);
        logger.debug("Set payloadArray into requestBody.");

        String requestBodyString = objectMapper.writeValueAsString(requestBody);
        logger.debug("Serialized requestBody to string.");

        HttpEntity<String> request = new HttpEntity<>(requestBodyString, headers);
        logger.debug("Created HttpEntity request object.");

        String url = DEAM_READ_URL + "/entity_store/fetchByEntityName";
        logger.debug("Constructed target URL: {}", url);

        try {
            logger.debug("Executing restTemplate POST exchange.");
            ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.POST, request, JsonNode.class);
            logger.debug("Received response from restTemplate. Status code: {}", response.getStatusCode());

            JsonNode body = response.getBody();
            logger.debug("Extracted body from response.");
            return body;
        } catch (Exception e) {
            logger.warn("Fetch returned no data or failed for {}: {}", entityName, e.getMessage());
            logger.debug("Returning null due to fetch failure.");
            return null;
        }
    }

    private ObjectNode buildRequestData(String entitySlug, String operation, String uuid, JsonNode payload) {
        logger.debug("Entering buildRequestData method.");

        ObjectNode requestData = objectMapper.createObjectNode();
        logger.debug("Created requestData ObjectNode.");

        requestData.put("is_enabled", true);
        logger.debug("Put 'is_enabled' = true into requestData.");

        requestData.put("name", entitySlug);
        logger.debug("Put 'name' = '{}' into requestData.", entitySlug);

        requestData.put("operation", operation);
        logger.debug("Put 'operation' = '{}' into requestData.", operation);

        requestData.set("payload", payload);
        logger.debug("Set 'payload' into requestData.");

        requestData.put("uuid", uuid);
        logger.debug("Put 'uuid' = '{}' into requestData.", uuid);

        logger.debug("Returning fully constructed requestData.");
        return requestData;
    }

    private ObjectNode buildMessageModel(ObjectNode requestData) {
        logger.debug("Entering buildMessageModel method.");

        ObjectNode messageModel = objectMapper.createObjectNode();
        logger.debug("Created messageModel ObjectNode.");

        messageModel.put("store", "entity_store");
        logger.debug("Put 'store' = 'entity_store' into messageModel.");

        messageModel.set("requestData", requestData);
        logger.debug("Set 'requestData' into messageModel.");

        messageModel.put("environment", "test");
        logger.debug("Put 'environment' = 'test' into messageModel.");

        messageModel.put("transactionId", "abc1234");
        logger.debug("Put 'transactionId' = 'abc1234' into messageModel.");

        messageModel.set("errors", objectMapper.createArrayNode());
        logger.debug("Set empty 'errors' ArrayNode into messageModel.");

        messageModel.put("version", "1.0");
        logger.debug("Put 'version' = '1.0' into messageModel.");

        logger.debug("Returning fully constructed messageModel.");
        return messageModel;
    }

    private ResponseEntity<String> persistData(ObjectNode messageModel, HttpHeaders headers) throws Exception {
        logger.debug("Entering persistData method.");

        String messageModelString = objectMapper.writeValueAsString(messageModel);
        logger.debug("Serialized messageModel to string.");

        HttpEntity<String> request = new HttpEntity<>(messageModelString, headers);
        logger.debug("Created HttpEntity request object.");

        String url = DEAM_READ_URL + "/persist";
        logger.debug("Constructed persist target URL: {}", url);

        logger.debug("Executing restTemplate POST exchange for persistence.");
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        logger.debug("Received response from persistence call. Status code: {}", response.getStatusCode());

        return response;
    }
}