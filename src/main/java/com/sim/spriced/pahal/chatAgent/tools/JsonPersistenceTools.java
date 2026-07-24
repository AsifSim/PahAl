package com.sim.spriced.pahal.chatAgent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class JsonPersistenceTools {

    private final String BASE_DIR = "storage/users";

    @Tool(description = "Logs the user's mood for a specific phase of the day (MORNING, AFTERNOON, EVENING, NIGHT).")
    public String logMood(String userId, String sessionId, String phaseOfDay, String mood, int score, String notes) {
        try {
            Path path = Paths.get(BASE_DIR, userId, "sessions", sessionId);
            Files.createDirectories(path);
            File file = path.resolve("mood_tracker.json").toFile();
            return "Saved mood to: " + file.getAbsolutePath();
        } catch (Exception e) {
            return "Failed to save mood: " + e.getMessage();
        }
    }
}
