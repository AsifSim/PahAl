package com.sim.spriced.pahal.controllers;


import com.sim.spriced.pahal.services.DeamGeneratorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/deam")
public class DeamGeneratorController {

    @Autowired
    private DeamGeneratorService deamGeneratorService;

    @PostMapping("/generate")
    public ResponseEntity<List<Map<String, Object>>> generateDeams(@RequestBody Map<String, Object> fsmPayload) {
        List<Map<String, Object>> deamMaps = deamGeneratorService.generateDeamsFromFsm(fsmPayload);
        return ResponseEntity.ok(deamMaps);
    }

    @PutMapping("/{entityName}")
    public ResponseEntity<String> saveDeamStructure(@PathVariable String entityName, @RequestBody Map<String, Object> payload) {
        // Logic to save the updated DEAM structure to DB or filesystem
        return ResponseEntity.ok("DEAM Artifact saved successfully.");
    }
}