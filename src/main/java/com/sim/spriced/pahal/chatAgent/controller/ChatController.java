package com.sim.spriced.pahal.chatAgent.controller;

import com.sim.spriced.pahal.chatAgent.service.AiChatService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class ChatController {

    private final AiChatService aiChatService;

    public ChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String userId = request.getOrDefault("userId", "usr_1001");
        String sessionId = request.getOrDefault("sessionId", "sess_001");
        String chatId = request.getOrDefault("chatId", "chat_" + System.currentTimeMillis());
        String query = request.get("query");
        String role = request.getOrDefault("role", "STANDARD_USER");

        String reply = aiChatService.handleQuery(userId, sessionId, chatId, query, role);
        return Map.of("response", reply, "chatId", chatId);
    }

    @GetMapping("/suggestions/{userId}")
    public List<Map<String, Object>> getSuggestions(@PathVariable String userId) {
        return aiChatService.getSuggestions(userId);
    }
}
