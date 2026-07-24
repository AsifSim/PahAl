package com.sim.spriced.pahal.chatAgent.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sim.spriced.pahal.chatAgent.model.ChatDto;
import com.sim.spriced.pahal.chatAgent.model.MessageDto;
import com.sim.spriced.pahal.chatAgent.tools.AgentTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiChatService {
    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);
    private final ChatClient chatClient;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final String BASE_DIR = "storage/users";

    public AiChatService(ChatClient.Builder builder, AgentTools agentTools) {
        this.chatClient = builder.defaultTools(agentTools).build();
    }

    public String handleQuery(String userId, String sessionId, String chatId, String query, String userRole) {
        String systemPrompt = """
            You are an advanced AI engineering assistant with access to tools.
            1. If asked to investigate a single code, use investigateProduct.
            2. If asked for multiple records/entities, use fetchProducts and ALWAYS format the result as a Markdown Table.
            3. If the user asks for a GRAPH or CHART based on data, output EXACTLY this format block on a new line:
               <chart>{"type":"bar", "label":"Data", "labels":["X","Y"], "data":[10,20]}</chart>
            4. Wrap internal reasoning inside <thought>...</thought> tags.
            """;

        String rawResponse;
        try {
            // Attempt to call the local Ollama LLM
            rawResponse = chatClient.prompt().system(systemPrompt).user(query).call().content();

            // If the LLM somehow returns null or empty without throwing an exception
            if (rawResponse == null || rawResponse.trim().isEmpty()) {
                log.warn("LLM returned an empty response.");
                return "=== THOUGHT CHAIN ===\nLLM execution completed but returned no text.\n\n=== RESPONSE ===\nI processed your request, but my reasoning engine returned an empty response. Please try rephrasing.";
            }
        } catch (Exception e) {
            log.error("LLM Connection Error: ", e);
            // Graceful fallback message for the UI
            return "=== THOUGHT CHAIN ===\nConnection to local Ollama lost.\n\n=== RESPONSE ===\n**Error:** I lost connection to the local Ollama engine (`Connection Reset`). \n\nAndroid likely killed the background process to save memory. Please switch to your Termux terminal and restart `ollama serve`.";
        }

        String thoughtChain = extractTag(rawResponse, "thought");
        String finalAnswer = rawResponse.replaceAll("<thought>.*?</thought>", "").trim();

        String title = "Investigation";
        try {
             title = chatClient.prompt().user("Give a max 3 word title for: " + query).call().content().replaceAll("[^a-zA-Z0-9 ]", "").trim();
             if(title.isEmpty()) title = "Chat Session";
        } catch(Exception ignored) {}

        saveChatToFile(userId, sessionId, chatId, title, query, thoughtChain, finalAnswer);
        return "=== THOUGHT CHAIN ===\n" + thoughtChain + "\n\n=== RESPONSE ===\n" + finalAnswer;
    }

    private void saveChatToFile(String userId, String sessionId, String chatId, String title, String query, String thoughtChain, String answer) {
        try {
            Path chatDir = Paths.get(BASE_DIR, userId, "sessions", sessionId, "chats");
            Files.createDirectories(chatDir);
            File chatFile = chatDir.resolve(chatId + ".json").toFile();

            List<MessageDto> history = new ArrayList<>();
            if (chatFile.exists()) {
                ChatDto existing = mapper.readValue(chatFile, ChatDto.class);
                if (existing.messages() != null) history.addAll(existing.messages());
            }

            history.add(new MessageDto("USER", null, query, LocalDateTime.now()));
            history.add(new MessageDto("ASSISTANT", thoughtChain, answer, LocalDateTime.now()));

            ChatDto updatedChat = new ChatDto(chatId, sessionId, title, "Chat", history);
            mapper.writerWithDefaultPrettyPrinter().writeValue(chatFile, updatedChat);
        } catch (Exception e) { log.error("Failed to save chat file!", e); }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getSuggestions(String userId) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        try {
            File actDir = Paths.get(BASE_DIR, userId, "activities").toFile();
            if (actDir.exists() && actDir.isDirectory()) {
                File[] files = actDir.listFiles((dir, name) -> name.endsWith(".json"));
                if (files != null) {
                    Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                    for (int i = 0; i < Math.min(files.length, 3); i++) {
                        suggestions.add(mapper.readValue(files[i], Map.class));
                    }
                }
            }
        } catch (Exception e) { }
        return suggestions;
    }

    public List<Map<String, String>> getPastChats(String userId, String sessionId) {
        List<Map<String, String>> chatList = new ArrayList<>();
        try {
            File chatDir = Paths.get(BASE_DIR, userId, "sessions", sessionId, "chats").toFile();
            if (chatDir.exists() && chatDir.isDirectory()) {
                File[] files = chatDir.listFiles((dir, name) -> name.endsWith(".json"));
                if (files != null) {
                    for (File file : files) {
                        ChatDto chat = mapper.readValue(file, ChatDto.class);
                        chatList.add(Map.of("chatId", chat.chatId(), "title", chat.chatTitle()));
                    }
                }
            }
        } catch (Exception e) { }
        return chatList;
    }

    public ChatDto getChatHistory(String userId, String sessionId, String chatId) {
        try {
            File chatFile = Paths.get(BASE_DIR, userId, "sessions", sessionId, "chats", chatId + ".json").toFile();
            if (chatFile.exists()) return mapper.readValue(chatFile, ChatDto.class);
        } catch (Exception e) { }
        return null;
    }

    private String extractTag(String text, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "Executing standard inference...";
    }
}
