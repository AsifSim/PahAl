package com.sim.spriced.pahal.chatAgent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String BASE_DIR = "storage/users";

    public AgentTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "Fetches multiple product records from the database. Use this when the user asks for a list or table of products. Limit dictates how many to fetch.")
    public String fetchProducts(int limit) {
        log.info(">>>> TOOL CALLED: fetchProducts (limit {})", limit);
        try {
            List<Map<String, Object>> result = jdbcTemplate.queryForList("SELECT * FROM products LIMIT ?", limit);
            String json = mapper.writeValueAsString(result);
            log.info(">>>> TOOL RESULT: Fetched {} records", result.size());
            return "[TOOL: DB] " + json;
        } catch (Exception e) {
            log.error(">>>> TOOL ERROR: Database failed", e);
            return "[TOOL: DB Error] " + e.getMessage();
        }
    }

    @Tool(description = "Fetches product data and price history from the PostgreSQL database using a specific product code (e.g., P-100, P-200).")
    public String investigateProduct(String productCode) {
        log.info(">>>> TOOL CALLED: investigateProduct for code '{}'", productCode);
        try {
            List<Map<String, Object>> result = jdbcTemplate.queryForList("SELECT * FROM products WHERE code = ?", productCode);
            if (result.isEmpty()) return "[TOOL: DB] No data found for " + productCode;
            return "[TOOL: DB] Data retrieved:\n" + mapper.writeValueAsString(result.get(0));
        } catch (Exception e) { return "[TOOL: DB Error] " + e.getMessage(); }
    }

    @Tool(description = "Logs a specific user activity/investigation into a JSON file so it can be resumed or replayed later.")
    public String logActivity(String userId, String sessionId, String activityName, String targetCode, String findings) {
        log.info(">>>> TOOL CALLED: logActivity ('{}' for '{}')", activityName, targetCode);
        try {
            Path dir = Paths.get(BASE_DIR, userId, "activities");
            Files.createDirectories(dir);
            File file = dir.resolve("act_" + System.currentTimeMillis() + ".json").toFile();
            Map<String, Object> activity = Map.of("activityName", activityName, "targetCode", targetCode, "findings", findings, "timestamp", LocalDateTime.now().toString());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, activity);
            return "[TOOL: Logger] Activity saved successfully.";
        } catch (Exception e) { return "[TOOL: Logger Error] " + e.getMessage(); }
    }
}
