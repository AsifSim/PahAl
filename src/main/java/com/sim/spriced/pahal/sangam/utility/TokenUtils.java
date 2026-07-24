package com.sim.spriced.pahal.sangam.utility;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

@Component
public class TokenUtils {

    public String extractUsername(HttpServletRequest request) {
        try {
            String authHeader = request.getHeader("Authorization");

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return "System_User";
            }

            String token = authHeader.split(" ")[1];
            String payload = token.split("\\.")[1];

            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            String json = new String(decoded);

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> map = mapper.readValue(json, Map.class);

            return (String) (
                    map.getOrDefault("preferred_username",
                            map.getOrDefault("name",
                                    map.getOrDefault("username",
                                            map.getOrDefault("sub",
                                                    map.getOrDefault("email", "Unknown_User")))))
            );

        } catch (Exception e) {
            return "Token_Decode_Error";
        }
    }
}