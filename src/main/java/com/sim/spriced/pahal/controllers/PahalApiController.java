package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.services.JsonMicroserviceGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/raven")
@CrossOrigin(origins = "*") // Allows the local Java UI client to communicate without CORS issues
public class PahalApiController {

    private final JsonMicroserviceGenerator pipelineService;

    @Autowired
    public PahalApiController(JsonMicroserviceGenerator pipelineService) {
        log.info("[Initialization] Injecting RavenPipelineService dependency into PahalApiController.");
        this.pipelineService = pipelineService;
        log.info("[Initialization] PahalApiController successfully instantiated.");
    }

    @PostMapping("/process")
    public ResponseEntity<String> processRequirementDocument(@RequestBody String rawIncomingJson) {
        log.info("[API Request Received] Reached /api/raven/process endpoint.");
        log.debug("[Payload Sample] Raw JSON character length: {}", rawIncomingJson != null ? rawIncomingJson.length() : 0);

        try {
            log.info("[Execution Chain] Invoking RavenPipelineService.executePipeline()...");
            String generatedCodeResult = pipelineService.executePipeline(rawIncomingJson);

            log.info("[Execution Chain] Pipeline completed successfully. Length of generated code: {} characters.", generatedCodeResult.length());
            log.info("[API Response] Returning HTTP 200 OK status to the Raven UI client.");
            return ResponseEntity.ok(generatedCodeResult);

        } catch (Exception e) {
            log.error("[Execution Failure] CRITICAL error caught inside code generation workflow pipeline!", e);
            log.warn("[API Error Response] Returning HTTP 500 Internal Server Error status to the user interface.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error executing AI code generation chain: " + e.getMessage());
        }
    }
}