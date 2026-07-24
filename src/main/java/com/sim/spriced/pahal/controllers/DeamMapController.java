package com.sim.spriced.pahal.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*")
public class DeamMapController {

    private static final Logger log = LoggerFactory.getLogger(DeamMapController.class);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public DeamMapController() {
        log.info("DeamMapController initialized with TBRD YAML support");
    }

    @PostMapping(value = "/api/code/deam-map", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> generateDeamReadMap(@RequestBody String tbrdYaml) {
        log.info("Entered generateDeamReadMap()");
        log.debug("Received TBRD YAML length = {}", tbrdYaml != null ? tbrdYaml.length() : 0);

        ObjectNode finalRootNode = jsonMapper.createObjectNode();

        try {
            // Parse the TBRD YAML
            JsonNode rootNode = yamlMapper.readTree(tbrdYaml);
            log.debug("Parsed TBRD YAML successfully");

            JsonNode enrichments = rootNode.path("dataEnrichment");

            if (enrichments.isArray()) {
                log.debug("Found {} enrichment entries", enrichments.size());

                for (JsonNode enrichment : enrichments) {
                    String enrichmentName = enrichment.path("name").asText();
                    String targetEntity = enrichment.path("entity").asText();
                    log.debug("Processing enrichment: {} -> entity: {}", enrichmentName, targetEntity);

                    // Build where condition
                    JsonNode whereClause = enrichment.path("where");
                    String field = whereClause.path("field").asText();
                    String operator = whereClause.path("operator").asText();

                    ObjectNode whereCondition = jsonMapper.createObjectNode();
                    whereCondition.put("scope", "");
                    whereCondition.put("operator", operator.isEmpty() ? "=" : operator);
                    whereCondition.put("attributeName", field);
                    ArrayNode attributeValueArray = jsonMapper.createArrayNode();
                    whereCondition.set("attributeValue", attributeValueArray);
                    whereCondition.put("logicalOperator", "");

                    ArrayNode whereArray = jsonMapper.createArrayNode();
                    whereArray.add(whereCondition);

                    // Build attributeNames from produces
                    ArrayNode attributeNamesArray = jsonMapper.createArrayNode();
                    JsonNode produces = enrichment.path("produces");
                    if (produces.isArray()) {
                        for (JsonNode prod : produces) {
                            String colName = prod.path("alias").asText();
                            if (colName.isEmpty()) {
                                colName = prod.path("name").asText();
                            }
                            attributeNamesArray.add(colName);
                        }
                    }

                    // Build filter container
                    ObjectNode filterContainer = jsonMapper.createObjectNode();
                    filterContainer.putObject("join");
                    filterContainer.putNull("limit");
                    filterContainer.putNull("offset");
                    filterContainer.putObject("orderBy");
                    filterContainer.set("where", whereArray);
                    filterContainer.set("attributeNames", attributeNamesArray);

                    // Build deamReads array
                    ObjectNode readElement = jsonMapper.createObjectNode();
                    readElement.put("filterName", enrichmentName);
                    readElement.set("filter", filterContainer);

                    ArrayNode deamReadsArray = jsonMapper.createArrayNode();
                    deamReadsArray.add(readElement);

                    // Entity wrapper
                    ObjectNode entityWrapper = jsonMapper.createObjectNode();
                    entityWrapper.put("entityName", targetEntity);
                    entityWrapper.set("deamReads", deamReadsArray);

                    // Set in root under the target entity name
                    finalRootNode.set(targetEntity, entityWrapper);
                }
            }

            String cleanJsonOutput = jsonMapper.writeValueAsString(finalRootNode);
            log.debug("Generated DEAM map JSON length = {}", cleanJsonOutput.length());
            log.info("Exiting generateDeamReadMap()");
            return ResponseEntity.ok(cleanJsonOutput);

        } catch (Exception e) {
            log.error("Failed to generate DEAM map from TBRD YAML", e);
            try {
                ObjectNode errorNode = jsonMapper.createObjectNode();
                errorNode.put("error", "Failed parsing TBRD YAML: " + e.getMessage());
                return ResponseEntity.badRequest().body(jsonMapper.writeValueAsString(errorNode));
            } catch (Exception ignored) {
                return ResponseEntity.badRequest().body("{\"error\":\"Critical serialization failure.\"}");
            }
        }
    }
}