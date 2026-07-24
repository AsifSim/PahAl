package com.sim.spriced.pahal.sangam.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class GoogleSheetsLoggingService {

    private static final String CLASSNAME = "GoogleSheetsLoggingService";
    private static final Logger logger = LoggerFactory.getLogger(GoogleSheetsLoggingService.class);
    
    @Value("${google.sheet.webhook.url}")
    private String webhookUrl;

    @Value("${config.date-format.standard}")
    private String dateFormat;

    private final RestTemplate restTemplate = new RestTemplate();

    public void logToSheet(String buildId, String repo, String branch, String server, 
                           String requestedBy, String cloneStatus, String buildStatus, 
                           String deploymentStatus, String startupStatus) {
        
        logger.info("{} : Entering method logToSheet", CLASSNAME);
        logger.info("[GS-SYNC] Starting Google Sheets sync for Build ID: {}", buildId);

        try {
            Map<String, String> payload = buildPayload(buildId, repo, branch, server, requestedBy, cloneStatus, buildStatus, deploymentStatus, startupStatus);
            restTemplate.postForObject(webhookUrl, payload, String.class);
            logger.info("[GS-SYNC] Successfully synced Build: {}", buildId);

        } catch (Exception e) {
            logger.error("Unexpected error in ClassName : {}, method logToSheet, error: {} ", CLASSNAME, e.getMessage(), e);
        }
        
        logger.info("{} : Exiting method logToSheet", CLASSNAME);
    }

    private Map<String, String> buildPayload(String buildId, String repo, String branch, String server, 
                                             String requestedBy,String cloneStatus, String buildStatus, String deploymentStatus ,String startupStatus) {
        
        logger.info("{} : Entering method buildPayload", CLASSNAME);
        Map<String, String> payload = new HashMap<>();
        
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern(dateFormat));
        
        payload.put("timestamp", timestamp);
        payload.put("buildId", buildId);
        payload.put("repo", repo);
        payload.put("branch", branch);
        payload.put("server", server);
        payload.put("requestedBy", requestedBy);
        payload.put("cloneStatus", cloneStatus);
        payload.put("startupStatus", startupStatus);
        payload.put("buildStatus", buildStatus);
        payload.put("deploymentStatus", deploymentStatus);

        logger.info("{} : Exiting method buildPayload", CLASSNAME);
        return payload;
    }
}