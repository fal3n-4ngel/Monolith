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
 * All client bearer keys in one place, resolved from a single {@code MONOLITH_CLIENT_KEYS} secret
 * instead of one Secret Manager entry per app.
 *
 * <p>The value is a map of {@code clientName -> token}, accepted as JSON
 * (<code>{"continuum":"tok_…"}</code>) or as CSV (<code>continuum=tok_…,budget-cli=tok_…</code>).
 * Adding an app is a new <i>version</i> of this one secret plus a row in {@code clients.json} — the
 * count of Secret Manager secrets never grows.
 *
 * <p>Holds key material in memory only; never logs a value.
 */
@Component
public class ClientKeyMap {

    private static final Logger log = LoggerFactory.getLogger(ClientKeyMap.class);

    private final Map<String, String> keysByName;

    public ClientKeyMap(ObjectMapper objectMapper,
                        @Value("${MONOLITH_CLIENT_KEYS:}") String raw) {
        this.keysByName = parse(objectMapper, raw);
        log.info("[Auth] Loaded {} client key(s) from MONOLITH_CLIENT_KEYS", keysByName.size());
    }

    /** @return the token for {@code clientName}, if the aggregated secret carries one. */
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
        try {
            if (trimmed.startsWith("{")) {
                Map<String, String> parsed = mapper.readValue(trimmed, new TypeReference<>() {
                });
                return parsed == null ? Map.of() : parsed;
            }
        } catch (Exception e) {
            throw new IllegalStateException("MONOLITH_CLIENT_KEYS looks like JSON but did not parse", e);
        }

        // CSV fallback: name=token,name2=token2
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
