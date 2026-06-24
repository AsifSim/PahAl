package com.sim.spriced.pahal.controllers;

import com.sim.spriced.pahal.services.CodeGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/code")
@CrossOrigin(origins = "*")
public class CodeGenerationController {

    private static final Logger logger =
            LoggerFactory.getLogger(CodeGenerationController.class);

    private final CodeGenerationService codeGenerationService;

    @Autowired
    public CodeGenerationController(CodeGenerationService codeGenerationService) {
        logger.info("Entering CodeGenerationController constructor");

        this.codeGenerationService = codeGenerationService;
        logger.debug("codeGenerationService = {}", codeGenerationService);

        logger.info("Exiting CodeGenerationController constructor");
    }

    @GetMapping("/branches")
    public ResponseEntity<List<String>> fetchRepositoryBranches() {

        logger.info("Entering fetchRepositoryBranches");

        try {
            List<String> branches = codeGenerationService.getRemoteBranches();
            logger.debug("branches = {}", branches);

            ResponseEntity<List<String>> responseEntity =
                    ResponseEntity.ok(branches);
            logger.debug("responseEntity = {}", responseEntity);

            logger.info("Exiting fetchRepositoryBranches");

            return responseEntity;

        } catch (Exception e) {

            logger.warn("Exception occurred", e);
            logger.debug("exceptionMessage = {}", e.getMessage());

            ResponseEntity<List<String>> responseEntity =
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(null);
            logger.debug("responseEntity = {}", responseEntity);

            logger.info("Exiting fetchRepositoryBranches");

            return responseEntity;
        }
    }

    @PostMapping("/compile")
    public ResponseEntity<String> executeCodeCompilationPipeline(
            @RequestBody Map<String, String> requestPayload) {

        logger.info("Entering executeCodeCompilationPipeline");

        logger.debug("requestPayload = {}", requestPayload);

        String baseBranch = requestPayload.get("baseBranch");
        logger.debug("baseBranch = {}", baseBranch);

        String newBranchName = requestPayload.get("newBranchName");
        logger.debug("newBranchName = {}", newBranchName);

        String workflowJson = requestPayload.get("workflowJson");
        logger.debug("workflowJson = {}", workflowJson);

        if (baseBranch == null || newBranchName == null || workflowJson == null) {

            logger.warn("Required parameters missing");

            ResponseEntity<String> responseEntity =
                    ResponseEntity.badRequest()
                            .body("Error: Missing parameters 'baseBranch', 'newBranchName', or 'workflowJson'.");
            logger.debug("responseEntity = {}", responseEntity);

            logger.info("Exiting executeCodeCompilationPipeline");

            return responseEntity;
        }

        try {
            // FIXED: Added a inline ProgressCallback lambda to monitor progress inside pipeline logs
            String javaResult =
                    codeGenerationService.generateCodeAndCommit(
                            baseBranch,
                            newBranchName,
                            workflowJson,
                            (percentage, stageMessage) -> logger.info("[Pipeline Progress {}%] {}", percentage, stageMessage)
                    );

            logger.debug("javaResult = {}", javaResult);

            ResponseEntity<String> responseEntity =
                    ResponseEntity.ok(javaResult);

            logger.debug("responseEntity = {}", responseEntity);

            logger.info("Exiting executeCodeCompilationPipeline");

            return responseEntity;

        } catch (Exception e) {

            logger.warn("Exception occurred", e);
            logger.debug("exceptionMessage = {}", e.getMessage());
            logger.debug("baseBranch = {}", baseBranch);
            logger.debug("newBranchName = {}", newBranchName);
            logger.debug("workflowJson = {}", workflowJson);

            ResponseEntity<String> responseEntity =
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("Pipeline failure encountered during code generation sequence: "
                                    + e.getMessage());

            logger.debug("responseEntity = {}", responseEntity);

            logger.info("Exiting executeCodeCompilationPipeline");

            return responseEntity;
        }
    }
}