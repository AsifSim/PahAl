package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.services.FsmGeneratorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/code")
public class FsmGeneratorController {

    @Autowired
    private FsmGeneratorService fsmGeneratorService;

    @PostMapping("/fsm-structure")
    public ResponseEntity<Map<String, Object>> generateFromDocument(@RequestParam("file") MultipartFile file) throws Exception {
        Map<String, Object> response = fsmGeneratorService.generateFromMultipartFile(file);

        // Passing the generated full document extraction to create the FSM map
        Map<String, Object> fsmResponse = fsmGeneratorService.generateFsmFromPayload(response);

        // Attach the original parsed sections so the UI can send them to DEAM/WFM generators
        fsmResponse.put("sections", response.get("sections"));
        fsmResponse.put("document_name", response.get("document_name"));

        return ResponseEntity.ok(fsmResponse);
    }

    @PutMapping("/fsm-structure/{fileName}")
    public ResponseEntity<String> saveFsmStructure(@PathVariable String fileName, @RequestBody Map<String, Object> payload) {
        // Implementation for writing/updating the FSM map onto the file system/DB goes here.
        return ResponseEntity.ok("FSM Artifact saved successfully.");
    }
}