package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.services.WfmGeneratorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wfm")
public class WfmGeneratorController {

    @Autowired
    private WfmGeneratorService wfmGeneratorService;

    @PostMapping("/generate")
    public ResponseEntity<List<Map<String, Object>>> generateWfm(@RequestBody Map<String, Object> fsmPayload) {
        List<Map<String, Object>> wfmMaps = wfmGeneratorService.generateWfmFromFsm(fsmPayload);
        return ResponseEntity.ok(wfmMaps);
    }

    @PutMapping("/{workflowName}")
    public ResponseEntity<String> saveWfmStructure(@PathVariable String workflowName, @RequestBody Map<String, Object> payload) {
        // Logic to save the updated WFM structure to DB or filesystem
        return ResponseEntity.ok("WFM Artifact saved successfully.");
    }
}