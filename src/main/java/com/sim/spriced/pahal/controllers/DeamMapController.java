package com.sim.spriced.pahal.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*")
public class DeamMapController {

    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping(value = "/api/code/deam-map", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> generateDeamReadMap(@RequestBody String workflowJsonString) {
        // Create a clear root ObjectNode to hold all clean structured objects ({ "OptionDesignConfigData": {...} })
        ObjectNode finalRootNode = mapper.createObjectNode();

        try {
            JsonNode rootNode = mapper.readTree(workflowJsonString);
            JsonNode steps = rootNode.path("workflow_steps");

            if (steps.isArray()) {
                for (JsonNode step : steps) {
                    // Only process database lookup actions
                    if ("DATABASE_LOOKUP".equals(step.path("type").asText())) {
                        String entityName = step.path("target_entity").asText();
                        String filterName = step.path("filter_name").asText();

                        // 1. Build the single 'where' criteria object
                        ObjectNode whereCondition = mapper.createObjectNode();
                        whereCondition.put("scope", "");
                        whereCondition.put("operator", "=");

                        // Extract attribute name from where clause parameters
                        JsonNode params = step.path("where_clause_parameters");
                        String attributeName = "short_part_number"; // Fallback default
                        if (params.isArray() && params.size() > 0) {
                            String paramVal = params.get(0).asText();
                            if (paramVal.contains(".")) {
                                attributeName = paramVal.substring(paramVal.lastIndexOf(".") + 1);
                            } else {
                                attributeName = paramVal;
                            }
                        }
                        whereCondition.put("attributeName", attributeName);
                        whereCondition.putArray("attributeValue"); // []
                        whereCondition.put("logicalOperator", "");

                        ArrayNode whereArray = mapper.createArrayNode();
                        whereArray.add(whereCondition);

                        // 2. Extract selected columns arrays
                        ArrayNode attributeNamesArray = mapper.createArrayNode();
                        JsonNode columns = step.path("columns_to_fetch");
                        if (columns.isArray()) {
                            for (JsonNode col : columns) {
                                attributeNamesArray.add(col.asText());
                            }
                        }

                        // 3. Assemble the basic filter root framework
                        ObjectNode filterContainer = mapper.createObjectNode();
                        filterContainer.putObject("join");      // {}
                        filterContainer.putNull("limit");
                        filterContainer.putNull("offset");
                        filterContainer.putObject("orderBy");   // {}
                        filterContainer.set("where", whereArray);
                        filterContainer.set("attributeNames", attributeNamesArray);

                        // 4. Construct complete deamReads element structure
                        ObjectNode readElement = mapper.createObjectNode();
                        readElement.put("filterName", filterName);
                        readElement.set("filter", filterContainer);

                        ArrayNode deamReadsArray = mapper.createArrayNode();
                        deamReadsArray.add(readElement);

                        // 5. Build the entity wrapper containing entityName property explicitly
                        ObjectNode entityWrapper = mapper.createObjectNode();
                        entityWrapper.put("entityName", entityName);
                        entityWrapper.set("deamReads", deamReadsArray);

                        // Set directly under the entity name key context inside the root node
                        finalRootNode.set(entityName, entityWrapper);
                    }
                }
            }

            // --- THE CRITICAL FIX ---
            // Serialize explicitly to a plain JSON string. This prevents the UI from reading internal Jackson field properties!
            String cleanJsonOutput = mapper.writeValueAsString(finalRootNode);
            System.out.println("cleanJsonOutput = "+cleanJsonOutput);
            return ResponseEntity.ok(cleanJsonOutput);

        } catch (Exception e) {
            try {
                ObjectNode errorNode = mapper.createObjectNode();
                errorNode.put("error", "Failed parsing engine blueprint: " + e.getMessage());
                return ResponseEntity.badRequest().body(mapper.writeValueAsString(errorNode));
            } catch (Exception ignored) {
                return ResponseEntity.badRequest().body("{\"error\":\"Critical serialization failure.\"}");
            }
        }
    }
}