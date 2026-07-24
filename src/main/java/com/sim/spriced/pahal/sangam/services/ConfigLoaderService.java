package com.sim.spriced.pahal.sangam.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

@Service
public class ConfigLoaderService {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoaderService.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private InputStream getFile(String file) {
        return getClass().getClassLoader().getResourceAsStream("config/" + file);
    }

    public List<Map<String, Object>> loadRepos() {
        try (InputStream is = getFile("repos.json")) {

            if (is == null) {
                log.warn("repos.json not found");
                return new ArrayList<>();
            }

            Map<String, Object> data = mapper.readValue(is, Map.class);

            return (List<Map<String, Object>>) data.getOrDefault("repositories", new ArrayList<>());

        } catch (Exception e) {
            log.error("Error loading repos", e);
            return new ArrayList<>();
        }
    }

    public List<Map<String, Object>> loadServers() {
        try (InputStream is = getFile("servers.json")) {

            if (is == null) {
                log.warn("servers.json not found");
                return new ArrayList<>();
            }

            Map<String, Object> data = mapper.readValue(is, Map.class);

            List<String> servers = (List<String>) data.getOrDefault("servers", new ArrayList<>());

            List<Map<String, Object>> result = new ArrayList<>();
            for (String s : servers) {
                result.add(Map.of("label", s, "value", s));
            }

            return result;

        } catch (Exception e) {
            log.error("Error loading servers", e);
            return new ArrayList<>();
        }
    }
}
