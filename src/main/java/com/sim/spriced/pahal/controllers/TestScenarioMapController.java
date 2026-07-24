package com.sim.spriced.pahal.controllers;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sim.spriced.pahal.services.OllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestScenarioMapController {

    private static final Logger log = LoggerFactory.getLogger(TestScenarioMapController.class);
    private final OllamaService ollamaService;

    public TestScenarioMapController(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @PostMapping("/generate-test-matrix")
    public ResponseEntity<String> generateTestMatrix(@RequestBody String tbrdYaml) {
        log.info("Entered generateTestMatrix()");
        // Validate YAML
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.readTree(tbrdYaml);
        } catch (Exception e) {
            log.warn("Invalid TBRD YAML.", e);
            return ResponseEntity.badRequest().body("Invalid TBRD YAML: " + e.getMessage());
        }

        // Build the test‑matrix prompt
        String prompt = buildTestMatrixPrompt(tbrdYaml);
        log.debug("Test matrix prompt length = {}", prompt.length());

        // Call LLM (Ollama)
        String rawResponse = ollamaService.generate(prompt);
        log.debug("Raw test matrix response = {}", rawResponse);

        // Extract JSON from response
        String testMatrixJson = extractJson(rawResponse);
        if (testMatrixJson == null) {
            testMatrixJson = rawResponse;
        }

        log.info("Exiting generateTestMatrix()");
        return ResponseEntity.ok(testMatrixJson);
    }

    private String buildTestMatrixPrompt(String yaml) {
        return """
            You are a test specification engineer. Read the TBRD YAML below and generate a **test_specification_matrix** JSON object.
            The matrix must contain scenarios that cover:
            
            - **Happy path**: all validations pass, business rules succeed, state transitions complete, calculations produce correct values, final response is success.
            - **Every technical validation failure**: one scenario per field‑type check that fails.
            - **Every functional validation failure**: one scenario per rule (range checks, minimum values, etc.).
            - **Every blocking business rule**: one scenario where the condition is true and the expected error/rejection occurs, including any DB_UPDATE side effects and state transitions (if the rule is a guard).
            - **State transition guards**: if a guard has ALTERNATE_TRANSITION, include a scenario that triggers the alternate transition.
            - **Calculation branches**: if a calculation has multiple conditional branches (e.g., AND / OR), cover each branch.
            - **Edge cases**: missing required fields, fields with wrong types, extreme values.
            
            Use the YAML's request fields, validations, business rules, state machine, calculations, and error codes to create realistic payloads and expected outcomes.
            
            The output must be a single JSON object with the key "test_specification_matrix" containing an array of "scenarios". Each scenario must have:
            - "scenario_id": string (e.g., "TC_HAPPY_PATH")
            - "description": string
            - "given_request_payload": object matching the YAML request fields
            - "expected_validation_status": string (e.g., "VALIDATION_SUCCESS", "HALT_TECHNICAL_ERR_...", etc.)
            - "expected_business_rule_result": string (e.g., "PASS", "ERR_...")
            - "expected_state_after": string (the state after the transition, or null if not applicable)
            - "expected_calculation_results": object with computed variable names and values (or null)
            - "expected_response_status": string (e.g., "SUCCESS", "FAILED", "REJECTED")
            
            Do NOT wrap the JSON in markdown. Output ONLY the JSON object.
            
            <TBRD_YAML>
            """ + yaml + """
            </TBRD_YAML>""";
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start != -1 && end != -1 && start <= end) {
            return text.substring(start, end + 1);
        }
        return text.trim();
    }
}
