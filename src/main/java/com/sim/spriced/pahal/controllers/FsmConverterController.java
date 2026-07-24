//package com.sim.spriced.pahal.controllers;
//
//import com.sim.spriced.pahal.services.FsmGeneratorService;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.util.Map;
//
//@RestController
//@RequestMapping("/api/code")
//@CrossOrigin(origins = "*")
//public class FsmConverterController {
//
//    private static final Logger logger = LoggerFactory.getLogger(FsmConverterController.class);
//    private final FsmGeneratorService fsmGeneratorService;
//
//    @Autowired
//    public FsmConverterController(FsmGeneratorService fsmGeneratorService) {
//        this.fsmGeneratorService = fsmGeneratorService;
//    }
//
//    @PostMapping("/fsm-structure")
//    public ResponseEntity<?> convertDocxToFsmStructure(@RequestParam("file") MultipartFile file) {
//        logger.info("Received request to convert file: {}", file.getOriginalFilename());
//
//        try {
//            if (file == null || file.isEmpty()) {
//                logger.warn("File is null or empty");
//                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
//                        .body(Map.of("status", "ERROR", "message", "File cannot be null or empty."));
//            }
//            logger.debug("File validated, passing to service. Size: {} bytes", file.getSize());
//
//            // You will need to implement generateFromMultipartFile in FsmGeneratorService
//            // to handle the Apache POI parsing logic
//            Map<String, Object> fsmJsonStructure = fsmGeneratorService.generateFromMultipartFile(file);
//            logger.info("FSM structure generated successfully");
//
//            String fileName = file.getOriginalFilename().replace(".docx", "");
//            fsmGeneratorService.saveFsmJson(fsmJsonStructure, fileName);
//
//            return ResponseEntity.ok(fsmJsonStructure);
//        } catch (IllegalArgumentException e) {
//            logger.error("Validation error: {}", e.getMessage());
//            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
//                    .body(Map.of("status", "ERROR", "message", e.getMessage()));
//        } catch (Exception e) {
//            logger.error("Compilation error: ", e);
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body(Map.of("status", "ERROR", "message", "FSM Structural compilation error: " + e.getMessage()));
//        }
//    }
//
//    @PutMapping("/fsm-structure/{fileName}")
//    public ResponseEntity<?> updateFsmStructure(
//            @PathVariable String fileName,
//            @RequestBody Map<String, Object> updatedFsmJson) {
//
//        logger.info("Received request to update FSM file manually: {}", fileName);
//
//        try {
//            if (updatedFsmJson == null || updatedFsmJson.isEmpty()) {
//                logger.warn("Updated JSON payload is empty");
//                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
//                        .body(Map.of("status", "ERROR", "message", "JSON content cannot be empty."));
//            }
//
//            // Reuse your existing service method to overwrite the file in the same location
//            fsmGeneratorService.saveFsmJson(updatedFsmJson, fileName);
//            logger.info("FSM structure updated and saved successfully for: {}", fileName);
//
//            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "FSM structure updated successfully."));
//
//        } catch (Exception e) {
//            logger.error("Error updating FSM JSON: ", e);
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body(Map.of("status", "ERROR", "message", "Failed to update JSON: " + e.getMessage()));
//        }
//    }
//}