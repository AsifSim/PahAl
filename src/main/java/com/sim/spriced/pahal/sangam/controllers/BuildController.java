package com.sim.spriced.pahal.sangam.controllers;

import com.sim.spriced.pahal.sangam.models.BuildRequest;
import com.sim.spriced.pahal.sangam.models.BuildResponse;
import com.sim.spriced.pahal.sangam.repositories.DeploymentRepository;
import com.sim.spriced.pahal.sangam.services.BuildService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/build")
@CrossOrigin(origins = "*")
public class BuildController {
    
    @Autowired
    private BuildService buildService;

    
    @Autowired
    private DeploymentRepository deploymentRepository;
    
    @PostMapping("/initiate")
    public ResponseEntity<BuildResponse> initiateBuild(@Valid @RequestBody BuildRequest request) {
        BuildResponse response = buildService.initiateBuild(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
    
    @GetMapping("/status/{buildId}")
    public ResponseEntity<BuildResponse> getBuildStatus(@PathVariable String buildId) {
        BuildResponse response = buildService.getBuildStatus(buildId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history/latest")
    public ResponseEntity<?> getLatestDeployment(
        @RequestParam String repoName,
        @RequestParam String branchName
    ) {
        // Now 'deploymentRepository' is recognized
        return deploymentRepository.findTopByRepoNameAndBranchNameOrderByCreatedAtDesc(repoName, branchName)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}