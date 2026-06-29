package com.sim.spriced.pahal.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class FsmGeneratorService {
    private static final Logger logger = LoggerFactory.getLogger(FsmGeneratorService.class);

    private static final String FSM_DIR = "FSM";

    public void saveFsmJson(Map<String, Object> fsmStructure, String fileName) throws IOException {
        // 1. Resolve path to project root/FSM
        Path directoryPath = Paths.get(FSM_DIR);

        // 2. Create directory if it doesn't exist
        if (!Files.exists(directoryPath)) {
            Files.createDirectories(directoryPath);
        }

        // 3. Define file path
        Path filePath = directoryPath.resolve(fileName + ".json");

        // 4. Write JSON file
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), fsmStructure);

        logger.info("Successfully stored FSM JSON at: {}", filePath.toAbsolutePath());
    }


    public Map<String, Object> generateFromMultipartFile(MultipartFile file) throws Exception {
        logger.info("Extracting structured sections from: {}", file.getOriginalFilename());

        XWPFDocument document = new XWPFDocument(file.getInputStream());
        List<Map<String, Object>> sections = new ArrayList<>();

        // Simulating the section extraction logic
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("section_title", "TBRD-03: Microservices & Business Rules (API)");

        List<String> cleanedContent = new ArrayList<>();
        document.getParagraphs().forEach(p -> {
            String line = p.getText().trim();
            if (!line.isEmpty()) {
                cleanedContent.add(line);
            }
        });
        section.put("raw_content", cleanedContent);
        sections.add(section);
        document.close();

        // Build the structure to match your original Python output
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("model", "NA");
        output.put("document_name", file.getOriginalFilename());
        output.put("sections", sections);

        logger.info("Extraction complete. Returning structured sections.");
        return output;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateFsmFromPayload(Map<String, Object> payload) {
        logger.info("Compiling FSM structure from payload.");
        Map<String, Object> responseStructure = new LinkedHashMap<>();

        String serviceName = "generic_extracted_service";
        logger.debug("Initialized default serviceName: {}", serviceName);
        String associatedWorkflow = "generic_extracted_workflow";
        logger.debug("Initialized default associatedWorkflow: {}", associatedWorkflow);

        if (payload.containsKey("sections")) {
            logger.info("Payload contains sections, beginning discovery.");
            List<Map<String, Object>> sections = (List<Map<String, Object>>) payload.get("sections");
            for (Map<String, Object> section : sections) {
                List<String> rawContent = (List<String>) section.get("raw_content");
                if (rawContent != null) {
                    for (String line : rawContent) {
                        if (line.toLowerCase().contains("service name")) {
                            serviceName = line.replaceAll("(?i)Service Name\\s*–\\s*", "").trim();
                            logger.info("Discovered Service Name: {}", serviceName);
                        } else if (line.toLowerCase().contains("associated workflow")) {
                            associatedWorkflow = line.replaceAll("(?i)Associated Workflow\\s*–\\s*", "").trim();
                            logger.info("Discovered Workflow: {}", associatedWorkflow);
                        }
                    }
                }
            }
        }

        responseStructure.put("service_name", serviceName);
        logger.debug("Added service_name to response.");
        responseStructure.put("associated_workflow", associatedWorkflow);
        logger.debug("Added associated_workflow to response.");

        Map<String, Object> dynamicStates = new LinkedHashMap<>();
        dynamicStates.put("INIT", Map.of("on", "START_PROCESS", "target", "PARSE_DOCUMENT_CONTEXT"));
        dynamicStates.put("PARSE_DOCUMENT_CONTEXT", Map.of("on", "CONTEXT_READY", "target", "EXECUTE_RULE_EVALUATION"));
        dynamicStates.put("EXECUTE_RULE_EVALUATION", Map.of("on", "EVALUATION_COMPLETE", "target", "COMPLETED"));
        dynamicStates.put("COMPLETED", Map.of("type", "final"));
        logger.info("FSM matrix states defined.");

        responseStructure.put("fsm_matrix_states", dynamicStates);
        logger.debug("Added fsm_matrix_states to response.");
        responseStructure.put("status", "SUCCESS");
        logger.info("FSM generation finished successfully.");

        return responseStructure;
    }
}