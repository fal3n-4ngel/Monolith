package com.dashboard.api.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * All non-owner client keys in one {@code MONOLITH_CLIENT_KEYS} secret — a {@code name -> token}
 * map, accepted as JSON or {@code name=token,...} CSV. Adding a client is a new version of this
 * one secret rather than a new secret.
 */
@Component
public class ClientKeyMap {

    private static final Logger log = LoggerFactory.getLogger(ClientKeyMap.class);

    private final Map<String, String> keysByName;

    public ClientKeyMap(ObjectMapper objectMapper, @Value("${MONOLITH_CLIENT_KEYS:}") String raw) {
        this.keysByName = parse(objectMapper, raw);
        log.info("[Auth] Loaded {} client key(s) from MONOLITH_CLIENT_KEYS", keysByName.size());
    }

    public Optional<String> keyFor(String clientName) {
        String token = keysByName.get(clientName);
        return (token == null || token.isBlank()) ? Optional.empty() : Optional.of(token.trim());
    }

    public int size() {
        return keysByName.size();
    }

    private static Map<String, String> parse(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            try {
                Map<String, String> parsed = mapper.readValue(trimmed, new TypeReference<>() {
                });
                return parsed == null ? Map.of() : parsed;
            } catch (Exception e) {
                throw new IllegalStateException("MONOLITH_CLIENT_KEYS looks like JSON but did not parse", e);
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : trimmed.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalStateException(
                        "MONOLITH_CLIENT_KEYS entry '" + pair.trim() + "' is not 'name=token'");
            }
            out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return out;
    }
}
