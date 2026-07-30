package com.sim.spriced.pahal.dvm.controller;

import com.sim.spriced.pahal.dvm.dto.AgentRequest;
import com.sim.spriced.pahal.dvm.dto.AgentResponse;
import com.sim.spriced.pahal.dvm.service.AgentExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentExecutionService agentExecutionService;

    @PostMapping("/generate-dvm")
    public ResponseEntity<AgentResponse> generateDvm(@RequestBody AgentRequest request) {
        log.info("Received DVM generation request for FSM: {}", request.getFsm());

        try {
            AgentResponse response = agentExecutionService.executeAgent(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("DVM generation failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/generate-dvm-async")
    public CompletableFuture<ResponseEntity<AgentResponse>> generateDvmAsync(
            @RequestBody AgentRequest request) {
        log.info("Received async DVM generation request");

        return CompletableFuture
                .supplyAsync(() -> {
                    try {
                        AgentResponse response = agentExecutionService.executeAgent(request);
                        return ResponseEntity.ok(response);
                    } catch (Exception e) {
                        log.error("Async DVM generation failed", e);
                        return ResponseEntity.<AgentResponse>internalServerError().build();
                    }
                });
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Agent service is running");
    }
}
