package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.dto.TestExecutionRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/yamuna")
@CrossOrigin(origins = "*") // Allows your static HTML to communicate easily
public class TestExecutionController {

    private final RestTemplate restTemplate;
    private final String TARGET_SERVICE_URL = "http://localhost:8880/spriced/platform";

    // Constructor Injection
    public TestExecutionController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostMapping("/execute")
    public ResponseEntity<?> executeTestCase(@RequestBody TestExecutionRequest payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-TENANT-ID", "tenantA");

        HttpEntity<TestExecutionRequest> entity = new HttpEntity<>(payload, headers);

        try {
            // Forward the request to your microservice
            ResponseEntity<String> response = restTemplate.postForEntity(
                    TARGET_SERVICE_URL, entity, String.class);

            return ResponseEntity.ok(response.getBody());
        } catch (Exception e) {
            // Return useful info for the UI to display in the "Actual" field
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}