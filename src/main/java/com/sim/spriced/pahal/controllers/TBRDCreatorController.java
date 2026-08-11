package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.services.OllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/diagram")
public class TBRDCreatorController {

    Logger logger = LoggerFactory.getLogger(TBRDCreatorController.class);

    private final OllamaService ollamaService;
    private final String tbrdTemplate;          // original (kept for reference if needed)
    private final String tbrdTemplateSkeleton;  // comments removed, only structure

    public TBRDCreatorController(OllamaService ollamaService,
                                 @Value("${ollama.prompt.template.path:classpath:tbrd-template.yaml}") String templatePath)
            throws IOException {
        this.ollamaService = ollamaService;
        // Load the full template once
        this.tbrdTemplate = new String(
                new ClassPathResource(templatePath).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        // Create a skeleton without comments
        this.tbrdTemplateSkeleton = stripCommentsFromYaml(tbrdTemplate);
    }

    /**
     * Removes all YAML comment lines and inline comments.
     * Leaves only the structural keys with their empty "" placeholders.
     */
    private String stripCommentsFromYaml(String yaml) {
        StringBuilder sb = new StringBuilder();
        for (String line : yaml.split("\n")) {
            // Skip lines that are purely comments (after optional whitespace)
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) continue;
            // Remove inline comments (the pattern: space # ...)
            int commentIdx = line.indexOf(" #");
            if (commentIdx != -1) {
                line = line.substring(0, commentIdx);
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    @PostMapping(value = "/convert",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> convertDiagram(@RequestParam("file") MultipartFile file) {

        logger.info("Entering convertDiagram()");

        // ----- 1. File validation -----
        logger.info("Step 1: Validating uploaded file");

        if (file.isEmpty()) {
            logger.warn("Uploaded file is empty");
            logger.info("Exiting convertDiagram() with BAD_REQUEST: File is empty");
            return ResponseEntity.badRequest().body("File is empty");
        }

        logger.debug("Uploaded file name: {}", file.getOriginalFilename());
        logger.debug("Uploaded file size: {} bytes", file.getSize());

        String contentType = file.getContentType();
        logger.debug("Uploaded file content type: {}", contentType);

        if (!"image/png".equals(contentType)) {
            logger.warn("Unsupported file type received: {}", contentType);
            logger.info("Exiting convertDiagram() with BAD_REQUEST: Only PNG images are accepted");

            return ResponseEntity.badRequest().body(
                    "Only PNG images are accepted. Please export your draw.io diagram as PNG.");
        }

        logger.info("File validation successful. PNG image accepted");

        // ----- 2. Read the uploaded image bytes -----
        logger.info("Step 2: Reading uploaded image bytes");

        byte[] imageBytes;

        try {
            imageBytes = file.getBytes();

            logger.debug("Successfully read uploaded image");
            logger.debug("Image byte size: {} bytes", imageBytes.length);

        } catch (IOException e) {
            logger.error("Failed to read uploaded file: {}", e.getMessage(), e);
            logger.info("Exiting convertDiagram() with INTERNAL_SERVER_ERROR");

            return ResponseEntity.internalServerError()
                    .body("Failed to read uploaded file: " + e.getMessage());
        }

        // ========== STEP 1 – Describe the diagram in plain English ==========
        logger.info("Step 3: Starting diagram description using vision model");

        String step1Prompt = """
            Describe this flowchart in extreme detail.
            List every:
            - Step / process (rectangle) – include its label.
            - Decision (diamond) – the condition text and both branches (Yes/No, True/False, etc.).
            - Any state or status values that appear (e.g. INITIATED, APPROVED, REJECTED).
            - Data fields mentioned (input fields, database columns, output fields).
            - Actions performed (updates, calculations, calls, notifications, state transitions).
            - Transitions between states (from → to, with the command or event name).
            - Error or rejection paths.
            Use exactly the same words as shown in the diagram.
            """;

        logger.debug("Step 1 prompt prepared");
        logger.debug("Step 1 prompt length: {} characters", step1Prompt.length());

        String diagramDescription;

        try {
            logger.info("Calling Ollama vision model for diagram description");

            diagramDescription = ollamaService.generateWithImage(step1Prompt, imageBytes);
            logger.debug("diagramDescription = {}",diagramDescription);

            logger.debug("Vision model response received");
            logger.debug("Vision model response length: {} characters",
                    diagramDescription != null ? diagramDescription.length() : 0);

            if (diagramDescription == null || diagramDescription.isBlank()) {
                logger.error("Step 1 vision model returned an empty description");
                logger.info("Exiting convertDiagram() with INTERNAL_SERVER_ERROR");

                return ResponseEntity.internalServerError()
                        .body("Step 1 (vision) returned an empty description.");
            }

            logger.info("Step 1 completed successfully");

        } catch (Exception e) {
            logger.error("Step 1 vision processing failed: {}", e.getMessage(), e);
            logger.info("Exiting convertDiagram() with INTERNAL_SERVER_ERROR");

            return ResponseEntity.internalServerError()
                    .body("Step 1 (vision) failed: " + e.getMessage());
        }

        // ========== STEP 2 – Fill the YAML skeleton using a text-only model ==========
        logger.info("Step 4: Starting YAML generation using text-only model");

        String step2Prompt = """
            You are an expert at filling YAML templates precisely.
            Below is a YAML skeleton that contains ONLY keys and empty string placeholders ("").
            Your task is to REPLACE EVERY empty string with the correct value,
            using ONLY the flowchart description provided.

            CRITICAL RULES:
            - USE EXACTLY THE INFORMATION FROM THE DESCRIPTION.
            - If a field is NOT mentioned in the description, keep it as "".
            - DO NOT add any comments. DO NOT output any line starting with '#'.
            - DO NOT copy any example values from any source – use ONLY the description.
            - Maintain the exact YAML structure (indentation, lists, order).
            - All lists must use "- ".
            - Every placeholder between double-quotes must either be filled or remain "".

            FLOWCHART DESCRIPTION:
            %s

            YAML SKELETON TO FILL:
            %s
            """.formatted(diagramDescription, tbrdTemplateSkeleton);

        logger.debug("Step 2 prompt prepared");
        logger.debug("Diagram description length: {} characters",
                diagramDescription.length());
        logger.debug("YAML skeleton length: {} characters",
                tbrdTemplateSkeleton != null ? tbrdTemplateSkeleton.length() : 0);
        logger.debug("Step 2 prompt length: {} characters",
                step2Prompt.length());

        String finalYaml;

        try {
            logger.info("Calling Ollama text-only model for YAML generation");

            finalYaml = ollamaService.generate(step2Prompt);

            logger.debug("YAML generation response received");
            logger.debug("Generated YAML length: {} characters",
                    finalYaml != null ? finalYaml.length() : 0);

            if (finalYaml == null || finalYaml.isBlank()) {
                logger.error("Step 2 text model returned empty YAML");
                logger.info("Exiting convertDiagram() with INTERNAL_SERVER_ERROR");

                return ResponseEntity.internalServerError()
                        .body("Step 2 (text model) returned empty YAML.");
            }

            logger.info("Step 2 completed successfully");

        } catch (Exception e) {
            logger.error("Step 2 YAML generation failed: {}", e.getMessage(), e);
            logger.info("Exiting convertDiagram() with INTERNAL_SERVER_ERROR");

            return ResponseEntity.internalServerError()
                    .body("Step 2 (YAML generation) failed: " + e.getMessage());
        }

        // ----- 3. Clean up any markdown code fences -----
        logger.info("Step 5: Cleaning generated YAML");

        String cleanYaml = finalYaml.trim();

        logger.debug("Trimmed YAML length: {} characters", cleanYaml.length());
        logger.debug("Generated YAML starts with code fence: {}",
                cleanYaml.startsWith("```"));

        if (cleanYaml.startsWith("```")) {
            logger.debug("Markdown code fence detected. Removing code fences");

            cleanYaml = cleanYaml
                    .replaceAll("^```(?:yaml)?\\s*", "")
                    .replaceAll("\\s*```$", "");

            logger.debug("Code fences removed");
            logger.debug("Clean YAML length: {} characters", cleanYaml.length());
        } else {
            logger.debug("No markdown code fence detected");
        }

        // ----- 4. Return the cleaned YAML -----
        logger.info("Step 6: Returning cleaned YAML response");
        logger.debug("Final YAML length: {} characters", cleanYaml.length());

        logger.info("convertDiagram() completed successfully");

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(cleanYaml);
    }
}