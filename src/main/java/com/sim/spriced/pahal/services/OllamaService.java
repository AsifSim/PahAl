package com.sim.spriced.pahal.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ollama.api.url:http://localhost:11434/api/generate}")
    private String ollamaApiUrl;

    @Value("${ollama.vision.model:llama3.2-vision:11b}")   // <-- configurable vision model
    private String visionModel;

    @Value("${spring.ai.ollama.chat.model}")
    private String chatModel;

    public OllamaService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        log.info("OllamaService initialized with default URL: http://localhost:11434/api/generate");
    }

    public String generate(String prompt) {
        log.info("Entering generate()");
        log.debug("prompt = {}", prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        log.debug("headers created = {}", headers);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", chatModel);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);
        requestBody.put("options", Map.of(
                "temperature", 0.1,
                "top_p", 0.9,
                "top_k", 40,
                "repeat_penalty", 1.1,
                "num_ctx", 8192
        ));
        log.debug("requestBody = {}", requestBody);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        log.debug("entity created");

        log.debug("Calling Ollama API at {}", ollamaApiUrl);
        ResponseEntity<String> response = restTemplate.exchange(
                ollamaApiUrl,
                HttpMethod.POST,
                entity,
                String.class
        );
        log.debug("response = {}", response);

        log.debug("response status code = {}", response.getStatusCode());
        log.debug("response headers = {}", response.getHeaders());
        log.debug("response body = {}", response.getBody());

        String generatedResponse = extractResponseText(response.getBody());
        log.debug("generatedResponse = {}", generatedResponse);

        log.info("Exiting generate()");
        return generatedResponse;
    }

    private String extractResponseText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            log.debug("root = {}", root);
            String response = root.path("response").asText();
            log.debug("Extracted response = {}", response);
            return response;
        } catch (Exception e) {
            log.warn("Failed to parse Ollama response as JSON, returning raw body");
            return responseBody;
        }
    }

    public String generateWithImage(String prompt, byte[] imageBytes) {
        log.info("Entering generateWithImage()");
        log.debug("prompt length = {}", prompt.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", visionModel);   // uses configurable vision model
        requestBody.put("prompt", prompt);
        requestBody.put("images", new String[]{base64Image});  // crucial for vision models
        requestBody.put("stream", false);
        requestBody.put("options", Map.of(
                "temperature", 0.1,
                "top_p", 0.9,
                "top_k", 40,
                "repeat_penalty", 1.1
        ));
        log.debug("requestBody (without image data) = {}", requestBody);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        log.debug("Calling Ollama API at {} with vision model {}", ollamaApiUrl, visionModel);

        ResponseEntity<String> response = restTemplate.exchange(
                ollamaApiUrl,
                HttpMethod.POST,
                entity,
                String.class
        );
        log.debug("response status code = {}", response.getStatusCode());

        String generatedResponse = extractResponseText(response.getBody());
        log.info("Exiting generateWithImage()");
        return generatedResponse;
    }
}
